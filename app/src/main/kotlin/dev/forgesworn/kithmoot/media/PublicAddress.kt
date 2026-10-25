package dev.forgesworn.kithmoot.media

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which resolved addresses a client may connect to for a name it did not
 * choose itself (a Spry node, or a public call relay an operator advertised).
 * Only globally routable unicast addresses pass: not private, loopback,
 * link-local, shared, documentation, benchmarking, tunnelling or reserved
 * ranges. Pure functions on the address bytes, so every range edge is testable
 * without a network.
 */
internal object PublicAddress {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        return isPublic(address.address)
    }

    /** [bytes] is a 4-byte IPv4 or a 16-byte IPv6 address. Anything else is refused. */
    fun isPublic(bytes: ByteArray): Boolean {
        val b = IntArray(bytes.size) { bytes[it].toInt() and 255 }
        return when (b.size) {
            4 -> publicV4(b[0], b[1], b[2])
            16 -> publicV6(b)
            else -> false
        }
    }

    private fun publicV4(a: Int, b: Int, c: Int): Boolean = !(
        a == 0 ||                                  // 0.0.0.0/8 this network
        a == 10 ||                                 // private
        a == 100 && b in 64..127 ||                // 100.64.0.0/10 shared address space
        a == 127 ||                                // loopback
        a == 169 && b == 254 ||                    // link-local
        a == 172 && b in 16..31 ||                 // private
        a == 192 && b == 0 && c == 0 ||            // 192.0.0.0/24 IETF protocol assignments
        a == 192 && b == 0 && c == 2 ||            // 192.0.2.0/24 documentation (TEST-NET-1)
        a == 192 && b == 88 && c == 99 ||          // 192.88.99.0/24 6to4 relay anycast
        a == 192 && b == 168 ||                    // private
        a == 198 && b in 18..19 ||                 // 198.18.0.0/15 benchmarking
        a == 198 && b == 51 && c == 100 ||         // 198.51.100.0/24 documentation (TEST-NET-2)
        a == 203 && b == 0 && c == 113 ||          // 203.0.113.0/24 documentation (TEST-NET-3)
        a >= 224                                   // multicast, reserved and broadcast
    )

    private fun publicV6(b: IntArray): Boolean {
        val zeroTo = { end: Int -> (0 until end).all { b[it] == 0 } }
        // ::ffff:a.b.c.d reaches the IPv4 address inside it.
        if (zeroTo(10) && b[10] == 255 && b[11] == 255) return publicV4(b[12], b[13], b[14])
        // 64:ff9b::/96 (NAT64): the address it reaches is the IPv4 one in the last four bytes.
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && (4 until 12).all { b[it] == 0 }) {
            return publicV4(b[12], b[13], b[14])
        }
        // Only global unicast, 2000::/3.
        if ((b[0] and 0xe0) != 0x20) return false
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) return false   // 2001::/32 Teredo
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return false   // 2001:db8::/32 documentation
        if (b[0] == 0x20 && b[1] == 0x02) return false                                    // 2002::/16 6to4
        if (b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0) return false           // 3fff::/20 documentation
        return true
    }

    /** [Dns] message deliberately holds no hostname: a name is not worth logging. */
    internal const val REFUSED = "The name does not resolve to a public address."
}

/**
 * Resolves with [inner], then refuses the whole answer unless every address
 * passes [allowed]. OkHttp connects only to what this returns, so a name
 * cannot be checked as public and then connected to as something else.
 */
internal class PublicOnlyDns(
    private val inner: Dns = Dns.SYSTEM,
    private val allowed: (InetAddress) -> Boolean = PublicAddress::isPublic,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = inner.lookup(hostname)
        if (addresses.isEmpty() || !addresses.all(allowed)) throw UnknownHostException(PublicAddress.REFUSED)
        return addresses
    }
}
