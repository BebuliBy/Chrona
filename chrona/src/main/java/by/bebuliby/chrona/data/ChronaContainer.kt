package by.bebuliby.chrona.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import by.bebuliby.chrona.data.client.*

class ChronaApplication : Application() {
    val container: ChronaContainer by lazy { ChronaContainer(this) }
}

class ChronaContainer(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val collectorLock = Mutex() // serializes reconnecting service instances until the old actor has flushed
    private val database = Room.databaseBuilder(application, ChronaDatabase::class.java, "chrona-local.db")
        .addMigrations(ClientMigration.MIGRATION_1_2, ClientMigration.MIGRATION_2_3)
        .build()
    val repository = ChronaRepository(database)
    val settings = LocalSettings(application)
    val ready = scope.async { settings.initialize(); repository.initialize() }
    private val syncStore = ClientSyncStore(database)
    val client = AndroidClient(SessionClient(HttpsTransport(), KeystoreSessionVault(application), syncStore::bind),
        syncStore, settings, scope)
    val clientReady = scope.async { ready.await(); client.initialize() }
}
