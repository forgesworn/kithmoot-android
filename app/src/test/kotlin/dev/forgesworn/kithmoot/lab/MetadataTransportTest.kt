package dev.forgesworn.kithmoot.lab

import dev.forgesworn.kithmoot.BuildConfig
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelaySocketListener
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real local HTTP/WS and bounded fault controls; NOT app-owned qualification controls. */
class MetadataTransportTest {
    private val enabled = BuildConfig.DEBUG && BuildConfig.LAB_METADATA_OBSERVER
    private val stage = "transport-unit"
    private val window = "1".repeat(64)
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/lab/metadata-vectors.json")).use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject.getValue("manifestInput").jsonObject
    private fun spec(duration: Long = 60_000) = MetadataObserver.Spec(window,stage,
        fixture.getValue("terms").jsonObject.mapValues { it.value.jsonPrimitive.content },ByteArray(32) { it.toByte() },"bob",duration,
        listOf("control"),"wss://signer.invalid")
    private fun arm(duration: Long = 60_000) { if (enabled) MetadataObserver.arm(spec(duration)) }
    private fun end(): JsonObject? = if (enabled) MetadataObserver.disarm(stage,window) else null
    private fun records(snapshot: JsonObject) = snapshot.getValue("records").jsonArray.map { it.jsonObject }
    private fun defaults(client: OkHttpClient) { if (!enabled) {
        assertTrue(client.interceptors.isEmpty())
        assertThrows(IllegalStateException::class.java) { MetadataObserver.arm(spec()) }
    } }

