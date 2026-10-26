#!/usr/bin/env python3
"""Generate actual BuildConfig sources for the Gradle profile matrix; no Kotlin/APK compilation.
Run after other Gradle qualification has finished. Local release refusal uses a dry-run task graph.
All refusal cases require the specific expected policy error, never merely a failed command.
"""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

class BuildProfileMatrix(unittest.TestCase):
    def run_case(self, properties=(), expected_fields=None, refusal=None, release_graph=False):
        args = [str(ROOT / "gradlew"), "--console=plain", "--no-daemon"]
        if release_graph:
            args += [":app:assembleRelease", "--dry-run"]
        else:
            args += [f":app:generate{variant.title()}BuildConfig" for variant in (expected_fields or {"debug": {}, "release": {}})]
        args += ["-P" + value for value in properties]
        result = subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(result.returncode == 0, refusal is None, result.stdout)
        if refusal is not None:
            self.assertIn(refusal, result.stdout)
        else:
            for variant, expected in expected_fields.items():
                path = ROOT / f"app/build/generated/source/buildConfig/{variant}/dev/forgesworn/kithmoot/BuildConfig.java"
                self.assertTrue(path.is_file(), str(path))
                fields = {name: value for _, name, value in re.findall(r"public static final (String|boolean) (\w+) = ([^;]+);", path.read_text())}
                for name, value in expected.items():
                    self.assertEqual(fields.get(name), value, f"{path}: {name}")

    def fields(self, profile="disabled", network="nostr", legacy="false", admin="false"):
        return {"INVITATION_PROFILE": f'"{profile}"', "ACCOUNT_NETWORK_DEFAULT": f'"{network}"',
            "EXPERIMENTAL_INVITATION_MLS": legacy, "EXPERIMENTAL_ADMIN_INVITATION_MLS": admin}

    def test_default_and_selected_release_fields(self):
        default = self.fields()
        self.run_case(expected_fields={"debug": default, "release": default})
        selected = self.fields(profile="admin2", network="private-invitations")
        self.run_case(("kithmoot.invitationProfile=admin2", "kithmoot.accountNetworkDefault=private-invitations"),
            expected_fields={"debug": selected, "release": selected})

    def test_development_paths_and_release_guard(self):
        self.run_case(("kithmoot.experimentalInvitationMls=true",),
            expected_fields={"debug": self.fields(legacy="true"), "release": self.fields()})
        local = ("kithmoot.experimentalInvitationMls=true", "kithmoot.adminInvitationBundle=true")
        self.run_case(local, expected_fields={"debug": self.fields(legacy="true", admin="true")})
        self.run_case(local, release_graph=True, refusal="Local Admin2 development bundle cannot build release tasks.")

    def test_invalid_and_mixed_properties_refuse(self):
        selected = ("kithmoot.invitationProfile=admin2", "kithmoot.accountNetworkDefault=private-invitations")
        profile_error = "kithmoot.invitationProfile must be disabled or admin2"
        product_error = "Admin2 product profile requires private-invitations and excludes both local development switches."
        cases = [
            (("kithmoot.invitationProfile=Admin2",), profile_error),
            (("kithmoot.invitationProfile=admin2",), product_error),
            (("kithmoot.invitationProfile=admin2", "kithmoot.accountNetworkDefault=nostr"), product_error),
            (("kithmoot.accountNetworkDefault=unknown",), "kithmoot.accountNetworkDefault must be nostr or private-invitations"),
            (("kithmoot.adminInvitationBundle=true",), "Admin2 bundle requires explicit experimental invitation development opt-in."),
            (("kithmoot.experimentalInvitationMls=yes",), "kithmoot.experimentalInvitationMls must be true or false"),
            (("kithmoot.adminInvitationBundle=TRUE",), "kithmoot.adminInvitationBundle must be true or false"),
            (selected + ("kithmoot.experimentalInvitationMls=true",), product_error),
            (selected + ("kithmoot.adminInvitationBundle=true",), product_error),
            (selected + ("kithmoot.experimentalInvitationMls=true", "kithmoot.adminInvitationBundle=true"), product_error),
        ]
        for properties, reason in cases:
            with self.subTest(properties=properties):
                self.run_case(properties, refusal=reason)

if __name__ == "__main__":
    unittest.main(failfast=True)
