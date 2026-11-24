#!/usr/bin/env python3
"""Prepare the pinned Android WebRTC archive used by KithMoot."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
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
            subprocess.run(
                [
                    "curl", "--fail", "--location", "--silent", "--show-error",
                    "--proto", "=https", "--tlsv1.2", "--retry", "2", "--max-time", "90",
                    "--header", "Cache-Control: no-cache", "--max-filesize", str(pin["bytes"]),
                    "--output", str(archive), pin["url"],
                ],
                check=True,
            )
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
