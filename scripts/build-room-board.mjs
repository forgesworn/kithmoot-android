#!/usr/bin/env node
// Reproduce the bundled room board page from an exact reviewed KithMoot tree.
// Usage: node scripts/build-room-board.mjs /path/to/kithmoot   (after `npm ci` there)
//
// The page is KithMoot's keyless board (src/room-board-port.ts over src/room-board-sync.ts) bundled with
// scripts/room-board/bridge.ts, beside Girnel's room view exactly as KithMoot vendors it
// (vendor/forgesworn-girnel-embed-*.tgz, installed as @forgesworn/girnel-embed).
import { readFile, writeFile, mkdir, rm, readdir } from 'node:fs/promises'
import { resolve, dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const web = resolve(process.argv[2] || '')
const revision = 'a9de6a5f07d8aeb270681020d696bce8a11c7b18'
const sources = ['src/room-board-port.ts', 'src/room-board-sync.ts', 'src/room-board-wire.ts', 'src/room-board-words.ts', 'package.json', 'package-lock.json']
const git = (...args) => execFileSync('git', args, { cwd: web, encoding: 'utf8' }).trim()
if (git('rev-parse', 'HEAD') !== revision) throw Error('Use the pinned reviewed KithMoot revision')
if (git('diff', '--name-only', 'HEAD', '--', ...sources, 'vendor')) throw Error('Room board sources must be clean')
const require = createRequire(resolve(web, 'package.json'))
const { build, version: esbuildVersion } = require('esbuild')
if (esbuildVersion !== '0.28.2') throw Error('Use the pinned esbuild 0.28.2')

const hash = value => createHash('sha256').update(value).digest('hex')
const out = resolve(root, 'app/src/main/assets/room-board')
await rm(out, { recursive: true, force: true })
await mkdir(out, { recursive: true })

// The page's own script.
const bridgePath = resolve(root, 'scripts/room-board/bridge.ts')
const entry = (await readFile(bridgePath, 'utf8')).replace("'../../../kithmoot/src/room-board-port.js'", JSON.stringify(resolve(web, 'src/room-board-port.ts')))
const result = await build({ stdin: { contents: entry, resolveDir: web, sourcefile: 'room-board-bridge.ts', loader: 'ts' }, bundle: true, format: 'iife',
  target: 'es2022', platform: 'browser', minify: true, legalComments: 'inline', write: false, metafile: true })
const bundle = result.outputFiles[0].contents
await writeFile(resolve(out, 'board.js'), bundle)

// Every package the script carries, with its licence, for the notices.
const packages = new Map()
for (const input of Object.keys(result.metafile.inputs)) {
  const match = /node_modules\/((?:@[^/]+\/)?[^/]+)\//.exec(input)
  if (!match || packages.has(match[1])) continue
  const dir = resolve(web, 'node_modules', match[1])
  const manifest = JSON.parse(await readFile(join(dir, 'package.json'), 'utf8'))
  const licenceFile = (await readdir(dir)).find(name => /^(licen[cs]e|copying)(\.|$)/i.test(name))
  packages.set(match[1], { version: manifest.version, licence: manifest.license, text: licenceFile ? await readFile(join(dir, licenceFile), 'utf8') : '' })
}
const embed = resolve(web, 'node_modules/@forgesworn/girnel-embed')
const notices = ['Room board page third-party notices', '',
  'The board script bundles these packages:', '',
  ...[...packages].sort(([a], [b]) => a.localeCompare(b)).flatMap(([name, p]) => [`${name}@${p.version}`, `SPDX: ${p.licence}`, '', p.text.trim(), '', '']),
  'Girnel room view (@forgesworn/girnel-embed), as KithMoot vendors it:', '',
  (await readFile(join(embed, 'LICENSE'), 'utf8')).trim(), '',
  (await readFile(join(embed, 'THIRD-PARTY-NOTICES.txt'), 'utf8')).trim(), ''].join('\n')
await writeFile(resolve(out, 'THIRD-PARTY-NOTICES.txt'), notices)

// Girnel's room view, byte for byte, at the paths its page names (/j/girnel/...).
const girnel = {}
const copy = async (from, to) => {
  const bytes = await readFile(from)
  await mkdir(dirname(to), { recursive: true })
  await writeFile(to, bytes)
  girnel[relative(out, to)] = hash(bytes)
}
await copy(join(embed, 'public/embedded/room.html'), resolve(out, 'j/girnel/embedded/room.html'))
for (const name of (await readdir(join(embed, 'public/assets'))).sort()) await copy(join(embed, 'public/assets', name), resolve(out, 'j/girnel/assets', name))

const lock = JSON.parse(await readFile(resolve(web, 'package-lock.json'), 'utf8'))
const embedLock = lock.packages['node_modules/@forgesworn/girnel-embed']
const embedProvenance = JSON.parse(await readFile(join(embed, 'provenance.json'), 'utf8'))
const sourceHashes = {}
for (const name of sources) sourceHashes[name] = hash(await readFile(resolve(web, name)))
const files = Object.fromEntries(Object.entries(girnel).sort(([a], [b]) => a.localeCompare(b)))
await writeFile(resolve(out, 'provenance.json'), JSON.stringify({
  schemaVersion: 1,
  source: 'https://github.com/forgesworn/kithmoot',
  revision,
  esbuild: esbuildVersion,
  sources: sourceHashes,
  bridgeSha256: hash(await readFile(bridgePath)),
  bundleSha256: hash(bundle),
  noticesSha256: hash(notices),
  girnel: { package: embedLock.resolved, version: embedLock.version, integrity: embedLock.integrity, repository: embedProvenance.repository, commit: embedProvenance.source, files },
}, null, 2) + '\n')
console.log(`Built the pinned room board page: script ${bundle.length} bytes (SHA-256 ${hash(bundle)}), ${Object.keys(files).length} Girnel files`)
