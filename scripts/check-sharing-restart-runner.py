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
profile=os.environ.get('FAKE_PROFILE','sharing');native=profile.startswith('native-');rekey=profile.startswith('native-rekey-');retirement=profile.startswith('native-retirement-');pending=profile.startswith('native-pending-refusal-');replacement=profile.startswith('native-replacement-') or pending;transitioned=rekey or retirement or replacement
case='dev.forgesworn.kithmoot.epoch.NativeReplacementRestartTest' if replacement else 'dev.forgesworn.kithmoot.epoch.NativeRetirementRestartTest' if retirement else 'dev.forgesworn.kithmoot.epoch.NativeRekeyRestartTest' if rekey else 'dev.forgesworn.kithmoot.ui.'+('NativeHostRestartTest' if native else 'RoomSharingRestartTest')
marker='native_replacement' if replacement else 'native_retirement' if retirement else 'native_rekey' if rekey else 'native_host' if native else 'sharing'
transition=profile.removeprefix('native-pending-refusal-') if pending else profile.removeprefix('native-replacement-') if replacement else profile.removeprefix('native-retirement-') if retirement else 'before-handoff' if profile=='native-rekey-before' else 'after-handoff'
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
 if mode in ('cleanup-failure','no-checkpoint-and-cleanup-failure'):sys.exit(7)
 killed.touch();sys.exit(0)
if 'clear' in args:
 print('Success');sys.exit(7 if mode=='clear-failure' else 0)
