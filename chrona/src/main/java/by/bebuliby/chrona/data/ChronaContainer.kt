package by.bebuliby.chrona.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import by.bebuliby.chrona.data.client.*
import by.bebuliby.chrona.BuildConfig
import by.bebuliby.chrona.data.sync.AutomaticSyncScheduler
import by.bebuliby.chrona.data.sync.DefaultNetworkObserver
import by.bebuliby.chrona.data.update.ApkUpdater
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect

class ChronaApplication : Application() {
    val container: ChronaContainer by lazy { ChronaContainer(this) }
    override fun onCreate() { super.onCreate(); container } // headless service/worker process, not Activity-only
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
    private val automaticScheduler = AutomaticSyncScheduler(application)
    val updater = ApkUpdater(application, BuildConfig.CHRONA_API_BASE_URL)
    fun scheduleBackgroundSync() = automaticScheduler.enqueue()
    fun scheduleNextBackgroundSync() = automaticScheduler.next()
    private val transport = HttpsTransport()
    val client = AndroidClient(SessionClient(transport, KeystoreSessionVault(application), syncStore::bind),
        syncStore, settings, scope, automaticScheduler::enqueue, automaticScheduler::cancelPending)
    private val defaultNetwork = DefaultNetworkObserver(application, client::defaultNetworkChanged)
    val clientReady = scope.async { ready.await(); client.initialize(BuildConfig.CHRONA_API_BASE_URL) }
    init {
        scope.launch {
            try {
                defaultNetwork.start()
                clientReady.await()
                automaticScheduler.periodic()
                repository.facts.map { it.pending }.distinctUntilChanged().collect { pending ->
                    if (pending > 0) client.signalAutomaticSync()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { client.backgroundFailure(error) }
        }
    }
}
