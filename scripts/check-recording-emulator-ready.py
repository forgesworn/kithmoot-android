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
        command = [adb, "-s", serial, "shell", *args]
        try:
            result = subprocess.run(command, check=True, capture_output=True,
                                    text=True, timeout=15)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            # A failed home launch used to lose its actual Android error and
            # leave only the command's exit code in the hosted job log.
            def diagnostic(value):
                if isinstance(value, bytes):
                    value = value.decode("utf-8", errors="replace")
                return (value or "")[-8192:]
            (reports / "launcher-command-failure.json").write_text(json.dumps({
                "arguments": list(args), "returncode": getattr(error, "returncode", None),
                "timeout_seconds": getattr(error, "timeout", None),
                "stdout": diagnostic(error.stdout),
                "stderr": diagnostic(error.stderr),
            }, indent=2) + "\n")
            raise
        return result.stdout.strip()

    if shell("getprop", "ro.kernel.qemu") != "1" or shell("getprop", "sys.boot_completed") != "1":
        raise ValueError("The selected disposable emulator has not booted")
    shell("input", "keyevent", "82")
    # AOSP's boot-completed flag can precede SdkSetup finishing provisioning.
    # That temporary HOME disables itself; launching its previously resolved
    # component races its removal. Wait for the real launcher without changing
    # provisioning settings or treating the setup activity as ready.
    setup_deadline = time.monotonic() + 30
    home_resolutions = []
    while True:
        resolved = shell("cmd", "package", "resolve-activity", "--brief", "--user", "0",
                         "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()
        home = resolved[-1] if resolved else ""
        home_resolutions.append(home)
        (reports / "launcher-home-resolution.json").write_text(json.dumps(home_resolutions) + "\n")
        if not re.fullmatch(r"[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+", home):
            raise ValueError("The emulator did not resolve a home activity")
        if not home.startswith("com.android.sdksetup/"):
            break
        if time.monotonic() >= setup_deadline:
            raise RuntimeError("Emulator setup never finished; recording UI tests were not started")
        time.sleep(1)
    package = home.split("/")[0]
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
                                          "home_resolution_probes": len(home_resolutions),
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
