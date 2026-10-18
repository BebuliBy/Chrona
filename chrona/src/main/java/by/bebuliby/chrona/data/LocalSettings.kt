package by.bebuliby.chrona.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import by.bebuliby.chrona.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.security.MessageDigest

interface AutomaticSyncPreferences {
    suspend fun automaticEnabled(origin: String, accountId: String): Boolean
    suspend fun setAutomaticEnabled(origin: String, accountId: String, enabled: Boolean)
}

private val Context.chronaPreferences: DataStore<Preferences> by preferencesDataStore("chrona_local")

data class LocalSettingsSnapshot(
    val profileId: String,
    val deviceId: String,
    val preferences: AppPreferences,
    val photoUri: String?,
    val trackingEnabled: Boolean,
)

class LocalSettings(context: Context) : AutomaticSyncPreferences {
    private val store = context.applicationContext.chronaPreferences
    private val profile = stringPreferencesKey("profile_id")
    private val device = stringPreferencesKey("device_id")
    private val theme = stringPreferencesKey("theme")
    private val palette = stringPreferencesKey("palette")
    private val style = stringPreferencesKey("style")
    private val photo = stringPreferencesKey("local_photo_uri")
    private val consent = booleanPreferencesKey("tracking_enabled")

    suspend fun initialize() {
        store.edit {
            if (it[profile] == null) it[profile] = UUID.randomUUID().toString()
            if (it[device] == null) it[device] = UUID.randomUUID().toString()
        }
    }

    val snapshots: Flow<LocalSettingsSnapshot> = store.data.map {
        LocalSettingsSnapshot(it[profile].orEmpty(), it[device].orEmpty(),
            AppPreferences(runCatching { ThemeMode.valueOf(it[theme].orEmpty()) }.getOrDefault(ThemeMode.SYSTEM),
                it[palette] ?: "ocean", it[style] ?: "standard"),
            it[photo], it[consent] ?: false)
    }

    suspend fun preferences(value: AppPreferences) {
        if (value.paletteId.isBlank() || value.styleId.isBlank()) throw CoreFailure("VALIDATION", "Не выбрана тема.")
        store.edit { it[theme] = value.themeMode.name; it[palette] = value.paletteId; it[style] = value.styleId }
    }
    suspend fun photo(uri: String) { store.edit { it[photo] = uri } }
    suspend fun tracking(enabled: Boolean) { store.edit { it[consent] = enabled } }
    private fun automaticKey(origin: String, accountId: String) = booleanPreferencesKey("automatic_sync_v1_" +
        MessageDigest.getInstance("SHA-256").digest("$origin\u0000$accountId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) })
    override suspend fun automaticEnabled(origin: String, accountId: String): Boolean =
        store.data.map { it[automaticKey(origin, accountId)] ?: true }.first()
    override suspend fun setAutomaticEnabled(origin: String, accountId: String, enabled: Boolean) {
        store.edit { it[automaticKey(origin, accountId)] = enabled }
    }
}
