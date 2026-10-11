#!/usr/bin/env python3
"""UI Upload/Send and recipient Show/Play through a real private Wildbloom daemon.

Uses a deliberately selected disposable emulator, synthetic MP4, ephemeral TLS
and an explicitly authorised storage identity. No public node or discovery.
The recipient uses its own RoomSession/socket in a separate Android process.
Both processes share one debug installation on the selected emulator.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

from recording_private_node_proxy import RecordingNodeProxy


def required(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"Set {name}")
    return value


def digest(path):
    checksum = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def copy_fixture(adb, serial, target, source):
    """Stream synthetic inputs without retaining a whole recording in host RAM."""
    command = [adb, "-s", serial, "shell", "-T", "run-as",
               "dev.forgesworn.kithmoot", "sh", "-c", f"'cat > {target}'"]
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE) as process:
        try:
            with Path(source).open("rb") as stream:
                shutil.copyfileobj(stream, process.stdin, length=1024 * 1024)
            process.stdin.close()
            process.stdin = None
            output, error = process.communicate(timeout=30)
            if process.returncode:
                raise subprocess.CalledProcessError(process.returncode, command, output, error)
        except BaseException:
            if process.poll() is None:
                process.kill()
            process.wait()
            raise


def main():
    os.umask(0o077)
    serial = required("ANDROID_SERIAL")
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise RuntimeError("Choose a disposable emulator explicitly")
    adb = str(Path(required("ANDROID_HOME")) / "platform-tools/adb")
    binary = Path(required("KITHMOOT_WILDBLOOM_BINARY")).resolve()
    sample = Path(required("KITHMOOT_RECORDING_SAMPLE")).resolve()
    host_mode = os.environ.get("KITHMOOT_RECORDING_HOST_PROXY", "0")
    if host_mode not in ("0", "1"):
        raise RuntimeError("Choose host proxy mode 0 or 1")
    host_mode = host_mode == "1"
    delay = float(os.environ.get("KITHMOOT_RECORDING_PROXY_CHUNK_DELAY", "0"))
    if not 0 <= delay <= 0.1 or (delay and not host_mode):
        raise RuntimeError("Choose a host-proxy chunk delay between 0 and 0.1 seconds")
    if not binary.is_file() or not os.access(binary, os.X_OK) or not sample.is_file():
        raise RuntimeError("Compiled private-node daemon and synthetic MP4 are required")
    if host_mode and not 0 < sample.stat().st_size <= 256 * 1024 * 1024:
        raise RuntimeError("Synthetic sample must fit the production plaintext recording limit")
    root = Path(__file__).resolve().parent.parent
    reports = Path(os.environ.get("KITHMOOT_RECORDING_REPORTS",
                                  str(root / "app/build/reports/native-recording-private-node"))).resolve()
    reports.mkdir(parents=True, exist_ok=True)

    def device(*args, **kwargs):
        return subprocess.run([adb, "-s", serial, *args], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)

    for prop, expected in [("ro.kernel.qemu", "1"), ("sys.boot_completed", "1")]:
        if device("shell", "getprop", prop).stdout.decode().strip() != expected:
            raise RuntimeError("Selected disposable emulator must be booted")
    for apk in ["app/build/outputs/apk/debug/app-debug.apk",
                "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        device("install", "-r", "-d", str(root / apk))
    fixture = "recording-ui-network-" + os.urandom(6).hex()
    fixture_path = "no_backup/" + fixture
    daemon = None
    instrumentation = None
    proxy = None
    reverse_created = False
    with tempfile.TemporaryDirectory(prefix="kithmoot-private-node-ui-") as scratch_name:
        scratch = Path(scratch_name)
        config = scratch / "openssl.cnf"
        config.write_text("""[req]
