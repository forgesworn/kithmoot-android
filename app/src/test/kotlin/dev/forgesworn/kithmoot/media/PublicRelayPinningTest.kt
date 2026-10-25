package dev.forgesworn.kithmoot.media

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicRelayPinningTest {
    private val login = PublicRelayLogin("a".repeat(64), "relay.example.org",
        listOf("turn:relay.example.org:3478?transport=udp", "turns:relay.example.org:5349?transport=tcp", "turn:RELAY.example.org"),
        "1793003600:pub-0123456789abcdef01234567", "c2VjcmV0", 1_793_003_600)
    private fun at(vararg addresses: String): (String) -> List<InetAddress> = { addresses.map(InetAddress::getByName) }

    @Test fun everyUrlCarriesTheCheckedAddressAndTheNameIsKept() {
        val pinned = PublicRelayPinning.pin(login, at("8.8.4.4"))!!
        assertEquals(listOf("turn:8.8.4.4:3478?transport=udp", "turns:8.8.4.4:5349?transport=tcp", "turn:8.8.4.4"), pinned.urls)
        assertEquals("relay.example.org", pinned.host)
        val server = CallIceServers.resolveDiscovered(pinned, null, relayOnly = true).single()
        assertEquals("relay.example.org", server.hostname)
        assertEquals(pinned.urls, server.urls)
    }

    @Test fun ipv6IsBracketed() {
        val pinned = PublicRelayPinning.pin(login, at("2606:4700:4700::1111"))!!
        assertEquals("turn:[2606:4700:4700:0:0:0:0:1111]:3478?transport=udp", pinned.urls.first())
    }

    @Test fun aNameThatDoesNotResolveToAPublicAddressIsNotPinned() {
        for (address in listOf("10.0.0.1", "127.0.0.1", "192.168.1.1", "100.64.0.1", "::1", "fe80::1", "64:ff9b::a00:1")) {
            assertNull(PublicRelayPinning.pin(login, PublicOnlyDns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName(address))
            })::lookup), address)
            assertNull(PublicRelayPinning.pin(login, at(address)), address)
        }
        assertNull(PublicRelayPinning.pin(login) { throw UnknownHostException("relay.example.org") })
        assertNull(PublicRelayPinning.pin(login) { emptyList() })
    }

    @Test fun aUrlForAnotherHostRefusesThePin() {
        val other = PublicRelayLogin(login.key, login.host, listOf("turn:other.example.org:3478"), login.username, login.credential, login.expiresAt)
        assertNull(PublicRelayPinning.pin(other, at("8.8.4.4")))
        assertNull(PublicRelayPinning.rewrite("turn:[::1]:3478", "relay.example.org", "8.8.4.4"))
    }

    @Test fun nothingSecretOrIdentifyingIsInItsText() {
        val pinned = PublicRelayPinning.pin(login, at("8.8.4.4"))!!
        assertFalse(pinned.toString().contains("relay.example.org"))
        assertFalse(pinned.toString().contains("8.8.4.4"))
        assertFalse(pinned.toString().contains("c2VjcmV0"))
    }

    @Test fun theCheckLooksForARelayCandidate() {
        assertTrue(PublicRelayCheck.isRelayCandidate("candidate:1 1 udp 41885439 8.8.4.4 50000 typ relay raddr 0.0.0.0 rport 0 generation 0"))
        assertFalse(PublicRelayCheck.isRelayCandidate("candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host generation 0"))
        assertFalse(PublicRelayCheck.isRelayCandidate("candidate:1 1 udp 1686052607 8.8.4.4 50000 typ srflx raddr relay rport 0"))
    }
}
