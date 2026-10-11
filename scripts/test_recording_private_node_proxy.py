"""Socket-level fixture checks; these do not qualify the Wildbloom daemon."""
import hashlib
import http.client
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from recording_private_node_proxy import RecordingNodeProxy


class StreamingProxyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix="kithmoot-proxy-socket-check-")
        cls.root = Path(cls.scratch.name)
        config = cls.root / "openssl.cnf"
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
        cls.certificate = cls.root / "cert.pem"
        cls.key = cls.root / "key.pem"
        subprocess.run(["openssl", "req", "-new", "-x509", "-newkey", "rsa:2048",
                        "-nodes", "-days", "1", "-config", str(config),
                        "-keyout", str(cls.key), "-out", str(cls.certificate)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.source = cls.root / "synthetic.bin"
        with cls.source.open("wb") as stream:
            stream.write(b"FSWNENC2")
            chunk = bytes(range(256)) * 256
            remaining = 8 * 1024 * 1024 - 8
            while remaining:
                piece = chunk[:min(remaining, len(chunk))]
                stream.write(piece)
                remaining -= len(piece)
        cls.expected = cls.digest(cls.source)
        cls.context = ssl.create_default_context(cafile=str(cls.certificate))

    @classmethod
    def tearDownClass(cls):
        cls.scratch.cleanup()

    @staticmethod
    def digest(path):
        checksum = hashlib.sha256()
        with Path(path).open("rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                checksum.update(chunk)
        return checksum.hexdigest()

    def setUp(self):
        self.backend_puts = 0
        self.backend_gets = 0
        self.received = self.root / "received.bin"
        owner = self

        class Backend(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_PUT(self):
                owner.backend_puts += 1
                remaining = int(self.headers["Content-Length"])
                with owner.received.open("wb") as stream:
                    while remaining:
                        chunk = self.rfile.read(min(remaining, 65536))
                        if not chunk:
                            return
                        stream.write(chunk)
                        remaining -= len(chunk)
                body = json.dumps({"source": "synthetic-backend", "size": owner.received.stat().st_size}).encode()
                self.send_response(202)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except BrokenPipeError:
                    pass

            def do_GET(self):
                owner.backend_gets += 1
                self.send_response(200)
                self.send_header("Content-Length", str(owner.received.stat().st_size))
                self.end_headers()
                with owner.received.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(65536), b""):
                        self.wfile.write(chunk)

        self.backend = ThreadingHTTPServer(("127.0.0.1", 0), Backend)
        self.backend.daemon_threads = True
        self.backend_thread = threading.Thread(target=self.backend.serve_forever, daemon=True)
        self.backend_thread.start()
        self.proxy = RecordingNodeProxy(self.backend.server_address[1],
            "recording-ui-network-012345abcdef", self.certificate, self.key,
            port=0, max_blob_bytes=9 * 1024 * 1024)
        self.proxy.start()

    def tearDown(self):
        self.proxy.close()
        self.backend.shutdown()
        self.backend_thread.join(timeout=10)
        self.backend.server_close()

    def connection(self):
        return http.client.HTTPSConnection("127.0.0.1", self.proxy.server.server_address[1],
                                           context=self.context, timeout=15)

    def upload(self, digest=None):
        connection = self.connection()
        try:
            with self.source.open("rb") as stream:
                connection.request("PUT", "/upload", stream, {
                    "Content-Length": str(self.source.stat().st_size),
                    "X-SHA-256": digest or self.expected,
                    "Authorization": "Nostr synthetic-fixture-only"})
                response = connection.getresponse()
                return response.status, response.read()
        finally:
            connection.close()

    def test_large_roundtrip_preserves_bytes_and_actual_backend_response(self):
        status, body = self.upload()
        self.assertEqual(202, status)
        self.assertEqual({"source": "synthetic-backend", "size": 8388608}, json.loads(body))
        self.assertEqual(self.expected, self.digest(self.received))
        connection = self.connection()
        try:
            connection.request("GET", f"/{self.expected}.bin")
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            checksum = hashlib.sha256()
            size = 0
            for chunk in iter(lambda: response.read(65536), b""):
                checksum.update(chunk)
                size += len(chunk)
            self.assertEqual(8388608, size)
            self.assertEqual(self.expected, checksum.hexdigest())
        finally:
            connection.close()
        self.assertEqual(1, self.backend_puts)
        self.assertEqual(1, self.backend_gets)
        self.assertEqual(["PUT 202", "GET 200"], self.proxy.snapshot()["responses"])

    def test_oversized_request_is_refused_before_backend_offer(self):
        connection = self.connection()
        try:
            connection.putrequest("PUT", "/upload")
            connection.putheader("Content-Length", str(10 * 1024 * 1024))
            connection.endheaders()
            response = connection.getresponse()
            self.assertEqual(502, response.status)
            response.read()
        finally:
            connection.close()
        self.assertEqual(0, self.backend_puts)

    def test_corrupted_digest_cannot_become_a_successful_receipt(self):
        status, _ = self.upload("00" * 32)
        self.assertEqual(502, status)
        self.assertEqual([], self.proxy.snapshot()["responses"])

    def test_download_does_not_forward_signer_authorisation(self):
        connection = self.connection()
        try:
            connection.request("GET", f"/{self.expected}.bin", headers={"Authorization": "Nostr synthetic-fixture-only"})
            response = connection.getresponse()
            self.assertEqual(400, response.status)
            response.read()
        finally:
            connection.close()
        self.assertEqual(0, self.backend_gets)


if __name__ == "__main__":
    unittest.main()
