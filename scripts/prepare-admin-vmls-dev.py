#!/usr/bin/env python3
"""Prepare only the explicitly selected local Admin2 candidate; never a production pin."""
import hashlib
import json
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

SOURCE = "5b3eb5fe28255b5c0cc09d959a3010a3d48f9562"
FILES = {"jniLibs/arm64-v8a/libvmls_ffi.so", "jniLibs/x86_64/libvmls_ffi.so", "kotlin/dev/forgesworn/vmls/ffi/vmls_ffi.kt", "manifest.json", "manifest.txt"}
OUTPUT = Path(__file__).resolve().parent.parent / "app/build/vmls-admin-ffi"

def fail(message):
    raise SystemExit("error: " + message)

def verify(root):
    manifest = json.loads((root / "manifest.json").read_text())
    if manifest.get("format") != 1 or manifest.get("source_commit") != SOURCE or manifest.get("min_sdk") != 26:
        fail("Admin2 development manifest differs from the selected candidate")
    records = manifest.get("files", [])
    if len(records) != 3 or {r.get("path") for r in records} != FILES - {"manifest.json", "manifest.txt"}:
        fail("unexpected Admin2 development bundle files")
    for record in records:
        data = (root / record["path"]).read_bytes()
        if record.get("bytes") != len(data) or record.get("sha256") != hashlib.sha256(data).hexdigest():
            fail("Admin2 development checksum failed: " + record["path"])
    kotlin = (root / "kotlin/dev/forgesworn/vmls/ffi/vmls_ffi.kt").read_text()
    if "VmlsAdminInvitationContextV2" not in kotlin or "getCurrentAdminRolesV1" not in kotlin:
        fail("Admin2 development bindings are missing")
    return manifest

def main():
    if sys.argv[1:] == ["--verify-prepared"]:
        verify(OUTPUT)
        return
    if len(sys.argv) != 4 or sys.argv[1] != "--development":
        fail("usage: prepare-admin-vmls-dev.py --development ARCHIVE EXPECTED_SHA256")
    archive = Path(sys.argv[2]).resolve()
    expected = sys.argv[3]
    if len(expected) != 64 or any(c not in "0123456789abcdef" for c in expected):
        fail("supply the independently observed local candidate archive SHA256")
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        fail("Admin2 development archive checksum differs")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="vmls-admin-dev-", dir=OUTPUT.parent))
    try:
        with zipfile.ZipFile(archive) as bundle:
            entries = [entry.filename for entry in bundle.infolist() if not entry.is_dir()]
            if len(entries) != len(FILES) or set(entries) != FILES:
                fail("unexpected Admin2 development archive file set")
            for name in FILES:
                destination = staging / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(bundle.read(name))
        verify(staging)
        (staging / "local-development.json").write_text(json.dumps({"source_commit": SOURCE, "archive_sha256": expected, "production": False}, indent=2) + "\n")
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        os.replace(staging, OUTPUT)
    finally:
        if staging.exists():
            shutil.rmtree(staging)

if __name__ == "__main__":
    main()
