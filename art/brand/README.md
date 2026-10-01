# Android brand assets

The launcher uses the four-arm vortex from the KithMoot identity of 29 September
2026, mirrored here from `kithmoot/art/brand/2026-09-29` so the Android checkout
builds without a sibling checkout:

- `kithmoot-symbol-gradient.svg` is the full-colour mark. Its rendering,
  `app/src/main/res/drawable-nodpi/brand_artwork.png`, is the adaptive icon's
  foreground, on a white background layer (the guide keeps the deep-blue arms
  off dark grounds).
- `icon.svg` is the small flat mark. Android 13 themed icons use its outline as
  the monochrome layer.

Run `python3 scripts/build-brand-icons.py` from this repository to regenerate
the XML wrappers, the background colour and the monochrome vector. The PNG is
rendered by `npm run brand:build` in the `kithmoot` repository, which writes it
here when the two checkouts sit side by side. Both API 26 adaptive and API 33
themed resources are included. A debug build verifies packaging, not a
production store release.
