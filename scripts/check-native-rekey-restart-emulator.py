#!/usr/bin/env python3
"""SIGKILL an ordinary native successor before or after its first handoff."""
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("owner_restart", Path(__file__).with_name("check-sharing-restart-emulator.py"))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("before-handoff", "after-handoff"):
            raise RuntimeError("Choose exactly before-handoff or after-handoff")
        driver.main("native-rekey-before" if sys.argv[1] == "before-handoff" else "native-rekey-after")
    except Exception as error:
        print(f"Native rekey restart acceptance failed: {error}", file=sys.stderr)
        sys.exit(1)
