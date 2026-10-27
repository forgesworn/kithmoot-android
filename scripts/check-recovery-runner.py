#!/usr/bin/env python3
"""Fault-check the actual emulator driver with a fake adb, never a device."""
import os
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

DRIVER = Path(__file__).with_name("check-recovery-emulator.sh")

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys, time
from pathlib import Path
args = sys.argv[1:]
Path(os.environ["FAKE_MARKER"]).touch()
if os.environ.get("FAKE_CALLS"):
    with open(os.environ["FAKE_CALLS"], "a") as log: log.write(json.dumps(args) + "\n")
if "ro.kernel.qemu" in args:
    print(os.environ.get("FAKE_QEMU", "1")); sys.exit(0)
if "get-state" in args:
    print("device"); sys.exit(0)
if "resolve-activity" in args:
    print(os.environ.get("FAKE_LAUNCHER", "fixture.launcher/Home")); sys.exit(0)
if "instrument" in args:
    state = Path(os.environ["FAKE_COUNTER"])
    index = int(state.read_text()) if state.exists() else 0
    state.write_text(str(index + 1))
    counts = [int(n) for n in os.environ["FAKE_COUNTS"].split(",")]
    mode = os.environ.get("FAKE_MODE", "ok") if index == 0 else "ok"
    if mode in ("stall", "slow-status"):
        sys.stdout.write("INSTRUMENTATION_STATUS: test=fixture_started\r\n"); sys.stdout.flush()
        time.sleep(30 if mode == "stall" else 1)
        if mode == "slow-status": Path(os.environ["FAKE_STREAM_DONE"]).touch()
    if mode == "failure": print("FAILURES!!!\nTests run: 9, Failures: 1")
    elif mode == "wrong-count": print("OK (8 tests)")
    else: print(f"OK ({counts[index]} tests)")
    sys.exit(7 if mode in ("command-failure", "diagnostics-failure") else 0)
if "logcat" in args or "screencap" in args:
    if "logcat" in args and os.environ.get("FAKE_SETUP_LOGCAT_FAILURE") == "1": sys.exit(255)
    if os.environ.get("FAKE_MODE") == "diagnostics-failure": sys.exit(3)
    print("fake diagnostics"); sys.exit(0)
if "pidof" in args: print("4242"); sys.exit(0)
if "pull" in args:
    target = os.environ.get("FAKE_PULL_TARGET")
    if target and args[args.index("pull") + 1] != target:
        sys.exit(0)
    counter = Path(os.environ["FAKE_PULL_COUNTER"])
    index = int(counter.read_text()) if counter.exists() else 0
    counter.write_text(str(index + 1))
    if index < int(os.environ.get("FAKE_PULL_FAILURES", "0")):
        print("partial artifact copy", file=sys.stderr); sys.exit(7)
