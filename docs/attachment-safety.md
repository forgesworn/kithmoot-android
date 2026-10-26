# Attachment safety policy

`AttachmentSafety` is the pure Kotlin/JVM port of the TypeScript attachment policy in `src/attachment-safety.ts`. The verbatim `attachment-safety-v1.json` vectors pin cross-implementation answers; focused tests also pin complete decisions, precedence and user-facing wording.

## Attacker and assets

The sender controls the message name, declared media type, envelope name and file contents. A malicious file host can also return arbitrary ciphertext, truncate it or replace it. The policy protects the recipient from disguised programs and shortcuts, misleading names, dangerous file types and unsafe preview choices. Its assets are the recipient's device and files, the integrity of names shown or saved, and the boundary between data a renderer may consume and data that must remain opaque.

Transport trust and plaintext classification are separate. HTTPS, a download size check, the ciphertext SHA-256 check and authenticated decryption establish which encrypted attachment was received and prevent unauthenticated plaintext from being exposed. They do **not** make the sender's plaintext safe. Only after those checks may the caller pass the decrypted bytes and names to `AttachmentSafety`; the names, declared type and sniffed plaintext remain hostile inputs there.

## Pure policy boundary

The classifier reads at most the first 1,024 bytes, cleans names and returns an immutable `AttachmentDecision` containing the tier, reason, save policy, preview kind, MIME type, family, message and confirmation. It performs no network, storage, UI, rendering, decryption, mutation or other side effects. It does not open files or choose external applications.

This is a policy check, not a malware scanner. A quiet or warn decision is not a guarantee that a file is benign, that its format parser is safe, or that saved content cannot be malicious. Signature sniffing and extension rules cannot detect every exploit or harmful payload.

The caller retains all lifecycle and resource responsibilities:

- enforce download, ciphertext, plaintext, image-dimension and decompression limits before expensive work or allocation;
- authenticate the ciphertext hash and every encrypted record before exposing plaintext;
- own coroutine or thread cancellation, deadlines, temporary-file cleanup and plaintext erasure;
- fence stale completions against the currently selected attachment, message retraction, room/session changes and viewer disposal before publishing or rendering a result;
- enforce the returned block, warning, save and preview decision, and use the cleaned envelope name and safe preview MIME type.

Existing trusted-path code illustrates these ownership boundaries. `AttachmentViewer` bounds streamed downloads, cancels its HTTP call on disposal and scopes work to the selected attachment; `ChatPane` gates an open viewer on its exact originating `MessageRef` and attachment index still belonging to a visible, unretracted message. `openAttachment` checks the complete ciphertext hash and authenticates all records before returning plaintext. `openFileAttachment` authenticates into a private temporary file, checks interruption and publishes the destination only after verification. These mechanisms remain app/lifecycle responsibilities, not additions to the pure classifier.

Related existing coverage includes `ChatAttachmentTest` (wrong hashes and keys, tampering and truncation), `FileAttachmentTest` (no plaintext after authentication failure, size limits and interrupted work cleanup), `RecordingAttachmentRequestTest` (cancellation and prevention of stale decrypted results), and `RichChatUiTest` (tap-driven authenticated image viewing). Cancellation/stale-result tests do not belong in `AttachmentSafetyTest`: the classifier has no asynchronous state to cancel or fence.

## Goal 2 isolated image boundary

The room-owning app process still performs the trusted download, 32 MiB encrypted-input check, complete ciphertext hash check, authenticated decryption and `AttachmentSafety` classification. Immediately after `openAttachment`, the viewer classifies `AttachmentInput(file.bytes, file.name, file.type, attachment.name, sender)`. A `BLOCK` decision shows its message without rendering or offering Open in. A `WARN` decision remains visible over any preview that the decision permits. Text remains inert and recording playback is unchanged.

Only one approved PNG, JPEG, WebP or GIF plaintext crosses to `IsolatedAttachmentDecoderService`, a non-exported `android:isolatedProcess="true"` bound service. The AIDL request carries a read-only `SharedMemory` handle, its bounded length, the approved MIME type and a callback: never a URL, decryption key, room, account or storage capability. The app writes the plaintext, unmaps its writable mapping and only then applies `PROT_READ`. No plaintext or pixel byte array crosses an ordinary Binder transaction.

