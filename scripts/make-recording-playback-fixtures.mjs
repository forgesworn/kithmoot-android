// Test-only silence and generated canvas colours; no accounts, microphone, camera or relays.
import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import { pathToFileURL, fileURLToPath } from 'node:url';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const reference=path.resolve(process.argv[2] ?? path.join(root, '../kithmoot'));
const wildbloom=path.resolve(process.argv[3] ?? path.join(root, '../wildbloom'));
const base=await fs.mkdtemp(path.join(os.tmpdir(), 'synthetic-recording-fixtures-'));
const require=createRequire(path.join(reference, 'package.json'));
const {chromium}=require('@playwright/test');
const {build}=require('esbuild');
await build({entryPoints:[path.join(reference, 'app/src/call-recorder.ts')],bundle:true,platform:'browser',format:'iife',globalName:'SyntheticRecorder',outfile:`${base}/recorder.js`});
await build({entryPoints:[path.join(wildbloom, 'src/core/crypto.ts')],bundle:true,platform:'node',format:'esm',outfile:`${base}/sealer.mjs`});
const {encryptPrivacyEnvelope}=await import(pathToFileURL(`${base}/sealer.mjs`).href);
const browser=await chromium.launch({headless:true,args:['--autoplay-policy=no-user-gesture-required']});
const output=path.join(root, 'app/src/androidTest/assets/recording');
await fs.mkdir(output,{recursive:true});
try {
 const page=await browser.newPage();
 await page.addScriptTag({path:`${base}/recorder.js`});
 for(const video of [false,true]) {
  const captured=await page.evaluate(async video=>{
   const canvas=document.createElement('canvas');canvas.width=320;canvas.height=180;
   const context=canvas.getContext('2d');let step=0;
   const paint=()=>{context.fillStyle=step++%2?'#ff0000':'#00ff00';context.fillRect(0,0,320,180)};
   paint();const stream=video?canvas.captureStream(15):undefined;
   const interval=video?setInterval(paint,100):undefined;
   const recorder=new SyntheticRecorder.CallRecorder({maxBytes:16*1024*1024,video:stream?.getVideoTracks()[0]});
   await new Promise(resolve=>setTimeout(resolve,10000));
   const blob=await recorder.stop();clearInterval(interval);stream?.getTracks().forEach(t=>t.stop());
   return {type:blob.type,bytes:Array.from(new Uint8Array(await blob.arrayBuffer()))};
  },video);
  const name=video?'synthetic-browser-video.webm':'synthetic-browser-audio.webm';
  const source=Buffer.from(captured.bytes);await fs.writeFile(`${base}/${name}`,source);
  const sealed=await encryptPrivacyEnvelope(new File([source],name,{type:captured.type}));
  const ciphertext=Buffer.from(await sealed.file.arrayBuffer());
  const hash=createHash('sha256').update(ciphertext).digest('hex');
  const key=Buffer.from(sealed.recoveryKey.slice(5),'base64url').toString('hex');
  await fs.writeFile(`${output}/${name}.enc`,ciphertext);
  await fs.writeFile(`${output}/${name}.json`,JSON.stringify({synthetic:true,name,type:sealed.sourceType,url:`https://synthetic.example/${hash}`,sha256:hash,key,size:ciphertext.length,sourceSha256:createHash('sha256').update(source).digest('hex')},null,2)+'\n');
  console.log(`${name}: ${source.length} source bytes; ${captured.type}; synthetic only.`);
 }
 const name='synthetic-browser-audio.ogg';
 execFileSync('ffmpeg',['-v','error','-y','-i',`${base}/synthetic-browser-audio.webm`,'-c:a','copy',`${base}/${name}`],{stdio:['ignore','ignore','inherit']});
 const source=await fs.readFile(`${base}/${name}`);
 const sealed=await encryptPrivacyEnvelope(new File([source],name,{type:'audio/ogg;codecs=opus'}));
 const ciphertext=Buffer.from(await sealed.file.arrayBuffer());
 const hash=createHash('sha256').update(ciphertext).digest('hex');
 await fs.writeFile(`${output}/${name}.enc`,ciphertext);
 await fs.writeFile(`${output}/${name}.json`,JSON.stringify({synthetic:true,name,type:sealed.sourceType,url:`https://synthetic.example/${hash}`,sha256:hash,key:Buffer.from(sealed.recoveryKey.slice(5),'base64url').toString('hex'),size:ciphertext.length,sourceSha256:createHash('sha256').update(source).digest('hex')},null,2)+'\n');
 console.log('Synthetic Ogg fixture sealed.');
} finally {await browser.close(); await fs.rm(base,{recursive:true,force:true})}