if "bugreport" in args: Path(args[-1]).write_bytes(b"fixture bugreport"); sys.exit(0)
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
        shutil.copyfile(DRIVER.with_name("run-recovery-instrumentation.py"),
                        self.driver.with_name("run-recovery-instrumentation.py"))
        shutil.copyfile(DRIVER.with_name("pull-recovery-proof.py"),
                        self.driver.with_name("pull-recovery-proof.py"))
        sdk = self.root / "sdk"
        (sdk / "platform-tools").mkdir(parents=True)
        adb = sdk / "platform-tools/adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(0o700)
        self.env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL="emulator-9998",
                        FAKE_MARKER=str(self.root / "adb-called"), FAKE_COUNTER=str(self.root / "counter"),
                        FAKE_PULL_COUNTER=str(self.root / "pull-counter"),
                        FAKE_COUNTS=",".join(re.findall(r"^run_tests [\w-]+ (\d+)", DRIVER.read_text(), re.M)))
        self.reports = self.root / "app/build/reports/recovery-emulator"

    def run_driver(self, mode="ok", **env):
        return subprocess.run(["bash", str(self.driver)], env=dict(self.env, FAKE_MODE=mode, **env),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)

    def test_all_command_success_and_expected_counts_pass(self):
        result = self.run_driver()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.reports / "session-recover.txt").exists())

    def run_restart_matrix(self, fail_at=""):
        matrix = DRIVER.with_name("check-recovery-matrix.sh")
        target = self.driver.with_name(matrix.name)
        shutil.copyfile(matrix, target)
        for name in set(re.findall(r"python3 scripts/([\w-]+\.py)", matrix.read_text())):
            self.driver.with_name(name).write_text(
                "import os, sys\nfrom pathlib import Path\n"
                "name = Path(__file__).name\n"
                "with open(os.environ['FAKE_RESTART_CALLS'], 'a') as log: log.write(name + '\\n')\n"
                "sys.exit(7 if name == os.environ.get('FAKE_RESTART_FAIL_AT') else 0)\n")
        calls = self.root / "restart-calls.txt"
        result = subprocess.run(["bash", str(target), "restarts"],
            env=dict(self.env, FAKE_LAUNCHER="com.android.launcher3/Home",
                     FAKE_SETUP_LOGCAT_FAILURE="1", FAKE_RESTART_CALLS=str(calls),
                     FAKE_RESTART_FAIL_AT=fail_at),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
        reports = self.root / "app/build/reports/sharing-restart-emulator"
        self.assertEqual("255\n", (reports / "emulator-setup-logcat.exit").read_text())
        return result, calls.read_text().splitlines()

    def test_restart_setup_logcat_failure_does_not_skip_acceptance(self):
        result, calls = self.run_restart_matrix()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(15, len(calls))

    def test_restart_setup_logcat_failure_does_not_hide_failed_acceptance(self):
        result, calls = self.run_restart_matrix("check-sharing-restart-emulator.py")
        self.assertEqual(7, result.returncode)
        self.assertEqual(["check-sharing-restart-emulator.py"], calls)

    def test_setup_logcat_failure_does_not_skip_acceptance(self):
        calls = self.root / "calls.jsonl"
        result = self.run_driver(FAKE_LAUNCHER="com.android.launcher3/Home",
                                 FAKE_SETUP_LOGCAT_FAILURE="1", FAKE_CALLS=str(calls))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("255\n", (self.reports / "emulator-setup-logcat.exit").read_text())
        trace = [json.loads(line) for line in calls.read_text().splitlines()]
        self.assertTrue(any("force-stop" in command for command in trace))
        self.assertEqual(len(self.env["FAKE_COUNTS"].split(",")),
                         sum("instrument" in command for command in trace))

    def test_setup_logcat_failure_does_not_hide_failed_acceptance(self):
        result = self.run_driver("command-failure", FAKE_LAUNCHER="com.android.launcher3/Home",
                                 FAKE_SETUP_LOGCAT_FAILURE="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("runner=7, tee=0", result.stderr)
        self.assertFalse((self.reports / "nearby-room-ui.txt").exists())

    def test_ok_summary_with_failed_adb_refuses_and_keeps_diagnostics(self):
        result = self.run_driver("command-failure")
        self.assertEqual(1, result.returncode)
        self.assertIn("runner=7, tee=0", result.stderr)
        self.assertIn("OK (9 tests)", (self.reports / "storage-and-ui.txt").read_text())
        self.assertTrue((self.reports / "storage-and-ui-logcat.txt").exists())
        self.assertTrue((self.reports / "storage-and-ui-screen.png").exists())
        self.assertFalse((self.reports / "nearby-room-ui.txt").exists())

    def test_copy_retry_never_repeats_an_instrumentation_suite(self):
        calls = self.root / "calls.jsonl"
        target = "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/room-workspace.png"
        result = self.run_driver(FAKE_PULL_FAILURES="1", FAKE_CALLS=str(calls), FAKE_PULL_TARGET=target)
        self.assertEqual(0, result.returncode, result.stderr)
        trace = [json.loads(line) for line in calls.read_text().splitlines()]
        tests = [command for command in trace if "instrument" in command]
        self.assertEqual(len(self.env["FAKE_COUNTS"].split(",")), len(tests))
        copies = [command for command in trace if "pull" in command and target in command]
        self.assertEqual(2, len(copies))
        self.assertEqual(copies[0], copies[1])
        self.assertIn("status=7", (self.reports / "proof-pull-room-workspace.png-1.txt").read_text())
        self.assertIn("status=0", (self.reports / "proof-pull-room-workspace.png-2.txt").read_text())

    def test_unavailable_required_copy_stops_before_later_suites(self):
        target = "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/room-workspace.png"
        result = self.run_driver(FAKE_PULL_FAILURES="3", FAKE_PULL_TARGET=target)
        self.assertEqual(1, result.returncode)
        self.assertIn("Required proof copy failed after three attempts", result.stderr)
        self.assertTrue((self.reports / "room-workspace.txt").exists())
        self.assertFalse((self.reports / "room-countdown-journey.txt").exists())

    def test_unavailable_relay_auth_copy_stops_before_later_suites(self):
        target = "/sdcard/Android/data/dev.forgesworn.kithmoot/files/relay-auth"
        result = self.run_driver(FAKE_PULL_FAILURES="3", FAKE_PULL_TARGET=target)
        self.assertEqual(1, result.returncode)
        self.assertIn("Required proof copy failed after three attempts", result.stderr)
        self.assertTrue((self.reports / "relay-account-auth-ui.txt").exists())
        self.assertFalse((self.reports / "update-release-notes-ui.txt").exists())

    def test_assertion_failure_with_successful_adb_refuses(self):
        result = self.run_driver("failure")
        self.assertEqual(1, result.returncode)
        self.assertIn("runner=0, tee=0", result.stderr)

    def test_wrong_success_count_refuses(self):
        result = self.run_driver("wrong-count")
        self.assertEqual(1, result.returncode)
        self.assertIn("Instrumentation did not pass: storage-and-ui", result.stderr)

    def test_missing_diagnostics_does_not_hide_the_original_command_failure(self):
        result = self.run_driver("diagnostics-failure")
        self.assertEqual(1, result.returncode)
        self.assertIn("runner=7, tee=0", result.stderr)
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

    def wrapper_command(self, deadline):
        return [sys.executable, str(self.driver.with_name("run-recovery-instrumentation.py")),
                "--adb", str(self.root / "sdk/platform-tools/adb"), "--serial", "emulator-9998",
                "--reports", str(self.reports), "--report", "fixture", "--deadline-seconds", str(deadline)]

    def test_method_status_is_flushed_before_completion_without_carriage_returns(self):
        finished = self.root / "stream-finished"
        process = subprocess.Popen(self.wrapper_command(10),
            env=dict(self.env, FAKE_MODE="slow-status", FAKE_STREAM_DONE=str(finished)),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            first = process.stdout.readline()
            self.assertEqual(b"INSTRUMENTATION_STATUS: test=fixture_started\n", first)
            self.assertFalse(finished.exists())
            output, error = process.communicate(timeout=15)
            self.assertEqual(0, process.returncode, error)
            self.assertIn(b"OK (9 tests)", output)
        finally:
            if process.poll() is None: process.kill(); process.wait(timeout=5)
            process.stdout.close(); process.stderr.close()

    def test_stalled_batch_fails_and_preserves_logs_and_own_app_thread_request(self):
        calls = self.root / "calls.jsonl"
        result = subprocess.run(self.wrapper_command(1),
            env=dict(self.env, FAKE_MODE="stall", FAKE_CALLS=str(calls)),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=15)
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertIn("test=fixture_started", result.stdout)
        self.assertNotIn("OK (", result.stdout)
        for suffix in ("logcat.txt", "processes.txt", "screen.png", "thread-request.txt", "bugreport.zip"):
            self.assertTrue((self.reports / f"fixture-timeout-{suffix}").exists(), suffix)
        trace = [json.loads(line) for line in calls.read_text().splitlines()]
        signal = next(i for i, command in enumerate(trace) if "run-as" in command)
        bugreport = next(i for i, command in enumerate(trace) if "bugreport" in command)
        self.assertEqual(["shell", "run-as", "dev.forgesworn.kithmoot", "kill", "-3", "4242"], trace[signal][2:])
        self.assertLess(signal, bugreport)


if __name__ == "__main__":
    unittest.main()
