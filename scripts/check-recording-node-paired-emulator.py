#!/usr/bin/env python3
"""Real Link custody/restart/withdrawal probe. Does not qualify relay delivery."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.parse import urlparse
from urllib.request import Request, urlopen

APP = "dev.forgesworn.kithmoot"
PROBE = "dev.forgesworn.kithmoot.g5.G7CadenceStatusTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--control", required=True)
    parser.add_argument("--relay-port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This probe replaces test app data and refuses physical devices")
    control = urlparse(args.control)
    if control.scheme != "http" or control.hostname != "127.0.0.1" or not control.port or control.path not in ("", "/"):
        parser.error("Fixture control must be a loopback HTTP origin")
    if not 1 <= args.relay_port <= 65535:
        parser.error("Invalid loopback relay port")
    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    apks = [root / "app/build/outputs/apk/debug/app-debug.apk",
            root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    for apk in apks:
        if not apk.is_file():
            parser.error("Build the app and debug instrumentation APKs before this probe")
    inputs = {
        "apk_sha256": [hashlib.sha256(p.read_bytes()).hexdigest() for p in apks],
        "source_sha256": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in (root / "app/src/androidTest/kotlin/dev/forgesworn/kithmoot/g5/G7CadenceStatusTest.kt",
                                    Path(__file__).resolve())},
    }
    # Preserve exact inputs even when a phase fails. A rebuilt APK must never
    # be described as the candidate that produced an earlier result.
    (args.output / "inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")

    def device(*parts, timeout=120):
        result = subprocess.run(adb + list(parts), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            (args.output / "device-command-failure.json").write_text(json.dumps({
                "operation": parts[0], "returncode": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr,
            }, indent=2) + "\n")
            raise RuntimeError("Device command failed; inspect device-command-failure.json")
        return result.stdout

    def fixture(path, method="GET"):
        request = Request(args.control.rstrip("/") + "/" + path, method=method)
        with urlopen(request, timeout=120) as response:
            return json.load(response)

    if device("shell", "getprop", "ro.kernel.qemu", timeout=15).strip() != "1":
        parser.error("Target is not an Android emulator")
    deadline = time.monotonic() + 180
    while device("shell", "getprop", "sys.boot_completed", timeout=15).strip() != "1":
        if time.monotonic() >= deadline:
            raise RuntimeError("Emulator did not complete boot")
        time.sleep(2)
    fixture("ready")
    for port in (control.port, args.relay_port):
        device("reverse", f"tcp:{port}", f"tcp:{port}")
    for apk in apks:
        device("install", "-r", "-t", str(apk), timeout=180)
    # Explicit emulator-only driver: no physical account or recordings touched.
    for package in (APP, APP + ".test"):
        device("shell", "pm", "clear", package)
        # Match the existing real-Link lab: avoid cold instrumentation dex
        # compilation consuming Android's application startup deadline.
        device("shell", "cmd", "package", "compile", "-m", "speed", "-f", package, timeout=180)
    device("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    device("shell", "wm", "dismiss-keyguard")
    steps = []
    for action, key, required in (
        ("recording-queue", "recording-node-queue", ["realLinkQueueAccepted", "custodyPending"]),
        ("recording-lost-withdrawal", "recording-node-lost-withdrawal", ["originalQueueSurvivedProcessRestart", "realNodeWithdrew", "lostReplyRemainsPending"]),
        ("recording-withdrawal-replay", "recording-node-withdrawal-replay", ["originalWithdrawalSurvivedProcessRestart", "realNodeAcceptedExactReplay", "pendingCustodyRetired"]),
    ):
        device("shell", "am", "force-stop", APP)
        result = device("shell", "am", "instrument", "-w", "-e", "class", PROBE,
                        "-e", "fixture_control", args.control.rstrip("/"), "-e", "g7_action", action,
                        APP + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=360)
        (args.output / (action + ".log")).write_text(result)
        if "OK (1 test)" not in result or "FAILURES" in result or "INSTRUMENTATION_FAILED" in result:
            for label, command in (("crash", ["logcat", "-d", "-b", "crash"]),
                                   ("exit-info", ["shell", "dumpsys", "activity", "exit-info", APP])):
                try:
                    diagnostic = subprocess.run(adb + command, capture_output=True, text=True, timeout=20)
                    (args.output / (action + "-" + label + ".log")).write_text(diagnostic.stdout + diagnostic.stderr)
                except subprocess.TimeoutExpired:
                    (args.output / (action + "-" + label + ".log")).write_text("Diagnostic command timed out\n")
            raise RuntimeError(f"{action} failed; inspect the private instrumentation log")
        answer = fixture("journey/" + key)
        if not all(answer.get(field) is True for field in required):
            raise RuntimeError(f"{action} did not prove its required custody transitions")
        steps.append({"action": action, "passed": True, "checks": required})
        print(action + ": passed", flush=True)
        if action == "recording-lost-withdrawal":
            if fixture("restart", method="POST").get("restarted") is not True:
                raise RuntimeError("The node did not confirm restart before withdrawal replay")
            steps.append({"action": "node-restart-before-withdrawal-replay", "passed": True})
    if [hashlib.sha256(p.read_bytes()).hexdigest() for p in apks] != inputs["apk_sha256"]:
        raise RuntimeError("APK inputs changed during qualification; no receipt issued")
    receipt = {"qualified": False, "physical_acceptance": False, "relay_delivery_qualified": False,
               "production_authorizer_qualified": False, "paired_link_custody_qualified": True,
               "steps": steps, **inputs,
               "pairing_capabilities_recorded": False,
               "scope": "Real JNI/Link and node router; fixture grant authorizer; reply deliberately dropped after real node withdrawal"}
    (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")


if __name__ == "__main__":
    main()
