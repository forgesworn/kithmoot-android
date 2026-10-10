#!/usr/bin/env python3
"""Prepare or verify exact private same-run APK artifacts; never installs them."""
import argparse
from pathlib import Path
import sys
from native_pending_apk_inputs import prepare, verify

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "verify"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--head")
    args = parser.parse_args()
    try:
        root = Path(__file__).resolve().parent.parent
        if args.action == "prepare":
            if args.head is not None:
                raise RuntimeError("Preparation derives the actual checkout head")
            prepare(root, args.directory)
            print("Prepared exact private pending-store APK inputs")
        else:
            if args.head is None:
                raise RuntimeError("Verification requires the workflow's exact source head")
            manifest = verify(root, args.directory, args.head)
            rows = {row["name"]: row for row in manifest["apks"]}
            print("NATIVE_PENDING_APK_INPUTS head=" + manifest["head"] +
                  " appSha256=" + rows["app-debug.apk"]["sha256"] +
                  " instrumentationSha256=" + rows["app-debug-androidTest.apk"]["sha256"] + " checked=true")
    except Exception as error:
        print(f"Exact pending-store APK input acceptance failed: {error}", file=sys.stderr)
        sys.exit(1)