if 'logcat' in args:print('fake diagnostics');sys.exit(0)
if 'instrument' in args:
 if any('#a_prepare' in a for a in args):
  if mode in ('no-checkpoint','no-checkpoint-and-cleanup-failure'):print('OK (1 test)');sys.exit(0)
  if mode=='checkpoint-timeout':
   while not killed.exists():time.sleep(.01)
   sys.exit(0)
  prefix=case+':' if mode=='class-prefix' else 'unexpected:' if mode=='invalid-prefix' else ''
  if mode=='conflicting-pids':
   print('INSTRUMENTATION_STATUS: '+marker+'_restart_pid=9999',flush=True)
  print(prefix+'INSTRUMENTATION_STATUS: '+marker+'_restart_checkpoint=ready',flush=True)
  print('INSTRUMENTATION_STATUS: '+marker+'_restart_pid=4242',flush=True)
  if mode=='duplicate-checkpoint-pid':print('INSTRUMENTATION_STATUS: '+marker+'_restart_pid=4242',flush=True)
  if transitioned and mode!='no-checkpoint-mode':
   print('INSTRUMENTATION_STATUS: '+marker+'_restart_mode='+('wrong-boundary' if mode=='wrong-checkpoint-mode' else transition),flush=True)
   if mode=='duplicate-checkpoint-mode':print('INSTRUMENTATION_STATUS: '+marker+'_restart_mode='+transition,flush=True)
  if replacement and mode!='no-checkpoint-bundle':print('INSTRUMENTATION_STATUS_CODE: 2',flush=True)
  while not killed.exists():time.sleep(.01)
  print('INSTRUMENTATION_RESULT: shortMsg=Process crashed.',flush=True);sys.exit(0)
 if pending:marker='native_pending_refusal'
 if mode!='no-recovery-pid':
  print('INSTRUMENTATION_STATUS: '+marker+'_recovery_pid='+('4242' if mode=='same-recovery-pid' else '5252'))
  if mode=='duplicate-recovery-pid':print('INSTRUMENTATION_STATUS: '+marker+'_recovery_pid=5252')
 if transitioned and mode!='no-recovery-mode':
  print('INSTRUMENTATION_STATUS: '+marker+'_recovery_mode='+('wrong-boundary' if mode=='wrong-recovery-mode' else transition))
  if mode=='duplicate-recovery-mode':print('INSTRUMENTATION_STATUS: '+marker+'_recovery_mode='+transition)
 if pending:
  from native_pending_store_refusal import FAULTS,WINDOWS
  i=WINDOWS.index(transition)
  rows=[]
  for target,fault in sorted(FAULTS):
   row={'window':transition,'stage':'NOTICE_ARCHIVED' if i==3 else 'INDEX_VERIFIED' if i==4 else 'ORIGINALS_RETAINED','target':target,'fault':fault,'originalId':'11'*32,'originalCreatedAt':'100','welcomeId':'22'*32,'welcomeCreatedAt':'100','originalBytes':'426','attempts':'0' if i==0 else '1','offered':'1' if i>=2 else '0','chargedBytes':'0' if i==0 else '426','epoch':'1','devices':'3','sourceGeneration':'0','proposedGeneration':'1','indexGeneration':'1' if i>=3 else '0','newRadios':'0','newSubscriptions':'0','newOffers':'0','relayRequests':'0','filesUnchanged':'true','keysUnchanged':'true','cleanupVerified':'true'}
   rows.append(row)
  if mode=='pending-missing-row':rows.pop()
  if mode=='pending-duplicate-row':rows[-1]=rows[0]
  if mode=='pending-changed-original':rows[-1]['originalId']='33'*32
  if mode=='pending-offer':rows[-1]['newOffers']='1'
  if mode=='pending-unclean':rows[-1]['cleanupVerified']='false'
  for row in rows:print('NATIVE_PENDING_STORE_REFUSAL '+' '.join(k+'='+v for k,v in row.items()))
 if replacement and not pending:
  death=0 if transition=='committed-source-before-offer' else 1
  completed=death+(1 if transition in ('committed-source-before-offer','charged-original-before-offer') else 0)
  fields={'original_id':'11'*32,'welcome_id':'22'*32,'original_created_at':'100','welcome_created_at':'100','original_bytes':'426','attempts_at_death':str(death),'attempts_after_completion':str(completed),'debt_at_death':str(426*death),'debt_after_completion':str(426*completed),'devices':'3','same_epoch':'1','invitation_generation':'1','internet_requests':'0','cleanup_verified':'true'}
  mutations={'replacement-reset-attempts':('attempts_after_completion','0'),'replacement-multiplied-debt':('debt_after_completion',str(426*(completed+1))),'replacement-changed-epoch':('same_epoch','0'),'replacement-changed-devices':('devices','2'),'replacement-wrong-generation':('invitation_generation','2'),'replacement-internet-offer':('internet_requests','1'),'replacement-unclean':('cleanup_verified','false'),'replacement-same-originals':('welcome_id','11'*32),'replacement-changed-original-time':('welcome_created_at','101'),'replacement-oversize':('original_bytes','16385')}
  if mode in mutations:fields[mutations[mode][0]]=mutations[mode][1]
  if mode=='replacement-missing-welcome':fields.pop('welcome_id')
  for name,value in fields.items():print('INSTRUMENTATION_STATUS: '+marker+'_recovery_'+name+'='+value)
  if mode=='replacement-duplicate-debt':print('INSTRUMENTATION_STATUS: '+marker+'_recovery_debt_at_death='+str(426*death))
 print('OK (2 tests)'  if mode=='wrong-count' else 'OK (1 test)')
 sys.exit(7 if mode=='recovery-failure' else 0)
