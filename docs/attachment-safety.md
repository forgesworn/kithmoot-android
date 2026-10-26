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

Existing trusted-path code illustrates these ownership boundaries. `AttachmentViewer` bounds streamed downloads, cancels its HTTP call on disposal and scopes work with `LaunchedEffect(attachment)`; `ChatPane` gates an open viewer on the attachment still belonging to a visible, unretracted message. `openAttachment` checks the complete ciphertext hash and authenticates all records before returning plaintext. `openFileAttachment` authenticates into a private temporary file, checks interruption and publishes the destination only after verification. These mechanisms remain app/lifecycle responsibilities, not additions to the pure classifier.

Related existing coverage includes `ChatAttachmentTest` (wrong hashes and keys, tampering and truncation), `FileAttachmentTest` (no plaintext after authentication failure, size limits and interrupted work cleanup), `RecordingAttachmentRequestTest` (cancellation and prevention of stale decrypted results), and `RichChatUiTest` (tap-driven authenticated image viewing). Cancellation/stale-result tests do not belong in `AttachmentSafetyTest`: the classifier has no asynchronous state to cancel or fence.

## Goal 1 scope

Goal 1 adds only the protocol policy, its tests and vectors, and this documentation. It does not change `:app`, attachment download or decryption lifecycles, Compose screens, image/text/media renderers, or the files callers open. Integrating the decision into a viewer or save flow is separate later work and must preserve the trusted path above.
