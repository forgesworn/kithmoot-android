#!/usr/bin/env python3
"""Bound shutdown of this Linux CI job's exact emulator, never local devices."""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or sys.platform != "linux":
        raise RuntimeError("Shutdown is restricted to the dedicated Linux CI job")
    serial = os.environ["ANDROID_SERIAL"]
    avd = os.environ["KITHMOOT_RECORDING_AVD"]
    if not re.fullmatch(r"emulator-[0-9]+", serial) or avd not in {
        "kithmoot-recording-capture", "kithmoot-recording-session", "kithmoot-recording-codecs"
    }:
        raise RuntimeError("Expected the explicitly owned recording CI emulator")
    sdk = Path(os.environ["ANDROID_HOME"]).resolve()
    adb = sdk / "platform-tools/adb"

    def command(*args):
        return subprocess.run([str(adb), "-s", serial, *args], capture_output=True,
                              text=True, timeout=10, check=True).stdout.strip()

    if command("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Refusing shutdown of a physical device")
    if command("emu", "avd", "name").splitlines()[0] != avd:
        raise RuntimeError("The selected emulator belongs to another AVD")
    port = serial.removeprefix("emulator-")

    def identity(pid):
        p = Path("/proc") / str(pid)
        try:
            exe = (p / "exe").resolve(strict=True)
            args = (p / "cmdline").read_bytes().split(b"\0")
            stat = (p / "stat").read_text().rsplit(")", 1)[1].split()
            if stat[0] == "Z":
                return None
            return (p.stat().st_uid, str(exe), tuple(args), stat[19])
        except (FileNotFoundError, ProcessLookupError):
            return None

    candidates = []
    for p in Path("/proc").iterdir():
        if not p.name.isdecimal():
            continue
        try:
            if p.stat().st_uid != os.getuid():
                continue
            observed = identity(int(p.name))
        except (PermissionError, FileNotFoundError, ProcessLookupError):
            continue
        if observed is None or observed[0] != os.getuid():
            continue
        exe, args = Path(observed[1]), observed[2]
        if not exe.is_relative_to(sdk / "emulator/qemu") or not exe.name.startswith("qemu-system-"):
            continue
        pairs = list(zip(args, args[1:]))
        if (b"-avd", avd.encode()) in pairs and (b"-port", port.encode()) in pairs:
            candidates.append((int(p.name), observed))
    if len(candidates) != 1:
        raise RuntimeError("The exact owned emulator process could not be identified")
    pid, original = candidates[0]
    actions = []
    try:
        command("emu", "kill")
        actions.append("adb-emu-kill")
    except (subprocess.SubprocessError, OSError):
        actions.append("adb-emu-kill-unconfirmed")

    def stopped_after(seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            observed = identity(pid)
            if observed is None:
                return True
            if observed != original:
                raise RuntimeError("The original emulator process identity changed")
            time.sleep(0.25)
        return False

    stopped = stopped_after(20)
    for sig, seconds in [(signal.SIGTERM, 10), (signal.SIGKILL, 5)]:
        if stopped:
            break
        if identity(pid) != original:
            raise RuntimeError("Refusing to signal a changed process")
        try:
            os.kill(pid, sig)
        except ProcessLookupError:
            pass
        actions.append(sig.name)
        stopped = stopped_after(seconds)
    report = {"avd": avd, "serial": serial, "pid": pid, "actions": actions,
              "original_process_stopped": stopped, "physical": False}
    reports = Path(os.environ["KITHMOOT_RECORDING_REPORTS"])
    reports.mkdir(parents=True, exist_ok=True)
    (reports / "emulator-shutdown.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
    if not stopped:
        raise RuntimeError("The original emulator did not terminate")


if __name__ == "__main__":
    main()
