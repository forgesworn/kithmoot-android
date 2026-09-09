# Android brand assets

The approved sculptural artwork is stored at `app/src/main/res/drawable-nodpi/brand_artwork.png` and supplies the ordinary
launcher icon and the product entrance. Adaptive wrappers preserve safe margins
for launcher masks. Android 13 themed icons and notifications use a monochrome
companion built from `icon.svg`.

Run `python3 scripts/build-brand-icons.py` from this repository to regenerate
XML wrappers and monochrome vectors. This does not modify the artwork. The source
master and generation prompt are mirrored here so the Android checkout builds
without a sibling checkout. The `drawable-nodpi` artwork is copied unchanged from
the approved product master. Both API 26 adaptive and API 33 themed resources are
included. A debug build verifies packaging, not a production store release.
