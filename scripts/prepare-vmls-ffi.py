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

EXPECTED_ARCHIVE_SHA256 = "69bc48ed9d845c825a54423c94a993153ada34a0f37efedd7e11b1eca00e4f28"
EXPECTED_COMMIT = "5b3eb5fe28255b5c0cc09d959a3010a3d48f9562"
# Exact manifest bytes from the archive pinned above bind its payload hashes
# to the reviewed artifact even when the original ZIP is no longer retained.
EXPECTED_MANIFEST_SHA256 = "d933371363be35945c74286359a816e2c6a5a5f5588e81f3ee9eb4afe36ad73d"
EXPECTED_TEXT_MANIFEST_SHA256 = "8759f9c93c49cebb7a47dd7ee69bc2c69461767a1a26462e88fae9f60ce875b7"
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


def verify_prepared(output: Path) -> None:
    files = {str(path.relative_to(output)) for path in output.rglob("*") if path.is_file()}
    if files != EXPECTED_FILES or any(path.is_symlink() for path in output.rglob("*")) or output.is_symlink():
        fail("prepared VMLS bundle has an unexpected file set or symlink")
    if sha256_file(output / "manifest.json") != EXPECTED_MANIFEST_SHA256 or sha256_file(output / "manifest.txt") != EXPECTED_TEXT_MANIFEST_SHA256:
        fail("prepared VMLS manifest does not match the reviewed archive")
    manifest = json.loads((output / "manifest.json").read_text())
    if manifest.get("format") != 1 or manifest.get("source_commit") != EXPECTED_COMMIT or manifest.get("min_sdk") != 26:
        fail("prepared VMLS manifest does not identify the reviewed source")
    records = manifest["files"]
    if len(records) != 3 or {r["path"] for r in records} != EXPECTED_FILES - {"manifest.json", "manifest.txt"}:
        fail("prepared VMLS manifest has an unexpected payload set")
    for record in records:
        path = output / record["path"]
        if path.stat().st_size != record["bytes"] or sha256_file(path) != record["sha256"]:
            fail("prepared VMLS checksum failed: " + record["path"])


def main() -> None:
    repository = Path(__file__).resolve().parent.parent
    output = repository / "app/build/vmls-ffi"
    if sys.argv[1:] == ["--verify-prepared"]:
        verify_prepared(output)
        return
    if len(sys.argv) != 2:
        fail("usage: scripts/prepare-vmls-ffi.py ARCHIVE | --verify-prepared")
    archive = Path(sys.argv[1]).resolve()
    if not archive.is_file():
        fail(f"VMLS archive does not exist: {archive}")
    if sha256_file(archive) != EXPECTED_ARCHIVE_SHA256:
        fail("VMLS archive SHA-256 does not match the reviewed artifact")

    with zipfile.ZipFile(archive) as bundle:
        entries = [entry.filename for entry in bundle.infolist() if not entry.is_dir()]
        if len(entries) != len(EXPECTED_FILES) or set(entries) != EXPECTED_FILES:
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
            verify_prepared(temporary)
            if output.exists():
                shutil.rmtree(output)
            os.replace(temporary, output)
        except BaseException:
            shutil.rmtree(temporary, ignore_errors=True)
            raise


if __name__ == "__main__":
    main()
