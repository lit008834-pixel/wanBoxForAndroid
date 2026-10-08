// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import androidx.work.Constraints
import androidx.work.NetworkType
import io.nekohasekai.sagernet.utils.awaitCancellable
import kotlinx.coroutines.CancellationException
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        val workManager = RemoteWorkManager.getInstance(app)
        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        if (subscriptions.isEmpty()) {
            try { workManager.cancelUniqueWork(WORK_NAME).awaitCancellable() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Logs.w("SubscriptionUpdater: cancel work failed", e) }
            Logs.d("SubscriptionUpdater: no auto-update subscriptions, work cancelled")
            return
        }

        val plan = SubscriptionSchedule.plan(subscriptions.map {
            SubscriptionSchedule.Entry(it.subscription!!.autoUpdateDelay, it.subscription!!.lastUpdated)
        }, System.currentTimeMillis() / 1000L)!!
        val minDelay = plan.intervalMinutes
        val minInitDelay = plan.initialDelaySeconds

        // main process
        try {
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true).build())
                    .setInputData(
                        Data.Builder()
                            // Run the worker in the :bg process (RemoteWorkerService),
                            // where DataStore.serviceState is maintained by BaseService.
                            .putString(
                                RemoteListenableWorker.ARGUMENT_PACKAGE_NAME,
                                app.packageName
                            )
                            .putString(
                                RemoteListenableWorker.ARGUMENT_CLASS_NAME,
                                RemoteWorkerService::class.java.name
                            )
                            .build()
                    )
                    .apply {
                        if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                    }
                    .build()
            ).awaitCancellable()
            Logs.d("SubscriptionUpdater: work enqueued")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w("SubscriptionUpdater: enqueue work failed", e)
        }
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : RemoteCoroutineWorker(appContext, params) {

        override suspend fun doRemoteWork(): Result {
            Logs.d("SubscriptionUpdater: work started, serviceState=${DataStore.serviceState}")
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            for (profile in subscriptions) {
                val subscription = profile.subscription!!

                if ((System.currentTimeMillis() / 1000 - subscription.lastUpdated.toLong()) < subscription.autoUpdateDelay.toLong().coerceAtLeast(15) * 60) {
                    Logs.d("work: not updating " + profile.displayName())
                    continue
                }
                Logs.d("work: updating " + profile.displayName())

                GroupUpdater.executeUpdate(profile, false)
            }
            return Result.success()
        }
    }

}
