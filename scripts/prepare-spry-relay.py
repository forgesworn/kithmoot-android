#!/usr/bin/env python3
"""Verify and unpack the one reviewed spry-turn Android relay library bundle.

The built-in call relay (kithmoot docs/design/2026-10-28-built-in-relay.md,
stage A3) runs spry-turn's supervised node inside the app through
libspry_relay.so and the JNI class dev.forgesworn.spry.SpryRelayNative. Only
the two libraries and the release's provenance record are unpacked, into
app/build/spry-relay, after the archive digest, its exact file list, the
source commit, the ABI version and every file hash have been checked.
"""

import hashlib
import json
import os
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

EXPECTED_ARCHIVE_SHA256 = "b6d42dd7feec9c04327a1f9c7d869d662da40a502643ddc8e88c0cf73efe8aa4"
EXPECTED_COMMIT = "656235b4de6d34d1b897a55e03c9bd4f19241b5b"
EXPECTED_RELEASE = "v0.4.0"
EXPECTED_ABI = 1
# Exact provenance bytes from the archive pinned above bind the library hashes
# to the reviewed release even when the original archive is no longer kept.
EXPECTED_PROVENANCE_SHA256 = "50dff7fb49685cece83e629a0b5968e46cf726badb6993975cb3dd4ed8d7e8c7"
PREFIX = "spry-relay-v0.4.0-android/"
ARCHIVE_FILES = {
    "Cargo.lock",
    "LICENSE",
    "NOTICE",
    "README.md",
    "SHA256SUMS",
    "THIRD_PARTY_LICENSES.txt",
    "consumer-rules.pro",
    "docs/embedding.md",
    "docs/supervised-mode.md",
    "include/spry_relay.h",
    "jniLibs/arm64-v8a/libspry_relay.so",
    "jniLibs/x86_64/libspry_relay.so",
    "provenance.json",
}
LIBRARIES = {"jniLibs/arm64-v8a/libspry_relay.so", "jniLibs/x86_64/libspry_relay.so"}
PREPARED_FILES = LIBRARIES | {"provenance.json"}
# The keep rules the app copies into proguard-rules.pro; a change upstream
# must be reviewed here, not picked up silently.
EXPECTED_CONSUMER_RULES_SHA256 = "8ab4f9de201c4543e15cc45ba6ecfad7167f126095c5833e5f333232469576f8"


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


def check_provenance(provenance: dict) -> dict:
    if provenance.get("kind") != "spry-turn-release-artefact" or provenance.get("target") != "android":
        fail("spry relay provenance is not for the Android library")
    if provenance.get("gitCommit") != EXPECTED_COMMIT or provenance.get("name") != EXPECTED_RELEASE:
        fail("spry relay provenance does not identify the reviewed release")
    if provenance.get("abiVersion") != EXPECTED_ABI or provenance.get("jniClass") != "dev.forgesworn.spry.SpryRelayNative":
        fail("spry relay provenance has an unexpected ABI or JNI class")
    files = provenance.get("files")
    if not isinstance(files, dict) or set(files) != ARCHIVE_FILES - {"SHA256SUMS", "provenance.json"}:
        fail("spry relay provenance has an unexpected file set")
    return files


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: scripts/prepare-spry-relay.py ARCHIVE|--verify-prepared")
    repository = Path(__file__).resolve().parent.parent
    output = repository / "app/build/spry-relay"
    if sys.argv[1] == "--verify-prepared":
        verify_prepared(output)
        return
    archive = Path(sys.argv[1]).resolve()
    if not archive.is_file():
        fail(f"spry relay archive does not exist: {archive}")
    if sha256_file(archive) != EXPECTED_ARCHIVE_SHA256:
        fail("spry relay archive SHA-256 does not match the reviewed release")

    with tarfile.open(archive, "r:gz") as bundle:
        members = {}
        for member in bundle.getmembers():
            if member.isdir():
                continue
            if not member.isfile() or not member.name.startswith(PREFIX):
                fail("spry relay archive holds a link or a file outside its folder")
            members[member.name[len(PREFIX):]] = member
        if set(members) != ARCHIVE_FILES:
            fail("spry relay archive has an unexpected file set")
        read = lambda name: bundle.extractfile(members[name]).read()
        provenance_bytes = read("provenance.json")
        if sha256_bytes(provenance_bytes) != EXPECTED_PROVENANCE_SHA256:
            fail("spry relay provenance differs from the reviewed release")
        files = check_provenance(json.loads(provenance_bytes))
        contents = {name: read(name) for name in files}
        for name, expected in files.items():
            if sha256_bytes(contents[name]) != expected:
                fail(f"spry relay checksum failed for {name}")
        if sha256_bytes(contents["consumer-rules.pro"]) != EXPECTED_CONSUMER_RULES_SHA256:
            fail("spry relay keep rules changed; review them and update proguard-rules.pro")

        # Replacement is confined to this generated build directory.
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix="spry-relay-", dir=output.parent))
        try:
            for name in LIBRARIES:
                destination = temporary / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(contents[name])
            (temporary / "provenance.json").write_bytes(provenance_bytes)
            if output.exists():
                shutil.rmtree(output)
            os.replace(temporary, output)
        except BaseException:
            shutil.rmtree(temporary, ignore_errors=True)
            raise


def verify_prepared(output: Path) -> None:
    """Refuse stale or modified generated files before Android compilation."""
    try:
        provenance_bytes = (output / "provenance.json").read_bytes()
        provenance = json.loads(provenance_bytes)
    except (OSError, ValueError):
        fail("spry relay provenance is missing or invalid; prepare the pinned bundle first")
    if sha256_bytes(provenance_bytes) != EXPECTED_PROVENANCE_SHA256:
        fail("prepared spry relay differs from the reviewed release; prepare the pinned bundle first")
    files = check_provenance(provenance)
    actual = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
    if actual != PREPARED_FILES:
        fail("prepared spry relay directory has an unexpected file set")
    for name in LIBRARIES:
        if sha256_file(output / name) != files[name]:
            fail(f"prepared spry relay checksum failed for {name}")


if __name__ == "__main__":
    main()
