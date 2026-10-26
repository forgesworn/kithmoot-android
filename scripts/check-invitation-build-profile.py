#!/usr/bin/env python3
"""Execute the actual Gradle profile/configuration matrix; no APK compilation or native preparation.
Run after other Gradle qualification has finished. Local release refusal uses a dry-run task graph.
"""
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

class BuildProfileMatrix(unittest.TestCase):
    def run_case(self, properties=(), succeeds=True, expected=(), release_graph=False):
        args = [str(ROOT / "gradlew"), "--console=plain", "--no-daemon", ":app:verifyInvitationProfile"]
        args += ["-P" + value for value in properties]
        if release_graph:
            args += [":app:assembleRelease", "--dry-run"]
        result = subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(result.returncode == 0, succeeds, result.stdout)
        for value in expected:
            self.assertIn(value, result.stdout)

    def test_default_and_selected_release_fields(self):
        self.run_case(expected=('INVITATION_PROFILE=disabled;ACCOUNT_NETWORK_DEFAULT=nostr',
            'release:PROFILE="disabled";LEGACY=false;ADMIN_DEV=false', 'debug:PROFILE="disabled";LEGACY=false;ADMIN_DEV=false'))
        self.run_case(('kithmoot.invitationProfile=admin2', 'kithmoot.accountNetworkDefault=private-invitations'),
            expected=('release:PROFILE="admin2";LEGACY=false;ADMIN_DEV=false', 'debug:PROFILE="admin2";LEGACY=false;ADMIN_DEV=false'))

    def test_development_paths_and_release_guard(self):
        self.run_case(('kithmoot.experimentalInvitationMls=true',),
            expected=('debug:PROFILE="disabled";LEGACY=true;ADMIN_DEV=false', 'release:PROFILE="disabled";LEGACY=false;ADMIN_DEV=false'))
        local = ('kithmoot.experimentalInvitationMls=true', 'kithmoot.adminInvitationBundle=true')
        self.run_case(local, expected=('debug:PROFILE="disabled";LEGACY=true;ADMIN_DEV=true',))
        self.run_case(local, succeeds=False, release_graph=True,
            expected=('Local Admin2 development bundle cannot build release tasks.',))

    def test_invalid_and_mixed_properties_refuse(self):
        selected = ('kithmoot.invitationProfile=admin2', 'kithmoot.accountNetworkDefault=private-invitations')
        cases = [
            ('kithmoot.invitationProfile=Admin2',),
            ('kithmoot.invitationProfile=admin2',),
            ('kithmoot.invitationProfile=admin2', 'kithmoot.accountNetworkDefault=nostr'),
            ('kithmoot.accountNetworkDefault=unknown',),
            ('kithmoot.adminInvitationBundle=true',),
            ('kithmoot.experimentalInvitationMls=yes',),
            ('kithmoot.adminInvitationBundle=TRUE',),
            selected + ('kithmoot.experimentalInvitationMls=true',),
            selected + ('kithmoot.adminInvitationBundle=true',),
            selected + ('kithmoot.experimentalInvitationMls=true', 'kithmoot.adminInvitationBundle=true'),
        ]
        for properties in cases:
            with self.subTest(properties=properties):
                self.run_case(properties, succeeds=False)

if __name__ == "__main__":
    unittest.main()
