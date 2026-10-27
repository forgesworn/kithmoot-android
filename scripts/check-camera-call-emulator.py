#!/usr/bin/env python3
"""Run opt-in camera or call endurance checks on an explicitly selected emulator.

Install the matching app and instrumentation APKs first. This runner neither
installs nor clears applications and refuses physical devices.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", choices=["camera", "call"])
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--reports", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=2700)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Choose a disposable emulator explicitly")
    if not 60 <= args.seconds <= 2700:
        parser.error("--seconds must be between 60 and 2700")
    adb = [args.adb, "-s", args.serial]

    def shell(*command):
        return subprocess.run(adb + ["shell", *command], check=True,
                              capture_output=True, text=True, timeout=20).stdout.strip()

    if shell("getprop", "ro.kernel.qemu") != "1":
        parser.error("Refusing qualification on a physical device")
    if shell("getprop", "sys.boot_completed") != "1":
        parser.error("Wait for the selected emulator to finish booting")
    args.reports.mkdir(parents=True, exist_ok=True)
    package = "dev.forgesworn.kithmoot"
    command = adb + ["shell", "am", "instrument", "-w", "-r"]
    if args.suite == "camera":
        command += ["-e", "cameraProfile", "true", "-e", "class",
                    package + ".media.CameraLifecycleProfileTest"]
        filename = "camera-lifecycle-profile.json"
        timeout = 600
    else:
        command += ["-e", "callSoakSeconds", str(args.seconds), "-e", "class",
                    package + ".media.BrowserCallInteropTest#browserAndNativeSustainMediaAcrossRecovery"]
        filename = "call-soak.json"
        timeout = args.seconds + 240
    command += [package + ".test/androidx.test.runner.AndroidJUnitRunner"]
    # A previous successful report must never stand in for the current run.
    remote = f"/sdcard/Android/data/{package}/files/{filename}"
    shell("rm", "-f", remote)
    log = args.reports / f"{args.suite}-instrumentation.txt"
    try:
        with log.open("w") as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired:
        # The adb client timing out does not stop instrumentation on Android.
        shell("am", "force-stop", package)
        raise
    text = log.read_text()
    # am instrument returns zero for some crashes and skipped tests.
    if (result.returncode or not re.search(r"^OK \(1 test\)", text, re.M) or
            len(re.findall(r"^INSTRUMENTATION_STATUS_CODE: 0$", text, re.M)) != 1 or
            re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|^INSTRUMENTATION_STATUS_CODE: -[234]$", text, re.M)):
        raise RuntimeError(f"Qualification failed; inspect {log}\n{text[-8000:]}")
    destination = args.reports / filename
    subprocess.run(adb + ["pull", remote, str(destination)], check=True, timeout=20)
    report = json.loads(destination.read_text())
    if report.get("passed") is not True:
        raise RuntimeError("The device report does not confirm completion")
    if args.suite == "call" and report.get("elapsedMs", 0) < args.seconds * 1000:
        raise RuntimeError("The call ended before the requested duration")
    print(f"PASS: {args.suite}; report: {destination}")


if __name__ == "__main__":
    main()
