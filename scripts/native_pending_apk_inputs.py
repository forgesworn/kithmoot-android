"""Bind the pending-store shards to the verify job's exact private APKs."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

APKS = {
    "app-debug.apk": "app/build/outputs/apk/debug/app-debug.apk",
    "app-debug-androidTest.apk": "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
}
MAX_APK_BYTES = 256 * 1024 * 1024
MANIFEST = "inputs.json"


def head(root):
    value = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise RuntimeError("Invalid source head")
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root, text=True):
        raise RuntimeError("APK inputs require a clean tracked source head")
    return value


def digest(path):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_APK_BYTES:
        raise RuntimeError("APK input is missing, linked or outside its size bound")
    value = hashlib.sha256()
    count = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            count += len(chunk)
            if count > MAX_APK_BYTES:
                raise RuntimeError("APK changed beyond its size bound")
            value.update(chunk)
    if count != path.stat().st_size:
        raise RuntimeError("APK size changed during hashing")
    return {"bytes": count, "sha256": value.hexdigest()}


def prepare(root, destination):
    source_head = head(root)
    destination.mkdir(parents=True, exist_ok=False)
    rows = []
    for name, relative in APKS.items():
        source = root / relative
        original = digest(source)
        shutil.copyfile(source, destination / name)
        if digest(destination / name) != original or digest(source) != original:
            raise RuntimeError("APK changed while preparing exact inputs")
        rows.append(dict(name=name, **original))
    if head(root) != source_head:
        raise RuntimeError("Source head changed while preparing exact inputs")
    manifest = {"v": 1, "head": source_head, "apks": rows}
    (destination / MANIFEST).write_text(json.dumps(manifest, sort_keys=True) + "\n")
    return manifest


def unique_object(pairs):
    value = {}
    for name, item in pairs:
        if name in value:
            raise RuntimeError("Duplicate APK manifest field")
        value[name] = item
    return value


def verify(root, directory, expected_head):
    if directory.is_symlink() or not directory.is_dir():
        raise RuntimeError("APK artifact directory is unavailable or linked")
    if {path.name for path in directory.iterdir()} != set(APKS) | {MANIFEST}:
        raise RuntimeError("APK artifact is incomplete or has unexpected inputs")
    path = directory / MANIFEST
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 4096:
        raise RuntimeError("APK manifest is missing, linked or oversized")
    manifest = json.loads(path.read_text(), object_pairs_hook=unique_object)
    if not isinstance(manifest, dict) or manifest.keys() != {"v", "head", "apks"}:
        raise RuntimeError("Invalid APK manifest fields")
    if type(manifest["v"]) is not int or manifest["v"] != 1 or manifest["head"] != expected_head or head(root) != expected_head:
        raise RuntimeError("APK inputs do not belong to this exact checkout head")
    rows = manifest["apks"]
    if not isinstance(rows, list) or len(rows) != 2:
        raise RuntimeError("Require exactly two APK inputs")
    names = []
    for row in rows:
        if not isinstance(row, dict) or row.keys() != {"name", "bytes", "sha256"} or row["name"] not in APKS:
            raise RuntimeError("Invalid APK manifest row")
        if type(row["bytes"]) is not int or not 0 < row["bytes"] <= MAX_APK_BYTES:
            raise RuntimeError("Invalid APK size")
        if not isinstance(row["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]):
            raise RuntimeError("Invalid APK digest")
        if digest(directory / row["name"]) != {key: row[key] for key in ("bytes", "sha256")}:
            raise RuntimeError("APK input disagrees with its verify-job digest")
        names.append(row["name"])
    if set(names) != set(APKS):
        raise RuntimeError("Duplicated or missing APK input")
    return manifest
