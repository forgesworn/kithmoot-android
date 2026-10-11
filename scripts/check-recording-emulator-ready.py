#!/usr/bin/env python3
"""Warm and verify a disposable emulator's launcher before recording UI tests."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time


def focused_home(windows, package):
    focus = re.search(r"mCurrentFocus=(.*)", windows)
    if not focus:
        return False
    value = focus.group(1)
    return package + "/" in value and "application error" not in value.lower()


def check(adb, serial, reports):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Choose a disposable emulator explicitly")
    reports.mkdir(parents=True, exist_ok=True)
    receipt = reports / "launcher-readiness.json"
    receipt.unlink(missing_ok=True)

    def shell(*args):
        return subprocess.run([adb, "-s", serial, "shell", *args], check=True,
                              capture_output=True, text=True, timeout=15).stdout.strip()

    if shell("getprop", "ro.kernel.qemu") != "1" or shell("getprop", "sys.boot_completed") != "1":
        raise ValueError("The selected disposable emulator has not booted")
    home = shell("cmd", "package", "resolve-activity", "--brief", "--user", "0",
                 "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()[-1]
    if not re.fullmatch(r"[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+", home):
        raise ValueError("The emulator did not resolve a home activity")
    package = home.split("/")[0]
    shell("input", "keyevent", "82")
    shell("am", "start", "-W", "--user", "0", "-a", "android.intent.action.MAIN",
          "-c", "android.intent.category.HOME", "-n", home)
    deadline = time.monotonic() + 30
    stable = 0
    probes = 0
    restarted = False
    while time.monotonic() < deadline:
        windows = shell("dumpsys", "window")
        (reports / "launcher-window.txt").write_text(windows)
        probes += 1
        focus = re.search(r"mCurrentFocus=(.*)", windows)
        if (not restarted and focus and
                "application not responding: " + package in focus.group(1).lower()):
            # A cold emulator can ANR its launcher before our app starts.
            # Keep the failure evidence and recover this owned launcher once;
            # the restarted activity must still pass both focus checks.
            (reports / "launcher-before-restart-window.txt").write_text(windows)
            (reports / "launcher-before-restart-anr.txt").write_text(
                shell("dumpsys", "activity", "lastanr"))
            shell("am", "force-stop", "--user", "0", package)
            shell("am", "start", "-W", "--user", "0", "-a", "android.intent.action.MAIN",
                  "-c", "android.intent.category.HOME", "-n", home)
            restarted = True
            stable = 0
            deadline = time.monotonic() + 30
            continue
        stable = stable + 1 if focused_home(windows, package) else 0
        if stable >= 2:
            receipt.write_text(json.dumps({"passed": True, "serial": serial,
                                          "home": home, "probes": probes,
                                          "launcher_restarted": restarted,
                                          "stable_focus_checks": stable}, indent=2) + "\n")
            return
        time.sleep(1)
    raise RuntimeError("Emulator home never acquired stable focus; recording UI tests were not started")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--reports", required=True, type=Path)
    args = parser.parse_args()
    check(args.adb, args.serial, args.reports)
