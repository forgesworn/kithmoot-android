#!/usr/bin/env node
// Run after :protocol:test and `npm run build:lib` in the reviewed web checkout.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'

const web = process.argv[2]
if (!web) throw new Error('Usage: node scripts/verify-shared-project-web.mjs /path/to/kithmoot')
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const load = name => import(pathToFileURL(path.resolve(web, `dist/src/${name}.js`)).href)
const { projectRecord, projectAuthority, unwrapProject } = await load('projects')
const { localIdentity } = await load('identity')
const { localPeerCrypt } = await load('dm')
const fixture = JSON.parse(fs.readFileSync(path.join(root, 'protocol/build/interop/shared-project-android.json'), 'utf8'))
const key = Uint8Array.from(Buffer.from(fixture.recipientTestSecretHex, 'hex'))
const identity = { ...localIdentity(key), ...localPeerCrypt(key) }
const record = projectRecord(fixture.event, fixture.now)
assert.equal(record?.op, 'snapshot')
assert.equal(projectAuthority({ owner: fixture.event.pubkey, project: record.project }, record.definition), fixture.authority)
assert.deepEqual(await unwrapProject(fixture.wrap, identity, fixture.now), fixture.event)
assert.equal(projectRecord(fixture.follow, fixture.now)?.op, 'follow')
assert.deepEqual(await unwrapProject(fixture.followWrap, identity, fixture.now), fixture.follow)
console.log(JSON.stringify({ passed: true, checks: ['Kotlin snapshot signature and schema', 'Kotlin authority digest',
  'Kotlin snapshot NIP44 wrap', 'Kotlin follow signature and schema', 'Kotlin follow NIP44 wrap'], fixture: 'shared-project-android.json' }))
