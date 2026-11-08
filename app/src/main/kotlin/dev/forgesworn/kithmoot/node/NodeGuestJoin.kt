package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.session.RoomIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.net.URI

/** A link permits only one bounded encrypted ask. The waiting socket has no
 * room grant; a validated root answer is persisted before room access begins.
 * Decline is silent and never deletes data or chooses another relay. */
internal suspend fun joinNodeAsGuest(record: NodeRoomRecord, identity: RoomIdentity, store: NodeStore, sockets: RelaySocketFactory,
    allowed: () -> Boolean, now: () -> Long = { System.currentTimeMillis() / 1000 }, name: String? = null,
    onWaiting: (Long) -> Unit = {}): NodeCustody = coroutineScope {
    check(allowed())
    val node = requireNotNull(NodeRooms.read(record.wire, now(), record.authority))
    val ask = NodeAdmissions.request(node, identity.deviceSecretKey, identity.credential.toJson(), now(), name)
    val transport = NodeRoomTransport.create(node, LocalSigner(identity.deviceSecretKey), NodeDoor.ASK, sockets, this, now, allowed)
    val admitted = CompletableDeferred<NodeCustody>()
    var receiving: Job? = null
    val permissionWatch = launch { while (isActive) { if (!allowed()) throw NodeRequestException(403); delay(1000) } }
    try {
        transport.start()
        withTimeout(8000) { transport.connected.first { node.relay in it } }
        receiving = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.subscribe(listOf(Filter(kinds = listOf(1059), tags = mapOf("#p" to listOf(identity.devicePubkey))))).collect { event ->
                if (admitted.isCompleted || !allowed()) return@collect
                val answer = NodeAdmissions.readAnswer(node, identity.deviceSecretKey, event.toJson(), ask.requestId, ask.expiresAt, now())
                if (answer !is NodeRoomAdmission) return@collect
                try {
                    check(allowed())
                    fun update(current: NodeCustody?): NodeCustody {
                        require(current == null || current.node.event.id == node.event.id && current.device == identity.devicePubkey && current.pendingClose == null)
                        val receiver = GrantReceiver.restore(node.authority, node.room, identity.devicePubkey, current?.receiver)
                        receiver.offer(answer.grant)
                        require(receiver.grantsFor(URI(node.origin).host, now()).any { it.event.id == NodeEvidence.string(answer.grant, "id") })
                        val values = buildJsonObject {
                            current?.wire?.forEach { (key, value) -> put(key, value) }
                            put("v", 1); put("node", node.wire); put("participant", identity.participant); put("deviceKey", identity.deviceSecretKey.toHex())
                            put("credential", identity.credential.toJson()); put("secret", answer.secret.toHex()); put("phase", "admitted")
                            put("grant", answer.grant); put("epochHint", answer.epochHint); put("receiver", receiver.snapshot())
                        }
                        return NodeCustody.read(values)
                    }
                    val saved = store.admit(identity.participant, node.room, ::update)
                    check(allowed()); admitted.complete(saved)
                } finally { answer.secret.fill(0) }
            }
        }
        check(allowed())
        if (!transport.publishConfirmed(ask.event, 6000)) throw NodeRequestException(403)
        onWaiting(ask.expiresAt)
        withTimeout(maxOf(1, minOf(NODE_ASK_SECONDS, ask.expiresAt - now())) * 1000) { admitted.await() }
    } finally { transport.close(); receiving?.cancel(); permissionWatch.cancel() }
}
