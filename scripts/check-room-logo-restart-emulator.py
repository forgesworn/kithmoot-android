#!/usr/bin/env python3
"""Require killed-app recovery of accepted room artwork or its removal."""
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("owner_restart", Path(__file__).with_name("check-sharing-restart-emulator.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("image", "removal"):
            raise RuntimeError("Choose exactly one artwork checkpoint: image or removal")
        driver.main("room-logo-" + sys.argv[1])
    except Exception as error:
        print(f"Room logo restart acceptance failed: {error}", file=sys.stderr)
        for note in getattr(error, "__notes__", ()):
            print(note, file=sys.stderr)
        sys.exit(1)
