package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.relay.RoomRoute
import java.util.Collections

/** Public observation only. It confers no signing or command authority. */
data class NativeHostingBinding(
    val room: String, val authority: String, val participant: String,
    val device: String, val route: RoomRoute, val relays: List<String>, val pin: String,
)

enum class NativeHostingStatus { STARTING, READY, RECOVERING, SUSPENDED, FAILED, CLOSED }
enum class NativeHostingLifecycle { ACTIVE, RETIRED, CLOSED }

/** Last observed source metadata, with current foreground ownership separate.
 * Contains no signer, invitation bearer, epoch secret or signed-event body. */
data class NativeHostingState(
    val binding: NativeHostingBinding,
    val status: NativeHostingStatus,
    val lifecycle: NativeHostingLifecycle? = null,
    val epoch: Int? = null,
    val revision: Long? = null,
    /** Non-secret controller observation identity; never persisted authority. */
    val ownerGeneration: Long? = null,
    val approved: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val pendingOriginals: List<String> = emptyList(),
) {
    val canRetry: Boolean get() = status == NativeHostingStatus.RECOVERING && pendingOriginals.isNotEmpty()
    val canChangeMembers: Boolean get() = status == NativeHostingStatus.READY &&
        lifecycle in setOf(NativeHostingLifecycle.ACTIVE, NativeHostingLifecycle.RETIRED) &&
        epoch != null && revision != null && ownerGeneration != null && pendingOriginals.isEmpty()
    fun paused() = copy(status = if (status == NativeHostingStatus.FAILED) status else NativeHostingStatus.SUSPENDED)

    companion object {
        internal fun starting(binding: NativeKeeperBinding, ownerGeneration: Long? = null) = NativeHostingState(
            NativeHostingBinding(binding.room, binding.authority, binding.participant, binding.device,
                binding.route, frozen(binding.relays), binding.pin), NativeHostingStatus.STARTING,
            ownerGeneration = ownerGeneration,
        )
        internal fun frozen(values: List<String>): List<String> = Collections.unmodifiableList(ArrayList(values))
    }
}
