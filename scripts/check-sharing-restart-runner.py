import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

FAKE = r'''#!/usr/bin/env python3
import os,sys,time,json
from pathlib import Path
args=sys.argv[1:];mode=os.environ.get('FAKE_MODE','ok')
profile=os.environ.get('FAKE_PROFILE','sharing');native=profile.startswith('native-');rekey=profile.startswith('native-rekey-')
case='dev.forgesworn.kithmoot.epoch.NativeRekeyRestartTest' if rekey else 'dev.forgesworn.kithmoot.ui.'+('NativeHostRestartTest' if native else 'RoomSharingRestartTest')
marker='native_rekey' if rekey else 'native_host' if native else 'sharing'
transition='before-handoff' if profile=='native-rekey-before' else 'after-handoff'
root=Path(os.environ['FAKE_ROOT']);killed=root/'killed'
with (root/'calls').open('a') as out:out.write(json.dumps(args)+'\n')
if 'ro.kernel.qemu' in args:print('0' if mode=='not-qemu' else '1');sys.exit(7 if mode=='qemu-failure' else 0)
if any('pidof' in a for a in args):
 if mode=='death-probe-failure' and killed.exists():sys.exit(7)
 if mode=='empty-death-probe' and killed.exists():sys.exit(0)
 if killed.exists() and mode!='survives' and any('KITHMOOT_SHARING_NO_PID' in a for a in args):print('KITHMOOT_SHARING_NO_PID');sys.exit(0)
 if mode=='changed-pid':print('9999')
 elif not killed.exists() or mode=='survives':print('4242')
 sys.exit(0)
if 'kill' in args:
 if mode=='kill-failure':sys.exit(7)
 killed.touch();sys.exit(0)
if 'force-stop' in args:
 if mode=='cleanup-failure':sys.exit(7)
 killed.touch();sys.exit(0)
if 'clear' in args:
 print('Success');sys.exit(7 if mode=='clear-failure' else 0)
if 'logcat' in args:print('fake diagnostics');sys.exit(0)
if 'instrument' in args:
 if any('#a_prepare' in a for a in args):
  if mode=='no-checkpoint':print('OK (1 test)');sys.exit(0)
  if mode=='checkpoint-timeout':
   while not killed.exists():time.sleep(.01)
   sys.exit(0)
  prefix=case+':' if mode=='class-prefix' else 'unexpected:' if mode=='invalid-prefix' else ''
  if mode=='conflicting-pids':
   print('INSTRUMENTATION_STATUS: '+marker+'_restart_pid=9999',flush=True)
  print(prefix+'INSTRUMENTATION_STATUS: '+marker+'_restart_checkpoint=ready',flush=True)
  print('INSTRUMENTATION_STATUS: '+marker+'_restart_pid=4242',flush=True)
  if rekey and mode!='no-checkpoint-mode':
   print('INSTRUMENTATION_STATUS: '+marker+'_restart_mode='+('wrong-boundary' if mode=='wrong-checkpoint-mode' else transition),flush=True)
   if mode=='duplicate-checkpoint-mode':print('INSTRUMENTATION_STATUS: '+marker+'_restart_mode='+transition,flush=True)
  while not killed.exists():time.sleep(.01)
  print('INSTRUMENTATION_RESULT: shortMsg=Process crashed.',flush=True);sys.exit(0)
 if mode!='no-recovery-pid':
  print('INSTRUMENTATION_STATUS: '+marker+'_recovery_pid='+('4242' if mode=='same-recovery-pid' else '5252'))
  if mode=='duplicate-recovery-pid':print('INSTRUMENTATION_STATUS: '+marker+'_recovery_pid=5252')
 if rekey and mode!='no-recovery-mode':
  print('INSTRUMENTATION_STATUS: '+marker+'_recovery_mode='+('wrong-boundary' if mode=='wrong-recovery-mode' else transition))
  if mode=='duplicate-recovery-mode':print('INSTRUMENTATION_STATUS: '+marker+'_recovery_mode='+transition)
 print('OK (2 tests)' if mode=='wrong-count' else 'OK (1 test)')
 sys.exit(7 if mode=='recovery-failure' else 0)
sys.exit(0)
'''
class DriverTest(unittest.TestCase):
 profile='sharing'
 def run_case(self, mode='ok', serial='emulator-9998'):
  with tempfile.TemporaryDirectory(prefix='kithmoot-sharing-driver-') as folder:
   root=Path(folder);(root/'scripts').mkdir();(root/'sdk/platform-tools').mkdir(parents=True)
   for name in ('check-sharing-restart-emulator.py','check-native-host-restart-emulator.py','check-native-rekey-restart-emulator.py'):
    shutil.copyfile(Path(__file__).with_name(name),root/'scripts'/name)
   rekey=self.profile.startswith('native-rekey-')
   driver=root/'scripts'/('check-native-rekey-restart-emulator.py' if rekey else 'check-sharing-restart-emulator.py' if self.profile=='sharing' else 'check-native-host-restart-emulator.py')
   adb=root/'sdk/platform-tools/adb';adb.write_text(FAKE);adb.chmod(0o700)
   env=dict(os.environ,ANDROID_HOME=str(root/'sdk'),ANDROID_SERIAL=serial,FAKE_ROOT=str(root),FAKE_MODE=mode,FAKE_PROFILE=self.profile,KITHMOOT_SHARING_PREPARE_SECONDS='2',KITHMOOT_SHARING_DEATH_SECONDS='1',KITHMOOT_NATIVE_HOST_PREPARE_SECONDS='2',KITHMOOT_NATIVE_HOST_DEATH_SECONDS='1',KITHMOOT_NATIVE_REKEY_PREPARE_SECONDS='2',KITHMOOT_NATIVE_REKEY_DEATH_SECONDS='1')
   command=['python3',str(driver)]+(['before-handoff' if self.profile=='native-rekey-before' else 'after-handoff'] if rekey else [])
   result=subprocess.run(command,env=env,capture_output=True,text=True,timeout=30)
   calls=(root/'calls').read_text() if (root/'calls').exists() else ''
   reports=root/'app/build/reports'/(self.profile+'-restart-emulator')
   return result,calls,(reports/'failure-logcat.txt').exists()
 def test_valid_active_kill_and_exact_recovery_pass(self):
  r,c,_=self.run_case();self.assertEqual(0,r.returncode,r.stderr);self.assertIn('"kill", "-9", "4242"',c);self.assertIn('requireRestart',c)
 def test_android_class_prefix_on_ready_status_passes(self):
  r,c,_=self.run_case('class-prefix');self.assertEqual(0,r.returncode,r.stderr);self.assertIn('"kill", "-9", "4242"',c)
 def test_unrelated_marker_prefix_cannot_authorise_kill(self):
  r,c,d=self.run_case('invalid-prefix');self.assertEqual(1,r.returncode);self.assertNotIn('"kill",',c);self.assertNotIn('#b_recover',c);self.assertTrue(d)
 def test_no_checkpoint_cannot_pass_with_a_success_summary(self):
  r,c,d=self.run_case('no-checkpoint');self.assertEqual(1,r.returncode);self.assertNotIn('"kill",',c);self.assertTrue(d)
 def test_changed_pid_is_never_killed(self):
  r,c,_=self.run_case('changed-pid');self.assertEqual(1,r.returncode);self.assertNotIn('"kill",',c)
 def test_conflicting_checkpoint_pids_are_never_killed(self):
  r,c,_=self.run_case('conflicting-pids');self.assertEqual(1,r.returncode);self.assertNotIn('"kill",',c)
 def test_original_pid_cannot_count_as_recovery(self):
  r,_,_=self.run_case('same-recovery-pid');self.assertEqual(1,r.returncode)
 def test_missing_recovery_pid_cannot_count_as_recovery(self):
  r,_,_=self.run_case('no-recovery-pid');self.assertEqual(1,r.returncode)
 def test_duplicate_recovery_status_cannot_count_as_recovery(self):
  r,_,_=self.run_case('duplicate-recovery-pid');self.assertEqual(1,r.returncode)
 def test_failed_kill_refuses_recovery(self):
  r,c,_=self.run_case('kill-failure');self.assertEqual(1,r.returncode);self.assertNotIn('#b_recover',c)
 def test_surviving_app_refuses_recovery(self):
  r,c,_=self.run_case('survives');self.assertEqual(1,r.returncode);self.assertNotIn('#b_recover',c)
 def test_failed_recovery_command_refuses_even_with_ok_summary(self):
  r,_,d=self.run_case('recovery-failure');self.assertEqual(1,r.returncode);self.assertTrue(d)
 def test_failed_death_probe_refuses_recovery(self):
  r,c,d=self.run_case('death-probe-failure');self.assertEqual(1,r.returncode);self.assertNotIn('#b_recover',c);self.assertTrue(d)
 def test_empty_death_probe_is_not_confirmation(self):
  r,c,d=self.run_case('empty-death-probe');self.assertEqual(1,r.returncode);self.assertNotIn('#b_recover',c);self.assertTrue(d)
 def test_checkpoint_timeout_cleans_up_without_kill_or_recovery(self):
  r,c,d=self.run_case('checkpoint-timeout');self.assertEqual(1,r.returncode);self.assertNotIn('"kill",',c);self.assertNotIn('#b_recover',c);self.assertIn('force-stop',c);self.assertTrue(d)
 def test_failed_qemu_probe_cannot_pass_with_one_in_output(self):
  r,c,_=self.run_case('qemu-failure');self.assertEqual(1,r.returncode);self.assertNotIn('instrument',c)
 def test_failed_cleanup_cannot_claim_acceptance(self):
  r,_,_=self.run_case('cleanup-failure');self.assertEqual(1,r.returncode);self.assertNotIn('new-process recovery passed',r.stdout)
 def test_wrong_recovery_count_refuses(self):
  r,_,_=self.run_case('wrong-count');self.assertEqual(1,r.returncode)
 def test_physical_serial_refused_before_adb(self):
  r,c,_=self.run_case(serial='physical-fixture');self.assertEqual(1,r.returncode);self.assertEqual('',c)
 def test_non_qemu_refused_before_instrumentation(self):
  r,c,_=self.run_case('not-qemu');self.assertEqual(1,r.returncode);self.assertNotIn('instrument',c);self.assertNotIn('"kill",',c)
