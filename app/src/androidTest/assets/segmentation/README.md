# Segmentation fixture

NASA photograph of astronaut Eileen Collins, distributed as scikit-image's
`data.astronaut()`. Public domain; no known copyright restrictions, as recorded
by the scikit-image maintainers. This test image is packaged only in the
instrumentation APK, never the application APK or a training dataset.

Source: https://raw.githubusercontent.com/scikit-image/scikit-image/v0.25.2/skimage/data/astronaut.png
Provenance and rights: https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut
Original NASA collection: https://flic.kr/p/r9qvLn
SHA-256: `88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5`; 791,555 bytes.

Used to catch a reversed person/background confidence mask and to verify that
actual foreground and background are separated. It is not an acceptance test
for the owner's moving camera, low light, hair, held objects or thermal load.
