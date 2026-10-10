#!/usr/bin/env python3
"""Retain every result and retry only read-only proof copying, never a test."""
import argparse
from pathlib import Path
import re
import subprocess
import sys


def pull(adb, serial, source, destination, reports, server_port=None, run=subprocess.run):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Proof copying requires an explicitly selected emulator")
    prefix = [adb]
    if server_port is not None:
        if not re.fullmatch(r"[1-9][0-9]*", server_port):
            raise ValueError("Invalid adb server port")
        prefix += ["-P", server_port]
    prefix += ["-s", serial]
    guard = run(prefix + ["shell", "getprop", "ro.kernel.qemu"], capture_output=True, text=True, timeout=60)
    if guard.returncode != 0 or guard.stdout.strip() != "1":
        raise ValueError("Refusing proof copying from a non-emulator device")
    if not source.startswith("/sdcard/Android/data/dev.forgesworn.kithmoot/files/"):
        raise ValueError("Source must be this disposable app's proof directory")
    reports = Path(reports); reports.mkdir(parents=True, exist_ok=True)
    name = re.sub(r"[^a-zA-Z0-9_.-]", "_", source.rsplit("/", 1)[-1])
    command = prefix + ["pull", source, str(destination)]
    for attempt in range(1, 4):
        try:
            result = run(command, capture_output=True, text=True, timeout=60)
            output = result.stdout + result.stderr
            status = str(result.returncode)
        except subprocess.TimeoutExpired as error:
            def text(value):
                return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
            output = text(error.stdout) + text(error.stderr)
            status = "timeout"
        # Keep partial-copy/transport evidence before deciding whether to retry.
        report = reports / f"proof-pull-{name}-{attempt}.txt"
        report.write_text(f"attempt={attempt} status={status}\n" + output)
        print(f"Proof copy {name}, attempt {attempt}: {status}", flush=True)
        print(output, end="" if output.endswith("\n") else "\n", flush=True)
        if status == "0":
            return attempt
    raise RuntimeError(f"Required proof copy failed after three attempts: {name}")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", required=True)
    p.add_argument("--serial", required=True)
    p.add_argument("--source", required=True)
    p.add_argument("--destination", required=True)
    p.add_argument("--reports", required=True)
    p.add_argument("--server-port")
    a = p.parse_args()
    pull(a.adb, a.serial, a.source, a.destination, a.reports, a.server_port)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Recovery proof copy failed: {error}", file=sys.stderr)
        sys.exit(1)
