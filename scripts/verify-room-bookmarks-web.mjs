#!/usr/bin/env node
// Actual browser RoomBookmarks implementation in both directions, using synthetic keys only.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'

const web = path.resolve(process.argv[2] ?? '../kithmoot')
const prepare = process.argv.includes('--prepare')
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(path.join(web, 'package.json'))
const { build } = require('esbuild')
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'kithmoot-bookmark-interop-'))
try {
  const entry = `
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { RoomBookmarks } from './app/src/room-bookmarks.ts'
import { memoryDeviceStore } from './app/src/device-store.ts'
import { knownRooms } from './app/src/rooms-store.ts'
import { encodeRoomLink } from './src/link.ts'
import { dmPolicy } from './src/dm.ts'
import { getPublicKey, finalizeEvent } from 'nostr-tools/pure'
import { encrypt, decrypt, getConversationKey } from 'nostr-tools/nip44'
const secret = new Uint8Array(32).fill(1)
const pubkey = getPublicKey(secret)
const signer = { pubkey, nip44: {
  encrypt: async (peer, value) => encrypt(value, getConversationKey(secret, peer)),
  decrypt: async (peer, value) => decrypt(value, getConversationKey(secret, peer)),
}, signEvent: async template => finalizeEvent(template, secret) }
const sent = []
const bookmarks = new RoomBookmarks(memoryDeviceStore(), signer, {
  publish: async event => { sent.push(event) }, subscribe: () => () => {}, close: () => {},
}, () => {}, () => {})
if (${JSON.stringify(prepare)}) {
  Date.now = () => 1789000000000
  const link = encodeRoomLink('https://example.test/j/', { invitation: { bearer: new Uint8Array(32).fill(2), inviter: pubkey, persistent: true }, relays: ['wss://relay.example'], iceUrls: [], policy: dmPolicy(pubkey, getPublicKey(new Uint8Array(32).fill(3))) })
  const room = { roomId: 'a'.repeat(64), link, name: 'Browser private chat', openedAt: Date.now() / 1000, readAt: 123, keep: true }
  bookmarks.save(room)
  while (sent.length < 1) await new Promise(resolve => setTimeout(resolve, 5))
  fs.mkdirSync(${JSON.stringify(path.join(root, 'app/src/test/resources'))}, { recursive: true })
  fs.writeFileSync(${JSON.stringify(path.join(root, 'app/src/test/resources/room-bookmarks-web.json'))}, JSON.stringify({ room, event: sent[0] }, null, 2) + '\\n')
} else {
  const fixture = JSON.parse(fs.readFileSync(${JSON.stringify(path.join(root, 'app/build/interop/room-bookmarks-android.json'))}, 'utf8'))
  await bookmarks.receive(fixture.saved)
  assert.equal(knownRooms(bookmarks.rooms)[0]?.name, 'Android private chat')
  assert.equal(knownRooms(bookmarks.rooms)[0]?.roomId, fixture.roomId)
  await bookmarks.receive(fixture.removed)
  await bookmarks.receive(fixture.saved)
  assert.deepEqual(knownRooms(bookmarks.rooms), [])
}
bookmarks.close()
console.log(${JSON.stringify(prepare ? 'Browser bookmark fixture prepared with the real web writer.' : 'Browser accepted the Android bookmark and removal; stale replay did not resurrect it.')})
`
  const output = path.join(directory, 'interop.mjs')
  await build({ stdin: { contents: entry, resolveDir: web, sourcefile: 'bookmark-interop.mjs' },
    bundle: true, platform: 'node', format: 'esm', outfile: output })
  await import(pathToFileURL(output).href)
} finally { fs.rmSync(directory, { recursive: true, force: true }) }