    @Test fun oneShotUsesRealWriteOnceAndReturnsExactResponseObject() {
        val server = MockWebServer();server.enqueue(MockResponse().setBody("unaltered response"));server.start()
        val calls = AtomicInteger();val original = AtomicReference<Response>()
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { calls.incrementAndGet(); for (b in "hello🌱Alice".toByteArray()) sink.writeByte(b.toInt()) }
        }
        val client = MetadataHttp.builder().addInterceptor { chain -> chain.proceed(chain.request()).also(original::set) }.build()
        if (!enabled) assertEquals(1,client.interceptors.size)
        arm()
        try {
            client.newCall(Request.Builder().url(server.url("/actual")).post(body).build()).execute().use {
                assertSame(original.get(),it);assertEquals("unaltered response",it.body!!.string())
            }
            assertEquals(1,calls.get());assertEquals("hello🌱Alice",server.takeRequest(2,TimeUnit.SECONDS)!!.body.readUtf8())
        } finally { val snap=end();if(snap!=null) {
            val bodies=records(snap).filter { it["valueKind"]?.jsonPrimitive?.content == "request-body-write" }
            assertEquals(1,bodies.size);assertEquals(1,bodies[0].getValue("matches").jsonObject.getValue("messageAlice").jsonPrimitive.int)
            assertTrue(bodies[0].getValue("bodyComplete").jsonPrimitive.boolean)
            assertTrue(records(snap).none { it.getValue("direction").jsonPrimitive.content=="in" && it.getValue("transport").jsonPrimitive.content=="http-value" })
            assertEquals(0,snap.getValue("activeCallbacks").jsonPrimitive.int)
        };server.shutdown() }
    }
    @Test fun refusedConnectCapturesAttemptButNeverSerializesBodyOrChangesThrowable() {
        val port=ServerSocket(0).use { it.localPort }
        val calls=AtomicInteger();val body=object:RequestBody(){override fun contentType()=null;override fun writeTo(sink:BufferedSink){calls.incrementAndGet();sink.writeUtf8("never serialized")}}
        val client=MetadataHttp.builder().retryOnConnectionFailure(false).connectTimeout(300,TimeUnit.MILLISECONDS).build();defaults(client)
        arm()
        try { assertThrows(IOException::class.java) { client.newCall(Request.Builder().url("http://127.0.0.1:$port/refused").header("X-Lab","attempt-only").post(body).build()).execute() };assertEquals(0,calls.get()) }
        finally { val snap=end();if(snap!=null) {
            assertTrue(records(snap).any { it["valueKind"]?.jsonPrimitive?.content=="request-url" })
            assertTrue(records(snap).none { it["valueKind"]?.jsonPrimitive?.content=="request-body-write" })
            assertEquals(0,snap.getValue("errors").jsonPrimitive.int);assertTrue(snap.getValue("coverageGaps").jsonPrimitive.int>0)
        } }
        val failure=IOException("synthetic transport failure")
        val exact=MetadataHttp.builder().addInterceptor { throw failure }.build()
        arm();try { assertSame(failure,assertThrows(IOException::class.java){exact.newCall(Request.Builder().url("http://127.0.0.1:1/unchanged").build()).execute()}) }finally{end()}
    }
    @Test fun duplexRemainsOriginalAndExplicitlyUnsupportedNotPretendedCapture() {
        val calls=AtomicInteger();val body=object:RequestBody(){override fun contentType()=null;override fun isDuplex()=true;override fun writeTo(sink:BufferedSink){calls.incrementAndGet()}}
        val responseBody=ResponseBody.create(null,"untouched")
        val client=MetadataHttp.builder().addInterceptor { chain ->
            assertSame(body,chain.request().body)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(responseBody).build()
        }.build()
        arm();try { client.newCall(Request.Builder().url("http://127.0.0.1:1/duplex-unit").post(body).build()).execute().use { assertSame(responseBody,it.body) };assertEquals(0,calls.get()) }
        finally { val snap=end();if(snap!=null) assertTrue(snap.getValue("coverageGaps").jsonPrimitive.int>0) }
    }
    @Test fun overflowingObserverDoesNotDropReplayOrCorruptRealHttpBytes() {
        val server=MockWebServer();server.enqueue(MockResponse().setBody("ok"));server.start()
        val total=8_000_001;val calls=AtomicInteger()
        val body=object:RequestBody(){override fun contentType()=null;override fun isOneShot()=true
            override fun contentLength()=total.toLong()
            override fun writeTo(sink:BufferedSink){calls.incrementAndGet();val chunk=ByteArray(8192){65};var left=total
                while(left>0){val n=minOf(left,chunk.size);sink.write(chunk,0,n);left-=n}}
        }
        val client=MetadataHttp.client();arm()
        try { client.newCall(Request.Builder().url(server.url("/bounded-overflow")).post(body).build()).execute().use { assertEquals("ok",it.body!!.string()) }
            val actual=server.takeRequest(2,TimeUnit.SECONDS)!!;assertEquals(total.toLong(),actual.bodySize);assertEquals(1,calls.get())
        }finally{val snap=end();if(snap!=null){assertTrue(snap.getValue("overflow").jsonPrimitive.boolean);assertTrue(snap.getValue("errors").jsonPrimitive.int>0)};server.shutdown()}
    }
    @Test fun realProductionSocketAdapterObservesFramesWithoutInventingPositiveControls() {
        val server=MockWebServer();val hostClose=CountDownLatch(1);val hostClosed=CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onMessage(webSocket:WebSocket,text:String){if(text.startsWith("[\"REQ\""))webSocket.send("[\"EOSE\",\"unit\"]");if(text.startsWith("[\"CLOSE\""))hostClose.countDown()}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){hostClosed.countDown()}
        }));server.start()
        val opened=CountDownLatch(1);val received=CountDownLatch(1)
        arm();var socket:dev.forgesworn.kithmoot.relay.RelaySocket?=null
        try {
            socket=OkHttpRelaySockets().open(server.url("/socket").toString().replaceFirst("http:","ws:"),object:RelaySocketListener{
                override fun onOpen(){opened.countDown()};override fun onMessage(text:String){if(text=="[\"EOSE\",\"unit\"]")received.countDown()};override fun onClosed(reason:String){}
            })
            assertTrue(opened.await(3,TimeUnit.SECONDS));socket.send("[\"REQ\",\"unit\",{\"kinds\":[1]}]")
            assertTrue(received.await(3,TimeUnit.SECONDS));socket.send("[\"CLOSE\",\"unit\"]");assertTrue(hostClose.await(3,TimeUnit.SECONDS))
        }finally{socket?.close();val snap=end();if(snap!=null){assertEquals(3,records(snap).count { it["transport"]?.jsonPrimitive?.content=="websocket" });assertTrue(snap.getValue("positiveControls").jsonObject.isEmpty());assertFalse(snap.getValue("absenceClaim").jsonPrimitive.boolean)};try{assertTrue("mock peer WebSocket closed",hostClosed.await(3,TimeUnit.SECONDS))}finally{server.shutdown()}}
    }
    @Test fun fileTransferClonesKeepObserverThroughCancellationListenerAndSinglePassStreaming() {
        val root = java.io.File("build/metadata-file-clone-control").apply { check(isDirectory || mkdirs()) }
        val directory = java.nio.file.Files.createTempDirectory(root.toPath(), "case-").toFile()
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val writes = AtomicInteger()
        val client = dev.forgesworn.kithmoot.session.fileTransferHttp.newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .eventListener(object : EventListener() {
                override fun requestBodyEnd(call: Call, byteCount: Long) { writes.incrementAndGet() }
            }).build()
        if (enabled) assertEquals(1, client.interceptors.size) else defaults(client)
        val source = java.io.File(directory, "source.bin").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        val sealed = dev.forgesworn.kithmoot.session.sealPickedFile({ source.inputStream() }, source.length(), directory, source.name, "application/octet-stream")
        val origin = server.url("/").toString().removeSuffix("/")
        val url = "$origin/${sealed.hash}"
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"url":"$url","sha256":"${sealed.hash}","size":${sealed.file.length()}}"""))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(sealed.file.readBytes())))
        arm()
        try {
            val auth = dev.forgesworn.kithmoot.session.mediaAuthorisation("upload", sealed.hash, origin,
                ByteArray(32).also { it[31] = 1 }, 1000, 1060)
            val attachment = dev.forgesworn.kithmoot.session.uploadFileMedia(sealed, origin, auth, client)
            val upload = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PUT", upload.method); assertArrayEquals(sealed.file.readBytes(), upload.body.readByteArray())
            assertEquals(1, writes.get())
            // The actual product request adds its own dispatcher and cancellation EventListener.
            dev.forgesworn.kithmoot.session.FileAttachmentRequest(attachment, java.io.File(directory, "opened"), client).use { request ->
                val opened = request.download()
                assertArrayEquals(source.readBytes(), opened.file.readBytes())
                assertSame(opened, request.download())
            }
            assertEquals("GET", requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).method)
            assertEquals(2, server.requestCount); assertEquals(1, writes.get())
        } finally {
            val snapshot = end()
            try { if (snapshot != null) {
                val captured = records(snapshot)
                assertEquals(2, captured.count { it["valueKind"]?.jsonPrimitive?.content == "request-url" })
                val bodies = captured.filter { it["valueKind"]?.jsonPrimitive?.content == "request-body-write" }
                assertEquals(1, bodies.size)
                assertEquals(sealed.file.length(), bodies.single().getValue("capturedValueBytes").jsonPrimitive.long)
                assertTrue(bodies.single().getValue("bodyComplete").jsonPrimitive.boolean)
                assertEquals(0, snapshot.getValue("errors").jsonPrimitive.int)
                assertEquals(0, snapshot.getValue("activeCallbacks").jsonPrimitive.int)
            } } finally { server.shutdown(); directory.deleteRecursively() }
        }
    }

    @Test fun callbackCloseFenceAndDeadlineCannotBleedIntoRearmedWindow() {
        if(!enabled){assertThrows(IllegalStateException::class.java){MetadataObserver.arm(spec())};return}
        arm();val lease=requireNotNull(MetadataObserver.lease());val entered=CountDownLatch(1);val release=CountDownLatch(1);val closed=CountDownLatch(1)
        val work=Thread{lease.safe{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS))}}
        work.start();assertTrue(entered.await(2,TimeUnit.SECONDS));val closer=Thread{lease.close();closed.countDown()};closer.start()
        assertFalse(closed.await(30,TimeUnit.MILLISECONDS));release.countDown();assertTrue(closed.await(2,TimeUnit.SECONDS));work.join();closer.join();end()
        arm();lease.safe{error("closed old lease ran in new window")};assertTrue(records(requireNotNull(end())).isEmpty())
        arm(100);val expiring=requireNotNull(MetadataObserver.lease());Thread.sleep(150);expiring.safe{error("expired lease ran")};expiring.close()
        val expired=requireNotNull(end());assertTrue(expired.getValue("coverageGaps").jsonPrimitive.int>0)
        val attempt=requireNotNull(MetadataObserver.attempt());assertThrows(IllegalStateException::class.java){MetadataObserver.arm(spec())};attempt.close();arm();end()
    }
}
