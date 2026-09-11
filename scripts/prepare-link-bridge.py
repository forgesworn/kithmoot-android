#!/usr/bin/env python3
"""Verify and unpack the one reviewed ForgeSworn Link Android bundle."""

import hashlib
import json
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

EXPECTED_ARCHIVE_SHA256 = "d803438b9cb562e23319d70f009e507f23584ee13d5b204ea1847d74e531ce29"
EXPECTED_COMMIT = "9db4043ed87dff4b1c6b1623be1e79e66130c93b"
EXPECTED_FILES = {
    "jniLibs/arm64-v8a/liblink_ffi.so",
    "jniLibs/x86_64/liblink_ffi.so",
    "kotlin/dev/forgesworn/link/ffi/link_ffi.kt",
    "manifest.json",
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
        fail("usage: scripts/prepare-link-bridge.py ARCHIVE")
    archive = Path(sys.argv[1]).resolve()
    repository = Path(__file__).resolve().parent.parent
    output = repository / "app/build/link-bridge"
    if not archive.is_file():
        fail(f"Link archive does not exist: {archive}")
    if sha256_file(archive) != EXPECTED_ARCHIVE_SHA256:
        fail("Link archive SHA-256 does not match the reviewed artifact")

    with zipfile.ZipFile(archive) as bundle:
        names = {entry.filename for entry in bundle.infolist() if not entry.is_dir()}
        if names != EXPECTED_FILES:
            fail("Link archive has an unexpected file set")
        manifest = json.loads(bundle.read("manifest.json"))
        if manifest.get("format") != 1 or manifest.get("source_commit") != EXPECTED_COMMIT:
            fail("Link manifest does not identify the reviewed source commit")
        if manifest.get("min_sdk") != 26:
            fail("Link manifest has an incompatible minimum SDK")
        records = manifest.get("files")
        if not isinstance(records, list) or {record.get("path") for record in records} != EXPECTED_FILES - {"manifest.json"}:
            fail("Link manifest has an unexpected native or binding file set")
        for record in records:
            path = record["path"]
            content = bundle.read(path)
            if record.get("bytes") != len(content) or record.get("sha256") != hashlib.sha256(content).hexdigest():
                fail(f"Link manifest checksum failed for {path}")

        # Replacement is confined to this generated build directory. The
        # caller cannot redirect cleanup at another path.
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix="link-bridge-", dir=output.parent))
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
