#!/usr/bin/env python3
"""Prepare the pinned Android WebRTC archive used by KithMoot."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent
PIN_PATH = ROOT / "third-party/webrtc/android.json"
DESTINATION = ROOT / "app/build/webrtc/kithmoot-webrtc.aar"
ENTRIES = {
    "AndroidManifest.xml",
    "classes.jar",
    "jni/arm64-v8a/libjingle_peerconnection_so.so",
    "jni/x86_64/libjingle_peerconnection_so.so",
}


def verified(path, pin):
    return (
        path.is_file()
        and path.stat().st_size == pin["bytes"]
        and hashlib.sha256(path.read_bytes()).hexdigest() == pin["sha256"]
    )


def prepare(source, pin):
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=DESTINATION.parent) as directory:
        archive = Path(directory) / "candidate.aar"
        if source is None:
            with urlopen(pin["url"], timeout=60) as response:
                if not response.url.startswith("https://"):
                    raise SystemExit("The WebRTC archive transfer must use HTTPS.")
                archive.write_bytes(response.read(pin["bytes"] + 1))
        else:
            shutil.copyfile(source, archive)
        if not verified(archive, pin):
            raise SystemExit("The Android WebRTC archive does not match the reviewed digest.")
        with ZipFile(archive) as contents:
            if sorted(contents.namelist()) != sorted(ENTRIES) or contents.testzip() is not None:
                raise SystemExit("The Android WebRTC archive has an unexpected layout.")
        archive.replace(DESTINATION)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", nargs="?", type=Path, help="Use a local copy of the pinned archive")
    parser.add_argument("--verify-prepared", action="store_true", help="Verify the archive for Gradle")
    args = parser.parse_args()
    if args.archive and args.verify_prepared:
        parser.error("choose a local archive or --verify-prepared")
    pin = json.loads(PIN_PATH.read_text())
    if args.verify_prepared:
        if not verified(DESTINATION, pin):
            raise SystemExit("Run python3 scripts/prepare-webrtc-android.py to prepare the reviewed SDK.")
        return
    if args.archive or not verified(DESTINATION, pin):
        prepare(args.archive, pin)
    print(f"Android WebRTC {pin['version']} verified")


if __name__ == "__main__":
    main()
