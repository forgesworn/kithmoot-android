#!/usr/bin/env python3
"""SIGKILL an active owner on a disposable emulator, then require recovery."""
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


def main(profile="sharing"):
    profiles = {
        "sharing": (CASE, "sharing", "KITHMOOT_SHARING", None),
        "native-host": (APP + ".ui.NativeHostRestartTest", "native_host", "KITHMOOT_NATIVE_HOST", None),
        "native-rekey-before": (APP + ".epoch.NativeRekeyRestartTest", "native_rekey", "KITHMOOT_NATIVE_REKEY", "before-handoff"),
        "native-rekey-after": (APP + ".epoch.NativeRekeyRestartTest", "native_rekey", "KITHMOOT_NATIVE_REKEY", "after-handoff"),
        **{"native-retirement-" + mode: (APP + ".epoch.NativeRetirementRestartTest",
            "native_retirement", "KITHMOOT_NATIVE_RETIREMENT", mode) for mode in
            ("pending-original", "reserved-before-offer", "offered-before-archive", "archive-before-hint")},
    }
    if profile not in profiles:
        raise RuntimeError("Unknown process-restart acceptance profile")
    case, marker, deadline_env, mode = profiles[profile]
    mode_args = [] if mode is None else ["-e", "transitionMode", mode]
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
    reports = Path(__file__).resolve().parent.parent / "app/build/reports" / (profile + "-restart-emulator")
    reports.mkdir(parents=True, exist_ok=True)

    def command(*args, timeout=20):
        return subprocess.run(adb + list(args), text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=timeout)

    qemu = command("shell", "getprop", "ro.kernel.qemu")
    if qemu.returncode != 0 or qemu.stdout.strip() != "1":
        raise RuntimeError("Refusing process-kill acceptance on a physical device")
    prepare_seconds = int(os.environ.get(deadline_env + "_PREPARE_SECONDS", "180"))
    death_seconds = int(os.environ.get(deadline_env + "_DEATH_SECONDS", "15"))
    if not 1 <= prepare_seconds <= 180 or not 1 <= death_seconds <= 15:
        raise RuntimeError("Invalid bounded acceptance deadline")
    process = None
    try:
        process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "class", case + "#a_prepare"] + mode_args + [RUNNER],
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
        reported_mode = None
        while not (ready and pid and (mode is None or reported_mode == mode)):
            if time.monotonic() >= deadline:
                raise RuntimeError("Active " + profile + " checkpoint timed out")
            try:
                line = events.get(timeout=0.2)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError("Preparation ended without its active checkpoint")
            # AndroidJUnitRunner can prefix its first status line with the
            # current class name. Accept only that exact known prefix.
            if line.startswith(case + ":"):
                line = line[len(case) + 1:]
            match = re.fullmatch(r"INSTRUMENTATION_STATUS: " + marker + r"_restart_pid=([1-9][0-9]*)", line)
            if match:
                if pid is not None and pid != match.group(1):
                    raise RuntimeError("Conflicting active checkpoint PIDs")
                pid = match.group(1)
            if line == "INSTRUMENTATION_STATUS: " + marker + "_restart_checkpoint=ready":
                ready = True
            if mode is not None and line.startswith("INSTRUMENTATION_STATUS: " + marker + "_restart_mode="):
                if reported_mode is not None or line != "INSTRUMENTATION_STATUS: " + marker + "_restart_mode=" + mode:
                    raise RuntimeError("Invalid or duplicate transition checkpoint mode")
                reported_mode = mode
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
        print(f"Observed SIGKILL of active {profile} PID {pid}; requiring new-process recovery", flush=True)
        recovered = command("shell", "am", "instrument", "-w", "-e", "class", case + "#b_recover",
                            "-e", "requireRestart", "true", *mode_args, RUNNER, timeout=180)
        (reports / "recover.txt").write_text(recovered.stdout)
        print(recovered.stdout, flush=True)
        if recovered.returncode != 0 or not re.search(r"^OK \(1 tests?\)$", recovered.stdout.replace("\r", ""), re.M):
            raise RuntimeError("New-process " + profile + " recovery did not pass exactly one case")
        recovery_lines = recovered.stdout.replace("\r", "").splitlines()
        recovery_pids = []
        recovery_modes = []
        for line in recovery_lines:
            if line.startswith(case + ":"):
                line = line[len(case) + 1:]
            match = re.fullmatch(r"INSTRUMENTATION_STATUS: " + marker + r"_recovery_pid=([1-9][0-9]*)", line)
            if match:
                recovery_pids.append(match.group(1))
            if mode is not None and line.startswith("INSTRUMENTATION_STATUS: " + marker + "_recovery_mode="):
                recovery_modes.append(line.split("=", 1)[1])
        if len(recovery_pids) != 1 or recovery_pids[0] == pid:
            raise RuntimeError("Recovery did not report exactly one different process PID")
        if mode is not None and recovery_modes != [mode]:
            raise RuntimeError("Recovery did not report exactly the requested transition mode")
    except Exception:
        try:
            (reports / "failure-logcat.txt").write_text(command("logcat", "-d", "-t", "20000").stdout)
        except Exception:
            print("Could not capture failure logcat", file=sys.stderr)
        raise
    finally:
        primary = sys.exc_info()[1]
        cleanup_errors = []
        # No intentional wait may leave the preparing app alive after a failure.
        try:
            stopped = command("shell", "am", "force-stop", APP)
            if stopped.returncode != 0:
                raise RuntimeError("Could not force-stop app during cleanup")
        except Exception as error:
            cleanup_errors.append(error)
        if profile.startswith("native-"):
            # Attempt key deletion even when force-stop itself fails.
            try:
                cleared = command("shell", "pm", "clear", APP)
                if cleared.returncode != 0 or cleared.stdout.strip() != "Success":
                    raise RuntimeError("Could not delete native host lab data and keys")
            except Exception as error:
                cleanup_errors.append(error)
        try:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)
        except Exception as error:
            cleanup_errors.append(error)
        if cleanup_errors:
            if primary is not None:
                for error in cleanup_errors:
                    primary.add_note("Secondary cleanup failure: " + str(error))
            else:
                first = cleanup_errors[0]
                for error in cleanup_errors[1:]:
                    first.add_note("Secondary cleanup failure: " + str(error))
                raise first

    print("Active " + profile + " SIGKILL and new-process recovery passed", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Sharing restart acceptance failed: {error}", file=sys.stderr)
        sys.exit(1)
