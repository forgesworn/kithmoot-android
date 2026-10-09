// Independent discovery derivation over the same fixture consumed by Kotlin.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const fixtures = JSON.parse(readFileSync(new URL('../app/src/test/resources/nearby-discovery-v1.json', import.meta.url)));
const digest = (domain, hex) => createHash('sha256').update(domain + '\0', 'ascii').update(Buffer.from(hex, 'hex')).digest();
for (const row of fixtures) {
  const scope = digest('kithmoot/nearby/scope/v1', row.rootRoomId).toString('hex');
  const uuid = digest('kithmoot/nearby/service/v1', scope).subarray(0, 16);
  uuid[6] = (uuid[6] & 15) | 128;
  uuid[8] = (uuid[8] & 63) | 128;
  const hex = uuid.toString('hex');
  const service = [hex.slice(0,8), hex.slice(8,12), hex.slice(12,16), hex.slice(16,20), hex.slice(20)].join('-');
  assert.equal(scope, row.scope);
  assert.equal(service, row.serviceUuid);
}
console.log(`${fixtures.length} shared nearby discovery vectors passed in JavaScript`);
