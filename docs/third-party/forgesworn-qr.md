# ForgeSworn QR (vendored)

`app/src/main/kotlin/dev/forgesworn/qr/` is the Kotlin port of the ForgeSworn
QR library (https://github.com/forgesworn/qr, `kotlin/core/src/main/kotlin`),
copied unchanged from commit `131644f`. It encodes the QR codes the app shows
and decodes the camera frames the scanner analyses, wholly on the device, with
no dependency beyond the Kotlin standard library.

Licence: MIT, in `forgesworn-qr-LICENSE.txt`.

To update it, copy the same directory from a newer commit of forgesworn/qr,
run the unit tests and note the new commit here.
