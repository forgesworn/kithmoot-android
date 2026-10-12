"""Strict public row validation; generated input is not emulator evidence."""
import re

WINDOWS = (
    "committed-source-before-offer", "charged-original-before-offer", "offered-before-index",
    "index-committed-before-source-acknowledgement", "reference-installed-before-subscription-switch",
)
FAULTS = {(target, fault) for target in ("SOURCE", "RECEIVER", "COURIER", "INDEX")
          for fault in ("MISSING", "CORRUPT")} | {
              ("INDEX", "OWNER_DEVICE"), ("INDEX", "ROUTE_PINS"), ("INDEX", "INVITATION")}
FIELDS = {
    "window", "stage", "target", "fault", "originalId", "originalCreatedAt", "welcomeId", "welcomeCreatedAt",
    "originalBytes", "attempts", "offered", "chargedBytes", "epoch", "devices", "sourceGeneration",
    "proposedGeneration", "indexGeneration", "newRadios", "newSubscriptions", "newOffers", "relayRequests",
    "filesUnchanged", "keysUnchanged", "cleanupVerified",
}


def pending_reporting_control(lines):
    """Require actual pretty-mode combined-bundle omission, after cleanup."""
    controls = []
    for line in lines:
        if line.startswith("dev.forgesworn.kithmoot.epoch.NativeReplacementRestartTest:"):
            line = line.split(":", 1)[1]
        if line.startswith("INSTRUMENTATION_STATUS: native_pending_refusal_probe_"):
            raise RuntimeError("Pending reporting control leaked combined probe metadata")
        line = line.removeprefix("INSTRUMENTATION_STATUS: stream=")
        if line.startswith("NATIVE_PENDING_REPORT_CONTROL"):
            controls.append(line)
    if controls != ["NATIVE_PENDING_REPORT_CONTROL shape=combined"]:
        raise RuntimeError("Pending reporting control is missing, duplicated or malformed")


def pending_refusal_measurement(lines, mode):
    if mode not in WINDOWS:
        raise RuntimeError("Unknown pending refusal window")
    rows = []
    for line in lines:
        if line.startswith("dev.forgesworn.kithmoot.epoch.NativeReplacementRestartTest:"):
            line = line.split(":", 1)[1]
        if line.startswith("INSTRUMENTATION_STATUS: stream="):
            line = line.removeprefix("INSTRUMENTATION_STATUS: stream=")
        if not line.startswith("NATIVE_PENDING_STORE_REFUSAL "):
            continue
        row = {}
        for field in line.removeprefix("NATIVE_PENDING_STORE_REFUSAL ").split(" "):
            name, separator, value = field.partition("=")
            if not separator or not value or name in row:
                raise RuntimeError("Invalid or duplicate pending refusal field")
            row[name] = value
        if row.keys() != FIELDS:
            raise RuntimeError("Missing or unexpected pending refusal field")
        rows.append(row)
    if len(rows) != 11 or {(row["target"], row["fault"]) for row in rows} != FAULTS:
        raise RuntimeError("Require exactly eleven unique pending refusal faults")
    index = WINDOWS.index(mode)
    expected = {
        "window": mode,
        "stage": "NOTICE_ARCHIVED" if index == 3 else "INDEX_VERIFIED" if index == 4 else "ORIGINALS_RETAINED",
        "attempts": "0" if index == 0 else "1", "offered": "1" if index >= 2 else "0",
        "epoch": "1", "devices": "3", "sourceGeneration": "0", "proposedGeneration": "1",
        "indexGeneration": "1" if index >= 3 else "0",
        "newRadios": "0", "newSubscriptions": "0", "newOffers": "0", "relayRequests": "0",
        "filesUnchanged": "true", "keysUnchanged": "true", "cleanupVerified": "true",
    }
    invariant = {key: rows[0][key] for key in FIELDS - {"target", "fault"}}
    for row in rows:
        if any(row[name] != value for name, value in expected.items()):
            raise RuntimeError("Pending refusal stage or conservation assertion disagrees")
        if any(row[name] != value for name, value in invariant.items()):
            raise RuntimeError("Pending matrix does not reuse the exact stage original pair")
        if any(not re.fullmatch(r"[0-9a-f]{64}", row[name]) for name in ("originalId", "welcomeId")):
            raise RuntimeError("Invalid pending original identifier")
        if row["originalId"] == row["welcomeId"]:
            raise RuntimeError("Pending originals must be distinct")
        if any(not re.fullmatch(r"[1-9][0-9]*", row[name]) for name in ("originalCreatedAt", "welcomeCreatedAt", "originalBytes")):
            raise RuntimeError("Invalid pending original numeric field")
        if row["originalCreatedAt"] != row["welcomeCreatedAt"] or not 1 <= int(row["originalBytes"]) <= 16384:
            raise RuntimeError("Pending original time or size is invalid")
        if row["chargedBytes"] != str(int(row["attempts"]) * int(row["originalBytes"])):
            raise RuntimeError("Pending original charged debt changed")
    return rows
