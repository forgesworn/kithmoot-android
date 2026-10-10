import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("proof", Path(__file__).with_name("pull-recovery-proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class ProofCopyTest(unittest.TestCase):
    def exercise(self, outcomes, qemu="1", serial="emulator-5554", source=None):
        self.commands = []
        def run(command, **options):
            self.commands.append(command)
            self.assertEqual(60, options["timeout"])
            if command[-3:] == ["shell", "getprop", "ro.kernel.qemu"]:
                return subprocess.CompletedProcess(command, 0, qemu, "")
            outcome = outcomes.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            status, stdout, stderr = outcome
            return subprocess.CompletedProcess(command, status, stdout, stderr)
        return proof.pull("fake-adb", serial, source or
            "/sdcard/Android/data/dev.forgesworn.kithmoot/files/artwork-review",
            self.directory, self.directory, "5038", run)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_success_does_not_repeat_copy(self):
        self.assertEqual(1, self.exercise([(0, "all files copied\n", "")]))
        self.assertEqual(2, len(self.commands))
        self.assertEqual(["fake-adb", "-P", "5038", "-s", "emulator-5554"], self.commands[-1][:5])

    def test_partial_copy_failure_is_retained_before_same_read_only_retry(self):
        self.assertEqual(2, self.exercise([(1, "partial file\n", "transport stopped\n"), (0, "complete\n", "")]))
        self.assertEqual(self.commands[-2], self.commands[-1])
        report = (self.directory / "proof-pull-artwork-review-1.txt").read_text()
        self.assertIn("status=1", report)
        self.assertIn("partial file\ntransport stopped", report)
        self.assertTrue(all("instrument" not in command and "install" not in command for command in self.commands))

    def test_timeout_preserves_partial_output_and_retries(self):
        timeout = subprocess.TimeoutExpired("adb", 60, output=b"partial", stderr=b"lost")
        self.assertEqual(2, self.exercise([timeout, (0, "complete", "")]))
        report = (self.directory / "proof-pull-artwork-review-1.txt").read_text()
        self.assertIn("status=timeout", report)
        self.assertIn("partiallost", report)

    def test_required_artifact_still_fails_after_three_attempts(self):
        with self.assertRaises(RuntimeError):
            self.exercise([(1, "", "failed")] * 3)
        self.assertEqual(4, len(self.commands))
        self.assertEqual(3, len(list(self.directory.glob("proof-pull-*.txt"))))

    def test_physical_serial_is_refused_before_any_command(self):
        with self.assertRaises(ValueError):
            self.exercise([], serial="physical-device")
        self.assertEqual([], self.commands)

    def test_non_emulator_and_other_app_sources_are_refused_before_copy(self):
        with self.assertRaises(ValueError):
            self.exercise([], qemu="0")
        self.assertEqual(1, len(self.commands))
        with self.assertRaises(ValueError):
            self.exercise([], source="/sdcard/another-app")
        self.assertEqual(1, len(self.commands))


if __name__ == "__main__":
    unittest.main()
