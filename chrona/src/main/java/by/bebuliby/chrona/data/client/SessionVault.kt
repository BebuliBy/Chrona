package by.bebuliby.chrona.data.client

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ClientSession(val accountId: String, val sessionId: String, val access: String, val refresh: String,
                    val expiresAt: Instant, val rotating: Boolean = false) {
    override fun toString() = "ClientSession(redacted)"
}
class PendingLogin(val requestId: String, val verifier: String, val expiresAt: Instant, val exchanging: Boolean = false) {
    override fun toString() = "PendingLogin(redacted)"
}
class PrivateConnection(val origin: String = "", val session: ClientSession? = null, val pending: PendingLogin? = null) {
    override fun toString() = "PrivateConnection(redacted)"
}
interface SessionVault {
    suspend fun load(): PrivateConnection
    suspend fun save(value: PrivateConnection)
}

/** Whole secret record atomically encrypted in noBackupFilesDir. No tokens in DataStore/Room/logs. */
class KeystoreSessionVault(application: Application) : SessionVault {
    private val lock = Mutex()
    private val file = AtomicFile(File(application.noBackupFilesDir, "chrona-connection.aesgcm"))
    private val aad = "chrona-client-v1:${application.packageName}".toByteArray(Charsets.UTF_8)
    private val alias = "chrona.client.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun load(): PrivateConnection = lock.withLock { withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext PrivateConnection()
        try {
            if (file.baseFile.length() > 65_536) throw ClientFailure("SECURE_STORAGE")
            val blob = file.openRead().use { it.readBytes() }
            if (blob.size < 29 || blob[0].toInt() != 1) throw ClientFailure("SECURE_STORAGE")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            val json = JSONObject(cipher.doFinal(blob.copyOfRange(13, blob.size)).toString(Charsets.UTF_8))
            val s = json.optJSONObject("session")
            val p = json.optJSONObject("pending")
            val rawOrigin = json.getString("origin")
            // Logging out before configuration may persist an empty, secret-free record.
            // Tokens/verifiers still require a validated bound HTTPS origin.
            if (rawOrigin.isBlank() && s == null && p == null) return@withContext PrivateConnection()
            PrivateConnection(ServerOrigin.parse(rawOrigin), s?.let {
                ClientSession(it.getString("accountId"), it.getString("sessionId"), it.getString("access"),
                    it.getString("refresh"), Instant.parse(it.getString("expiresAt")), it.optBoolean("rotating"))
            }, p?.let { PendingLogin(it.getString("requestId"), it.getString("verifier"), Instant.parse(it.getString("expiresAt")), it.optBoolean("exchanging")) })
        } catch (_: Exception) { throw ClientFailure("SECURE_STORAGE") }
    } }
    override suspend fun save(value: PrivateConnection) = lock.withLock { withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("origin", value.origin)
            value.session?.let { json.put("session", JSONObject().put("accountId", it.accountId).put("sessionId", it.sessionId)
                .put("access", it.access).put("refresh", it.refresh).put("expiresAt", it.expiresAt.toString()).put("rotating", it.rotating)) }
            value.pending?.let { json.put("pending", JSONObject().put("requestId", it.requestId).put("verifier", it.verifier)
                .put("expiresAt", it.expiresAt.toString()).put("exchanging", it.exchanging)) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try { stream.write(encrypted); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        } catch (_: Exception) { throw ClientFailure("SECURE_STORAGE") }
    } }
}
