# Android desktop-share fitting, 13 October 2026

A desktop share appeared cropped in both the call tile and expanded viewer.
Compose gave the tile renderer exact dimensions, so libwebrtc's preferred
aspect-fit measurement could not letterbox it. Fit the actual surface to the
rotated frame dimensions instead. Initialise the expanded texture buffer and
renderer aspect on the first surface callback, and reset zoom/pan for a new
share while retaining the decoded aspect when that share reuses a track.

The 3840-by-1080 synthetic live-video regression fails on the old tile. The
corrected renderer preserves all four bright desktop edges in the tile and
expanded viewer, including replacing a zoomed share on the same track. The two
existing chat/share UI tests also pass, covering drawing, zoom and real platform
picture-in-picture. These are emulator rendering checks, not physical-device or
end-to-end network acceptance. The validated debug APK was 0.6.83 (106); the
unchanged view inputs ship in candidate 0.6.84 (107).

Input and log hashes are recorded in `evidence/screen-share-fit-2026-10-13.json`.
Production signing, exact-source CI and publication remain required.
