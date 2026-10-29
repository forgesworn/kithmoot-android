package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.lab.MetadataHttp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.forgesworn.kithmoot.protocol.DisplayName
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

data class PublicProfile(val name: String?, val picture: String?, val createdAt: Long, val eventId: String, val nip05: String? = null)

fun decodePublicProfile(event: NostrEvent, authors: Set<String>, now: Long): PublicProfile? = runCatching {
    require(event.kind == 0 && event.pubkey in authors && event.content.length <= 16_384 && event.createdAt <= now + 300 && Events.verify(event))
    val data = Json.parseToJsonElement(event.content).jsonObject
    fun string(key: String) = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val name = DisplayName.sanitise(string("display_name")) ?: DisplayName.sanitise(string("name"))
    val picture = string("picture")?.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
    val nip05 = string("nip05")?.takeIf { it.length <= 254 && Regex("[A-Za-z0-9_.+-]{1,64}@[A-Za-z0-9.-]{1,189}").matches(it) }
    PublicProfile(name, picture, event.createdAt, event.id, nip05)
}.getOrNull()

private val pictureCache = object : android.util.LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}
private val pictureGeneration = java.util.concurrent.atomic.AtomicInteger()
fun forgetProfilePictures() { pictureGeneration.incrementAndGet(); pictures.dispatcher.cancelAll(); pictureCache.evictAll() }

private val pictures = MetadataHttp.builder().callTimeout(10, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()

/** Memory only, bounded bytes and decoded dimensions; no cookies or disk cache. */
private suspend fun loadPicture(url: String, policy: dev.forgesworn.kithmoot.account.AccountNetworkPolicy,
    token: dev.forgesworn.kithmoot.account.AccountNetworkPolicy.Snapshot): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!policy.current(token)) return@withContext null
    val generation = pictureGeneration.get()
    pictureCache.get(url)?.let { return@withContext it }
    runCatching {
        policy.resource(token, { pictures.newCall(Request.Builder().url(url).build()) }, { it.cancel() }).use { resource ->
        resource.value.execute().use { response ->
            require(response.isSuccessful)
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 1_048_576)
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1_048_576) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 1_048_576)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
            val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()?.also { if (pictureGeneration.get() == generation) pictureCache.put(url, it) }
        }
        }
    }.getOrNull()
}

@Composable
fun ProfileAvatar(participant: String, name: String?, profile: PublicProfile?, modifier: Modifier = Modifier) {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.forgesworn.kithmoot.KithMootApplication
    val policy = application.accountNetwork
    val network by policy.state.collectAsState()
    val url = profile?.picture.takeIf { policy.current(network) }
    val bitmap by key(url, network) { produceState<ImageBitmap?>(null) { if (url != null) value = loadPicture(url, policy, network) } }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(personInitial(name ?: profile?.name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** The letter on somebody's picture: their name's first letter or digit, or
 *  S for Somebody when they have none. Never a character of their key (C7). */
internal fun personInitial(name: String?): String =
    name?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: NAMELESS.take(1)
