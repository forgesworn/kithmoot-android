#!/usr/bin/env python3
"""Read-only preflight for the isolated Admin2 external UI journey.

No install, user switch, file seeding, backend launch, or instrumentation is
performed here. The reviewed continuation must seed the target app's private
files from private stdin and run `am instrument --user OWNER_ID` only after
every check below and the matching in-app guard have passed. This script does
not accept a bunker URI, key, or capability on its command line.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = "b460dc2f3352a3a136661d885a9c17425c0ba3a9"
MAIN_APK_SHA256 = "761743c3227ee57f7a4b734e38da40150a4bd3ac2c98decf6a20b6a10bb532e4"
AVD = "vennel_admin2_20261025"
SERIAL = "emulator-5592"
QEMU_PID = 73780
PUBLIC_RELAY = "wss://link-lab.forgesworn.dev:28544"
SEALED_MAIN = Path("/Users/darren/WebstormProjects/kithmoot-android/release/private-egress-admin2-lab-20261027") / BASE / "selected/artifacts/app-debug.apk"
ALLOWED_DIFF = {
    "app/src/androidTest/kotlin/dev/forgesworn/kithmoot/lab/Admin2ExternalUiDriverTest.kt",
    "scripts/admin2-external-ui-preflight.py",
}


def call(*argv: str) -> str:
    result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True, text=True)
    return result.stdout.strip()


def adb(*argv: str) -> str:
    return call("adb", "-s", SERIAL, *argv)


def check_source() -> dict[str, bool]:
    head = call("git", "-C", str(ROOT), "rev-parse", "HEAD")
    base = call("git", "-C", str(ROOT), "merge-base", "HEAD", BASE)
    changed = set(call("git", "-C", str(ROOT), "diff", "--name-only", BASE).splitlines())
    untracked = set(call("git", "-C", str(ROOT), "ls-files", "--others", "--exclude-standard").splitlines())
    return {
        "base_exact": base == BASE,
        "lab_branch": call("git", "-C", str(ROOT), "branch", "--show-current") == "lab/private-egress-admin2-20261027",
        "only_test_and_host_source": changed | untracked <= ALLOWED_DIFF,
        "head_present": bool(re.fullmatch(r"[0-9a-f]{40}", head)),
    }


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def signing_cert(apk: Path) -> str:
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    output = call(str(sdk / "build-tools/35.0.0/apksigner"), "verify", "--print-certs", str(apk))
    matches = re.findall(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)
    if len(matches) != 1:
        raise ValueError("single signing certificate required")
    return matches[0].lower()


def installed_base_apk_path(raw: str) -> str | None:
    match = re.fullmatch(r"package:(/data/app/(?:[A-Za-z0-9_~.+=-]+/)+base\.apk)", raw)
    if not match or any(segment in (".", "..") for segment in match.group(1).split("/")):
        return None
    return match.group(1)


def installed_apk(pkg: str, user_id: int, destination: Path) -> bool:
    path = installed_base_apk_path(adb("shell", "pm", "path", "--user", str(user_id), pkg))
    if path is None:
        return False
    with destination.open("wb") as output:
        subprocess.run(("adb", "-s", SERIAL, "exec-out", "cat", path),
                       stdout=output, stderr=subprocess.DEVNULL, check=True)
    return destination.is_file() and destination.stat().st_size > 0


def private_contract(input_data: dict) -> bool:
    root = input_data.get("expected_root_hex")
    bunker = input_data.get("bunker_uri")
    certs = (input_data.get("kithmoot_cert_sha256"), input_data.get("cambium_cert_sha256"))
    return (type(input_data.get("user_id")) is int and input_data["user_id"] > 0
            and type(input_data.get("user_serial")) is int
            and isinstance(root, str) and bool(re.fullmatch(r"[0-9a-f]{64}", root))
            and isinstance(bunker, str) and bunker.startswith("bunker://" + root + "?")
            and all(isinstance(cert, str) and bool(re.fullmatch(r"[0-9a-f]{64}", cert)) for cert in certs)
            and input_data.get("expected_caller_label") == "KithMoot"
            and input_data.get("public_relay_url") == PUBLIC_RELAY)


def target_device_ready(devices: str) -> bool:
    """Other devices may remain connected; only the fixed target is inspected."""
    target_rows = [line.split() for line in devices.splitlines()
                   if line.split() and line.split()[0] == SERIAL]
    return len(target_rows) == 1 and len(target_rows[0]) >= 2 and target_rows[0][1] == "device"


def owned_user_serial_matches(users: str, user_id: int, expected_serial: int) -> bool:
    """Accept one unambiguous dumpsys user row for the already-owned user."""
    if type(user_id) is not int or user_id <= 0 or type(expected_serial) is not int or expected_serial < 0:
        return False
    rows = [line.strip() for line in users.splitlines()
            if re.match(r"UserInfo\{" + str(user_id) + r":", line.strip())]
    if len(rows) != 1:
        return False
    match = re.fullmatch(r"UserInfo\{" + str(user_id) + r":[^}\r\n]*\}[ \t]+serialNo=([0-9]+)(?:[ \t]+.*)?", rows[0])
    return match is not None and int(match.group(1)) == expected_serial


def parser_controls() -> dict[str, bool]:
    header = "List of devices attached\n"
    return {
        "android_tilde_base_path": installed_base_apk_path("package:/data/app/~~Ab_12+-=/dev.forgesworn.kithmoot-xy==/base.apk") == "/data/app/~~Ab_12+-=/dev.forgesworn.kithmoot-xy==/base.apk",
        "android_path_traversal_refused": installed_base_apk_path("package:/data/app/~~Ab/../base.apk") is None,
        "android_multiple_paths_refused": installed_base_apk_path("package:/data/app/a/base.apk\npackage:/data/app/b/base.apk") is None,
        "target_with_other_devices": target_device_ready(header + SERIAL + "\tdevice\nphysical-synthetic\tdevice\nemulator-5590\tdevice\n"),
        "duplicate_target_refused": not target_device_ready(header + SERIAL + "\tdevice\n" + SERIAL + "\tdevice\n"),
        "offline_target_refused": not target_device_ready(header + SERIAL + "\toffline\n"),
        "missing_target_refused": not target_device_ready(header + "physical-synthetic\tdevice\n"),
        "owned_serial_matches": owned_user_serial_matches("  UserInfo{12:synthetic:10} serialNo=42 isPrimary=false\n", 12, 42),
        "stale_serial_refused": not owned_user_serial_matches("  UserInfo{12:synthetic:10} serialNo=43\n", 12, 42),
        "duplicate_user_refused": not owned_user_serial_matches("UserInfo{12:a:10} serialNo=42\nUserInfo{12:b:10} serialNo=42\n", 12, 42),
        "unknown_user_format_refused": not owned_user_serial_matches("UserInfo{12:synthetic:10} unknown=42\n", 12, 42),
        "missing_user_refused": not owned_user_serial_matches("UserInfo{13:synthetic:10} serialNo=42\n", 12, 42),
    }


def check_device(ownership: dict, private_input: dict) -> dict[str, bool]:
    """Fixed read-only probes. All values are compared; none enter tool output."""
    user_id = ownership["id"]
    if type(user_id) is not int or user_id <= 0:
        raise ValueError("secondary owner user required")
    process = call("ps", "-p", str(QEMU_PID), "-o", "command=")
    devices = call("adb", "devices")
    avd = adb("emu", "avd", "name").splitlines()[0]
    qemu = adb("shell", "getprop", "ro.kernel.qemu")
    api = adb("shell", "getprop", "ro.build.version.sdk")
    foreground = adb("shell", "am", "get-current-user")
    users = adb("shell", "dumpsys", "user")
    # The temporary copies of public installed APKs live in the worktree and
    # are deleted by TemporaryDirectory, including after a failed probe.
    with tempfile.TemporaryDirectory(prefix=".admin2-ui-apk-", dir=ROOT) as directory:
        main = Path(directory) / "main.apk"
        signer = Path(directory) / "signer.apk"
        main_found = installed_apk("dev.forgesworn.kithmoot", user_id, main)
        signer_found = installed_apk("dev.forgesworn.cambium", user_id, signer)
        main_hash = sha256(main) if main_found else None
        main_cert = signing_cert(main) if main_found else None
        signer_cert = signing_cert(signer) if signer_found else None
    return {
        "qemu_pid_and_avd_process": "qemu" in process.lower() and AVD in process,
        "owned_emulator_target_ready": target_device_ready(devices),
        "exact_avd": avd == AVD,
        "qemu_guest": qemu == "1",
        "api_35": api == "35",
        "secondary_user_foreground": foreground == str(user_id),
        "selected_main_apk_sealed": SEALED_MAIN.is_file() and sha256(SEALED_MAIN) == MAIN_APK_SHA256,
        "selected_main_apk_installed": main_hash == MAIN_APK_SHA256,
        "selected_main_certificate": main_cert == private_input.get("kithmoot_cert_sha256"),
        "exact_cambium_certificate": signer_cert == private_input.get("cambium_cert_sha256"),
        "input_owner_matches": private_input.get("user_id") == user_id
            and owned_user_serial_matches(users, user_id, private_input.get("user_serial")),
        "owner_metadata_matches": ownership.get("serial") == SERIAL
            and ownership.get("type") == "android.os.usertype.full.SECONDARY"
            and bool(ownership.get("campaign")) and bool(ownership.get("name")),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-check", action="store_true")
    parser.add_argument("--parser-controls", action="store_true")
    parser.add_argument("--read-only-device-preflight", action="store_true")
    parser.add_argument("--ownership-json", type=Path)
    args = parser.parse_args()
    if sum((args.source_check, args.read_only_device_preflight, args.parser_controls)) != 1:
        parser.error("choose exactly one check")
    try:
        checks = parser_controls() if args.parser_controls else check_source()
        if args.read_only_device_preflight:
            if args.ownership_json is None:
                parser.error("ownership metadata file required")
            metadata = args.ownership_json
            if metadata.stat().st_mode & 0o077:
                raise ValueError("ownership metadata must be private")
            ownership = json.loads(metadata.read_text())
            private_input = json.loads(sys.stdin.read())
            checks["private_input_contract"] = private_contract(private_input)
            if all(checks.values()):
                checks.update(check_device(ownership, private_input))
        # Only bounded booleans are emitted. No ADB output, paths, user numbers,
        # URIs, keys, capabilities, screenshots, or raw JUnit go to stdout.
        print(json.dumps({"checks": checks, "ready": all(checks.values())}, sort_keys=True))
        return 0 if all(checks.values()) else 1
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(json.dumps({"ready": False, "error_type": type(error).__name__}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
