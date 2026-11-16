# Local attachment cleaning

Android uses the same cleaner as web and desktop, pinned to KithMoot
[e0878edea424c98c3eddcc05e47656fb03e602b7](https://github.com/forgesworn/kithmoot/tree/e0878edea424c98c3eddcc05e47656fb03e602b7).
The MIT-licensed first-party source remains available there. Unmodified
Mediabunny (MPL-2.0), pdf-lib and fflate source references and full notices are
packaged in `app/src/main/assets/attachment-cleaner/THIRD-PARTY-NOTICES.txt`.
No conversion service is used. Google Play services are not required.

Run `npm ci` in a clean checkout of that exact web revision, then:

```
node scripts/build-attachment-cleaner.mjs /path/to/kithmoot
python3 scripts/check-attachment-cleaner.py
```

CI reproduces the bundle and refuses a byte difference. Every debug/release
build checks its bundle, bridge and notice hashes against the committed
provenance. Updating the source pin requires reviewing the cleaner and refreshing
both checks and provenance, not fetching new code at runtime.

The renderer loads three intercepted resources on a fresh random HTTPS origin:
the static page, bundled code and one private source. Other requests return a
local refusal; network loads, navigation, file/content access, persistent DOM
storage, geolocation and additional windows are disabled. The app transfers a
[message port to that exact origin](https://developer.android.com/reference/android/webkit/WebView#postWebMessage(android.webkit.WebMessage,%20android.net.Uri));
it exposes no Java object. The renderer receives no account, room, upload URL
or provider URI. Acknowledged 32 KiB chunks bound results to 64 MiB. A failure,
renderer exit, cancellation or 90-second deadline leaves the file unsent.
There is no original-file fallback. Plaintext scratch files stay in private app
storage and are removed in `finally`; existing outgoing-cache sweeps remove
interrupted-attempt leftovers.

The shared cleaner handles still/animated PNG, JPEG, GIF, WebP, supported fresh
pixel image conversions, MP4/MOV, WebM/Matroska, MP3, WAV, FLAC, Ogg, PDF, DOCX,
XLSX, PPTX, ODT, ODS, ODP and named plain-text formats. Unsupported codecs or
containers, active/embedded document payloads outside its policy and damaged
files are held. Animation and encoded media packets are retained where
supported. Common document properties, editing identities and contained image
tags are removed according to the pinned source's policy. It does not anonymise
visible words, faces, voices, file names or information people put in content.
Some signed or embedded documents need exporting to a fresh supported copy.

`OutgoingFilePrivacyTest` checks the pre-encryption boundary, original retention,
size refusal, cancellation, failure cleanup and corrected name/type.
`OutgoingMetadataTest` runs the real offline renderer and encrypted reader on
synthetic tagged images/media/documents on a disposable emulator. The retained
media/PDF outputs can be independently inspected with the pinned web libraries.
Physical devices and personal files require their own acceptance.
