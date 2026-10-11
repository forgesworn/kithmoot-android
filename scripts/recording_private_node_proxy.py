"""Bounded TLS streaming for the explicitly selected local recording fixture.

The real private daemon verifies Blossom authorisation. This proxy preserves
its response, checks ciphertext byte identity, and records counts without
retaining request authorisation or recording bodies in memory.
"""
import hashlib
import http.client
import json
import re
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class RecordingNodeProxy:
    CHUNK = 64 * 1024
    HOP_HEADERS = {"connection", "keep-alive", "proxy-authenticate",
                   "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"}

    def __init__(self, backend_port, fixture, certificate, key, *,
                 port=39847, max_blob_bytes=260 * 1024 * 1024, chunk_delay=0):
        if not 1024 <= backend_port <= 65535:
            raise ValueError("Choose a loopback private-node port")
        if not re.fullmatch(r"recording-ui-network-[0-9a-f]{12}", fixture):
            raise ValueError("Generated recording fixture name required")
        if not 73 < max_blob_bytes <= 260 * 1024 * 1024 or not 0 <= chunk_delay <= 1:
            raise ValueError("Choose bounded synthetic transfer limits")
        self.backend_port = backend_port
        self.fixture = fixture
        self.max_blob_bytes = max_blob_bytes
        self.chunk_delay = chunk_delay
        self.lock = threading.Lock()
        self.stats = {"puts": 0, "gets": 0, "responses": []}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass  # Never log authorisation, private URLs or request bodies.

            def do_GET(self):
                if self.path == "/__fixture/" + owner.fixture:
                    self.send_json(200, owner.snapshot())
                elif re.fullmatch(r"/[0-9a-f]{64}\.bin", self.path):
                    if self.headers.get("Authorization") is not None:
                        self.send_json(400, {"error": "GET must not carry signer authorisation"})
                        return
                    self.forward(False)
                else:
                    self.send_json(404, {"error": "Unknown fixture path"})

            def do_PUT(self):
                if self.path != "/upload":
                    self.send_json(404, {"error": "Unknown fixture path"})
                    return
                self.forward(True)

            def send_json(self, status, value):
                body = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                self.close_connection = True

            def forward(self, uploading):
                connection = http.client.HTTPConnection("127.0.0.1", owner.backend_port, timeout=600)
                headers_sent = False
                try:
                    self.connection.settimeout(600)
                    if self.headers.get("Transfer-Encoding") is not None:
                        raise ValueError("Fixture requires an exact ciphertext length")
                    size = int(self.headers.get("Content-Length", "0"))
                    if uploading and not 73 < size <= owner.max_blob_bytes:
                        raise ValueError("Ciphertext size outside fixture limit")
                    if not uploading and size != 0:
                        raise ValueError("GET must not have a body")
                    expected = self.headers.get("X-SHA-256", "")
                    if uploading and not re.fullmatch(r"[0-9a-f]{64}", expected):
                        raise ValueError("Ciphertext digest required")
                    if uploading and not self.headers.get("Authorization", "").startswith("Nostr "):
                        raise ValueError("Storage authorisation required")
                    with owner.lock:
                        owner.stats["puts" if uploading else "gets"] += 1
                    connection.putrequest(self.command, self.path, skip_accept_encoding=True)
                    for name, value in self.headers.items():
                        if name.lower() not in owner.HOP_HEADERS | {"host"}:
                            connection.putheader(name, value)
                    connection.endheaders()
                    if uploading:
                        checksum = hashlib.sha256()
                        remaining = size
                        first = True
                        while remaining:
                            chunk = self.rfile.read(min(remaining, owner.CHUNK))
                            if not chunk:
                                raise ValueError("Truncated ciphertext")
                            if first and chunk[:8] != b"FSWNENC2":
                                raise ValueError("Privacy envelope required")
                            first = False
                            checksum.update(chunk)
                            connection.send(chunk)
                            remaining -= len(chunk)
                            if owner.chunk_delay:
                                time.sleep(owner.chunk_delay)
                        if checksum.hexdigest() != expected:
                            raise ValueError("Ciphertext digest mismatch")
                        with owner.lock:
                            owner.stats.update(sha256=expected, size=size)
                    response = connection.getresponse()
                    with owner.lock:
                        owner.stats["responses"].append(f"{self.command} {response.status}")
                    self.send_response(response.status)
                    for name, value in response.getheaders():
                        if name.lower() not in owner.HOP_HEADERS:
                            self.send_header(name, value)
                    self.send_header("Connection", "close")
                    self.end_headers()
                    headers_sent = True
                    self.close_connection = True
                    while True:
                        chunk = response.read(owner.CHUNK)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        if owner.chunk_delay:
                            time.sleep(owner.chunk_delay)
                except (ValueError, OSError, http.client.HTTPException):
                    # Exact error details may contain private endpoint state.
                    # A failed stream must not become a successful node receipt.
                    if not headers_sent:
                        self.send_json(502, {"error": "Private fixture transfer failed"})
                finally:
                    connection.close()

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.server.daemon_threads = True
        try:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(certificate, key)
            self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        except BaseException:
            self.server.server_close()
            raise
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()

    def snapshot(self):
        with self.lock:
            return {**self.stats, "responses": list(self.stats["responses"])}

    def close(self):
        if self.thread.is_alive():
            self.server.shutdown()
            self.thread.join(timeout=10)
        self.server.server_close()
