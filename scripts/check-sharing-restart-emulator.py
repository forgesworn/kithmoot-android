#!/usr/bin/env python3
"""SIGKILL active native sharing on a disposable emulator, then require recovery."""
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time

APP = "dev.forgesworn.kithmoot"
RUNNER = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
CASE = APP + ".ui.RoomSharingRestartTest"


def main():
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise RuntimeError("Set ANDROID_SERIAL to a disposable emulator")
    sdk = os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("Set ANDROID_HOME")
    adb = [str(Path(sdk) / "platform-tools/adb")]
    if os.environ.get("ANDROID_ADB_SERVER_PORT"):
        adb += ["-P", os.environ["ANDROID_ADB_SERVER_PORT"]]
    adb += ["-s", serial]
    reports = Path(__file__).resolve().parent.parent / "app/build/reports/sharing-restart-emulator"
    reports.mkdir(parents=True, exist_ok=True)

    def command(*args, timeout=20):
        return subprocess.run(adb + list(args), text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout)

    qemu = command("shell", "getprop", "ro.kernel.qemu")
    if qemu.returncode != 0 or qemu.stdout.strip() != "1":
        raise RuntimeError("Refusing process-kill acceptance on a physical device")
    prepare_seconds = int(os.environ.get("KITHMOOT_SHARING_PREPARE_SECONDS", "180"))
    death_seconds = int(os.environ.get("KITHMOOT_SHARING_DEATH_SECONDS", "15"))
    if not 1 <= prepare_seconds <= 180 or not 1 <= death_seconds <= 15:
        raise RuntimeError("Invalid bounded acceptance deadline")
    process = None
    try:
        process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "class", CASE + "#a_prepare", RUNNER],
                                   text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1)
        events = queue.Queue()

        def collect():
            with (reports / "prepare.txt").open("w") as output:
                for line in process.stdout:
                    output.write(line); output.flush()
                    print(line, end="", flush=True)
                    events.put(line.rstrip("\r\n"))
            events.put(None)

        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        deadline = time.monotonic() + prepare_seconds
        pid = None
        ready = False
        while not (ready and pid):
            if time.monotonic() >= deadline:
                raise RuntimeError("Active sharing checkpoint timed out")
            try:
                line = events.get(timeout=0.2)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError("Preparation ended without its active checkpoint")
            match = re.fullmatch(r"INSTRUMENTATION_STATUS: sharing_restart_pid=([1-9][0-9]*)", line)
            if match:
                pid = match.group(1)
            if line == "INSTRUMENTATION_STATUS: sharing_restart_checkpoint=ready":
                ready = True
        alive = command("shell", "pidof", APP)
        if alive.returncode != 0 or alive.stdout.split() != [pid] or process.poll() is not None:
            raise RuntimeError("Checkpoint PID is not the live app process")
        killed = command("shell", "run-as", APP, "kill", "-9", pid)
        if killed.returncode != 0:
            raise RuntimeError("SIGKILL of the active app failed")
        deadline = time.monotonic() + death_seconds
        while True:
            # Distinguish a confirmed absent PID from an adb/transport failure.
            probe = "pidof " + APP + "; result=$?; if [ \"$result\" = 1 ]; then echo KITHMOOT_SHARING_NO_PID; else exit \"$result\"; fi"
            alive = command("shell", probe)
            if alive.returncode != 0:
                raise RuntimeError("Could not verify app death after SIGKILL")
            if alive.stdout.strip() == "KITHMOOT_SHARING_NO_PID":
                break
            if not re.fullmatch(r"[1-9][0-9]*(?: [1-9][0-9]*)*", alive.stdout.strip()):
                raise RuntimeError("Invalid app death probe output")
            if time.monotonic() >= deadline:
                raise RuntimeError("App remained alive after SIGKILL")
            time.sleep(0.1)
        process.wait(timeout=20)
        reader.join(timeout=5)
        if reader.is_alive():
            raise RuntimeError("Preparing command did not terminate after SIGKILL")
        print(f"Observed SIGKILL of active sharing PID {pid}; requiring new-process recovery", flush=True)
        recovered = command("shell", "am", "instrument", "-w", "-e", "class", CASE + "#b_recover",
                            "-e", "requireRestart", "true", RUNNER, timeout=180)
        (reports / "recover.txt").write_text(recovered.stdout)
        print(recovered.stdout, flush=True)
        if recovered.returncode != 0 or not re.search(r"^OK \(1 tests?\)$", recovered.stdout.replace("\r", ""), re.M):
            raise RuntimeError("New-process sharing recovery did not pass exactly one case")
    except Exception:
        try:
            (reports / "failure-logcat.txt").write_text(command("logcat", "-d", "-t", "20000").stdout)
        except Exception:
            print("Could not capture failure logcat", file=sys.stderr)
        raise
    finally:
        # No intentional wait may leave the preparing app alive after a failure.
        try:
            stopped = command("shell", "am", "force-stop", APP)
            if stopped.returncode != 0:
                raise RuntimeError("Could not force-stop app during cleanup")
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)

    print("Active sharing SIGKILL and new-process recovery passed", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Sharing restart acceptance failed: {error}", file=sys.stderr)
        sys.exit(1)
