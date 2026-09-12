package dev.forgesworn.kithmoot.g5

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.Nip55
import dev.forgesworn.kithmoot.account.checkedSignedEvent
import dev.forgesworn.kithmoot.account.nip55PayloadUri
import dev.forgesworn.kithmoot.account.unsignedEventJson
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FixtureSignerActivityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun answers_nip55_public_key_and_circle_grant_signing_requests() {
        val key = FixtureSignerActivity.answer(context, Intent().putExtra("type", Nip55.TYPE_GET_PUBLIC_KEY))
        val pubkey = requireNotNull(key.getStringExtra("result"))
        assertFalse(key.getBooleanExtra("rejected", false))

        val tags = listOf(listOf("server", "ws://${"a".repeat(52)}/events"), listOf("room", "b".repeat(64)))
        val unsigned = unsignedEventJson(pubkey, 24242, 1_800_000_000, tags, "")
        val signed = FixtureSignerActivity.answer(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$unsigned")).putExtra("type", Nip55.TYPE_SIGN_EVENT),
        )
        val event = NostrEvent.fromJson(Json.parseToJsonElement(requireNotNull(signed.getStringExtra("event"))).jsonObject)

        assertEquals(pubkey, checkedSignedEvent(event, pubkey, 24242, 1_800_000_000, tags, "").pubkey)
    }

    @Test fun signer_payload_uri_preserves_invitation_fragments() {
        val invitation = "https://moot.example/j/#capability?relay=wss://relay.example"

        val uri = nip55PayloadUri(invitation)

        assertEquals(invitation, uri.schemeSpecificPart)
        assertEquals(null, uri.fragment)
    }
}
