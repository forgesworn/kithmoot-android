#!/usr/bin/env python3
"""Fetch and verify the pinned, Capacitor-free BLE sources; never run upstream code."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PIN = json.loads((ROOT / "mesh-radio/source.json").read_text())
DEST = ROOT / "mesh-radio/build/source"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def prepared():
    return ({str(path.relative_to(DEST)) for path in DEST.rglob("*") if path.is_file()}
            == set(PIN["files"]) and
            all((DEST / name).is_file() and digest((DEST / name).read_bytes()) == sha
                for name, sha in PIN["files"].items()))


def main():
    if len(sys.argv) > 2:
        raise SystemExit("usage: prepare-mesh-radio.py [PINNED_SOURCE_ZIP]")
    if len(sys.argv) == 1 and prepared():
        print("Pinned mesh radio source already verified")
        return
    if len(sys.argv) == 2:
        data = Path(sys.argv[1]).read_bytes()
    else:
        url = f'https://codeload.github.com/{PIN["repository"]}/zip/{PIN["commit"]}'
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read(8 * 1024 * 1024 + 1)
    if digest(data) != PIN["archiveSha256"]:
        raise SystemExit("Mesh radio archive does not match the reviewed digest")
    DEST.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=DEST.parent) as temporary:
        staging = Path(temporary) / "source"
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            prefix = f'capacitor-mesh-ble-{PIN["commit"]}/'
            for name, sha in PIN["files"].items():
                payload = archive.read(prefix + name)
                if digest(payload) != sha:
                    raise SystemExit(f"Mesh radio source digest mismatch: {name}")
                target = staging / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(payload)
        if DEST.exists():
            shutil.rmtree(DEST)
        staging.rename(DEST)
    print(f'Prepared mesh radio {PIN["commit"]}')


if __name__ == "__main__":
    main()
