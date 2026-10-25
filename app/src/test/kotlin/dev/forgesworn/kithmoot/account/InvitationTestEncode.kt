package dev.forgesworn.kithmoot.account

import java.io.ByteArrayOutputStream

/** Manual synthetic fixture encoder, independent from the production reader. */
internal object InvitationTestEncode {
    fun context(persona: ByteArray, device: ByteArray, rendezvous: ByteArray, expiry: Long): ByteArray = ByteArrayOutputStream().apply {
        head(5, 16); uint(1); uint(1); uint(2); text("Vennel/invitation/1"); uint(3); uint(1)
        val values = listOf(ByteArray(32) { 4 }, persona, device, rendezvous) + (8..14).map { n -> ByteArray(32) { n.toByte() } }
        values.forEachIndexed { i, value -> uint(i + 4); bytes(value) }
        uint(15); head(5, 1); uint(1); uint(1); uint(16); uint(expiry)
    }.toByteArray()

    fun adminContext(persona: ByteArray, device: ByteArray, rendezvous: ByteArray, expiry: Long): ByteArray = ByteArrayOutputStream().apply {
        head(5, 17); uint(1); uint(2); uint(2); text("Vennel/invitation-admins/2"); uint(3); uint(1)
        val values = listOf(ByteArray(32) { 4 }, persona, device, rendezvous) + (8..14).map { n -> ByteArray(32) { n.toByte() } }
        values.forEachIndexed { i, value -> uint(i + 4); bytes(value) }
        uint(15); head(5, 1); uint(1); uint(1); uint(16); uint(expiry)
        uint(17); bytes(ByteArray(32) { 17 })
    }.toByteArray()

    fun body(context: ByteArray, binding: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        head(5, 3); uint(1); uint(1); uint(2); bytes(context); uint(3); bytes(binding)
    }.toByteArray()

    private fun ByteArrayOutputStream.head(major: Int, value: Long) {
        val prefix = major shl 5
        when {
            value < 24 -> write(prefix or value.toInt())
            value < 256 -> { write(prefix or 24); write(value.toInt()) }
            value < 65_536 -> { write(prefix or 25); be(value, 2) }
            value < 4_294_967_296L -> { write(prefix or 26); be(value, 4) }
            else -> { write(prefix or 27); be(value, 8) }
        }
    }
    private fun ByteArrayOutputStream.be(value: Long, width: Int) { for (i in width - 1 downTo 0) write((value ushr (i * 8)).toInt() and 255) }
    private fun ByteArrayOutputStream.uint(value: Int) = head(0, value.toLong())
    private fun ByteArrayOutputStream.uint(value: Long) = head(0, value)
    private fun ByteArrayOutputStream.bytes(value: ByteArray) { head(2, value.size.toLong()); write(value) }
    private fun ByteArrayOutputStream.text(value: String) { val raw = value.toByteArray(); head(3, raw.size.toLong()); write(raw) }
}
