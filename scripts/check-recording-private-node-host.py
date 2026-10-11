"""Explicit synthetic large-transfer qualification; no Android or physical proof.

Requires Python cryptography, Node 24 with an explicitly selected nostr-tools
package, an existing wildbloomd binary and a synthetic MP4 of 1-256 MiB.
All storage, signing keys and TLS trust are temporary and local to this check.
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import struct
import subprocess
import tempfile
import time

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from recording_private_node_proxy import RecordingNodeProxy


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1048576), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--nostr-package", type=Path, required=True)
    parser.add_argument("--sample", type=Path, required=True)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--chunk-delay", type=float, default=0)
    args = parser.parse_args()
    size = args.sample.stat().st_size
    if not 1048576 <= size <= 256 * 1048576 or not 0 <= args.chunk_delay <= 0.1:
        raise ValueError("Choose a synthetic 1-256 MiB sample and delay 0-0.1 seconds")
    if not args.binary.is_file() or not os.access(args.binary, os.X_OK):
        raise ValueError("Existing executable private daemon required")
    if shutil.disk_usage(tempfile.gettempdir()).free < 1024 * 1048576:
        raise RuntimeError("One GiB temporary free space required")
    args.reports.mkdir(parents=True, exist_ok=True)
    daemon = proxy = None
    with tempfile.TemporaryDirectory(prefix="recording-host-node-") as directory:
        root = Path(directory)
        try:
            metadata = json.dumps({"name": "synthetic.mp4", "size": size, "type": "video/mp4"},
                                  separators=(",", ":")).encode()
            prefix = struct.pack(">I", len(metadata)) + metadata
            padded = ((size + len(prefix) + 1048575) // 1048576) * 1048576
            records = padded // 1048576
            salt, nonce = os.urandom(32), os.urandom(8)
            header = b"FSWNENC2" + struct.pack(">II", 1048576, records) + nonce + salt
            key = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt,
                       info=b"forgesworn-aes-256-gcm-chunked/v2").derive(os.urandom(32))
            aes = AESGCM(key)
            envelope = root / "synthetic.enc"
            with args.sample.open("rb") as source, envelope.open("wb") as output:
                output.write(header)
                for counter in range(records):
                    start = prefix if counter == 0 else b""
                    plain = start + source.read(1048576 - len(start))
                    plain += os.urandom(1048576 - len(plain))
                    index = struct.pack(">I", counter)
                    output.write(aes.encrypt(nonce + index, plain, header + index))
                if source.read(1):
                    raise RuntimeError("Source changed while sealing")
            expected = digest(envelope)
            subprocess.run(["openssl", "req", "-new", "-x509", "-newkey", "rsa:2048",
                            "-nodes", "-days", "1", "-subj", "/CN=127.0.0.1",
                            "-addext", "subjectAltName=IP:127.0.0.1",
                            "-keyout", str(root / "key.pem"), "-out", str(root / "cert.pem")],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                backend_port = reservation.getsockname()[1]
            proxy = RecordingNodeProxy(backend_port, "recording-ui-network-" + os.urandom(6).hex(),
                                       root / "cert.pem", root / "key.pem", port=0,
                                       chunk_delay=args.chunk_delay)
            proxy.start()
            port = proxy.server.server_address[1]
            origin = f"https://127.0.0.1:{port}"
            # The signing secret is generated inside this short-lived subprocess.
            # Only its public key and single-use synthetic bearer reach this process.
            signer = r"""