distinguished_name = dn
x509_extensions = v3
prompt = no
[dn]
CN = 127.0.0.1
[v3]
subjectAltName = IP:127.0.0.1
basicConstraints = critical,CA:true
keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth
""")
        try:
            with (reports / "tls-generation.txt").open("wb") as log:
                subprocess.run(["openssl", "req", "-new", "-x509", "-newkey", "rsa:2048", "-nodes",
                                "-days", "1", "-config", str(config), "-keyout", str(scratch / "key.pem"),
                                "-out", str(scratch / "cert.pem")], check=True, stdout=log, stderr=log)
            subprocess.run(["openssl", "pkcs12", "-export", "-inkey", str(scratch / "key.pem"),
                            "-in", str(scratch / "cert.pem"), "-name", "recording-ui-fixture",
                            "-passout", "pass:synthetic-test-only", "-keypbe", "PBE-SHA1-3DES",
                            "-certpbe", "PBE-SHA1-3DES", "-macalg", "sha1",
                            "-out", str(scratch / "fixture.p12")], check=True)
            device("shell", "run-as", "dev.forgesworn.kithmoot", "mkdir", "-p", fixture_path)
            for name, source in [("fixture.p12", scratch / "fixture.p12"), ("sample.mp4", sample)]:
                # Both path components are generated constants; no user text enters shell code.
                copy_fixture(adb, serial, f"{fixture_path}/{name}", source)
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                backend_port = reservation.getsockname()[1]
            if host_mode:
                mappings = device("reverse", "--list").stdout.decode().splitlines()
                if any(len(line.split()) >= 2 and line.split()[-2] == "tcp:39847" for line in mappings):
                    raise RuntimeError("Refusing to replace an existing device reverse mapping")
                proxy = RecordingNodeProxy(backend_port, fixture, scratch / "cert.pem", scratch / "key.pem",
                                           chunk_delay=delay)
                proxy.start()
                device("reverse", "tcp:39847", "tcp:39847")
                reverse_created = True
            (reports / "fixture.json").write_text(json.dumps({"fixture": fixture}) + "\n")
            with (reports / "instrumentation.txt").open("wb") as log:
                instrumentation = subprocess.Popen([
                    adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
                    "-e", "recordingNetworkFixture", fixture, "-e", "recordingRealNode", "true",
                    "-e", "recordingHostProxy", "true" if host_mode else "false",
                    "-e", "recordingNodePort", str(backend_port),
                    "-e", "class", "dev.forgesworn.kithmoot.media.recording.RecordingUploadSendUiTest#"
                    "separate_process_recipient_receives_and_plays_without_sender_memory",
                    "dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner"],
                    stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 90
                public_key = None
                while instrumentation.poll() is None and time.monotonic() < deadline:
                    result = subprocess.run([adb, "-s", serial, "shell", "run-as",
                                             "dev.forgesworn.kithmoot", "cat",
                                             fixture_path + "/storage-public-key.txt"],
                                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                    key = result.stdout.decode().strip()
                    if result.returncode == 0 and re.fullmatch(r"[0-9a-f]{64}", key):
                        public_key = key
                        break
                    time.sleep(0.2)
                if public_key is None:
                    raise RuntimeError("UI did not reach storage-key authorisation checkpoint")
                env = {k: v for k, v in os.environ.items() if not k.startswith("WILDBLOOM_")}
                env["RUST_LOG"] = "warn"
                with (reports / "daemon.log").open("wb") as daemon_log:
                    daemon = subprocess.Popen([
                        str(binary), "--no-tor", "--bind", f"127.0.0.1:{backend_port}",
                        "--public-url", "https://127.0.0.1:39847", "--allow-pubkey", public_key,
                        "--data-dir", str(scratch / "node-data"), "--quota-bytes",
                        str(520 * 1024 * 1024 if host_mode else 4194304),
                        "--max-blob-bytes", str(260 * 1024 * 1024 if host_mode else 2097152),
                        "--repair-interval", "0"],
                        env=env, stdout=daemon_log, stderr=subprocess.STDOUT)
                    ready = False
                    deadline = time.monotonic() + 15
                    while daemon.poll() is None and time.monotonic() < deadline:
                        try:
                            with urllib.request.urlopen(f"http://127.0.0.1:{backend_port}/", timeout=1):
                                ready = True
                                break
                        except Exception:
                            time.sleep(0.1)
                    if not ready:
                        raise RuntimeError("Private daemon did not become ready")
                    device("shell", "run-as", "dev.forgesworn.kithmoot", "touch", fixture_path + "/node-ready")
                    instrumentation.wait(timeout=1500 if host_mode else 180)
            output = (reports / "instrumentation.txt").read_text()
            if instrumentation.returncode != 0 or not re.search(r"^OK \(1 tests?\)$", output, re.M) or re.search(
                    r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|AssumptionViolated|INSTRUMENTATION_STATUS_CODE: -3", output):
                raise RuntimeError("Real-node recipient UI failed; inspect instrumentation report")
            responses = proxy.snapshot()["responses"] if host_mode else device(
                "shell", "run-as", "dev.forgesworn.kithmoot", "cat",
                fixture_path + "/node-responses.txt").stdout.decode().splitlines()
            if responses != ["PUT 201", "GET 200"]:
                raise RuntimeError("Expected one real-node PUT 201 then GET 200")
            sender_pid = int(device("shell", "run-as", "dev.forgesworn.kithmoot", "cat",
                                    fixture_path + "/sender-process.txt").stdout.decode())
            recipient = json.loads(device("shell", "run-as", "dev.forgesworn.kithmoot", "cat",
                                          fixture_path + "/recipient-ready.json").stdout.decode())
            if sender_pid <= 0 or recipient["pid"] <= 0 or sender_pid == recipient["pid"]:
                raise RuntimeError("Recipient must run in a separate process")
            result = {"passed": True, "physical": False, "serial": serial,
                      "real_wildbloom_daemon": True, "separate_recipient_process": True,
                      "sender_pid": sender_pid, "recipient_pid": recipient["pid"],
                      "host_streaming_proxy": host_mode, "sample_bytes": sample.stat().st_size,
                      "proxy_chunk_delay_seconds": delay,
                      "responses": responses, "sample_sha256": digest(sample),
                      "daemon_sha256": digest(binary), "report_sha256": digest(reports / "instrumentation.txt")}
            (reports / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print("Private Wildbloom UI Upload/Send and recipient Show/Play passed")
        finally:
            active_error = sys.exc_info()[0] is not None
            cleanup_codes = {}
            cleanup_errors = []

            def attempt(label, action):
                try:
                    return action()
                except Exception as error:
                    cleanup_errors.append({"action": label, "error": type(error).__name__})
                    return -1

            def stop_process(process):
                if process.poll() is not None:
                    return
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)

            if instrumentation is not None:
                # ADB may exit while its on-device instrumentation still runs.
                cleanup_codes["app_force_stop"] = attempt("app_force_stop", lambda: subprocess.run(
                    [adb, "-s", serial, "shell", "am", "force-stop", "dev.forgesworn.kithmoot"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode)
                attempt("instrumentation_stopped", lambda: stop_process(instrumentation))
            if daemon is not None:
                attempt("daemon_stopped", lambda: stop_process(daemon))
            if proxy is not None:
                attempt("proxy_report", lambda: (reports / "proxy.json").write_text(
                    json.dumps(proxy.snapshot(), indent=2) + "\n"))
                attempt("proxy_stopped", proxy.close)
            if reverse_created:
                cleanup_codes["reverse_removed"] = attempt("reverse_removed", lambda: subprocess.run(
                    [adb, "-s", serial, "reverse", "--remove", "tcp:39847"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode)
            cleanup_codes["fixture_removed"] = attempt("fixture_removed", lambda: subprocess.run(
                [adb, "-s", serial, "shell", "run-as", "dev.forgesworn.kithmoot", "rm", "-rf", fixture_path],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode)
            cleanup = {"device_exit_codes": cleanup_codes,
                       "errors": cleanup_errors,
                       "daemon_stopped": daemon is None or daemon.poll() is not None,
                       "proxy_stopped": proxy is None or not proxy.thread.is_alive()}
            attempt("cleanup_report", lambda: (reports / "cleanup.json").write_text(json.dumps(cleanup, indent=2) + "\n"))
            if not active_error and (cleanup_errors or any(cleanup_codes.values()) or
                                     not cleanup["daemon_stopped"] or not cleanup["proxy_stopped"]):
                raise RuntimeError("Private fixture cleanup incomplete; inspect cleanup report")


if __name__ == "__main__":
    main()
