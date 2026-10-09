// Check the Android fixture against the actual shared TypeScript codec (Node 24).
// MESH_WEBRTC_LAN_SOURCE selects the explicit local source checkout, never a relay.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {execFileSync} from 'node:child_process';
const source = process.env.MESH_WEBRTC_LAN_SOURCE;
if (!source) throw new Error('MESH_WEBRTC_LAN_SOURCE is required');
const fixture = JSON.parse(fs.readFileSync(new URL('../app/src/test/resources/mesh-room-wire.json', import.meta.url)));
assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], {cwd: source, encoding: 'utf8'}).trim(), fixture.sourceCommit);
const {encodeFrame, decodeFrame} = await import(pathToFileURL(path.join(source, 'src/frame-codec.ts')));
for (const {frame, base64} of fixture.frames) {
  const bytes = Buffer.from(base64, 'base64');
  assert.deepEqual(decodeFrame(bytes), frame);
  assert.equal(Buffer.from(encodeFrame(frame)).toString('base64'), base64);
}
console.log(`Shared TypeScript codec: ${fixture.frames.length} frozen frames passed`);
