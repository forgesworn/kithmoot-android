#!/usr/bin/env python3
"""Run the native host profile through the same guarded SIGKILL driver."""
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("owner_restart", Path(__file__).with_name("check-sharing-restart-emulator.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)

if __name__ == "__main__":
    try:
        driver.main("native-host")
    except Exception as error:
        print(f"Native host restart acceptance failed: {error}", file=sys.stderr)
        sys.exit(1)