class NativeHostDriverTest(DriverTest):
 profile='native-host'
 def test_native_host_profile_selects_its_own_case(self):
  r,c,_=self.run_case();self.assertEqual(0,r.returncode,r.stderr)
  self.assertIn('NativeHostRestartTest#a_prepare',c);self.assertIn('NativeHostRestartTest#b_recover',c)
  self.assertNotIn('RoomSharingRestartTest',c)
 def test_native_lab_keys_are_deleted_after_recovery_failure(self):
  r,c,_=self.run_case('recovery-failure');self.assertEqual(1,r.returncode)
  self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
 def test_native_failed_key_cleanup_refuses_success(self):
  r,_,_=self.run_case('clear-failure');self.assertEqual(1,r.returncode)
  self.assertNotIn('new-process recovery passed',r.stdout)
 def test_native_key_cleanup_is_attempted_even_when_force_stop_fails(self):
  r,c,_=self.run_case('cleanup-failure');self.assertEqual(1,r.returncode)
  self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)

class NativeRekeyBeforeDriverTest(DriverTest):
 profile='native-rekey-before'
 def test_transition_profile_selects_exact_case_and_both_mode_arguments(self):
  r,c,_=self.run_case();self.assertEqual(0,r.returncode,r.stderr)
  self.assertIn('NativeRekeyRestartTest#a_prepare',c);self.assertIn('NativeRekeyRestartTest#b_recover',c)
  self.assertEqual(2,c.count('"transitionMode", "'+('before-handoff' if self.profile=='native-rekey-before' else 'after-handoff')+'"'))
  self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
 def test_wrong_checkpoint_boundary_is_not_killed(self):
  r,c,_=self.run_case('wrong-checkpoint-mode');self.assertEqual(1,r.returncode);self.assertNotIn('"kill",',c)
 def test_missing_checkpoint_boundary_is_not_killed(self):
  r,c,_=self.run_case('no-checkpoint-mode');self.assertEqual(1,r.returncode);self.assertNotIn('"kill",',c)
 def test_wrong_recovery_boundary_cannot_pass(self):
  r,_,_=self.run_case('wrong-recovery-mode');self.assertEqual(1,r.returncode)
 def test_missing_recovery_boundary_cannot_pass(self):
  r,_,_=self.run_case('no-recovery-mode');self.assertEqual(1,r.returncode)
 def test_duplicate_recovery_boundary_cannot_pass(self):
  r,_,_=self.run_case('duplicate-recovery-mode');self.assertEqual(1,r.returncode)
 def test_transition_failure_still_deletes_lab_keys(self):
  r,c,_=self.run_case('recovery-failure');self.assertEqual(1,r.returncode);self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
 def test_transition_failed_key_cleanup_cannot_pass(self):
  r,_,_=self.run_case('clear-failure');self.assertEqual(1,r.returncode);self.assertNotIn('new-process recovery passed',r.stdout)

class NativeRekeyAfterDriverTest(NativeRekeyBeforeDriverTest):
 profile='native-rekey-after'

if __name__=='__main__':unittest.main()
