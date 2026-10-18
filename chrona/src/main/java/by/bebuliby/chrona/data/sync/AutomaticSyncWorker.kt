package by.bebuliby.chrona.data.sync

import android.content.Context
import androidx.work.*
import by.bebuliby.chrona.data.ChronaApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Android-owned constrained work, not an always-running service or a force-stop bypass. */
class AutomaticSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? ChronaApplication)?.container ?: return Result.failure()
        if (inputData.getBoolean("fallback", false)) {
            container.clientReady.await()
            if (!container.client.isUiForeground() && container.client.backgroundEnabled()) container.scheduleBackgroundSync()
            return Result.success()
        }
        return try {
            container.clientReady.await()
            container.client.runBackgroundCycle()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            container.client.backgroundFailure(error)
            // Work success here only means no automatic retry; it is NOT a data ACK/sync success.
            Result.success()
        } finally {
            // Append AFTER the running unique work; KEEP here would silently lose the next tick.
            // A foreground tick does no IO but retains the chain: onStop racing this completion
            // must not lose a KEEP request just after the foreground check.
            if (!isStopped && container.client.backgroundEnabled())
                container.scheduleNextBackgroundSync()
        }
    }
}

class AutomaticSyncScheduler(context: Context) {
    private val app = context.applicationContext
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enqueue() {
        WorkManager.getInstance(app).enqueueUniqueWork(ON_DEMAND, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomaticSyncWorker>().setConstraints(constraints())
                .setInitialDelay(10, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun next() {
        WorkManager.getInstance(app).enqueueUniqueWork(ON_DEMAND, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AutomaticSyncWorker>().setConstraints(constraints())
                .setInitialDelay(10, TimeUnit.MINUTES).build())
    }
    fun periodic() {
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutomaticSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints())
                .setInputData(workDataOf("fallback" to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancelPending() { WorkManager.getInstance(app).cancelUniqueWork(ON_DEMAND) }
    companion object {
        private const val ON_DEMAND = "chrona-automatic-sync"
        private const val PERIODIC = "chrona-automatic-sync-periodic"
    }
}
