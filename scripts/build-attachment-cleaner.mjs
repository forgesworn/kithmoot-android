#!/usr/bin/env node
// Reproduce the bundled on-device cleaner from an exact reviewed KithMoot tree.
// Usage: node scripts/build-attachment-cleaner.mjs /path/to/kithmoot
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const web = resolve(process.argv[2] || '')
const revision = 'e0878edea424c98c3eddcc05e47656fb03e602b7'
if (execFileSync('git', ['rev-parse', 'HEAD'], { cwd: web, encoding: 'utf8' }).trim() !== revision) throw Error('Use the pinned reviewed web revision')
if (execFileSync('git', ['diff', '--name-only', 'HEAD', '--', 'app/src/attachment-metadata*', 'src/attachment-safety.ts', 'package.json', 'package-lock.json'], { cwd: web, encoding: 'utf8' }).trim()) throw Error('Cleaner sources must be clean')
const require = createRequire(resolve(web, 'package.json'))
const { build } = require('esbuild')
const entry = (await readFile(resolve(root, 'scripts/attachment-cleaner/bridge.ts'), 'utf8')).replace("'../../../kithmoot/app/src/attachment-metadata.js'", JSON.stringify(resolve(web, 'app/src/attachment-metadata.ts')))
const out = resolve(root, 'app/src/main/assets/attachment-cleaner')
await mkdir(out, { recursive: true })
const result = await build({ stdin: { contents: entry, resolveDir: web, sourcefile: 'attachment-cleaner-bridge.ts', loader: 'ts' }, bundle: true, format: 'iife', target: 'es2022', platform: 'browser', minify: true, legalComments: 'inline', write: false })
const bytes = result.outputFiles[0].contents
await writeFile(resolve(out, 'cleaner.js'), bytes)
const notices = await readFile(resolve(web, 'app/public/metadata-third-party-notices.txt'))
await writeFile(resolve(out, 'THIRD-PARTY-NOTICES.txt'), notices)
const hash = value => createHash('sha256').update(value).digest('hex')
const sources = ['app/src/attachment-metadata.ts', 'app/src/attachment-metadata-media.ts', 'app/src/attachment-metadata-document.ts', 'src/attachment-safety.ts', 'package-lock.json']
const sourceHashes = {}
for (const name of sources) sourceHashes[name] = hash(await readFile(resolve(web, name)))
await writeFile(resolve(out, 'provenance.json'), JSON.stringify({ schemaVersion: 1, source: 'https://github.com/forgesworn/kithmoot', revision, esbuild: require('esbuild/package.json').version, sources: sourceHashes, bundleSha256: hash(bytes), noticesSha256: hash(notices) }, null, 2) + '\n')
console.log(`Built pinned local cleaner: ${bytes.length} bytes, SHA-256 ${hash(bytes)}`)
