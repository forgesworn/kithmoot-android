#!/usr/bin/env python3
"""Capture public APK and physical-device install facts without reading app data."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path


PACKAGE = "dev.forgesworn.kithmoot"
PREVIEW_APK_SHA256 = "077af82e5f4fc2ecf890217630c9db1c20d95935ffeb16aad023a08ce66d03ba"
PREVIEW_CERT_SHA256 = "5a04a77faf9d747728b8d74175225aa1920add75371a9d566d801fe55e390874"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def run(*args: str, check: bool = True) -> str:
    result = subprocess.run(args, check=False, text=True, capture_output=True)
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "command failed"
        raise RuntimeError(f"{Path(args[0]).name}: {detail}")
    return result.stdout


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def normalise_sha(value: str) -> str:
    result = value.replace(":", "").lower()
    if not SHA256.fullmatch(result):
        raise argparse.ArgumentTypeError("fingerprints must be SHA-256 values")
    return result


def android_tools() -> tuple[str, str, str]:
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    roots.extend(
        [
            str(Path.home() / "Library/Android/sdk"),
            str(Path.home() / "Android/Sdk"),
        ]
    )
    adb = shutil.which("adb")
    candidates: list[Path] = []
    for raw_root in roots:
        if not raw_root:
            continue
        root = Path(raw_root)
        adb = adb or (str(root / "platform-tools/adb") if (root / "platform-tools/adb").is_file() else None)
        build_tools = root / "build-tools"
        if build_tools.is_dir():
            candidates.extend(build_tools.iterdir())
    for directory in sorted(candidates, reverse=True):
        apksigner = directory / "apksigner"
        aapt = directory / "aapt"
        if adb and apksigner.is_file() and aapt.is_file():
            return adb, str(apksigner), str(aapt)
    raise RuntimeError("Android adb, apksigner and aapt are required; set ANDROID_HOME")


def parse_apk(apk: Path, apksigner: str, aapt: str) -> dict[str, object]:
    badging = run(aapt, "dump", "badging", str(apk))
    package_line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    package = re.search(r"name='([^']+)'", package_line)
    version_code = re.search(r"versionCode='([0-9]+)'", package_line)
    version_name = re.search(r"versionName='([^']+)'", package_line)
    minimum = re.search(r"^sdkVersion:'([0-9]+)'$", badging, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'([0-9]+)'$", badging, re.MULTILINE)
    if not all((package, version_code, version_name, minimum, target)):
        raise RuntimeError("aapt returned incomplete APK identity")

    verification = run(apksigner, "verify", "--verbose", "--print-certs", str(apk))
    certificate = re.search(
        r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        verification,
        re.MULTILINE,
    )
    if certificate is None:
        raise RuntimeError("apksigner returned no signing certificate")

    def scheme(name: str) -> bool:
        match = re.search(rf"^Verified using {re.escape(name)}: (true|false)$", verification, re.MULTILINE)
        if match is None:
            raise RuntimeError(f"apksigner returned no {name} result")
        return match.group(1) == "true"

    return {
        "path": str(apk.resolve()),
        "sha256": digest(apk),
        "applicationId": package.group(1),
        "versionCode": int(version_code.group(1)),
        "versionName": version_name.group(1),
        "minSdk": int(minimum.group(1)),
        "targetSdk": int(target.group(1)),
        "debuggable": "\napplication-debuggable\n" in f"\n{badging}\n",
        "certificateSha256": certificate.group(1).lower(),
        "signatureSchemes": {
            "v1": scheme("v1 scheme (JAR signing)"),
            "v2": scheme("v2 scheme (APK Signature Scheme v2)"),
            "v3": scheme("v3 scheme (APK Signature Scheme v3)"),
        },
    }


def verify_lineage(apk: Path, lineage: Path, apksigner: str, production_cert: str) -> dict[str, object]:
    output = run(apksigner, "lineage", "--in", str(apk), "--print-certs", "-v")
    certs = [value.lower() for value in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)]
    if certs != [PREVIEW_CERT_SHA256, production_cert]:
        raise RuntimeError("APK does not embed the exact preview-to-production signer order")
    first, marker, _ = output.partition("Signer #2 in lineage certificate DN:")
    if not marker:
        raise RuntimeError("APK lineage does not contain exactly two signers")
    capabilities = {
        "installedData": "Has installed data capability: true" in first,
        "sharedUid": "Has shared UID capability    : true" in first,
        "permission": "Has permission capability    : true" in first,
        "rollback": "Has rollback capability      : true" in first,
        "auth": "Has auth capability          : true" in first,
    }
    expected = {
        "installedData": True,
        "sharedUid": False,
        "permission": True,
        "rollback": False,
        "auth": False,
    }
    if capabilities != expected:
        raise RuntimeError("APK previous-signer capabilities do not match the release contract")
    return {
        "sha256": digest(lineage),
        "signerCertificateSha256": certs,
        "previousSignerCapabilities": capabilities,
    }


def validate_channel(
    channel: str,
    apk: dict[str, object],
    lineage: Path | None,
    production_cert: str | None,
    apksigner: str,
) -> dict[str, object] | None:
    if apk["applicationId"] != PACKAGE:
        raise RuntimeError("APK application ID is not KithMoot")
    if channel == "preview":
        expected = {
            "sha256": PREVIEW_APK_SHA256,
            "versionCode": 20,
            "versionName": "0.5.12",
            "minSdk": 26,
            "targetSdk": 35,
            "debuggable": True,
            "certificateSha256": PREVIEW_CERT_SHA256,
            "signatureSchemes": {"v1": False, "v2": True, "v3": False},
        }
        for name, value in expected.items():
            if apk[name] != value:
                raise RuntimeError(f"public preview {name} does not match the pinned release")
        if lineage is not None or production_cert is not None:
            raise RuntimeError("preview capture does not accept production lineage inputs")
        return None

    if lineage is None or production_cert is None:
        raise RuntimeError("production capture requires --lineage and --production-cert-sha256")
    expected = {
        "versionCode": 27,
        "versionName": "0.6.4",
        "minSdk": 33,
        "targetSdk": 35,
        "debuggable": False,
        "certificateSha256": production_cert,
        "signatureSchemes": {"v1": False, "v2": False, "v3": True},
    }
    for name, value in expected.items():
        if apk[name] != value:
            raise RuntimeError(f"production APK {name} does not match the release contract")
    return verify_lineage(Path(str(apk["path"])), lineage, apksigner, production_cert)


def adb_value(adb: str, serial: str, *command: str) -> str:
    return run(adb, "-s", serial, *command).strip()


def one_physical_device(adb: str, requested: str | None) -> tuple[str, dict[str, object]]:
    rows = []
    for line in run(adb, "devices").splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            rows.append(fields[0])
    if requested:
        if requested not in rows:
            raise RuntimeError("ANDROID_SERIAL is not one connected, authorised device")
        rows = [requested]
    if len(rows) != 1:
        raise RuntimeError("connect exactly one physical device, or set ANDROID_SERIAL")
    serial = rows[0]
    properties = {
        name: adb_value(adb, serial, "shell", "getprop", prop)
        for name, prop in {
            "manufacturer": "ro.product.manufacturer",
            "model": "ro.product.model",
            "device": "ro.product.device",
            "androidRelease": "ro.build.version.release",
            "apiLevel": "ro.build.version.sdk",
            "securityPatch": "ro.build.version.security_patch",
            "characteristics": "ro.build.characteristics",
            "qemu": "ro.kernel.qemu",
        }.items()
    }
    if serial.startswith("emulator-") or properties["qemu"] == "1" or "emulator" in str(properties["characteristics"]).lower():
        raise RuntimeError("emulators cannot produce physical-device evidence")
    if not str(properties["apiLevel"]).isdigit() or int(str(properties["apiLevel"])) < 33:
        raise RuntimeError("the physical device must run Android 13 or later")
    properties.pop("qemu")
    properties["serialSha256"] = hashlib.sha256(serial.encode()).hexdigest()
    return serial, properties


def battery(adb: str, serial: str) -> dict[str, int]:
    output = adb_value(adb, serial, "shell", "dumpsys", "battery")
    values: dict[str, int] = {}
    for key in ("level", "scale", "status", "plugged", "temperature"):
        match = re.search(rf"^\s*{key}:\s*([0-9]+)$", output, re.MULTILINE)
        if match:
            values[key] = int(match.group(1))
    return values


def installed_package(adb: str, serial: str, apksigner: str, aapt: str) -> dict[str, object]:
    paths = adb_value(adb, serial, "shell", "pm", "path", PACKAGE).splitlines()
    base = next((line.removeprefix("package:") for line in paths if line.endswith("/base.apk")), None)
    if base is None:
        raise RuntimeError("KithMoot is not installed on the physical device")
    uid_output = adb_value(adb, serial, "shell", "cmd", "package", "list", "packages", "-U", PACKAGE)
    uid = re.search(r"\buid:([0-9]+)\b", uid_output)
    if uid is None:
        raise RuntimeError("Android returned no installed package UID")
    with tempfile.TemporaryDirectory(prefix="kithmoot-installed-") as directory:
        pulled = Path(directory) / "base.apk"
        run(adb, "-s", serial, "pull", base, str(pulled))
        result = parse_apk(pulled, apksigner, aapt)
    result.pop("path")
    result["uid"] = int(uid.group(1))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", required=True, choices=("preview", "production"))
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--lineage", type=Path)
    parser.add_argument("--production-cert-sha256", type=normalise_sha)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--apk-only", action="store_true")
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error("--apk must name a file")
    if args.lineage is not None and not args.lineage.is_file():
        parser.error("--lineage must name a file")
    if args.out.exists():
        parser.error("refusing to overwrite --out")

    adb, apksigner, aapt = android_tools()
    apk = parse_apk(args.apk, apksigner, aapt)
    lineage = validate_channel(args.channel, apk, args.lineage, args.production_cert_sha256, apksigner)
    evidence: dict[str, object] = {
        "schema": "kithmoot-physical-release-state/v1",
        "checkedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "claim": "APK identity and installed physical-device package state only",
        "channel": args.channel,
        "sourceCommit": run(
            "git", "-C", str(Path(__file__).resolve().parents[1]), "rev-parse", "HEAD"
        ).strip(),
        "apk": apk,
    }
    if lineage is not None:
        evidence["lineage"] = lineage

    if not args.apk_only:
        serial, device = one_physical_device(adb, os.environ.get("ANDROID_SERIAL"))
        installed = installed_package(adb, serial, apksigner, aapt)
        if installed["applicationId"] != apk["applicationId"]:
            raise RuntimeError("installed package ID differs from the supplied APK")
        for key in ("versionCode", "versionName", "certificateSha256"):
            if installed[key] != apk[key]:
                raise RuntimeError(f"installed package {key} differs from the supplied APK")
        evidence["device"] = device
        evidence["installedPackage"] = installed
        evidence["battery"] = battery(adb, serial)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(evidence, stream, indent=2, sort_keys=True)
        stream.write("\n")
    print(f"Recorded {evidence['claim']}: {args.out}")


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        raise SystemExit(f"error: {error}") from None
