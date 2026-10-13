import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("proof", Path(__file__).with_name("pull-recovery-proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class ProofCopyTest(unittest.TestCase):
    def exercise(self, outcomes, qemu="1", serial="emulator-5554", source=None, states=None):
        self.commands = []
        elapsed = [0]
        states = list(states or ["device"])
        def sleep(seconds): elapsed[0] += seconds
        def run(command, **options):
            self.commands.append(command)
            self.assertGreater(options["timeout"], 0)
            self.assertLessEqual(options["timeout"], 60)
            if command[-1] == "get-state":
                state = states.pop(0) if len(states) > 1 else states[0]
                return subprocess.CompletedProcess(command, 0 if state == "device" else 1, state, "")
            if command[-3:] == ["shell", "getprop", "ro.kernel.qemu"]:
                return subprocess.CompletedProcess(command, 0, qemu, "")
            outcome = outcomes.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            status, stdout, stderr = outcome
            return subprocess.CompletedProcess(command, status, stdout, stderr)
        return proof.pull("fake-adb", serial, source or
            "/sdcard/Android/data/dev.forgesworn.kithmoot/files/artwork-review",
            self.directory, self.directory, "5038", run, clock=lambda: elapsed[0], sleep=sleep)

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
        copies = [command for command in self.commands if "pull" in command]
        self.assertEqual(copies[0], copies[1])
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
        self.assertEqual(3, sum("pull" in command for command in self.commands))
        self.assertEqual(3, len(list(self.directory.glob("proof-pull-*.txt"))))

    def test_offline_transport_waits_then_copies_the_same_required_proof(self):
        self.assertEqual(2, self.exercise([(1, "", "device offline"), (0, "complete", "")],
                                        states=["offline", "offline", "device"]))
        copies = [command for command in self.commands if "pull" in command]
        self.assertEqual(copies[0], copies[1])
        report = (self.directory / "proof-transport-artwork-review-1.txt").read_text()
        self.assertEqual(2, report.count("offline"))
        self.assertIn("qemu=0 1", report)

    def test_permanently_offline_transport_still_fails_required_proof(self):
        with self.assertRaisesRegex(RuntimeError, "stayed offline"):
            self.exercise([(1, "", "device offline")], states=["offline"])
        self.assertEqual(1, sum("pull" in command for command in self.commands))
        self.assertIn("status=1", (self.directory / "proof-pull-artwork-review-1.txt").read_text())
        self.assertTrue(all("instrument" not in command and "install" not in command for command in self.commands))

    def test_reconnected_transport_must_still_prove_it_is_an_emulator(self):
        commands = []
        guards = ["1", "0"]
        def run(command, **options):
            commands.append(command)
            if command[-1] == "get-state":
                return subprocess.CompletedProcess(command, 0, "device", "")
            if command[-1] == "ro.kernel.qemu":
                return subprocess.CompletedProcess(command, 0, guards.pop(0), "")
            return subprocess.CompletedProcess(command, 1, "", "device offline")
        with self.assertRaisesRegex(ValueError, "non-emulator"):
            proof.pull("fake-adb", "emulator-5554",
                "/sdcard/Android/data/dev.forgesworn.kithmoot/files/artwork-review",
                self.directory, self.directory, run=run)
        self.assertEqual(1, sum("pull" in command for command in commands))

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
