package dev.forgesworn.kithmoot.media

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PublicAddressTest {
    // Literals only, so getByName does no lookup.
    private fun public(literal: String) = PublicAddress.isPublic(InetAddress.getByName(literal))

    private fun assertRefused(vararg literals: String) = literals.forEach { assertFalse("$it should be refused", public(it)) }
    private fun assertAllowed(vararg literals: String) = literals.forEach { assertTrue("$it should be allowed", public(it)) }

    @Test fun ipv4RangesAreRefusedAtBothEdgesAndAllowedJustOutside() {
        // (first, last, just below, just above)
        val ranges = listOf(
            listOf("0.0.0.0", "0.255.255.255", null, "1.0.0.0"),
            listOf("10.0.0.0", "10.255.255.255", "9.255.255.255", "11.0.0.0"),
            listOf("100.64.0.0", "100.127.255.255", "100.63.255.255", "100.128.0.0"),
            listOf("127.0.0.0", "127.255.255.255", "126.255.255.255", "128.0.0.0"),
            listOf("169.254.0.0", "169.254.255.255", "169.253.255.255", "169.255.0.0"),
            listOf("172.16.0.0", "172.31.255.255", "172.15.255.255", "172.32.0.0"),
            listOf("192.0.0.0", "192.0.0.255", "191.255.255.255", "192.0.1.0"),
            // TEST-NET-1 is not in the design's list; refused as the stricter reading.
            listOf("192.0.2.0", "192.0.2.255", "192.0.1.255", "192.0.3.0"),
            listOf("192.88.99.0", "192.88.99.255", "192.88.98.255", "192.88.100.0"),
            listOf("192.168.0.0", "192.168.255.255", "192.167.255.255", "192.169.0.0"),
            listOf("198.18.0.0", "198.19.255.255", "198.17.255.255", "198.20.0.0"),
            listOf("198.51.100.0", "198.51.100.255", "198.51.99.255", "198.51.101.0"),
            listOf("203.0.113.0", "203.0.113.255", "203.0.112.255", "203.0.114.0"),
            listOf("224.0.0.0", "255.255.255.255", "223.255.255.255", null),
        )
        for ((first, last, below, above) in ranges) {
            assertRefused(first!!, last!!)
            below?.let { assertAllowed(it) }
            above?.let { assertAllowed(it) }
        }
        assertAllowed("8.8.8.8", "1.1.1.1", "93.184.216.34", "62.238.98.53")
    }

    @Test fun ipv6IsGlobalUnicastOnlyAndTheListedSpecialRangesAreRefused() {
        assertRefused("::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "fec0::1", "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "4000::1", "::8.8.8.8")
        assertAllowed("2000::1", "2606:4700:4700::1111", "2a00:1450:4009:81f::200e", "3ffe::1")
        // 2001::/32 Teredo
        assertRefused("2001::", "2001:0:ffff:ffff:ffff:ffff:ffff:ffff")
        assertAllowed("2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:1::1")
        // 2001:db8::/32 documentation
        assertRefused("2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff")
        assertAllowed("2001:db7:ffff:ffff:ffff:ffff:ffff:ffff", "2001:db9::")
        // 2002::/16 6to4
        assertRefused("2002::", "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        assertAllowed("2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2003::")
        // 3fff::/20 documentation (RFC 9637)
        assertRefused("3fff::", "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff")
        assertAllowed("3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "3fff:1000::")
    }

    @Test fun nat64ChecksTheIpv4AddressInsideIt() {
        // 64:ff9b::/96 with a public IPv4 inside is how an IPv6-only network reaches IPv4 hosts.
        assertAllowed("64:ff9b::808:808", "64:ff9b::5dae:d822")
        assertRefused(
            "64:ff9b::a00:1",          // 10.0.0.1
            "64:ff9b::7f00:1",         // 127.0.0.1
            "64:ff9b::c0a8:1",         // 192.168.0.1
            "64:ff9b::a9fe:a9fe",      // 169.254.169.254
            "64:ff9b::c000:201",       // 192.0.2.1
            "64:ff9b::6440:1",         // 100.64.0.1
            "64:ff9b::e000:1",         // 224.0.0.1
            "64:ff9b::",               // 0.0.0.0
        )
        // Only the /96 counts: the local-use prefix and near misses stay refused.
        assertRefused("64:ff9b:1::808:808", "64:ff9a::808:808", "64:ff9b::1:808:808", "100::808:808")
    }

    @Test fun ipv4MappedIpv6ReachesTheAddressInsideIt() {
        fun mapped(a: Int, b: Int, c: Int, d: Int) = ByteArray(16).also {
            it[10] = -1; it[11] = -1; it[12] = a.toByte(); it[13] = b.toByte(); it[14] = c.toByte(); it[15] = d.toByte()
        }
        assertTrue(PublicAddress.isPublic(mapped(8, 8, 8, 8)))
        assertFalse(PublicAddress.isPublic(mapped(10, 0, 0, 1)))
        assertFalse(PublicAddress.isPublic(mapped(192, 0, 2, 1)))
        // Java normalises the same address to IPv4 before it gets here.
        assertFalse(public("::ffff:10.0.0.1"))
        assertTrue(public("::ffff:8.8.8.8"))
    }

    @Test fun malformedByteArraysAreRefused() {
        assertFalse(PublicAddress.isPublic(ByteArray(0)))
        assertFalse(PublicAddress.isPublic(ByteArray(5) { 8 }))
        assertFalse(PublicAddress.isPublic(ByteArray(15) { 0x20 }))
    }

    @Test fun theDnsRefusesTheWholeAnswerIfAnyAddressIsNotPublic() {
        fun dns(vararg literals: String) = PublicOnlyDns(object : Dns {
            override fun lookup(hostname: String) = literals.map { InetAddress.getByName(it) }
        })
        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), dns("8.8.8.8").lookup("relay.example.org"))
        assertEquals(2, dns("8.8.8.8", "2606:4700:4700::1111").lookup("relay.example.org").size)
        for (answer in listOf(arrayOf("10.0.0.1"), arrayOf("127.0.0.1"), arrayOf("::1"), arrayOf("8.8.8.8", "192.168.1.1"), arrayOf("fe80::1"), emptyArray<String>())) {
            val error = assertThrows(UnknownHostException::class.java) { dns(*answer).lookup("secret.relay.example.org") }
            // The failure says nothing about the name or the answer.
            assertFalse(error.message.orEmpty().contains("secret"))
            assertFalse(error.message.orEmpty().contains("10.0.0.1"))
        }
    }

    @Test fun theDnsPassesAResolverFailureThrough() {
        val failing = PublicOnlyDns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("no such host") })
        assertThrows(UnknownHostException::class.java) { failing.lookup("x.example.org") }
    }
}