sys.exit(0)
'''
class DriverTest(unittest.TestCase):
 profile='sharing'
 def run_case(self, mode='ok', serial='emulator-9998'):
  with tempfile.TemporaryDirectory(prefix='kithmoot-sharing-driver-') as folder:
   root=Path(folder);(root/'scripts').mkdir();(root/'sdk/platform-tools').mkdir(parents=True)
   for name in ('check-sharing-restart-emulator.py','check-native-host-restart-emulator.py','check-native-rekey-restart-emulator.py','check-native-retirement-restart-emulator.py','check-native-replacement-restart-emulator.py','check-native-pending-store-refusal-emulator.py','native_pending_store_refusal.py'):
    shutil.copyfile(Path(__file__).with_name(name),root/'scripts'/name)
   rekey=self.profile.startswith('native-rekey-');retirement=self.profile.startswith('native-retirement-');replacement=self.profile.startswith('native-replacement-');pending=self.profile.startswith('native-pending-refusal-')
   driver=root/'scripts'/('check-native-pending-store-refusal-emulator.py' if pending else 'check-native-replacement-restart-emulator.py' if replacement else 'check-native-retirement-restart-emulator.py' if retirement else 'check-native-rekey-restart-emulator.py' if rekey else 'check-sharing-restart-emulator.py' if self.profile=='sharing' else 'check-native-host-restart-emulator.py')
   adb=root/'sdk/platform-tools/adb';adb.write_text(FAKE);adb.chmod(0o700)
   env=dict(os.environ,ANDROID_HOME=str(root/'sdk'),ANDROID_SERIAL=serial,FAKE_ROOT=str(root),FAKE_MODE=mode,FAKE_PROFILE=self.profile,PYTHONPATH=str(root/'scripts'),KITHMOOT_SHARING_PREPARE_SECONDS='2',KITHMOOT_SHARING_DEATH_SECONDS='1',KITHMOOT_NATIVE_HOST_PREPARE_SECONDS='2',KITHMOOT_NATIVE_HOST_DEATH_SECONDS='1',KITHMOOT_NATIVE_REKEY_PREPARE_SECONDS='2',KITHMOOT_NATIVE_REKEY_DEATH_SECONDS='1',KITHMOOT_NATIVE_RETIREMENT_PREPARE_SECONDS='2',KITHMOOT_NATIVE_RETIREMENT_DEATH_SECONDS='1',KITHMOOT_NATIVE_REPLACEMENT_PREPARE_SECONDS='2',KITHMOOT_NATIVE_REPLACEMENT_DEATH_SECONDS='1')
   command=['python3',str(driver)]+([self.profile.removeprefix('native-pending-refusal-')] if pending else [self.profile.removeprefix('native-replacement-')] if replacement else [self.profile.removeprefix('native-retirement-')] if retirement else ['before-handoff' if self.profile=='native-rekey-before' else 'after-handoff'] if rekey else [])
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

class NativeRetirementPendingDriverTest(NativeRekeyBeforeDriverTest):
 profile='native-retirement-pending-original'
 def test_transition_profile_selects_exact_case_and_both_mode_arguments(self):
  r,c,_=self.run_case();self.assertEqual(0,r.returncode,r.stderr)
  self.assertIn('NativeRetirementRestartTest#a_prepare',c);self.assertIn('NativeRetirementRestartTest#b_recover',c)
  self.assertNotIn('NativeRekeyRestartTest',c)
  self.assertEqual(2,c.count('"transitionMode", "'+self.profile.removeprefix('native-retirement-')+'"'))
  self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
 def test_primary_failure_survives_cleanup_failure_and_still_clears_keys(self):
  r,c,_=self.run_case('no-checkpoint-and-cleanup-failure');self.assertEqual(1,r.returncode)
  self.assertIn('Preparation ended without its active checkpoint',r.stderr)
  self.assertIn('Secondary cleanup failure: Could not force-stop',r.stderr)
  self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
  self.assertNotIn('#b_recover',c)
  self.assertNotIn('new-process recovery passed',r.stdout)
class NativeRetirementReservedDriverTest(NativeRetirementPendingDriverTest):
 profile='native-retirement-reserved-before-offer'
class NativeRetirementOfferedDriverTest(NativeRetirementPendingDriverTest):
 profile='native-retirement-offered-before-archive'
class NativeRetirementArchivedDriverTest(NativeRetirementPendingDriverTest):
 profile='native-retirement-archive-before-hint'


class NativeReplacementCommittedDriverTest(NativeRekeyBeforeDriverTest):
 profile='native-replacement-committed-source-before-offer'
 def test_transition_profile_selects_exact_case_and_both_mode_arguments(self):
  r,c,_=self.run_case();self.assertEqual(0,r.returncode,r.stderr)
  self.assertIn('NativeReplacementRestartTest#a_prepare',c);self.assertIn('NativeReplacementRestartTest#b_recover',c)
  self.assertNotIn('NativeRekeyRestartTest',c);self.assertNotIn('NativeRetirementRestartTest',c)
  self.assertEqual(2,c.count('"transitionMode", "'+self.profile.removeprefix('native-replacement-')+'"'))
  self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
 def test_duplicate_or_incomplete_checkpoint_bundle_never_authorises_kill(self):
  for mode in ('duplicate-checkpoint-mode','duplicate-checkpoint-pid','no-checkpoint-bundle'):
   with self.subTest(mode=mode):
    r,c,_=self.run_case(mode);self.assertEqual(1,r.returncode)
    self.assertNotIn('"kill",',c);self.assertNotIn('#b_recover',c)
    self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
 def test_incomplete_or_inconsistent_numeric_receipts_refuse_after_key_cleanup(self):
  for mode in ('replacement-reset-attempts','replacement-multiplied-debt','replacement-changed-epoch','replacement-changed-devices','replacement-wrong-generation','replacement-internet-offer','replacement-unclean','replacement-same-originals','replacement-changed-original-time','replacement-oversize','replacement-missing-welcome','replacement-duplicate-debt'):
   with self.subTest(mode=mode):
    r,c,_=self.run_case(mode);self.assertEqual(1,r.returncode,r.stdout)
    self.assertIn('"pm", "clear", "dev.forgesworn.kithmoot"',c)
    self.assertNotIn('new-process recovery passed',r.stdout)
class NativeReplacementChargedDriverTest(NativeReplacementCommittedDriverTest):
 profile='native-replacement-charged-original-before-offer'
class NativeReplacementOfferedDriverTest(NativeReplacementCommittedDriverTest):
 profile='native-replacement-offered-before-index'
class NativeReplacementIndexDriverTest(NativeReplacementCommittedDriverTest):
 profile='native-replacement-index-committed-before-source-acknowledgement'
class NativeReplacementReferenceDriverTest(NativeReplacementCommittedDriverTest):
 profile='native-replacement-reference-installed-before-subscription-switch'


class PendingStoreRefusalRunnerTest(DriverTest):
 profile='native-pending-refusal-committed-source-before-offer'
 def test_all_five_pending_windows_use_original_prepare_and_new_matrix(self):
  for mode in ('committed-source-before-offer','charged-original-before-offer','offered-before-index','index-committed-before-source-acknowledgement','reference-installed-before-subscription-switch'):
   with self.subTest(window=mode):
    self.profile='native-pending-refusal-'+mode
    r,c,_=self.run_case();self.assertEqual(0,r.returncode,r.stderr)
    self.assertIn('#a_prepare',c);self.assertIn('#b_refuse_pending_stores',c)
    self.assertNotIn('#b_recover',c);self.assertIn('"kill", "-9", "4242"',c)
    self.assertEqual(2,c.count('"transitionMode", "'+mode+'"'))
 def test_partial_changed_or_unpreserved_matrix_cannot_pass(self):
  for mode in ('pending-missing-row','pending-duplicate-row','pending-changed-original','pending-offer','pending-unclean'):
   with self.subTest(fault=mode):
    r,c,d=self.run_case(mode);self.assertEqual(1,r.returncode);self.assertTrue(d)
    self.assertIn('"force-stop",',c);self.assertIn('"clear",',c)
 def test_original_positive_recovery_method_remains_separate(self):
  self.profile='native-replacement-committed-source-before-offer'
  r,c,_=self.run_case();self.assertEqual(0,r.returncode,r.stderr)
  self.assertIn('#b_recover',c);self.assertNotIn('#b_refuse_pending_stores',c)

if __name__=='__main__':unittest.main()
