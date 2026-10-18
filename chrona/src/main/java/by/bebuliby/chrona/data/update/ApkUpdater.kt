package by.bebuliby.chrona.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.core.content.FileProvider
import by.bebuliby.chrona.domain.ChronaEffect
import by.bebuliby.chrona.domain.ServerBaseUrl
import by.bebuliby.chrona.domain.UpdateUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Public managed update channel. Never uses SessionClient, credentials or API redirects. */
class ApkUpdater(context: Context, managedBaseUrl: String) {
    private val app = context.applicationContext
    private val base = ServerBaseUrl.normalize(managedBaseUrl)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(UpdateUiState())
    val state = mutable.asStateFlow()
    private val cache = AtomicFile(File(app.filesDir, "verified-update.json"))
    private val directory = File(app.cacheDir, "updates")
    private var known: Release? = null
    private var loaded = false
    @Volatile private var waitingForPermission = false

    private data class Release(val code: Long, val name: String, val path: String, val hash: String,
        val size: Long, val packageName: String, val minSdk: Int, val certificate: String, val json: String)

    suspend fun check() = withContext(Dispatchers.IO) {
        lock.withLock {
            loadKnown()
            mutable.update { it.copy(checking = true, message = null) }
            try {
                val bytes = request("latest.json") { connection ->
                    val deadline = System.nanoTime() + 30_000_000_000L
                    val length = connection.contentLengthLong
                    require(length <= MAX_MANIFEST)
                    connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            require(System.nanoTime() - deadline < 0)
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= MAX_MANIFEST)
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                }
                val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val release = parse(text)
                // An older/stale online response must not unlock a previously verified newer gate.
                if (release.code > installedCode() && release.code >= (known?.code ?: 0)) {
                    known = release
                    saveKnown(release)
                }
                publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                publish("Не удалось проверить обновления. " +
                    if (known != null) "Известное обязательное обновление всё ещё требуется." else "Локальные данные доступны.")
            } finally { mutable.update { it.copy(checking = false) } }
        }
    }

    suspend fun install(): ChronaEffect? = withContext(Dispatchers.IO) {
        if (mutable.value.downloading) return@withContext null
        lock.withLock {
            loadKnown()
            val release = known ?: return@withLock null
            mutable.update { it.copy(downloading = true, progressPercent = null, message = null) }
            try {
                require(directory.isDirectory || directory.mkdirs())
                val apk = File(directory, "Chrona-${release.code}.apk")
                if (!validFile(apk, release)) download(apk, release)
                // Always re-read/re-verify, including after a permission-screen round trip.
                verifyArchive(apk, release)
                if (!app.packageManager.canRequestPackageInstalls()) {
                    waitingForPermission = true
                    mutable.update { it.copy(installerPermissionRequired = true,
                        message = "Разрешите установку обновлений из Chrona в настройках Android.") }
                    ChronaEffect.OpenUpdateInstallPermission
                } else {
                    waitingForPermission = false
                    mutable.update { it.copy(installerPermissionRequired = false) }
                    ChronaEffect.OpenUpdateInstaller(FileProvider.getUriForFile(app,
                        "${app.packageName}.updates", apk).toString())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { it.copy(message = "Не удалось безопасно подготовить обновление. Повторите попытку; данные сохранены.") }
                null
            } finally { mutable.update { it.copy(downloading = false) } }
        }
    }

    suspend fun permissionReturned(): ChronaEffect? {
        if (!waitingForPermission) return null
        if (!app.packageManager.canRequestPackageInstalls()) {
            mutable.update { it.copy(installerPermissionRequired = true,
                message = "Установка не разрешена. Можно открыть настройки ещё раз кнопкой обновления.") }
            return null
        }
        return install()
    }

    private suspend fun download(apk: File, release: Release) {
        val temporary = File(directory, "download.part")
        try {
            request(release.path) { connection ->
                val deadline = System.nanoTime() + 600_000_000_000L
                val length = connection.contentLengthLong
                require(length == -1L || length == release.size)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            require(System.nanoTime() - deadline < 0)
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= release.size && total <= MAX_APK)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            mutable.update { it.copy(progressPercent = (total * 100 / release.size).toInt()) }
                        }
                        output.fd.sync()
                    }
                }
                require(total == release.size && hex(digest.digest()) == release.hash)
            }
            verifyArchive(temporary, release)
            require(temporary.renameTo(apk)) // same directory/filesystem: no partially published APK
        } finally { temporary.delete() }
    }

    private suspend fun validFile(file: File, release: Release): Boolean {
        if (!file.isFile || file.length() != release.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest()) == release.hash
    }

    private suspend fun verifyArchive(file: File, release: Release) {
        require(validFile(file, release))
        @Suppress("DEPRECATION")
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
            ?: throw IOException("Invalid APK")
        require(archive.packageName == app.packageName && archive.packageName == release.packageName)
        require(code(archive) == release.code && archive.versionName == release.name && release.code > installedCode())
        require(archive.applicationInfo?.minSdkVersion == release.minSdk && release.minSdk <= Build.VERSION.SDK_INT)
        val installed = installed()
        val expected = certificates(installed)
        require(expected.isNotEmpty() && certificates(archive) == expected && expected == setOf(release.certificate))
    }

    private fun parse(text: String): Release {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_MANIFEST)
        val json = JSONObject(text)
        require(number(json, "schemaVersion") == 1L && json.get("mandatory") == true)
        val code = number(json, "versionCode")
        require(code in 1..Int.MAX_VALUE.toLong())
        val name = json.get("versionName") as? String ?: throw IOException("Invalid name")
        require(name.isNotBlank() && name.length <= 128 && name.none { it.code < 32 })
        val path = json.get("apkPath") as? String ?: throw IOException("Invalid path")
        require(path == "releases/Chrona-$code.apk") // no redirects/absolute paths/percent encoding/traversal
        val hash = json.get("sha256") as? String ?: throw IOException("Invalid digest")
        val cert = json.get("signingCertificateSha256") as? String ?: throw IOException("Invalid certificate")
        require(Regex("[a-f0-9]{64}").matches(hash) && Regex("[a-f0-9]{64}").matches(cert))
        val size = number(json, "sizeBytes")
        require(size in 1..MAX_APK)
        val packageName = json.get("packageName") as? String ?: throw IOException("Invalid package")
        require(packageName == app.packageName)
        val sdk = number(json, "minSdk")
        require(sdk in 1..Build.VERSION.SDK_INT.toLong())
        // Trust is the running package's public signing identity, not a remote assertion alone.
        require(certificates(installed()) == setOf(cert))
        return Release(code, name, path, hash, size, packageName, sdk.toInt(), cert, text)
    }

    private fun number(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }

    private suspend fun <T> request(path: String, read: suspend (HttpsURLConnection) -> T): T {
        require(path == "latest.json" || Regex("releases/Chrona-[1-9][0-9]*\\.apk").matches(path))
        val origin = base ?: throw IOException("Managed channel unavailable")
        val connection = URL("$origin/updates/chrona/$path").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode == 200)
            require(connection.contentEncoding == null || connection.contentEncoding.equals("identity", true))
            return read(connection)
        } finally { connection.disconnect() }
    }

    private fun loadKnown() {
        if (loaded) return
        loaded = true
        try {
            val json = cache.openRead().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MAX_MANIFEST + 4096)
                    out.write(buffer, 0, count)
                }
                JSONObject(String(out.toByteArray(), StandardCharsets.UTF_8))
            }
            require(base != null && json.getString("base") == base)
            val release = parse(json.getJSONObject("manifest").toString())
            known = release.takeIf { it.code > installedCode() }
        } catch (_: Exception) { /* No verified newer cached release: do not manufacture a gate. */ }
        publish()
    }

    private fun saveKnown(release: Release) {
        val bytes = JSONObject().put("base", base).put("manifest", JSONObject(release.json)).toString().toByteArray(StandardCharsets.UTF_8)
        val stream = cache.startWrite()
        try { stream.write(bytes); cache.finishWrite(stream) }
        catch (error: Exception) { cache.failWrite(stream); throw error }
    }

    private fun publish(message: String? = null) {
        if (known?.code?.let { it <= installedCode() } == true) known = null
        mutable.update { it.copy(required = known != null, versionName = known?.name.orEmpty(), message = message) }
    }

    @Suppress("DEPRECATION")
    private fun installed() = app.packageManager.getPackageInfo(app.packageName, signatureFlags())
    private fun installedCode() = code(installed())
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val MAX_MANIFEST = 32 * 1024
        private const val MAX_APK = 256L * 1024 * 1024
    }
}
