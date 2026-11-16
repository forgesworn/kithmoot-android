// The sole bridge is a transferred MessagePort restricted to the local origin.
// File bytes enter through one intercepted private URL. No network API is used.
import { cleanAttachment } from '../../../kithmoot/app/src/attachment-metadata.js'
let started = false
window.onmessage = async event => {
  if (started || event.ports.length !== 1) return
  started = true
  const port = event.ports[0]!
  const send = (value: unknown) => port.postMessage(JSON.stringify(value))
  try {
    const request = JSON.parse(event.data)
    const response = await fetch('/source', { cache: 'no-store', credentials: 'omit' })
    if (!response.ok) throw Error('Private input unavailable')
    const bytes = await response.arrayBuffer()
    const file = await cleanAttachment(new File([bytes], request.name, { type: request.type || '', lastModified: 0 }))
    send({ status: 'begin', bytes: file.size, name: file.name, type: file.type })
    // One acknowledged chunk at a time bounds messages held by the renderer/Binder.
    let acknowledge: (() => void) | undefined
    port.onmessage = () => { const done = acknowledge; acknowledge = undefined; done?.() }
    port.start()
    for (let offset = 0; offset < file.size; offset += 32768) {
      const chunk = new Uint8Array(await file.slice(offset, offset + 32768).arrayBuffer())
      const ack = new Promise<void>(resolve => { acknowledge = resolve })
      send({ status: 'chunk', offset, data: btoa(String.fromCharCode(...chunk)) })
      await ack
    }
    send({ status: 'complete' })
  } catch {
    send({ status: 'error' })
  }
}
