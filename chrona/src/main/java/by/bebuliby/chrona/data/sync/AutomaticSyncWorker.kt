package by.bebuliby.chrona.data.sync

import android.content.Context
import androidx.work.*
import by.bebuliby.chrona.data.ChronaApplication
import by.bebuliby.chrona.data.client.ClientFailure
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Android-owned constrained work, not an always-running service or a force-stop bypass. */
class AutomaticSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? ChronaApplication)?.container ?: return Result.failure()
        return try {
            container.clientReady.await()
            if (container.client.runBackgroundCycle()) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            container.client.backgroundFailure(error)
            // Work success here only means no automatic retry; it is NOT a data ACK/sync success.
            if (error is ClientFailure && error.code in setOf("LOGIN_REQUIRED", "ACCOUNT_BINDING_CONFLICT",
                    "AUTOMATIC_SYNC_DISABLED", "SERVER_NOT_CONFIGURED", "BOOTSTRAP_EXISTING_BINDING", "SESSION_REFRESH_UNCERTAIN"))
                Result.success() else Result.retry()
        }
    }
}

class AutomaticSyncScheduler(context: Context) {
    private val app = context.applicationContext
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enqueue() {
        WorkManager.getInstance(app).enqueueUniqueWork(ON_DEMAND, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomaticSyncWorker>().setConstraints(constraints())
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun periodic() {
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutomaticSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancelPending() { WorkManager.getInstance(app).cancelUniqueWork(ON_DEMAND) }
    companion object {
        private const val ON_DEMAND = "chrona-automatic-sync"
        private const val PERIODIC = "chrona-automatic-sync-periodic"
    }
}