const {createRequire}=require('node:module');
let input='';process.stdin.on('data',c=>input+=c);process.stdin.on('end',()=>{
const p=JSON.parse(input),n=createRequire(p.package+'/package.json')('nostr-tools');
const sk=n.generateSecretKey(),now=Math.floor(Date.now()/1000);
const e=n.finalizeEvent({kind:24242,created_at:now,content:'Synthetic recording qualification',
tags:[['t','upload'],['x',p.hash],['server',p.origin],['expiration',String(now+300)]]},sk);
process.stdout.write(JSON.stringify({pubkey:e.pubkey,auth:'Nostr '+Buffer.from(JSON.stringify(e)).toString('base64url'),expires:now+300}));
});"""
            signed = json.loads(subprocess.run(["node", "-e", signer], input=json.dumps({
                "package": str(args.nostr_package.resolve()), "hash": expected, "origin": origin}),
                text=True, capture_output=True, check=True, timeout=30).stdout)
            env = {k: v for k, v in os.environ.items() if not k.startswith("WILDBLOOM_")}
            env["RUST_LOG"] = "warn"
            with (args.reports / "daemon.log").open("wb") as log:
                daemon = subprocess.Popen([str(args.binary.resolve()), "--no-tor", "--bind",
                    f"127.0.0.1:{backend_port}", "--public-url", origin, "--allow-pubkey", signed["pubkey"],
                    "--data-dir", str(root / "data"), "--quota-bytes", str(520 * 1048576),
                    "--max-blob-bytes", str(260 * 1048576), "--repair-interval", "0"],
                    env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 20
                while True:
                    try:
                        with socket.create_connection(("127.0.0.1", backend_port), timeout=1):
                            break
                    except OSError:
                        if daemon.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError("Private daemon did not become ready")
                        time.sleep(0.1)
                context = ssl.create_default_context(cafile=str(root / "cert.pem"))
                connection = http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=600)
                started = time.monotonic()
                try:
                    with envelope.open("rb") as source:
                        connection.request("PUT", "/upload", body=source, headers={
                            "Authorization": signed["auth"], "X-SHA-256": expected,
                            "Content-Type": "application/octet-stream", "Content-Length": str(envelope.stat().st_size)})
                    response = connection.getresponse()
                    body = response.read(65537)
                    if response.status != 201 or len(body) > 65536:
                        raise RuntimeError(f"Private upload did not succeed: HTTP {response.status}")
                    descriptor = json.loads(body)
                    if descriptor["sha256"] != expected or descriptor["size"] != envelope.stat().st_size:
                        raise RuntimeError("Daemon descriptor differs from sealed bytes")
                finally:
                    connection.close()
                upload_seconds = time.monotonic() - started
                expired_at_receipt = time.time() >= signed["expires"]
                del signed
                connection = http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=600)
                ciphertext, plaintext = hashlib.sha256(), hashlib.sha256()
                remaining = size
                try:
                    connection.request("GET", f"/{expected}.bin")
                    response = connection.getresponse()
                    if response.status != 200:
                        raise RuntimeError(f"Private download failed: HTTP {response.status}")
                    received_header = response.read(56)
                    if received_header != header:
                        raise RuntimeError("Downloaded envelope header changed")
                    ciphertext.update(received_header)
                    for counter in range(records):
                        sealed = response.read(1048576 + 16)
                        ciphertext.update(sealed)
                        index = struct.pack(">I", counter)
                        opened = aes.decrypt(nonce + index, sealed, header + index)
                        if counter == 0:
                            if opened[:len(prefix)] != prefix:
                                raise RuntimeError("Authenticated metadata changed")
                            opened = opened[len(prefix):]
                        count = min(remaining, len(opened))
                        plaintext.update(opened[:count]); remaining -= count
                    if response.read(1) or remaining or ciphertext.hexdigest() != expected:
                        raise RuntimeError("Downloaded ciphertext differs from upload")
                    if plaintext.hexdigest() != digest(args.sample):
                        raise RuntimeError("Authenticated plaintext differs from source")
                finally:
                    connection.close()
            result = {"passed": True, "android": False, "physical": False,
                "scope": "Independent Python envelope and host TLS streaming against real private daemon; no native UI/capture proof",
                "sample_bytes": size, "ciphertext_bytes": envelope.stat().st_size,
                "source_sha256": digest(args.sample), "ciphertext_sha256": expected,
                "daemon_sha256": digest(args.binary), "driver_sha256": digest(Path(__file__)),
                "chunk_delay_seconds": args.chunk_delay, "upload_seconds": upload_seconds,
                "authorisation_expired_at_receipt": expired_at_receipt, "proxy": proxy.snapshot()}
        finally:
            if daemon is not None and daemon.poll() is None:
                daemon.terminate()
                try:
                    daemon.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    daemon.kill(); daemon.wait(timeout=10)
            if proxy is not None:
                proxy.close()
    result["cleanup"] = {"daemon_stopped": daemon.poll() is not None,
                         "proxy_stopped": not proxy.thread.is_alive(),
                         "temporary_data_removed": not root.exists()}
    if not all(result["cleanup"].values()):
        raise RuntimeError("Private fixture cleanup did not complete")
    (args.reports / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print("Real private-node host transfer and authenticated plaintext passed")


if __name__ == "__main__":
    main()
