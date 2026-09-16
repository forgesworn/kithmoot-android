import { createRequire } from 'node:module'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const web = resolve(process.argv[2] ?? '../kithmoot')
const require = createRequire(resolve(web, 'package.json'))
const { build } = require('esbuild')
const out = resolve(root, 'app/build/browser-call-interop')
mkdirSync(out, { recursive: true })
const entry = `
import { Peer } from ${JSON.stringify(resolve(web, 'src/peer.ts'))};
let pc; let peer; const streams=[];
window.begin = async (nativeLow, iceServers=[], relayOnly=false) => {
  const native = (nativeLow ? '11' : '22').repeat(32), browser = (nativeLow ? '22' : '11').repeat(32);
  const canvas=document.createElement('canvas'); canvas.width=320; canvas.height=240; document.body.append(canvas);
  const ctx=canvas.getContext('2d'); let tick=0;
  setInterval(()=>{ctx.fillStyle=++tick%2?'#00aa88':'#2266cc';ctx.fillRect(0,0,320,240)},50);
  const sound=new AudioContext(); await sound.resume();
  const oscillator=sound.createOscillator(), destination=sound.createMediaStreamDestination();
  oscillator.frequency.value=440; oscillator.connect(destination); oscillator.start();
  streams.push(canvas.captureStream(15),destination.stream);
  peer=new Peer({localDevice:browser,remoteDevice:native,
    factory:()=>pc=new RTCPeerConnection({iceServers,iceTransportPolicy:relayOnly?'relay':'all',bundlePolicy:'max-bundle',rtcpMuxPolicy:'require'}),
    onSignal:body=>Native.signal(JSON.stringify(body)),
    onTrack:track=>{const el=document.createElement(track.kind==='video'?'video':'audio');el.srcObject=new MediaStream([track]);el.muted=true;el.autoplay=true;document.body.append(el);el.play().catch(()=>{})}
  });
  window.receive=body=>peer.handleSignal(body).catch(e=>Native.failure(String(e)));
  window.report=async()=>{const stats=await pc.getStats();let video=0,audio=0;stats.forEach(s=>{if(s.type==='inbound-rtp'){if(s.kind==='video')video+=s.framesDecoded||0;if(s.kind==='audio')audio+=s.packetsReceived||0}});Native.stats(video,audio,pc.connectionState)};
  window.changeCamera=async()=>{
    await peer.start(streams[1].getTracks());
    await new Promise(r=>setTimeout(r,1500));
    const next=canvas.captureStream(15); streams[0].getTracks().forEach(t=>t.stop()); streams[0]=next;
    Native.camera(next.getVideoTracks()[0].id);
    await peer.start(streams.flatMap(s=>s.getTracks()));
  };
  Native.camera(streams[0].getVideoTracks()[0].id);
  Native.ready(); await peer.start(streams.flatMap(s=>s.getTracks()));
};
`
await build({stdin:{contents:entry,resolveDir:web,sourcefile:'android-browser-call-interop.ts',loader:'ts'},bundle:true,platform:'browser',format:'iife',target:'chrome110',outfile:resolve(out,'browser-peer.js')})
writeFileSync(resolve(out,'source.json'),JSON.stringify({source:resolve(web,'src/peer.ts'),sha256:createHash('sha256').update(readFileSync(resolve(web,'src/peer.ts'))).digest('hex')})+'\n')
console.log('Prepared actual browser Peer for native interoperability test.')
