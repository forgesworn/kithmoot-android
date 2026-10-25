#!/usr/bin/env python3
"""Exercise the release/lineage payload boundary without an Android device."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("lineage_payload", Path(__file__).with_name("verify-lineage-apk-payload.py"))
payload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payload)


class LineagePayloadTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="public-relays-lineage-payload-")
        self.addCleanup(self.scratch.cleanup)
        self.production = Path(self.scratch.name) / "production.apk"
        self.emulator = Path(self.scratch.name) / "emulator.apk"
        self.shipped = {
            "AndroidManifest.xml": b"release manifest",
            "classes.dex": b"shipped code",
            "resources.arsc": b"shipped resources",
            "lib/arm64-v8a/liblink_ffi.so": b"arm64 link",
            "lib/arm64-v8a/libvmls_ffi.so": b"arm64 vmls",
        }
        self.tested = dict(self.shipped, **{
            "lib/x86_64/liblink_ffi.so": b"x86 link",
            "lib/x86_64/libvmls_ffi.so": b"x86 vmls",
        })

    def check(self):
        for path, entries in ((self.production, self.shipped), (self.emulator, self.tested)):
            with zipfile.ZipFile(path, "w") as archive:
                for name, value in entries.items():
                    archive.writestr(name, value)
        payload.verify(self.production, self.emulator)

    def test_only_extra_x86_libraries_are_accepted(self):
        self.check()

    def test_changed_code_manifest_resources_or_arm64_is_refused(self):
        for name in ("classes.dex", "AndroidManifest.xml", "resources.arsc", "lib/arm64-v8a/liblink_ffi.so"):
            with self.subTest(name=name):
                original = self.tested[name]
                self.tested[name] = b"different payload"
                with self.assertRaisesRegex(ValueError, "beyond"):
                    self.check()
                self.tested[name] = original

    def test_extra_non_abi_payload_is_refused(self):
        for name in ("assets/test-only-settings.json", "lib/x86_64/settings.json", "lib/x86_64/nested/library.so"):
            with self.subTest(name=name):
                self.tested[name] = b"{}"
                with self.assertRaisesRegex(ValueError, "beyond|Non-native"):
                    self.check()
                del self.tested[name]

    def test_missing_emulator_engine_is_refused(self):
        del self.tested["lib/x86_64/libvmls_ffi.so"]
        with self.assertRaisesRegex(ValueError, "Missing"):
            self.check()

    def test_emulator_abi_in_production_is_refused(self):
        self.shipped["lib/x86_64/liblink_ffi.so"] = b"not for shipment"
        with self.assertRaisesRegex(ValueError, "Production"):
            self.check()


if __name__ == "__main__":
    unittest.main()
