#!/usr/bin/env python3
"""Stream one disposable-emulator test batch and preserve a timed-out process."""
import argparse
import json
import re
import subprocess
import sys
import threading
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--server-port', type=int)
    parser.add_argument('--reports', type=Path, required=True)
    parser.add_argument('--report', required=True)
    parser.add_argument('--deadline-seconds', type=int, default=300)
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('Refusing instrumentation on a non-emulator serial')
    if not re.fullmatch(r'[a-z0-9-]+', args.report):
        parser.error('Use a simple report name without directory components')
    if not 1 <= args.deadline_seconds <= 900:
        parser.error('The batch deadline must be between 1 and 900 seconds')
    adb = [args.adb]
    if args.server_port is not None:
        if not 1 <= args.server_port <= 65535:
            parser.error('Invalid adb server port')
        adb += ['-P', str(args.server_port)]
    adb += ['-s', args.serial]
    if subprocess.check_output(adb + ['shell', 'getprop', 'ro.kernel.qemu'], timeout=20).strip() != b'1':
        parser.error('Refusing instrumentation on a non-emulator device')
    args.reports.mkdir(parents=True, exist_ok=True)
    extra = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
    command = adb + ['shell', 'am', 'instrument', '-w', '-r'] + extra + [
        'dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner']
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def stream():
        # Flush each status line rather than buffering it in a tr pipeline.
        for line in iter(process.stdout.readline, b''):
            sys.stdout.buffer.write(line.replace(b'\r', b''))
            sys.stdout.buffer.flush()

    reader = threading.Thread(target=stream, daemon=True)
    reader.start()
    try:
        code = process.wait(timeout=args.deadline_seconds)
        reader.join(timeout=5)
        return code
    except subprocess.TimeoutExpired:
        print(f'Instrumentation batch exceeded {args.deadline_seconds}s: {args.report}', flush=True)
        diagnostic_errors = []

        def capture(suffix, arguments, timeout=20):
            path = args.reports / f'{args.report}-timeout-{suffix}'
            try:
                with path.open('wb') as output:
                    result = subprocess.run(adb + arguments, stdout=output,
                                            stderr=subprocess.PIPE, timeout=timeout)
                if result.returncode:
                    diagnostic_errors.append(f'{suffix}: {result.stderr.decode(errors="replace").strip()}')
            except (OSError, subprocess.TimeoutExpired) as error:
                diagnostic_errors.append(f'{suffix}: {error}')

        capture('logcat.txt', ['logcat', '-d', '-t', '20000'])
        capture('processes.txt', ['shell', 'dumpsys', 'activity', 'processes'])
        capture('screen.png', ['exec-out', 'screencap', '-p'])
        try:
            pids = subprocess.check_output(adb + ['shell', 'pidof', 'dev.forgesworn.kithmoot'], timeout=15).decode().split()
            if pids and all(re.fullmatch(r'[0-9]+', pid) for pid in pids):
                # The debug app requests its own ART stacks; adbd need not restart.
                capture('thread-request.txt', ['shell', 'run-as', 'dev.forgesworn.kithmoot', 'kill', '-3'] + pids)
                time.sleep(2)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            diagnostic_errors.append(f'thread request: {error}')
        # A bugreport retrieves /data/anr without destroying the blocked invocation.
        capture('bugreport.txt', ['bugreport', str(args.reports / f'{args.report}-timeout-bugreport.zip')], timeout=60)
        (args.reports / f'{args.report}-timeout.json').write_text(json.dumps({
            'serial': args.serial, 'batch': args.report, 'deadlineSeconds': args.deadline_seconds,
            'diagnosticErrors': diagnostic_errors,
        }, indent=2) + '\n')
        return 124
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        reader.join(timeout=5)
        process.stdout.close()


if __name__ == '__main__':
    sys.exit(main())
