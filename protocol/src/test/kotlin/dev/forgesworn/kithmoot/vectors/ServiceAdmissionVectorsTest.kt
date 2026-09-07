package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceAdmissionVectorsTest {
    @Test fun canonicalAudiences() {
        for (v in Vectors.group("serviceAudience")) {
            assertEquals(v.text("name"), v.child("expected").childOrNull("result"), normaliseServiceAudience(v.child("input"))?.toJson())
        }
    }
    @Test fun memberPasses() {
        for (v in Vectors.group("memberPass")) {
            assertEquals(v.text("name"), v.child("expected").childOrNull("result"), decodeMemberPass(NostrEvent.fromJson(v.child("input").child("event")))?.toJson())
        }
    }
    @Test fun servicePolicies() {
        for (v in Vectors.group("servicePolicy")) {
            assertEquals(v.text("name"), v.child("expected").childOrNull("result"), decodeServicePolicy(NostrEvent.fromJson(v.child("input").child("event")))?.toJson())
        }
    }
    @Test fun independentServiceKeys() {
        for (v in Vectors.group("serviceScope")) {
            val input = v.child("input"); val output = v.child("output")
            val audience = requireNotNull(normaliseServiceAudience(input.child("audience")))
            val room = input.text("roomId")
            assertEquals(v.text("name"), output.text("authoritySkHex"), deriveServiceKey(input.bytes("authoritySkHex"), room, audience, "authority").toHex())
            assertEquals(v.text("name"), output.text("deviceSkHex"), deriveServiceKey(input.bytes("deviceSkHex"), room, audience, "device").toHex())
            assertEquals(v.text("name"), output.text("room"), deriveServiceRoom(input.bytes("trafficSecretHex"), room, audience))
        }
    }
}
