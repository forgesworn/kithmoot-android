package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.BuildConfig
import dev.forgesworn.kithmoot.account.EngineSnapshotFormat
import java.util.Base64

/** APK selection authorizes a wire profile; the person's persisted VMLS switch still starts the runtime. */
class InvitationAccessPolicy internal constructor(
    private val debug: Boolean,
    private val profile: String,
    accountNetworkDefault: String,
    private val legacyDevelopment: Boolean,
    private val localAdminDevelopment: Boolean,
) {
    init {
        require(profile in setOf("disabled", "admin2")) { "Unknown invitation product profile." }
        require(accountNetworkDefault in setOf("nostr", "private-invitations"))
        require(profile != "admin2" || (accountNetworkDefault == "private-invitations" && !legacyDevelopment && !localAdminDevelopment)) {
            "Admin2 product selection requires private invitations and excludes development switches."
        }
        require(!localAdminDevelopment || legacyDevelopment) { "Local Admin2 development requires legacy development opt-in." }
    }

    fun requireLegacy() = InvitationDevelopmentPolicy.requireEnabled(debug, legacyDevelopment)

    fun requireAdmin2() {
        check(profile == "admin2" || (debug && legacyDevelopment && localAdminDevelopment)) {
            "Admin2 requires the selected product profile or an explicitly enabled local development build."
        }
    }

    /** Shared private pairing and keeper registration carry no legacy group authorization. */
    fun requirePrivateCeremony() {
        check(profile == "admin2" || (debug && legacyDevelopment)) { "Private invitation operations are disabled in this build." }
    }

    /** Checked before opening native persisted state: no reinterpretation or migration between profiles. */
    internal fun requireRestore(format: EngineSnapshotFormat) = when (format) {
        EngineSnapshotFormat.Legacy -> Unit
        EngineSnapshotFormat.Invitation1 -> requireLegacy()
        EngineSnapshotFormat.Admin2 -> requireAdmin2()
    }

    companion object {
        internal fun current() = InvitationAccessPolicy(BuildConfig.DEBUG, BuildConfig.INVITATION_PROFILE,
            BuildConfig.ACCOUNT_NETWORK_DEFAULT, BuildConfig.EXPERIMENTAL_INVITATION_MLS, BuildConfig.EXPERIMENTAL_ADMIN_INVITATION_MLS)
    }
}

/** Bounded, canonical byte parsing has no authorization side effects. Public callers must select their policy first. */
internal object PrivateInvitationEncoding {
    fun decode(value: String): ByteArray {
        require(value.length in 1..2_000_000)
        val text = value.trim()
        return Base64.getDecoder().decode(text).also {
            require(it.isNotEmpty() && Base64.getEncoder().encodeToString(it) == text)
        }
    }
    fun fixed(value: String, size: Int): ByteArray {
        val text = value.trim()
        require(text.length == ((size + 2) / 3) * 4)
        return decode(text).also { require(it.size == size) }
    }
}
