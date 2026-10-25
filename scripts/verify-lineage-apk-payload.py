#!/usr/bin/env python3
"""The lineage app may add x86 libraries, but must test the shipped code."""

import hashlib
import sys
import zipfile


def entries(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        return {name: hashlib.sha256(archive.read(name)).digest() for name in names}


def verify(production, emulator):
    shipped, tested = entries(production), entries(emulator)
    for library in ("liblink_ffi.so", "libvmls_ffi.so"):
        if f"lib/arm64-v8a/{library}" not in shipped or f"lib/x86_64/{library}" not in tested:
            raise ValueError("Missing production or emulator native engine")
    if any(name.startswith("lib/x86") for name in shipped):
        raise ValueError("Production APK contains an emulator ABI")
    without_x86 = {name: digest for name, digest in tested.items() if not name.startswith("lib/x86_64/")}
    if shipped != without_x86:
        raise ValueError("Lineage APK differs from production beyond its x86 libraries")
    print(f"Verified {len(shipped)} identical APK payload entries; only x86 libraries added")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify-lineage-apk-payload.py PRODUCTION_APK EMULATOR_APK")
    verify(*sys.argv[1:])