The service accepts one asynchronous decode per binding. Static formats use `ImageDecoder` with software ARGB output, exact routed/header MIME agreement and partial images rejected. GIF uses isolated `Movie` plus a parser that walks every block, rejects truncation and trailing data, and obtains raw seek timing and display timing for every frame. GIF limits are explicit: at most 256 frames, width and height each at most 8,192, at most 16 megapixels per frame, and at most 64 MiB of aggregate RGBA. Every image frame must have a nonzero raw Graphic Control delay: explicit zero and missing GCE are rejected before Movie decoding because seek timestamps cannot distinguish consecutive raw-zero frames. Movie seeks otherwise use the original 10 ms delay units, with subsequent frames sampled 1 ms after their boundary because Android 15 Movie treats an exact boundary as the preceding frame. For UI scheduling only, a one-hundredth delay becomes 100 ms; all other display delays retain their exact units. All size arithmetic is checked. The same dimension, frame and RGBA limits apply to static output.

Decoded output contains only dimensions, contiguous frame offsets, bounded delays and a read-only shared RGBA allocation. The service unmaps writable output before `PROT_READ`. The app closes its input allocation as soon as the isolated decode returns, independently validates every output metadata field and the exact allocation length, copies each frame into an owned software `Bitmap`, and closes the output allocation. Published bitmaps follow Compose ownership and are not manually recycled while a `BitmapPainter` or emitted draw can still reference them. There is no main-process decoder fallback. Compose retains fit, actual size, zoom, pan and the attachment-name content description; GIF frames advance only while the viewer lifecycle is started.

Completion, cancellation, timeout, viewer closure, Binder death and unbinding all fence the session. Late callbacks cannot publish into a replacement viewer, and cancellation after a successful callback closes any resumed output that the coroutine discards. Unbinding cancels work and the isolated service terminates its own process, so decoder failure cannot take down the room or call. Corrupt or inconsistent output is rejected rather than rendered.

## External viewer boundary

Open in deliberately leaves the isolated-preview boundary. The app writes the already authenticated plaintext to its private cache and grants only the chosen external activity a read URI, using the classifier's cleaned name for UI and sanitised MIME type for the intent. A `WARN` save decision requires a confirmation first; `BLOCK` has no external-open action. The external application has its own parsers and privileges, so an in-app isolated preview is not a claim that opening the same file elsewhere is equally contained.

## Verification and remaining acceptance

Pure JVM tests cover raw and normalised GIF timing, missing graphics control, dimension/frame/aggregate limits, output-layout validation and exact duplicate-message retraction. Android instrumentation drives the real bound service with PNG, JPEG, WebP and the encrypted four-frame/two-colour GIF fixture, checking exact frame order and delays, mixed raw/UI timing, raw-zero rejection, manifest isolation, routed MIME mismatch and partial static input. On the Android 15 API 35 emulator, all six service tests passed against both debug and the final minified release. The release also passed `WebRtcNativeLoadTest` and the signing-lineage checks through `scripts/check-signing-lineage-emulator.sh`, which now includes the decoder gate. Four Compose tests passed with deterministic local encrypted fixtures, covering static display, fit/actual-size/pinch, retraction, GIF animation and close. Emulator evidence does not replace the remaining physical acceptance below.

The decoder uses the fully qualified process name `dev.forgesworn.kithmoot.attachment.decode`; the Android 15 emulator rejected the colon-form name during package parsing with `ParsedServiceImpl cannot be cast to java.lang.String`. The packaged manifest and runtime process checks pin the accepted name and unchanged isolated, non-exported boundary.

Physical GrapheneOS acceptance remains: verify all four formats and both GIF colours in order; pause/resume animation; exercise fit, actual size, zoom/pan and accessibility; cancel, close and retract during decode; confirm timeout/Binder-death recovery leaves the room and an active call healthy; inspect that `dev.forgesworn.kithmoot.attachment.decode` exits after every session; and check warned/blocked external-open behaviour. These checks must be recorded against the resulting build before release approval.
