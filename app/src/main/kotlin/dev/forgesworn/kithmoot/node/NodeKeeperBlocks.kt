package dev.forgesworn.kithmoot.node

import android.content.Context
import dev.forgesworn.kithmoot.account.parseDeviceBlocks
import dev.forgesworn.kithmoot.storage.ContactAccountVault
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a participant is blocked for an account here, as every node keeper
 * on this phone asks before it lists or lets in anybody: a blocked person's
 * asks are never shown and never answered, as the invitation desk treats
 * their requests. While the app has the account's contacts open it registers
 * that same check (blocked on this phone or in Signet); otherwise the
 * account's own saved blocks on this phone are read.
 */
internal object NodeKeeperBlocks {
    private val live = ConcurrentHashMap<String, (String) -> Boolean>()

    fun register(account: String, check: (String) -> Boolean): () -> Unit {
        live[account] = check
        return { live.remove(account, check) }
    }

    /** One keeper's check for [account]. Saved blocks are read at most once a minute; if they
     * cannot be read, nobody is let in without asking. */
    fun predicate(context: Context, account: String): (String) -> Boolean =
        readingSaved(account, { ContactAccountVault.peek(context, account, "blocks") })

    internal fun readingSaved(account: String, saved: () -> String?, clock: () -> Long = System::currentTimeMillis): (String) -> Boolean {
        var stored: Set<String>? = null
        var readAt = Long.MIN_VALUE
        val lock = Any()
        return { participant ->
            val person = participant.lowercase()
            val registered = live[account]
            if (registered != null) registered(person) else synchronized(lock) {
                val at = clock()
                if (stored == null || at - readAt >= 60_000) {
                    stored = runCatching { parseDeviceBlocks(saved(), account).mapTo(HashSet()) { it.participant } }.getOrNull()
                    readAt = at
                }
                stored?.contains(person) ?: true
            }
        }
    }
}
