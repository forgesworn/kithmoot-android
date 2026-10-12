# Valid invitation envelope measurements

Implements mesh-kit-private precode b26c0f0. Production code, the old 47 journal
test bodies, archive fixture, Android instrumentation and recovery runner remain
unchanged. Six new bounded tests measure twelve normal fresh-source cases:
Nearby/Internet/Mixed, ASCII/three-byte BMP canonical relay paths, ordinary and
maximum-width valid clocks. A seventh test measures three over-limit policy
refusals before either private authority or base-secret factory enters.

The local fresh journal plus four invitation/relay/crypto sources and test
compilation passes 54 journal tests, retaining all 60 old capacity rows. Twelve
new actual size rows compare unsigned sizing with real signed/decrypted events,
strict source cold reopening, unchanged committed bytes and cleanup. Maximum
observed invitation size is 10,105 bytes with eight 256-unit/752-byte URLs.
The conservative three-byte-per-unit plaintext upper bound is 6,355 bytes;
the real padding/event layout upper bound is 10,105 bytes at the wide clock.

Under the inspected URI, relay count/length and fixed body constraints, a
creation/replacement welcome cannot reach the 16 KiB cap. These examples and
source reasoning do not directly measure a 16 KiB case, other event kinds,
replacement preparation, source-file thresholds, Android/RF or auxiliary
entropy. The independent spend/file boundaries and every final gate remain.
No limit is changed and no authority JSON is manufactured.

Preserve failed 20b/c459 workflows and their actual maintenance caller trace.
Their production issues are separate from these fixture additions; this branch
cannot merge until its own complete hosted gate and all remaining requirements
qualify. No radio, firmware, MQTT/group or public relay operation is included.
