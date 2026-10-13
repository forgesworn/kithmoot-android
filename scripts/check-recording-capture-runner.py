#!/usr/bin/env python3
"""Fault-check the capture gate using fake adb, never a device."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parent
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['FAKE_CALLS'], 'a') as log:
    log.write(json.dumps(args) + '\n')
if 'get-state' in args: print('device')
elif 'ro.kernel.qemu' in args or 'sys.boot_completed' in args: print('1')
elif 'resolve-activity' in args:
    count = sum('resolve-activity' in json.loads(line)
                for line in Path(os.environ['FAKE_CALLS']).read_text().splitlines())
    homes = ['com.android.settings/.FallbackHome',
             'com.android.settings/com.android.settings.FallbackHome',
             'fixture.launcher/Home']
    print(homes[min(count - 1, 2)] if os.environ.get('FAKE_MODE') == 'fallback-home'
          else 'fixture.launcher/Home')
elif 'start' in args and os.environ.get('FAKE_MODE') == 'home-failed': sys.exit(7)
elif 'window' in args: print('mCurrentFocus=Window{fixture.launcher/Home}')
elif 'instrument' in args:
    count = int(os.environ.get('FAKE_COUNT', '29'))
    statuses = count - 1 if os.environ.get('FAKE_MODE') == 'missing-result' else count
    print('\n'.join(['INSTRUMENTATION_STATUS_CODE: 0'] * statuses))
    if os.environ.get('FAKE_MODE') == 'skipped': print('INSTRUMENTATION_STATUS_CODE: -3')
    print(f'OK ({count} tests)')
'''


class CaptureRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='kithmoot-capture-driver-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / 'scripts'
        scripts.mkdir()
        for name in ['check-recording-emulator.sh', 'check-recording-emulator-ready.py']:
            shutil.copyfile(SCRIPTS / name, scripts / name)
        sdk = self.root / 'sdk'
        (sdk / 'platform-tools').mkdir(parents=True)
        adb = sdk / 'platform-tools/adb'
        adb.write_text(FAKE_ADB)
        adb.chmod(0o700)
        self.calls = self.root / 'calls.jsonl'
        self.env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SERIAL='emulator-9998',
                        FAKE_CALLS=str(self.calls))

    def run_driver(self, mode='ok', suite='full', count='29'):
        return subprocess.run(['bash', str(self.root / 'scripts/check-recording-emulator.sh'), suite],
                              env=dict(self.env, FAKE_MODE=mode, FAKE_COUNT=count),
                              capture_output=True, text=True, timeout=30)

    def test_failed_readiness_prevents_install_and_instrumentation(self):
        result = self.run_driver('home-failed')
        self.assertNotEqual(0, result.returncode)
        commands = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertFalse(any('install' in args or 'instrument' in args for args in commands))
        self.assertTrue((self.root / 'app/build/reports/native-recording/launcher-command-failure.json').exists())

    def test_all_29_results_and_readiness_pass(self):
        result = self.run_driver()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / 'app/build/reports/native-recording/launcher-readiness.json').exists())

    def test_fallback_home_hands_over_before_capture_can_start(self):
        result = self.run_driver('fallback-home')
        self.assertEqual(0, result.returncode, result.stderr)
        reports = self.root / 'app/build/reports/native-recording'
        receipt = json.loads((reports / 'launcher-readiness.json').read_text())
        self.assertEqual('fixture.launcher/Home', receipt['home'])
        self.assertEqual(3, receipt['home_resolution_probes'])
        self.assertEqual(2, receipt['stable_focus_checks'])
        commands = [json.loads(line) for line in self.calls.read_text().splitlines()]
        launches = [args for args in commands if 'start' in args and '-n' in args]
        self.assertEqual(1, len(launches))
        self.assertIn('fixture.launcher/Home', launches[0])
        self.assertTrue(any('instrument' in args for args in commands))

    def test_summary_cannot_substitute_for_complete_results(self):
        self.assertNotEqual(0, self.run_driver('missing-result').returncode)

    def test_skipped_result_is_refused_even_with_ok_summary(self):
        self.assertNotEqual(0, self.run_driver('skipped').returncode)

    def test_wrong_summary_count_is_refused(self):
        for count in ['22', '25', '26', '27', '28']:
            with self.subTest(count=count):
                self.assertNotEqual(0, self.run_driver(count=count).returncode)

    def test_explicit_local_suite_requires_all_27_results(self):
        result = self.run_driver(suite='local-capture', count='27')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_codec_gate_runs_only_codec_cases_and_requires_every_result(self):
        result = self.run_driver(suite='codecs', count='5')
        self.assertEqual(0, result.returncode, result.stderr)
        commands = [json.loads(line) for line in self.calls.read_text().splitlines()]
        instrument = next(args for args in commands if 'instrument' in args)
        classes = instrument[instrument.index('class') + 1].split(',')
        self.assertEqual([
            'dev.forgesworn.kithmoot.media.recording.AacRecordingTest',
            'dev.forgesworn.kithmoot.media.recording.AvRecordingTest',
        ], classes)
        self.assertNotEqual(0, self.run_driver('missing-result', suite='codecs', count='5').returncode)


if __name__ == '__main__':
    unittest.main()
