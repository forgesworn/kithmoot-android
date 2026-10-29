package dev.forgesworn.kithmoot.mixed

/** Exact frozen Cambium previews. Pure: no UI, keys, network or storage. */
internal object ProductApprovalReview {
    const val NOTE = "These details come from the requesting app. They are shown here only and are never written to the activity log.\n\n"
    const val END = "This is a shortened preview. Heartwood validates the signing request."
    const val PERMISSIONS = "sign_event (kind 20460), sign_event (kind 22242), nip44_encrypt, nip44_decrypt"
    private val hex = Regex("[0-9a-f]{64}")
    private fun point(value: String): Boolean {
        if (!hex.matches(value)) return false
        val p = java.math.BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
        val x = java.math.BigInteger(value, 16)
        if (x >= p) return false
        val y2 = x.modPow(java.math.BigInteger.valueOf(3), p).add(java.math.BigInteger.valueOf(7)).mod(p)
        val y = y2.modPow(p.add(java.math.BigInteger.ONE).shiftRight(2), p)
        return y.multiply(y).mod(p) == y2
    }
    fun vaultScope(principal: String, persona: String, device: String, home: String, method: String,
                   expectedRoot: String, expectedDevice: String?, expectedHome: String?): Boolean =
        principal == "dev.forgesworn.kithmoot" && persona == expectedRoot && point(device) && hex.matches(home) &&
            device == expectedDevice && home == expectedHome && method in setOf("signBoxRequestV1/1", "signLeafBindingV1/1")
    data class Credential(val device: String, val createdAt: Long, val expiresAt: Long)
    data class Provision(val target: String, val nonce: String, val expiresAt: Long)
    fun credential(text: String, root: String, now: Long): Credential {
        require(point(root) && text.startsWith(NOTE)) { "Credential preview refused." }
        val lines = text.removePrefix(NOTE).split('\n')
        require(lines.size == 11) { "Credential preview refused." }
        val created = lines[2].removePrefix("Created at (Unix seconds): ").toLongOrNull()
        val device = lines[7].removePrefix("device | ")
        val expires = lines[8].removePrefix("expiration | ").toLongOrNull()
        require(created != null && expires != null && point(device) && device != root &&
            created in (now - 120)..(now + 120) && expires > now && expires - created in 1..2_592_000L) { "Credential freshness refused." }
        val expected = NOTE + "Kind: 20460\nAuthor: $root\nCreated at (Unix seconds): $created\nContent:\n(empty)\nTags: 4\n" +
            "d | $root\ndevice | $device\nexpiration | $expires\nscope | person\n" + END
        require(text == expected) { "Credential exact template refused." }
        return Credential(device, created, expires)
    }

    fun provision(text: String, root: String, target: String, index: Long, now: Long): Provision {
        require(point(root) && point(target) && index == 0L) { "Provision scope refused." }
        val lines = text.split('\n')
        require(lines.size == 7 && lines[0] == "Provision one rendezvous child" && lines[1] == "Identity: $root" &&
            lines[2] == "Target device: $target" && lines[3] == "Index: 0" &&
            lines[6] == "One approval only. Heartwood must also approve this exact request. Only its encrypted envelope is returned.") { "Provision preview refused." }
        val nonce = lines[4].removePrefix("Nonce: ")
        val expires = lines[5].removePrefix("Expires: ").toLongOrNull()
        require(lines[4].startsWith("Nonce: ") && nonce.matches(Regex("[A-Za-z0-9_-]{22}")) &&
            java.util.Base64.getUrlDecoder().decode(nonce).let { it.size == 16 && java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) == nonce } &&
            lines[5].startsWith("Expires: ") && expires != null && expires > now && expires - now <= 600) { "Provision freshness refused." }
        return Provision(target, nonce, expires)
    }
}
