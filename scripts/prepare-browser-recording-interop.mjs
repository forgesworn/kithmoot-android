// Bundle the actual browser session for native/browser live-warning qualification.
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
const modules = ['session', 'relay-pool', 'identity', 'meeting']
const entry = `
import { RoomSession } from ${JSON.stringify(resolve(web, 'src/session.ts'))};
import { NostrRelayPool } from ${JSON.stringify(resolve(web, 'src/relay-pool.ts'))};
import { localIdentity } from ${JSON.stringify(resolve(web, 'src/identity.ts'))};
import { signRecordingNotice, signRecordingCaptureNotice } from ${JSON.stringify(resolve(web, 'src/meeting.ts'))};
let session, transport, deviceSk, nativeDevice;
const receivedState = () => session?.liveRecordings().find(value => value.state.device === nativeDevice) ?? null;
const key = n => new Uint8Array(32).fill(n);
window.recordingInterop = {
  async start(config) {
    deviceSk = key(config.deviceKey); nativeDevice = config.nativeDevice;
    const pool = new NostrRelayPool([config.relay]);
    transport = {
      publish: event => pool.publish(event),
      publishGuarded: (event, current) => pool.publishGuarded(event, current),
      subscribe: (filters, onEvent, onEose) => pool.subscribe(filters, (event, via) => {
        onEvent(event, via);
        Native.received(event.id, JSON.stringify(receivedState()));
      }, onEose),
      close: () => pool.close()
    };
    session = new RoomSession({transport, secret: key(config.roomKey), authority: config.authority,
      identity: localIdentity(key(config.participantKey)), deviceSk: key(config.deviceKey),
      recordingProfile: 4, announceJitterMs: 0});
    await session.join([], {});
    await session.setCall({id: config.call, since: config.since});
    Native.ready(session.roomId, session.participant, session.device);
  },
  report() { Native.state(JSON.stringify(receivedState())); },
  async publish(on, id, version) {
    const notice = {on, id, version};
    const recording = {op: 'recording', ...notice,
      sig: signRecordingNotice({roomId: session.roomId, notice, authoritySk: deviceSk})};
    const capture = {id, version, capture: 'gallery', recorder: session.participant, device: session.device};
    await session.publishLiveRecording({call: session.call.id, recorder: session.participant, device: session.device, recording, ...(on ? {capture: {
      op: 'recording-capture', ...capture,
      sig: signRecordingCaptureNotice({roomId: session.roomId, notice: capture, authoritySk: deviceSk})}} : {})});
    Native.published(id, version);
  },
  async close() { if (session) await session.leave(); transport?.close(); }
};
`
await build({stdin: {contents: entry, resolveDir: web, sourcefile: 'android-browser-recording-interop.ts', loader: 'ts'},
  bundle: true, platform: 'browser', format: 'iife', target: 'chrome110', outfile: resolve(out, 'browser-recording.js')})
writeFileSync(resolve(out, 'recording-source.json'), JSON.stringify(Object.fromEntries(modules.map(name =>
  [`src/${name}.ts`, createHash('sha256').update(readFileSync(resolve(web, `src/${name}.ts`))).digest('hex')]))) + '\n')
console.log('Prepared actual browser RoomSession and relay pool for live recording interoperability.')
