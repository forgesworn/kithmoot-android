"""Negative controls for privacy and failure isolation in emulator observations."""
import json
import subprocess
import unittest

from recording_failure_diagnostics import collect_failure


class RecordingFailureDiagnosticsTest(unittest.TestCase):
    def test_command_and_crash_messages_are_omitted_and_other_apps_are_excluded(self):
        secret = "SYNTHETIC_SECRET_MUST_NOT_APPEAR"
        crash = "\n".join([
            "E/AndroidRuntime( 101): Process: other.application, PID: 101",
            "E/AndroidRuntime( 101): java.lang.SecurityException: " + secret,
            "E/AndroidRuntime( 202): Process: dev.forgesworn.kithmoot, PID: 202",
            "E/AndroidRuntime( 202): java.lang.OutOfMemoryError: " + secret,
            "E/AndroidRuntime( 202): Caused by: java.io.IOException: " + secret,
            "E/AndroidRuntime( 303): Process: dev.forgesworn.kithmoot.fake, PID: 303",
            "E/AndroidRuntime( 303): java.lang.IllegalStateException: " + secret,
        ])

        def run(arguments, **kwargs):
            self.assertEqual(2, kwargs["timeout"])
            output = crash if "logcat" in arguments else secret
            return subprocess.CompletedProcess(arguments, 0, output.encode(), secret.encode())

        report = collect_failure("adb", "emulator-5572", secret, RuntimeError(secret), 1, run)
        self.assertNotIn(secret, json.dumps(report))
        self.assertEqual("unknown", report["phase"])
        self.assertEqual(1, report["instrumentationReturncode"])
        self.assertEqual([], report["probes"]["appPids"]["pids"])
        self.assertEqual([202], report["probes"]["crash"]["appCrashPids"])
        self.assertEqual(["java.io.IOException", "java.lang.OutOfMemoryError"],
                         report["probes"]["crash"]["exceptionClasses"])

    def test_timeout_and_command_failure_do_not_prevent_later_observations(self):
        calls = []

        def run(arguments, **kwargs):
            calls.append(arguments)
            if "get-state" in arguments:
                raise subprocess.TimeoutExpired(arguments, 2, output=b"private output")
            if "pidof" in arguments:
                return subprocess.CompletedProcess(arguments, 1, b"private output", b"private error")
            output = b"1" if "getprop" in arguments else b""
            return subprocess.CompletedProcess(arguments, 0, output, b"")

        report = collect_failure("adb", "emulator-5572", "recipient-ui-and-transfer", RuntimeError(), None, run)
        self.assertEqual(5, len(calls))
        self.assertEqual({"exceptionType": "TimeoutExpired"}, report["probes"]["adbState"])
        self.assertEqual({"returnCode": 1}, report["probes"]["appPids"])
        self.assertEqual("1", report["probes"]["bootCompleted"]["flag"])
        self.assertIn("crash", report["probes"])
        self.assertNotIn("private", json.dumps(report))

    def test_physical_serial_is_refused_before_any_command(self):
        def run(*args, **kwargs):
            self.fail("A physical serial must not be probed")

        with self.assertRaises(ValueError):
            collect_failure("adb", "physical-device", "fixture-setup", RuntimeError(), None, run)


if __name__ == "__main__":
    unittest.main()
