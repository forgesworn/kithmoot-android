import { createRequire } from 'node:module'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

// Local synthetic qualification; no signer, relay, storage server or peer lookup.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const wildbloom = resolve(process.argv[2] ?? resolve(root, '../wildbloom'))
const artifacts = resolve(process.argv[3] ?? resolve(root, 'app/build/recording-interop'))
const require = createRequire(resolve(wildbloom, 'package.json'))
const { build } = require('esbuild')
await mkdir(artifacts, { recursive: true })
const reader = resolve(artifacts, 'wildbloom-reader.mjs')
await build({ entryPoints: [resolve(wildbloom, 'src/core/crypto.ts')], outfile: reader, bundle: true, platform: 'node', format: 'esm', target: 'node24' })
const { decryptPrivacyEnvelope, sha256Hex } = await import(pathToFileURL(reader).href)
const results = []
const names = process.argv[4] === '--playable-only' ? ['synthetic-gallery.mp4'] : ['synthetic-call.wav', 'canonical-name.wav', 'call.mp4']
for (const name of names) {
  const meta = JSON.parse(await readFile(resolve(artifacts, `${name}.json`), 'utf8'))
  if (meta.synthetic !== true) throw new Error('Only synthetic recording evidence is permitted')
  const bytes = await readFile(resolve(artifacts, `${name}.enc`))
  if (await sha256Hex(bytes) !== meta.sha256) throw new Error('Envelope hash mismatch')
  const recoveryKey = `wbk1_${Buffer.from(meta.key, 'hex').toString('base64url')}`
  const opened = await decryptPrivacyEnvelope(new File([bytes], 'encrypted.bin'), recoveryKey)
  if (opened.name !== meta.name || opened.type !== meta.type || opened.size !== meta.sourceSize) throw new Error('Metadata mismatch')
  if (await sha256Hex(opened) !== meta.sourceSha256) throw new Error('Plaintext mismatch')
  if (name.endsWith('.wav')) {
    const wav = Buffer.from(await opened.arrayBuffer())
    if (wav.toString('ascii', 0, 4) !== 'RIFF' || wav.readUInt32LE(24) !== 48000 || wav.readUInt32LE(40) !== 96000) throw new Error('Invalid mixed call export')
    // Independently measure both synthetic tones, rather than only non-zero PCM.
    const levels = [440, 660].map(frequency => {
      let sine = 0, cosine = 0
      for (let frame = 0; frame < 48000; frame++) {
        const sample = wav.readInt16LE(44 + frame * 2)
        sine += sample * Math.sin(frame * 2 * Math.PI * frequency / 48000)
        cosine += sample * Math.cos(frame * 2 * Math.PI * frequency / 48000)
      }
      return 2 * Math.hypot(sine, cosine) / 48000
    })
    if (levels.some(level => level < 3500 || level > 4500)) throw new Error('Both sides of the synthetic mix must survive export')
    results.push({ name, size: opened.size, toneAmplitudes: levels })
  } else if (name === 'synthetic-gallery.mp4') {
    const movie = resolve(artifacts, 'opened-synthetic-gallery.mp4')
    await writeFile(movie, Buffer.from(await opened.arrayBuffer()))
    const probe = spawnSync('ffprobe', ['-v', 'error', '-show_streams', '-of', 'json', movie], { encoding: 'utf8', timeout: 30_000 })
    if (probe.status !== 0) throw new Error('Independent MP4 inspection failed')
    const streams = JSON.parse(probe.stdout).streams
    const audio = streams.find(stream => stream.codec_type === 'audio')
    const video = streams.find(stream => stream.codec_type === 'video')
    const audioDuration = Number(audio?.duration)
    const videoDuration = Number(video?.duration)
    if (streams.length !== 2 || audio?.codec_name !== 'aac' || video?.codec_name !== 'h264' ||
        video.width !== 1280 || video.height !== 720 || !Number.isFinite(audioDuration) || !Number.isFinite(videoDuration) ||
        audioDuration <= 0 || videoDuration <= 0 || !(Number(video.nb_frames) >= 2) ||
        Math.abs(audioDuration - videoDuration) > 0.001) throw new Error('Unexpected playable recording tracks')
    const decoded = spawnSync('ffmpeg', ['-nostdin', '-v', 'error', '-i', movie, '-map', '0:a:0', '-map', '0:v:0', '-f', 'null', '-'], { encoding: 'utf8', timeout: 120_000 })
    if (decoded.status !== 0 || decoded.stderr.trim()) throw new Error('Independent complete audio/video decoding failed')
    results.push({ name, size: opened.size, scope: 'playable synthetic native gallery MP4',
      independentlyDecoded: true, tracks: streams.map(stream => ({ codec: stream.codec_name, duration: stream.duration })) })
  } else results.push({ name, size: opened.size, scope: 'synthetic bytes, not a playable video' })
}
const sources = [
  resolve(root, 'app/src/main/kotlin/dev/forgesworn/kithmoot/session/FileAttachment.kt'),
  resolve(root, 'app/src/main/kotlin/dev/forgesworn/kithmoot/media/recording/PcmRecording.kt'),
  resolve(wildbloom, 'src/core/crypto.ts'),
]
const hashes = await Promise.all(sources.map(async path => ({ path, sha256: createHash('sha256').update(await readFile(path)).digest('hex') })))
await writeFile(resolve(artifacts, 'receipt.json'), JSON.stringify({ passed: true, synthetic: true, results, sources: hashes }, null, 2) + '\n')
console.log(JSON.stringify({ passed: true, results }, null, 2))
