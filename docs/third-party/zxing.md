# ZXing QR code (vendored)

`app/src/main/java/dev/forgesworn/kithmoot/qr/zxing/` is the QR part of ZXing
core 3.5.3 (https://github.com/zxing/zxing, tag `zxing-3.5.3`), copied from
the Maven Central sources jar (`core-3.5.3-sources.jar`, SHA-1
`e842d62431675510e2b2116bbc8f75a8b1f6d1ca`).

Changes from upstream:

- Only `qrcode/`, `common/` and the root classes they need are kept. Every
  other barcode format, `MultiFormatReader` and `MultiFormatWriter` are removed.
- The package `com.google.zxing` is renamed to `dev.forgesworn.kithmoot.qr.zxing`.
  No other source change.

Licence: Apache License 2.0, in `zxing-LICENSE.txt`. Each file keeps its
upstream copyright header. Upstream's NOTICE covers Barcode4J (Data Matrix and
PDF417 code) and JCommander (command-line tools), neither of which is
included here.

It replaces Google ML Kit barcode scanning, which brought Google Play services
and Firebase libraries into the APK. It is an interim step: the ForgeSworn QR
library will replace it.
