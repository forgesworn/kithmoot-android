#!/usr/bin/env python3
"""Observe one staged recording through its original worker and TLS relays.

Consumes private staging state; never clears app data, pairs, stages, retries,
publishes from the phone, or compresses the protocol clock.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.parse import urlparse
from urllib.request import urlopen


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--stage-state", type=Path, required=True)
    parser.add_argument("--preserved-apks", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This qualification driver requires an explicitly owned emulator")
    state = json.loads(args.stage_state.read_text())
    apks = json.loads(args.preserved_apks.read_text())
    control = urlparse(state["control"])
    if control.scheme != "http" or control.hostname != "127.0.0.1" or not control.port:
        parser.error("Private fixture control must be a loopback HTTP origin")
    args.output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]

    def device(*parts, timeout=120):
        result = subprocess.run(adb + list(parts), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError("Owned emulator command failed; no automatic retry")
        return result.stdout.strip()

    def fixture(path):
        with urlopen(state["control"].rstrip("/") + "/" + path, timeout=10) as response:
            return json.load(response)

    def check_installed():
        for name, package in (("app", "dev.forgesworn.kithmoot"), ("receiver", "dev.forgesworn.kithmoot.test")):
            original = apks[name]
            if digest(original["path"]) != original["sha256"]:
                raise RuntimeError("Preserved APK changed")
            paths = device("shell", "pm", "path", package).splitlines()
            if len(paths) != 1 or not paths[0].startswith("package:"):
                raise RuntimeError("Expected one installed qualification APK")
            installed_digest = device("shell", "sha256sum", paths[0].removeprefix("package:"), timeout=300).split()[0]
            if installed_digest != original["sha256"]:
                raise RuntimeError("Installed APK differs from the preserved qualification input")

    assert device("shell", "getprop", "ro.kernel.qemu") == "1", "Target must be an emulator"
    check_installed()
    ready = fixture("ready")
    assert ready["worker"]["running"] and not ready["lab_slot_override_built"]
    assert not fixture("clock")["compressed"], "Protocol clock must remain real"
    assert state["stage"]["realLinkStageAccepted"] and not state["stage"]["recordingQueued"]
    start = state["stage"]["startEpoch"] * 3600
    end = state["stage"]["endEpoch"] * 3600
    inputs = {"app_sha256": apks["app"]["sha256"], "receiver_sha256": apks["receiver"]["sha256"],
              "driver_sha256": digest(__file__), "stage_state_sha256": digest(args.stage_state)}
    (args.output / "inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")
    print("Original APKs verified; waiting for the staged lease's real activation", flush=True)
    while time.time() < start:
        time.sleep(min(30, start - time.time()))
    assert time.time() < end - 1800, "Original lease has insufficient publication time; do not queue"
    current = fixture("ready")
    assert current["link_node_id"] == ready["link_node_id"] and current["routes"] == ready["routes"]
    assert current["worker"]["running"] and not fixture("clock")["compressed"]
    check_installed()
    for port in [control.port] + state["relay_ports"]:
        device("reverse", f"tcp:{port}", f"tcp:{port}")
    print("Original lease active; running one guarded delivery observation", flush=True)
    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
        "dev.forgesworn.kithmoot.g5.G7CadenceStatusTest", "-e", "fixture_control", state["control"],
        "-e", "g7_action", "recording-worker-delivery",
        "dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner"],
        capture_output=True, text=True, timeout=1980)
    log = args.output / "instrumentation.txt"
    log.write_text(result.stdout + result.stderr)
    passed = result.returncode == 0 and "OK (1 test)" in result.stdout and "FAILURES" not in result.stdout
    receipt = {"qualified": False, "physical_acceptance": False, "production_release": False,
        "paired_worker_delivery_test_passed": passed, "protocol_clock_compressed": False,
        "inputs": inputs, "instrumentation_sha256": digest(log),
        "limitations": ["Owner signer, carrier sidecars and private TLS relays are emulated",
            "Signed fixture event only; full recording UI and physical acceptance remain separate"]}
    (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if not passed:
        raise RuntimeError("Delivery observation failed; original custody retained, no retry or re-pair")
    print("Both original TLS relays delivered the exact signed event; journal confirmed after ingress", flush=True)


if __name__ == "__main__":
    main()
