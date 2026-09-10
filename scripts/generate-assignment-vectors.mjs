// Reference-generated public synthetic fixtures. No real identities or rooms.
import {createRequire} from 'node:module';
import {mkdtemp,readFile,writeFile,mkdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
const source=process.env.KITHMOOT_SOURCE;
if(!source)throw Error('Set KITHMOOT_SOURCE to the reviewed TypeScript checkout');
const require=createRequire(path.join(source,'package.json'));
const temp=await mkdtemp(path.join(tmpdir(),'assignment-vectors-'));
try {
 const bundle=path.join(temp,'reference.mjs');
 await require('esbuild').build({stdin:{contents:`export * from './src/assignments.ts'; export {localIdentity} from './src/identity.ts'; export {deriveChannel,encodeChatEvent,decodeChatEvent} from './src/chat.ts'; export {createDeviceCredential} from './src/credential.ts'; export {nip44} from 'nostr-tools';`,resolveDir:source,sourcefile:'assignment-reference.ts'},bundle:true,platform:'node',format:'esm',outfile:bundle});
 const {localIdentity,assignmentId,signAssignment,projectAssignments,validateAssignmentActions,assignmentPayload,deriveChannel,encodeChatEvent,decodeChatEvent,createDeviceCredential,nip44}=await import(pathToFileURL(bundle));
 const human=localIdentity(Buffer.alloc(32,1)),worker=localIdentity(Buffer.alloc(32,2)),other=localIdentity(Buffer.alloc(32,3));
 const room='a'.repeat(64),executor='worker_installation_01',vectors=[];let serial=0;
 function fixture(label,createExtra={}) {
  const events=[],request=`create_${label}_00000001`,assignment=assignmentId(human.pubkey,request);
  const add=async(actor,operation,previous=events.at(-1)?.id??null,extra={})=>{const e=await signAssignment(actor,room,{v:1,assignment,request:events.length?`update_${String(++serial).padStart(12,'0')}`:request,previous,operation,...extra},100+serial);events.push(e);return e};
  const record=(name=label,input=events,scope=room)=>vectors.push({name,room:scope,events:[...input],expected:projectAssignments(input,scope)});
  return {events,add,record,create:()=>add(human,{op:'create',objective:'Prepare the project brief',criteria:'Explain the evidence for build 41',owner:worker.pubkey,...createExtra})};
 }
 const f=fixture('review');await f.create();f.record('offered');await f.add(worker,{op:'claim',executor,next:'Inspect the build'});f.record('running');
 await f.add(worker,{op:'block',executor,question:'Which build?'});f.record('blocked');await f.add(human,{op:'answer',text:'Build 41'});f.record('answered');
 await f.add(worker,{op:'progress',executor,text:'Found the build',next:'Check evidence'});f.record('progress');
 const result=await f.add(worker,{op:'result',executor,summary:'Checks passed',evidence:'Build 41 evidence'});f.record('review');await f.add(human,{op:'accept',result:result.id});f.record('accepted');f.record('reversed-and-duplicated',[...f.events].reverse().concat(f.events));
 f.record('missing-history',[f.events[1]]);f.record('wrong-room',f.events,'b'.repeat(64));f.record('tampered-signature',[{...f.events[0],content:f.events[0].content+' '}]);
 const conflict=fixture('conflict');const root=await conflict.create();await conflict.add(worker,{op:'claim',executor,next:'First device'});await conflict.add(worker,{op:'claim',executor:'other_installation_01',next:'Second device'},root.id);conflict.record();conflict.record('conflict-reversed',[...conflict.events].reverse());
 for(const purpose of ['cancel','handoff']) {
  const x=fixture(purpose);await x.create();const claim=await x.add(worker,{op:'claim',executor,next:'Run'});const stop=await x.add(human,{op:'stop',purpose,reason:'Change plan'});x.record(`${purpose}-stopping`);
  await x.add(other,{op:'release',executor,evidence:'Pretend stopped'});x.record(`${purpose}-forged-release`);
  await x.add(worker,{op:'release',executor,evidence:'Execution stopped'},stop.id);x.record(`${purpose}-released`);
  if(purpose==='handoff') {await x.add(human,{op:'assign',owner:other.pubkey,reason:'Continue'});x.record('handoff-offered');const newClaim=await x.add(other,{op:'claim',executor:'new_installation_01',next:'Run'});await x.add(worker,{op:'result',executor,summary:'Late output',evidence:'Old attempt'},newClaim.id);x.record('old-owner-result-refused');}
 }
 const rejection=fixture('rejection');await rejection.create();await rejection.add(worker,{op:'claim',executor,next:'Run'});const first=await rejection.add(worker,{op:'result',executor,summary:'Draft',evidence:'First attempt'});await rejection.add(human,{op:'reject',result:first.id,reason:'Check another case'});rejection.record('rejected-new-attempt');await rejection.add(worker,{op:'claim',executor,next:'Run missing case'});const second=await rejection.add(worker,{op:'result',executor,summary:'Revised',evidence:'Second attempt'});await rejection.add(human,{op:'accept',result:first.id});rejection.record('old-result-acceptance-refused');await rejection.add(human,{op:'accept',result:second.id},second.id);rejection.record('revised-result-accepted');
 const attacks=fixture('stranger');const offered=await attacks.create();await attacks.add(other,{op:'claim',executor,next:'Steal it'});attacks.record('stranger-claim-refused');const owned=await attacks.add(worker,{op:'claim',executor,next:'Run'},offered.id);await attacks.add(other,{op:'result',executor,summary:'Pretend',evidence:'None'});attacks.record('stranger-result-refused');const blocked=await attacks.add(worker,{op:'block',executor,question:'Which build?'},owned.id);await attacks.add(other,{op:'answer',text:'Another project'});attacks.record('stranger-answer-refused');
 const device=fixture('device',{ownerDevice:'d'.repeat(64)});const initial=await device.create();await device.add(worker,{op:'claim',executor,next:'Wrong device'},initial.id,{device:'e'.repeat(64)});device.record('wrong-device-refused');await device.add(worker,{op:'claim',executor,next:'Right device'},initial.id,{device:'d'.repeat(64)});device.record('right-device-claimed');
 const payload=JSON.parse(f.events[0].content),invalid=[];
 for(const [name,content] of [['private-fields',{...payload,operation:{...payload.operation,privateNotes:'Never share'}}],['string-version',{...payload,v:'1'}],['null-device',{...payload,device:null}],['missing-previous',Object.fromEntries(Object.entries(payload).filter(([k])=>k!=='previous'))]]) {const event=await human.signEvent({kind:1464,created_at:100,tags:[['d','kithmoot/assignment/v1'],['room',room]],content:JSON.stringify(content)});invalid.push({name,room,event,valid:!!assignmentPayload(event,room)});}
 const actions=[{id:'review',label:'Review changes',description:'Review a selected revision',inputs:[{id:'revision',label:'Revision',required:true}]}];
 const actionVectors=[actions,[{...actions[0],shell:'arbitrary command'}],[{...actions[0],inputs:[{id:'revision',label:'Revision',required:'true'}]}],Array(9).fill(actions[0])].map((input,i)=>({name:`actions-${i}`,input,expected:validateAssignmentActions(input)??null}));
 const roomKey=Buffer.alloc(32,9),deviceKey=Buffer.alloc(32,4),deviceIdentity=localIdentity(deviceKey);
 const credential=await createDeviceCredential({identity:human,devicePubkey:deviceIdentity.pubkey,roomId:room,expiresAt:1000,now:()=>100});
 const message={id:'assignment_envelope_01',participant:human.pubkey,device:deviceIdentity.pubkey,credential,text:'Assignment create',sentAt:200,assignment:f.events[0]};
 const envelopeVectors=[];
 const envelope=(name,event,channel='assignments',scope=room)=>envelopeVectors.push({name,event,room:scope,keyHex:roomKey.toString('hex'),channel,now:200,expectedAssignment:decodeChatEvent(event,{roomId:scope,roomKey,channel:channel??undefined,now:200})?.assignment??null});
 const encoded=encodeChatEvent(message,{roomId:room,roomKey,deviceSk:deviceKey,channel:'assignments'});
 envelope('assignment-channel',encoded);envelope('not-main-chat',encoded,null);envelope('not-control-channel',encoded,'control');envelope('wrong-room-credential',encoded,'assignments','b'.repeat(64));
 const channel=deriveChannel(room,roomKey,'assignments');
 for(const [name,mutated] of [['foreign-inner-signer',{...message,assignment:f.events[1]}],['assignment-and-reaction',{...message,reaction:{to:'message_id',emoji:'👍'}}],['assignment-and-mention',{...message,mentions:[human.pubkey]}]]) {
  const event=await deviceIdentity.signEvent({kind:1460,created_at:200,tags:[['d',channel.id]],content:nip44.v2.encrypt(JSON.stringify(mutated),channel.key)});envelope(name,event);
 }
 const channels=[null,'assignments','control','transcript'].map(channel=>{const derived=deriveChannel(room,roomKey,channel??undefined);return {room,keyHex:roomKey.toString('hex'),channel,id:derived.id,derivedKeyHex:Buffer.from(derived.key).toString('hex')};});
 const target=path.resolve(import.meta.dirname,'../app/src/test/resources/assignment-vectors.json');await mkdir(path.dirname(target),{recursive:true});
 const hash=createHash('sha256').update(await readFile(path.join(source,'src/assignments.ts'))).digest('hex');
 await writeFile(target,JSON.stringify({source:{file:'src/assignments.ts',sha256:hash},vectors,invalid,actionVectors,envelopeVectors,channels},null,2)+'\n');
 console.log(JSON.stringify({target,projections:vectors.length,invalid:invalid.length,actions:actionVectors.length,envelopes:envelopeVectors.length,sourceSha256:hash}));
}finally{await rm(temp,{recursive:true,force:true})}
