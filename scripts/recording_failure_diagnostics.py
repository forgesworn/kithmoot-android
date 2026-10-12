"""Bounded, metadata-only observations of a failed synthetic emulator run."""
import re
import subprocess

APP = "dev.forgesworn.kithmoot"
PHASES = {
    "fixture-setup", "storage-key-authorisation", "daemon-startup",
    "recipient-ui-and-transfer", "report-validation", "response-validation",
    "recipient-identity-validation",
}


def crash_metadata(text):
    owners = {}
    pids = set()
    classes = set()
    app_frames = []
    library_frames = []
    for line in text.splitlines():
        record = re.match(r"[A-Z]/AndroidRuntime\(\s*([1-9][0-9]*)\s*\):\s*(.*)", line)
        if not record:
            continue
        pid, body = int(record[1]), record[2]
        process = re.fullmatch(r"Process: ([A-Za-z0-9_.:]+), PID: ([1-9][0-9]*)", body)
        if process:
            owners[pid] = int(process[2]) == pid and (
                process[1] == APP or process[1].startswith(APP + ":"))
            if owners[pid]:
                pids.add(pid)
            continue
        exception = re.match(r"(?:Caused by: )?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)(?=[:\s]|$)", body)
        if owners.get(pid) and exception and exception[1].endswith(("Exception", "Error")):
            classes.add(exception[1])
        frame = re.fullmatch(
            r"\s*at (?:dev\.forgesworn\.kithmoot\.|android\.|java\.|javax\.|kotlin\.|kotlinx\.|okhttp3\.|okio\.)"
            r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\(([A-Za-z][A-Za-z0-9_]{0,79}\.(?:kt|java)):([1-9][0-9]{0,4})\)", body)
        if owners.get(pid) and frame:
            frames = app_frames if body.lstrip().startswith("at dev.forgesworn.kithmoot.") else library_frames
            source = {"file": frame[1], "line": int(frame[2])}
            if len(frames) < 12 and source not in frames:
                frames.append(source)
    # A long networking stack must not discard the first-party caller.
    sources = app_frames + [source for source in library_frames if source not in app_frames]
    return {"appCrashPids": sorted(pids), "exceptionClasses": sorted(classes),
            "sourceFrames": sources[:12]}


def collect_failure(adb, serial, phase, error, instrumentation_returncode, run=subprocess.run):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Failure observations require the selected disposable emulator")
    receipt = {
        "phase": phase if phase in PHASES else "unknown",
        "exceptionType": type(error).__name__,
        "instrumentationReturncode": instrumentation_returncode,
        "probes": {},
    }
    probes = {
        "adbState": ["get-state"],
        "qemu": ["shell", "getprop", "ro.kernel.qemu"],
        "bootCompleted": ["shell", "getprop", "sys.boot_completed"],
        "appPids": ["shell", "pidof", APP],
        "crash": ["logcat", "-d", "-b", "crash", "-v", "brief", "-t", "200"],
    }
    for name, arguments in probes.items():
        try:
            result = run([adb, "-s", serial, *arguments], stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, timeout=2, check=False)
            observation = {"returnCode": result.returncode}
            text = result.stdout.decode(errors="replace").strip()
            if result.returncode == 0:
                if name == "adbState":
                    observation["state"] = text if text in {"device", "offline", "unknown"} else "unclassified"
                elif name in {"qemu", "bootCompleted"}:
                    observation["flag"] = text if text in {"0", "1"} else "unclassified"
                elif name == "appPids":
                    observation["pids"] = ([int(value) for value in text.split()]
                                           if re.fullmatch(r"[1-9][0-9]*(?:\s+[1-9][0-9]*){0,15}", text) else [])
                else:
                    observation.update(crash_metadata(text))
            receipt["probes"][name] = observation
        except Exception as diagnostic_error:
            receipt["probes"][name] = {"exceptionType": type(diagnostic_error).__name__}
    return receipt
