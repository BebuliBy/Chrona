package by.bebuliby.chrona.ui

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.Authenticator
import java.net.InetAddress
import javax.net.ssl.HttpsURLConnection

/** Root wraps ChronaApp with an explicitly reviewed origin policy; no domain contract change. */
val LocalChronaRemotePhotoPolicy = staticCompositionLocalOf { RemotePhotoPolicy() }
internal val LocalRemotePhotoCache = staticCompositionLocalOf<RemotePhotoCache?> { null }

internal sealed interface RemotePhotoResult {
    class Ready(val image: ImageBitmap, val allocationBytes: Long) : RemotePhotoResult
    data object Failed : RemotePhotoResult
}

/**
 * Composition/account/policy-scoped memory only. Serializing the tiny loads also deduplicates a URI
 * shared by the top bar and settings. Bounded entries/decoded bytes, failures cached (no retry loop).
 */
internal class RemotePhotoCache {
    private class Entry(val result: RemotePhotoResult, val expiresAt: Long)
    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private var bytes = 0L
    @Volatile private var closed = false

    suspend fun load(photo: SafeRemotePhoto): RemotePhotoResult = lock.withLock {
        if (closed) return@withLock RemotePhotoResult.Failed
        synchronized(entries) {
            entries[photo.key]?.let { entry ->
                if (System.nanoTime() < entry.expiresAt) entry.result
                else { entries.remove(photo.key); bytes -= entry.result.byteCount(); null }
            }
        }?.let { return@withLock it }
        val result = fetchRemotePhoto(photo)
        currentCoroutineContext().ensureActive()
        synchronized(entries) {
            if (!closed) {
                val ttl = if (result is RemotePhotoResult.Ready) 300_000_000_000L else 30_000_000_000L
                entries[photo.key] = Entry(result, System.nanoTime() + ttl)
                bytes += result.byteCount()
                while (entries.size > 12 || bytes > 8L * 1024 * 1024) {
                    val oldest = entries.entries.iterator()
                    bytes -= oldest.next().value.result.byteCount()
                    oldest.remove()
                }
            }
        }
        result
    }

    fun close() {
        closed = true
        synchronized(entries) { entries.clear(); bytes = 0 }
        // Do not recycle: a Compose frame might still reference a bitmap. Bounded references are released.
    }

    private fun RemotePhotoResult.byteCount(): Long =
        if (this is RemotePhotoResult.Ready) allocationBytes else 0L
}

private const val MAX_PAYLOAD = 2 * 1024 * 1024
private const val MAX_BITMAP_SIDE = 512

/** All blocking work is in IO. Errors never contain URL/exception messages and are never logged. */
private suspend fun fetchRemotePhoto(photo: SafeRemotePhoto): RemotePhotoResult = withContext(Dispatchers.IO) {
    try {
        withTimeout(12_000) {
            // URLConnection can consult a global cookie jar. Fail closed rather than send ambient cookies.
            if (!ambientCredentialsAbsent()) return@withTimeout RemotePhotoResult.Failed
            val addresses = InetAddress.getAllByName(photo.uri.host)
            if (addresses.isEmpty() || addresses.any { !isPublicPhotoAddress(it.address) }) return@withTimeout RemotePhotoResult.Failed
            currentCoroutineContext().ensureActive()
            val connection = photo.uri.toURL().openConnection() as? HttpsURLConnection ?: return@withTimeout RemotePhotoResult.Failed
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "image/jpeg, image/png, image/webp")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Authorization", "")
                connection.setRequestProperty("Cookie", "")
                if (connection.responseCode != 200) return@withTimeout RemotePhotoResult.Failed
                val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (mime !in setOf("image/jpeg", "image/png", "image/webp")) return@withTimeout RemotePhotoResult.Failed
                if (!connection.contentEncoding.isNullOrBlank() && !connection.contentEncoding.equals("identity", true)) return@withTimeout RemotePhotoResult.Failed
                if (connection.contentLengthLong > MAX_PAYLOAD) return@withTimeout RemotePhotoResult.Failed
                val deadline = System.nanoTime() + 8_000_000_000L
                val payload = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(16 * 1024)
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (System.nanoTime() > deadline) return@withTimeout RemotePhotoResult.Failed
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_PAYLOAD) return@withTimeout RemotePhotoResult.Failed
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                currentCoroutineContext().ensureActive()
                decodeRemotePhoto(payload)
            } finally {
                connection.disconnect()
            }
        }
    } catch (_: TimeoutCancellationException) {
        RemotePhotoResult.Failed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RemotePhotoResult.Failed
    }
}

private fun ambientCredentialsAbsent(): Boolean = runCatching {
    // Reflection preserves minSdk compatibility. If an old platform cannot expose its global
    // authenticator state, remote loading fails closed rather than risk ambient credentials.
    CookieHandler.getDefault() == null && Authenticator::class.java.getMethod("getDefault").invoke(null) == null
}.getOrDefault(false)

private fun decodeRemotePhoto(payload: ByteArray): RemotePhotoResult {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
    if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
        bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
        bounds.outWidth.toLong() * bounds.outHeight > 32_000_000) return RemotePhotoResult.Failed
    var sample = 1
    while (bounds.outWidth / sample > MAX_BITMAP_SIDE || bounds.outHeight / sample > MAX_BITMAP_SIDE) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
        ?: return RemotePhotoResult.Failed
    // Decoder behavior/ceil rounding is checked again, not trusted solely from the requested sample.
    if (bitmap.width > MAX_BITMAP_SIDE || bitmap.height > MAX_BITMAP_SIDE) {
        bitmap.recycle()
        return RemotePhotoResult.Failed
    }
    return RemotePhotoResult.Ready(bitmap.asImageBitmap(), bitmap.allocationByteCount.toLong())
}
