package dev.forgesworn.kithmoot.ui

import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.AssignmentVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android vault + native sockets + signed synthetic room peer. No live room or model. */
class RoomWorkRelayTest {
    @Test fun real_relay_question_answer_result_and_vault_recovery()=runBlocking {
        val room="a1".repeat(32);val key=ByteArray(32){9};val now=System.currentTimeMillis()/1000
        val person=PrimaryIdentity.create(room,now+3600,now,ByteArray(32){1},ByteArray(32){4})
        val agent=PrimaryIdentity.create(room,now+3600,now,ByteArray(32){2},ByteArray(32){5})
        val history=CopyOnWriteArrayList<NostrEvent>();val assignmentWrites=CopyOnWriteArrayList<NostrEvent>();val errors=CopyOnWriteArrayList<String>()
        val server=MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onClosing(socket:WebSocket,code:Int,reason:String){socket.close(code,reason)}
            val subscriptions=linkedMapOf<String,List<JsonObject>>()
            fun matches(event:NostrEvent,filters:List<JsonObject>)=filters.any { f ->
                (f["kinds"]==null||f.getValue("kinds").jsonArray.any{it.jsonPrimitive.int==event.kind})&&
                    (f["#d"]==null||f.getValue("#d").jsonArray.any{it.jsonPrimitive.content==event.tagValue("d")})
            }
            fun publish(socket:WebSocket,event:NostrEvent){
                if(history.none{it.id==event.id})history.add(event)
                subscriptions.toMap().forEach{(id,filters)->if(matches(event,filters))socket.send(buildJsonArray{add("EVENT");add(id);add(event.toJson())}.toString())}
            }
            override fun onMessage(socket:WebSocket,text:String){try {
                val message=Json.parseToJsonElement(text).jsonArray
                when(message[0].jsonPrimitive.content){
                    "REQ"->{val id=message[1].jsonPrimitive.content;val filters=message.drop(2).map{it.jsonObject};subscriptions[id]=filters
                        history.filter{matches(it,filters)}.forEach{socket.send(buildJsonArray{add("EVENT");add(id);add(it.toJson())}.toString())}
                        socket.send(buildJsonArray{add("EOSE");add(id)}.toString())}
                    "CLOSE"->subscriptions.remove(message[1].jsonPrimitive.content)
                    "EVENT"->{val outer=NostrEvent.fromJson(message[1]);publish(socket,outer)
                        socket.send(buildJsonArray{add("OK");add(outer.id);add(true);add("Stored synthetic event")}.toString())
                        val incoming=decodeChatEvent(outer,room,key,now+60,channel=ASSIGNMENT_CHANNEL)?.assignment
                        if(incoming!=null&&incoming.pubkey==person.participant){
                            assignmentWrites.add(incoming);val p=assignmentPayload(incoming,room)!!
                            fun reply(operation:JsonObject,previous:String,request:String):NostrEvent {
                                val inner=runBlocking{signAssignment(agent.signer,room,AssignmentPayload(p.assignment,request,previous,agent.devicePubkey,operation),now)}
                                publish(socket,encodeChatEvent("Assignment update",agent.participant,agent.credential,room,key,agent.deviceSecretKey,now,channel=ASSIGNMENT_CHANNEL,assignment=inner));return inner
                            }
                            if(p.operation["op"]==JsonPrimitive("create")) {
                                val claim=reply(buildJsonObject{put("op","claim");put("executor","relay_worker_0001");put("next","Inspect the build")},incoming.id,"relay_claim_request_01")
                                reply(buildJsonObject{put("op","block");put("executor","relay_worker_0001");put("question","Which build should I review?")},claim.id,"relay_question_request_01")
                            } else if(p.operation["op"]==JsonPrimitive("answer"))reply(buildJsonObject{put("op","result");put("executor","relay_worker_0001");put("summary","Build 41 reviewed");put("evidence","Synthetic release evidence for build 41")},incoming.id,"relay_result_request_01")
                        }
                    }
                }
            }catch(error:Exception){errors.add(error.toString())}}
        }))
        server.start()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=AssignmentVault(context,room,person.participant);vault.reset()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var pool=RelayPool(listOf("ws://127.0.0.1:${server.port}/"),OkHttpRelaySockets(),scope)
        var work=RoomWork(room,key,person,pool,vault,scope)
        suspend fun at(status:String)=withTimeout(30_000){work.journal.state.first{it.assignments.any{a->a.status==status}}.assignments.first{it.status==status}}
        try {
            pool.start();work.open()
            work.journal.submit(null,buildJsonObject{put("op","create");put("objective","Prepare build 41 brief");put("criteria","Explain the verified checks");put("owner",agent.participant);put("action","brief")},"android_relay_create_01",null)
            val blocked=at("blocked");assertEquals("Which build should I review?",blocked.question)
            work.journal.submit(blocked.id,buildJsonObject{put("op","answer");put("text","Build 41")},"android_relay_answer_01",blocked.head)
            val review=at("review");assertEquals("Synthetic release evidence for build 41",review.result!!["evidence"]!!.jsonPrimitive.content)
            work.journal.submit(review.id,buildJsonObject{put("op","accept");put("result",review.result!!["id"]!!)},"android_relay_accept_01",review.head)
            val accepted=at("accepted");assertEquals(3,assignmentWrites.size);assertTrue(errors.toString(),errors.isEmpty())
            work.close();pool.stop()
            // A second connection supplies no relay history. Only the encrypted
            // device vault can restore the complete accepted assignment.
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onClosing(socket:WebSocket,code:Int,reason:String){socket.close(code,reason)}
                override fun onMessage(socket:WebSocket,text:String){val m=Json.parseToJsonElement(text).jsonArray
                if(m[0]==JsonPrimitive("REQ"))socket.send(buildJsonArray{add("EOSE");add(m[1])}.toString())
                if(m[0]==JsonPrimitive("EVENT")){val event=NostrEvent.fromJson(m[1]);decodeChatEvent(event,room,key,now+60,channel=ASSIGNMENT_CHANNEL)?.assignment?.let{assignmentWrites.add(it)};socket.send(buildJsonArray{add("OK");add(event.id);add(true);add("Stored")}.toString())}
            }}))
            pool=RelayPool(listOf("ws://127.0.0.1:${server.port}/"),OkHttpRelaySockets(),scope);work=RoomWork(room,key,person,pool,vault,scope)
            pool.start();work.open();assertEquals(accepted,at("accepted"));assertEquals(0,work.journal.state.value.pendingSends)
            assertFalse(vault.load()!!.contains("Prepare build 41 brief"));assertEquals(3,assignmentWrites.size)
        } finally {work.close();pool.stop();scope.cancel();server.shutdown();vault.reset()}
    }
}
