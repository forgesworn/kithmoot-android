#!/usr/bin/env python3
"""Require one actual invitation replacement commit window and new-process recovery."""
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("owner_restart", Path(__file__).with_name("check-sharing-restart-emulator.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in driver.REPLACEMENT_MODES:
            raise RuntimeError("Choose exactly one replacement commit window")
        driver.main("native-replacement-" + sys.argv[1])
    except Exception as error:
        print(f"Native replacement restart acceptance failed: {error}", file=sys.stderr)
        for note in getattr(error, "__notes__", ()):
            print(note, file=sys.stderr)
        sys.exit(1)
