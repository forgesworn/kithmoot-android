#!/usr/bin/env python3
"""Fault-check the actual emulator driver with a fake adb, never a device."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

DRIVER = Path(__file__).with_name("check-recovery-emulator.sh")

FAKE_ADB = r'''#!/usr/bin/env python3
import os, sys
from pathlib import Path
args = sys.argv[1:]
Path(os.environ["FAKE_MARKER"]).touch()
if "ro.kernel.qemu" in args:
    print(os.environ.get("FAKE_QEMU", "1")); sys.exit(0)
if "resolve-activity" in args:
    print("fixture.launcher/Home"); sys.exit(0)
if "instrument" in args:
    state = Path(os.environ["FAKE_COUNTER"])
    index = int(state.read_text()) if state.exists() else 0
    state.write_text(str(index + 1))
    counts = [int(n) for n in os.environ["FAKE_COUNTS"].split(",")]
    mode = os.environ.get("FAKE_MODE", "ok") if index == 0 else "ok"
    if mode == "failure": print("FAILURES!!!\nTests run: 9, Failures: 1")
    elif mode == "wrong-count": print("OK (8 tests)")
    else: print(f"OK ({counts[index]} tests)")
    sys.exit(7 if mode in ("command-failure", "diagnostics-failure") else 0)
if "logcat" in args or "screencap" in args:
    if os.environ.get("FAKE_MODE") == "diagnostics-failure": sys.exit(3)
    print("fake diagnostics"); sys.exit(0)
sys.exit(0)
'''


class RecoveryRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="kithmoot-recovery-driver-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        self.driver = self.root / "scripts/check-recovery-emulator.sh"
        shutil.copyfile(DRIVER, self.driver)
        sdk = self.root / "sdk"
        (sdk / "platform-tools").mkdir(parents=True)
        adb = sdk / "platform-tools/adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(0o700)
        self.env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL="emulator-9998",
                        FAKE_MARKER=str(self.root / "adb-called"), FAKE_COUNTER=str(self.root / "counter"),
                        FAKE_COUNTS=",".join(re.findall(r"^run_tests [\w-]+ (\d+)", DRIVER.read_text(), re.M)))
        self.reports = self.root / "app/build/reports/recovery-emulator"

    def run_driver(self, mode="ok", **env):
        return subprocess.run(["bash", str(self.driver)], env=dict(self.env, FAKE_MODE=mode, **env),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)

    def test_all_command_success_and_expected_counts_pass(self):
        result = self.run_driver()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.reports / "session-recover.txt").exists())

    def test_ok_summary_with_failed_adb_refuses_and_keeps_diagnostics(self):
        result = self.run_driver("command-failure")
        self.assertEqual(1, result.returncode)
        self.assertIn("adb=7, tr=0, tee=0", result.stderr)
        self.assertIn("OK (9 tests)", (self.reports / "storage-and-ui.txt").read_text())
        self.assertTrue((self.reports / "storage-and-ui-logcat.txt").exists())
        self.assertTrue((self.reports / "storage-and-ui-screen.png").exists())
        self.assertFalse((self.reports / "nearby-room-ui.txt").exists())

    def test_assertion_failure_with_successful_adb_refuses(self):
        result = self.run_driver("failure")
        self.assertEqual(1, result.returncode)
        self.assertIn("adb=0, tr=0, tee=0", result.stderr)

    def test_wrong_success_count_refuses(self):
        result = self.run_driver("wrong-count")
        self.assertEqual(1, result.returncode)
        self.assertIn("Instrumentation did not pass: storage-and-ui", result.stderr)

    def test_missing_diagnostics_does_not_hide_the_original_command_failure(self):
        result = self.run_driver("diagnostics-failure")
        self.assertEqual(1, result.returncode)
        self.assertIn("adb=7, tr=0, tee=0", result.stderr)
        self.assertIn("Could not capture emulator logcat", result.stderr)
        self.assertIn("Could not capture emulator screen", result.stderr)

    def test_physical_serial_is_refused_before_any_adb_command(self):
        result = self.run_driver(ANDROID_SERIAL="physical-fixture")
        self.assertEqual(2, result.returncode)
        self.assertFalse((self.root / "adb-called").exists())

    def test_emulator_label_cannot_bypass_the_actual_qemu_check(self):
        result = self.run_driver(FAKE_QEMU="0")
        self.assertEqual(2, result.returncode)
        self.assertIn("non-emulator device", result.stderr)
        self.assertFalse((self.root / "counter").exists())


if __name__ == "__main__":
    unittest.main()
