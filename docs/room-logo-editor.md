# Local room logo editor

In a regular admitted room, open **Room details → Choose room logo** (or
**Change room logo**). Android's photo picker grants access to the selected
image without a library-wide media permission. Choose PNG, JPEG or still WebP,
adjust the square crop with zoom and horizontal/vertical sliders, then choose
**Share logo with room**. Existing overrides have **Remove logo for everyone**.
Cancel keeps the shared logo unchanged.

The picker URI remains local. Input is capped at 8 MiB, 16 megapixels and 8192
pixels per side before pixel allocation. Native decoding applies JPEG EXIF
orientation and limits the working image to a 2048-pixel long edge. Animation,
unsupported formats, partial images and transparent crops are refused.

The preview and output use the same crop. A fresh square sRGB canvas is encoded
as still WebP (PNG fallback), bounded to 256 pixels and 16 KiB. Sender-only
canonicalisation removes embedded metadata from that fresh encoding; the strict
recipient codec continues to reject metadata rather than repairing signed
input. Original bytes, names, paths and picker URIs never enter the room event.
There is no external logo host or image loader.

Sharing uses the encrypted control channel and requires relay confirmation.
The editor captures its RoomSession, RoomWork, participant/device and epoch.
Expiry, removal, a room switch, an account switch, a key transition or editor
cancellation invalidates queued dispatch. Confirmed events can already have
reached other members; cancellation cannot recall a published event.

Anonymous, quiet and two-person rooms do not expose these controls. This does
not implement project logos, project inheritance or native project recovery.

`LogoPixelsTest` checks source bounds, animation refusal, metadata stripping and
crop geometry. `RoomWorkLogoTest` checks cancelled/invalid editor publication
alongside confirmed removal, rekey and close guards. `RoomLogoEditorTest` uses
actual Android content URI reads, JPEG orientation, crop pixels, source-header
bounds, transparent/animated rejection, explicit removal, cancellation, stale
session controls and reachable actions with enlarged text in both themes.
These emulator checks do not establish physical photo-picker acceptance or a
published production APK. The complete G15 journey remains open.
