#!/usr/bin/env python3
"""Refuse a stale, altered or incomplete room board page before either APK is built."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assets = root / 'app/src/main/assets/room-board'
proof = json.loads((assets / 'provenance.json').read_text())
assert proof['schemaVersion'] == 1
assert proof['source'] == 'https://github.com/forgesworn/kithmoot'
assert proof['revision'] == '8b0a072887931c0763f7484b91e2f479f14c0048'
assert proof['esbuild'] == '0.28.2'


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


for path, key in [(assets / 'board.js', 'bundleSha256'),
                  (assets / 'THIRD-PARTY-NOTICES.txt', 'noticesSha256'),
                  (root / 'scripts/room-board/bridge.ts', 'bridgeSha256')]:
    assert sha256(path) == proof[key], f'Stale or altered room board page: {path.name}'

girnel = proof['girnel']['files']
assert girnel, 'The room board page names no Girnel files'
for name, digest in girnel.items():
    target = (assets / name).resolve()
    assert target.is_relative_to(assets.resolve() / 'j/girnel'), f'Invalid room board page entry: {name}'
    assert sha256(target) == digest, f'Stale or altered Girnel file: {name}'
expected = {'board.js', 'THIRD-PARTY-NOTICES.txt', 'provenance.json', *girnel}
present = {str(path.relative_to(assets)) for path in assets.rglob('*') if path.is_file()}
assert present == expected, f'Unpinned room board files: {sorted(present ^ expected)}'
print(f'Pinned room board page, bridge, notices and {len(girnel)} Girnel files verified.')
