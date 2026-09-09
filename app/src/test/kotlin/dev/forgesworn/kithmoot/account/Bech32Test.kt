package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Bech32Test {
    // The NIP-19 example: this key and this npub are the same thing.
    private val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    @Test fun `npub encodes and decodes the key`() {
        assertEquals(npub, npubOf(hex))
        assertEquals(hex, publicKeyFrom(npub))
        assertEquals(hex, publicKeyFrom(hex.uppercase()))
    }

    @Test fun `the short form shows both ends`() {
        assertEquals("npub180cvv07t…yjh6w6", shortNpub(hex))
    }

    @Test fun `a damaged npub is nothing`() {
        assertNull(publicKeyFrom(npub.dropLast(1) + "q"))
        assertNull(publicKeyFrom("nsec1" + npub.drop(5)))
        assertNull(publicKeyFrom("not a key"))
    }

    @Test fun `nsec round trips`() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val nsec = Bech32.encode("nsec", key)
        assertEquals(key.toHex(), secretKeyFrom(nsec)!!.toHex())
        assertNull(secretKeyFrom(npub))
    }
}
