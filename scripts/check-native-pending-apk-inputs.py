"""Generated exact-artifact refusal tests; no APK or device is qualified."""
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
from native_pending_apk_inputs import APKS, MANIFEST, MAX_APK_BYTES, head, prepare, verify


class PendingApkInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="kithmoot-pending-inputs-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True, capture_output=True)
        subprocess.run(["git", "-c", "user.name=Lab", "-c", "user.email=lab@example.invalid",
                        "commit", "--allow-empty", "-qm", "generated fixture"], cwd=self.root, check=True, capture_output=True)
        self.expected = head(self.root)
        for name, relative in APKS.items():
            path = self.root / relative; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("generated binary fixture " + name).encode())
        self.directory = self.root / "artifact"
        self.manifest = prepare(self.root, self.directory)

    def rewrite(self, change):
        value = json.loads((self.directory / MANIFEST).read_text())
        change(value)
        (self.directory / MANIFEST).write_text(json.dumps(value))

    def test_exact_generated_inputs_pass_and_keep_their_bytes(self):
        before = {path.name: path.read_bytes() for path in self.directory.iterdir()}
        self.assertEqual(self.manifest, verify(self.root, self.directory, self.expected))
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.directory.iterdir()})

    def test_changed_binary_refuses_without_repairing_it(self):
        path = self.directory / "app-debug.apk"; path.write_bytes(b"different artifact")
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)
        self.assertEqual(b"different artifact", path.read_bytes())

    def test_missing_input_refuses(self):
        (self.directory / "app-debug.apk").unlink()
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_extra_input_refuses(self):
        (self.directory / "unexpected.apk").write_bytes(b"other")
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_symlink_binary_refuses(self):
        path = self.directory / "app-debug.apk"; path.unlink(); path.symlink_to(self.root / APKS[path.name])
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_symlink_manifest_refuses(self):
        path = self.directory / MANIFEST; real = self.root / "copied.json"; real.write_bytes(path.read_bytes())
        path.unlink(); path.symlink_to(real)
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_symlink_artifact_directory_refuses(self):
        path = self.root / "linked"; path.symlink_to(self.directory, target_is_directory=True)
        with self.assertRaises(RuntimeError): verify(self.root, path, self.expected)

    def test_wrong_manifest_head_refuses(self):
        self.rewrite(lambda v: v.update(head="0" * 40))
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_wrong_workflow_head_refuses(self):
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, "0" * 40)

    def test_dirty_tracked_source_refuses(self):
        path = self.root / "tracked"; path.write_text("before")
        subprocess.run(["git", "add", "tracked"], cwd=self.root, check=True)
        subprocess.run(["git", "-c", "user.name=Lab", "-c", "user.email=lab@example.invalid", "commit", "-qm", "tracked fixture"], cwd=self.root, check=True)
        path.write_text("after")
        with self.assertRaises(RuntimeError): head(self.root)

    def test_preparation_cannot_overwrite_a_previous_artifact(self):
        with self.assertRaises(FileExistsError): prepare(self.root, self.directory)

    def test_linked_build_input_refuses_preparation(self):
        path = self.root / APKS["app-debug.apk"]; real = self.root / "linked-build-input"; path.rename(real); path.symlink_to(real)
        with self.assertRaises(RuntimeError): prepare(self.root, self.root / "second-artifact")

    def test_oversized_binary_refuses_before_hashing(self):
        path = self.directory / "app-debug.apk"
        with path.open("wb") as stream: stream.truncate(MAX_APK_BYTES + 1)
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_duplicate_json_field_refuses(self):
        (self.directory / MANIFEST).write_text('{"v":1,"v":1,"head":"' + self.expected + '","apks":[]}')
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_oversized_manifest_refuses(self):
        (self.directory / MANIFEST).write_bytes(b" " * 4097)
        with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def test_malformed_manifest_fields_refuse(self):
        changes = [lambda v: v.update(v=True), lambda v: v.update(v=2), lambda v: v.update(extra="value"),
                   lambda v: v.update(apks=[]), lambda v: v["apks"].append(v["apks"][0]),
                   lambda v: v["apks"].__setitem__(1, v["apks"][0]),
                   lambda v: v["apks"][0].update(name="../app-debug.apk"),
                   lambda v: v["apks"][0].update(bytes=True), lambda v: v["apks"][0].update(bytes=0),
                   lambda v: v["apks"][0].update(bytes=MAX_APK_BYTES + 1), lambda v: v["apks"][0].update(bytes=1),
                   lambda v: v["apks"][0].update(sha256="0" * 64), lambda v: v["apks"][0].update(sha256="bad"),
                   lambda v: v["apks"][0].update(extra="value")]
        for i, change in enumerate(changes):
            with self.subTest(change=i):
                (self.directory / MANIFEST).write_text(json.dumps(self.manifest)); self.rewrite(change)
                with self.assertRaises(RuntimeError): verify(self.root, self.directory, self.expected)

    def run_shard(self, *, serial="emulator-9998", qemu="1", workflow_head=None, window="committed-source-before-offer", corrupt=False):
        scripts = self.root / "scripts"; scripts.mkdir()
        for name in ("prepare-native-pending-inputs.py", "native_pending_apk_inputs.py", "check-native-pending-store-shard.sh"):
            shutil.copyfile(Path(__file__).with_name(name), scripts / name)
        (scripts / "check-native-pending-store-refusal-emulator.py").write_text(
            'import os,sys\nfrom pathlib import Path\nPath(os.environ["PENDING_FIXTURE_DRIVER"]).write_text(sys.argv[1])\n')
        artifact = self.root / "build/native-pending-store-apks"; artifact.parent.mkdir()
        self.directory.rename(artifact)
        if corrupt: (artifact / "app-debug.apk").write_bytes(b"changed")
        sdk = self.root / "sdk/platform-tools"; sdk.mkdir(parents=True)
        adb = sdk / "adb"
        adb.write_text('''#!/usr/bin/env python3
import os,sys,json
from pathlib import Path
with Path(os.environ["PENDING_FIXTURE_CALLS"]).open("a") as out:out.write(json.dumps(sys.argv[1:])+"\\n")
if "ro.kernel.qemu" in sys.argv:print(os.environ["PENDING_FIXTURE_QEMU"])
''')
        adb.chmod(0o700)
        calls = self.root / "calls"; driver = self.root / "driver"
        env = dict(os.environ, ANDROID_SERIAL=serial, ANDROID_HOME=str(sdk.parent),
                   GITHUB_SHA=workflow_head or self.expected, PENDING_WINDOW=window,
                   PENDING_FIXTURE_QEMU=qemu, PENDING_FIXTURE_CALLS=str(calls), PENDING_FIXTURE_DRIVER=str(driver))
        result = subprocess.run(["bash", str(scripts / "check-native-pending-store-shard.sh")], cwd=self.root,
                                env=env, capture_output=True, text=True, timeout=10)
        return result, calls.read_text() if calls.exists() else "", driver

    def test_shard_checks_exact_inputs_before_two_mock_installs_and_dispatch(self):
        result, calls, driver = self.run_shard()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("NATIVE_PENDING_APK_INPUTS head=" + self.expected, result.stdout)
        self.assertEqual(2, calls.count('"install"'))
        self.assertEqual("committed-source-before-offer", driver.read_text())

    def test_physical_device_refuses_before_install_and_dispatch(self):
        result, calls, driver = self.run_shard(qemu="0")
        self.assertNotEqual(0, result.returncode); self.assertNotIn('"install"', calls); self.assertFalse(driver.exists())

    def test_physical_serial_refuses_before_any_adb_call(self):
        result, calls, driver = self.run_shard(serial="physical-board")
        self.assertNotEqual(0, result.returncode); self.assertEqual("", calls); self.assertFalse(driver.exists())

    def test_malformed_emulator_serial_refuses_before_any_adb_call(self):
        result, calls, driver = self.run_shard(serial="emulator-9998-extra")
        self.assertNotEqual(0, result.returncode); self.assertEqual("", calls); self.assertFalse(driver.exists())

    def test_changed_apk_refuses_before_install_and_dispatch(self):
        result, calls, driver = self.run_shard(corrupt=True)
        self.assertNotEqual(0, result.returncode); self.assertNotIn('"install"', calls); self.assertFalse(driver.exists())

    def test_wrong_artifact_head_refuses_before_install_and_dispatch(self):
        result, calls, driver = self.run_shard(workflow_head="0" * 40)
        self.assertNotEqual(0, result.returncode); self.assertNotIn('"install"', calls); self.assertFalse(driver.exists())

    def test_unknown_window_refuses_before_any_adb_call(self):
        result, calls, driver = self.run_shard(window="wrong-window")
        self.assertNotEqual(0, result.returncode); self.assertEqual("", calls); self.assertFalse(driver.exists())


if __name__ == "__main__":
    unittest.main()
