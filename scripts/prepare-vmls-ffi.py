#!/usr/bin/env python3
"""Verify and unpack the one reviewed VMLS engine (vmls-ffi) Android bundle."""

import hashlib
import json
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

EXPECTED_ARCHIVE_SHA256 = "4569baaf4dd8df4e2f8b3b2e2c800bd20de86cc51c45d638352f1b70995e1cb6"
EXPECTED_COMMIT = "b35a1537a0992943fb1e3e7028bcb0c61e84e25d"
EXPECTED_FILES = {
    "jniLibs/arm64-v8a/libvmls_ffi.so",
    "jniLibs/x86_64/libvmls_ffi.so",
    "kotlin/dev/forgesworn/vmls/ffi/vmls_ffi.kt",
    "manifest.json",
    "manifest.txt",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: scripts/prepare-vmls-ffi.py ARCHIVE")
    archive = Path(sys.argv[1]).resolve()
    repository = Path(__file__).resolve().parent.parent
    output = repository / "app/build/vmls-ffi"
    if not archive.is_file():
        fail(f"VMLS archive does not exist: {archive}")
    if sha256_file(archive) != EXPECTED_ARCHIVE_SHA256:
        fail("VMLS archive SHA-256 does not match the reviewed artifact")

    with zipfile.ZipFile(archive) as bundle:
        names = {entry.filename for entry in bundle.infolist() if not entry.is_dir()}
        if names != EXPECTED_FILES:
            fail("VMLS archive has an unexpected file set")
        manifest = json.loads(bundle.read("manifest.json"))
        if manifest.get("format") != 1 or manifest.get("source_commit") != EXPECTED_COMMIT:
            fail("VMLS manifest does not identify the reviewed source commit")
        if manifest.get("min_sdk") != 26:
            fail("VMLS manifest has an incompatible minimum SDK")
        records = manifest.get("files")
        if not isinstance(records, list) or {record.get("path") for record in records} != EXPECTED_FILES - {"manifest.json", "manifest.txt"}:
            fail("VMLS manifest has an unexpected native or binding file set")
        for record in records:
            path = record["path"]
            content = bundle.read(path)
            if record.get("bytes") != len(content) or record.get("sha256") != hashlib.sha256(content).hexdigest():
                fail(f"VMLS manifest checksum failed for {path}")

        # Replacement is confined to this generated build directory. The
        # caller cannot redirect cleanup at another path.
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix="vmls-ffi-", dir=output.parent))
        try:
            for name in EXPECTED_FILES:
                destination = temporary / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(bundle.read(name))
            if output.exists():
                shutil.rmtree(output)
            os.replace(temporary, output)
        except BaseException:
            shutil.rmtree(temporary, ignore_errors=True)
            raise


if __name__ == "__main__":
    main()
