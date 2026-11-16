#!/usr/bin/env python3
"""Refuse stale or altered bundled cleaning code before either APK is built."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets/attachment-cleaner'
proof = json.loads((assets / 'provenance.json').read_text())
assert proof['schemaVersion'] == 1
assert proof['source'] == 'https://github.com/forgesworn/kithmoot'
assert proof['revision'] == 'e0878edea424c98c3eddcc05e47656fb03e602b7'
assert proof['esbuild'] == '0.28.2'
for path, key in [(assets / 'cleaner.js', 'bundleSha256'),
                  (assets / 'THIRD-PARTY-NOTICES.txt', 'noticesSha256'),
                  (root / 'scripts/attachment-cleaner/bridge.ts', 'bridgeSha256')]:
    assert hashlib.sha256(path.read_bytes()).hexdigest() == proof[key], f'Stale or altered cleaner: {path.name}'
print('Pinned offline attachment cleaner, bridge and notices verified.')
