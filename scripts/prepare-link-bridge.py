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

EXPECTED_ARCHIVE_SHA256 = "3459d321527e1ae8875b04b329385963deaf531fcd85fd1f9eb3e1725e77071d"
EXPECTED_COMMIT = "4cdc7de37f0b8947d8d746798da406d8b3932be1"
EXPECTED_MANIFEST_SHA256 = "1807cc3e5290cd9d2a0198534152c879d73f2747343db7be09e561ee3de07c8d"
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
        fail("usage: scripts/prepare-link-bridge.py ARCHIVE|--verify-prepared")
    repository = Path(__file__).resolve().parent.parent
    output = repository / "app/build/link-bridge"
    if sys.argv[1] == "--verify-prepared":
        verify_prepared(output)
        return
    archive = Path(sys.argv[1]).resolve()
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


def verify_prepared(output: Path) -> None:
    """Refuse stale or modified generated files before Android compilation."""
    try:
        manifest_bytes = (output / "manifest.json").read_bytes()
        manifest = json.loads(manifest_bytes)
    except (OSError, ValueError):
        fail("Link bridge manifest is missing or invalid; prepare the pinned bundle first")
    if hashlib.sha256(manifest_bytes).hexdigest() != EXPECTED_MANIFEST_SHA256:
        fail("Prepared Link manifest differs from the reviewed bundle; prepare the pinned bundle first")
    actual_files = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
    if actual_files != EXPECTED_FILES:
        fail("Prepared Link directory has an unexpected file set")
    if manifest.get("format") != 1 or manifest.get("source_commit") != EXPECTED_COMMIT:
        fail("Prepared Link manifest does not identify the reviewed source commit")
    if manifest.get("min_sdk") != 26:
        fail("Prepared Link manifest has an incompatible minimum SDK")
    records = manifest.get("files")
    if not isinstance(records, list) or any(not isinstance(record, dict) for record in records):
        fail("Prepared Link manifest has invalid file records")
    paths = [record.get("path") for record in records]
    if len(paths) != len(EXPECTED_FILES) - 1 or set(paths) != EXPECTED_FILES - {"manifest.json"}:
        fail("Prepared Link manifest has an unexpected file set")
    for record in records:
        path = output / record["path"]
        if not path.is_file() or path.stat().st_size != record.get("bytes") or sha256_file(path) != record.get("sha256"):
            fail(f"Prepared Link checksum failed for {record['path']}")


if __name__ == "__main__":
    main()
