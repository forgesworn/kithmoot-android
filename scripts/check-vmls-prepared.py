#!/usr/bin/env python3
"""Test the build-boundary verifier against a local reviewed ZIP, including forged manifest/hash cases.
Usage: python3 scripts/check-vmls-prepared.py ARCHIVE
Only a short-lived directory beneath this repository is used; no native build runs.
"""
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ARCHIVE = Path(sys.argv.pop(1)).resolve()
spec = importlib.util.spec_from_file_location("prepare_vmls_ffi", ROOT / "scripts/prepare-vmls-ffi.py")
preparer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preparer)

class PreparedBundleVerification(unittest.TestCase):
    def setUp(self):
        self.assertEqual(preparer.sha256_file(ARCHIVE), preparer.EXPECTED_ARCHIVE_SHA256)
        self.parent = ROOT / ".qualification-vmls-prepared"
        self.parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="case-", dir=self.parent)
        self.output = Path(self.temporary.name)
        with zipfile.ZipFile(ARCHIVE) as bundle:
            self.assertEqual(set(bundle.namelist()), preparer.EXPECTED_FILES)
            for name in preparer.EXPECTED_FILES:
                destination = self.output / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(bundle.read(name))

    def tearDown(self):
        self.temporary.cleanup()
        self.parent.rmdir()

    def test_exact_reviewed_bytes_pass_without_retained_archive(self):
        preparer.verify_prepared(self.output)

    def test_stale_payload_is_refused(self):
        (self.output / "kotlin/dev/forgesworn/vmls/ffi/vmls_ffi.kt").write_text("stale generated binding")
        with self.assertRaises(SystemExit):
            preparer.verify_prepared(self.output)

    def test_rewritten_payload_and_self_consistent_manifest_are_refused(self):
        path = "jniLibs/arm64-v8a/libvmls_ffi.so"
        payload = b"synthetic replacement"
        (self.output / path).write_bytes(payload)
        manifest_path = self.output / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        for record in manifest["files"]:
            if record["path"] == path:
                record.update(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaises(SystemExit):
            preparer.verify_prepared(self.output)

    def test_changed_source_commit_is_refused(self):
        path = self.output / "manifest.json"
        path.write_text(path.read_text().replace(preparer.EXPECTED_COMMIT, "0" * 40))
        with self.assertRaises(SystemExit):
            preparer.verify_prepared(self.output)

    def test_extra_files_and_symlinks_are_refused(self):
        extra = self.output / "stale.kt"
        extra.write_text("stale")
        with self.assertRaises(SystemExit):
            preparer.verify_prepared(self.output)
        extra.unlink()
        path = self.output / "manifest.txt"
        saved = self.output / "saved-manifest"
        path.rename(saved)
        path.symlink_to(saved)
        with self.assertRaises(SystemExit):
            preparer.verify_prepared(self.output)

if __name__ == "__main__":
    unittest.main()
