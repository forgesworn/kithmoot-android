#!/usr/bin/env node
// Synthetic test keys only. Run after npm run build:lib in the web checkout.
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'

const web = resolve(process.argv[2] ?? '../kithmoot')
const revision = process.argv[3]
if (!/^[0-9a-f]{40}$/.test(revision ?? '')) throw new Error('Supply the reviewed web source SHA as the third argument')
const load = path => import(pathToFileURL(resolve(web, 'dist/src', path)).href)
const { localIdentity } = await load('identity.js')
const { localPeerCrypt } = await load('dm.js')
const { projectAuthority } = await load('projects.js')
const { signProjectLogo, projectLogoRecord, wrapProjectLogo, unwrapProjectLogo, PROJECT_LOGO_APP } = await load('project-logo.js')
const resources = new URL('../protocol/src/test/resources/', import.meta.url)
const directory = JSON.parse(await readFile(new URL('shared-project-web.json', resources), 'utf8'))
const initial = directory.entries.find(entry => entry.id === 'kithmoot-initial').event
const body = JSON.parse(initial.content), now = directory.now
const ownerKey = new Uint8Array(32).fill(1), memberKey = new Uint8Array(32).fill(2)
const owner = { ...localIdentity(ownerKey), ...localPeerCrypt(ownerKey) }
const member = { ...localIdentity(memberKey), ...localPeerCrypt(memberKey) }
if (owner.pubkey !== initial.pubkey || member.pubkey !== directory.identities.member) throw new Error('Fixture identity mismatch')
const context = { reference: { owner: owner.pubkey, project: body.project }, definition: body.definition }
const image = JSON.parse(await readFile(new URL('logo-images-v1.json', resources), 'utf8')).vectors[0].payload
const record = { v: 1, op: 'logo', project: body.project, authority: projectAuthority(context.reference, context.definition), version: 1, request: 'browser-logo-vector-0001', image }
const inner = await signProjectLogo(owner, context, record, now)
const removal = await signProjectLogo(owner, context, { ...record, image: null, version: 2 }, now)
const outer = wrapProjectLogo(inner, member.pubkey, context, now)
const cases = []
const checkInner = (id, event, selected = context) => {
  const accepted = !!projectLogoRecord(event, selected, now)
  cases.push({ id, mode: 'inner', context: selected, event, accepted })
}
const checkOuter = async (id, event, selected = context) => {
  const decoded = await unwrapProjectLogo(event, member, selected, now)
  cases.push({ id, mode: 'outer', context: selected, event, accepted: !!decoded, ...(decoded ? { inner: decoded } : {}) })
}
checkInner('image', inner); checkInner('explicit-removal', removal)
checkInner('altered-content', { ...inner, content: removal.content })
const template = { kind: inner.kind, created_at: now, tags: inner.tags, content: inner.content }
checkInner('extra-body-field', await owner.signEvent({ ...template, content: JSON.stringify({ ...record, extra: true }) }))
checkInner('wrong-inner-domain', await owner.signEvent({ ...template, tags: [['d', body.project], ['l', PROJECT_LOGO_APP]] }))
checkInner('future-inner', await owner.signEvent({ ...template, created_at: now + 61 }))
checkInner('borrowed-owner', await member.signEvent(template))
const changed = structuredClone(context)
changed.definition.authorityRevision += 1
changed.definition.members.find(m => m.pubkey === member.pubkey).epoch += 1
checkInner('membership-changed', inner, changed)
await checkOuter('encrypted-image', outer)
await checkOuter('encrypted-removal', wrapProjectLogo(removal, member.pubkey, context, now))
await checkOuter('altered-outer', { ...outer, content: outer.content + 'x' })
await checkOuter('wrong-outer-domain', await owner.signEvent({ kind: outer.kind, created_at: now, tags: [['p', member.pubkey], ['l', 'kithmoot.projects.v1']], content: outer.content }))
await checkOuter('wrong-recipient', wrapProjectLogo(inner, owner.pubkey, context, now))
const source = await readFile(resolve(web, 'src/project-logo.ts'))
const output = { v: 1, source: { repository: 'forgesworn/kithmoot', revision, file: 'src/project-logo.ts', sha256: createHash('sha256').update(source).digest('hex') }, now, member: member.pubkey, cases }
const target = new URL('project-logo-web-v1.json', resources)
await writeFile(target, JSON.stringify(output, null, 2) + '\n')
ownerKey.fill(0); memberKey.fill(0)
console.log(`${cases.length} generated companion vectors: ${fileURLToPath(target)}`)
