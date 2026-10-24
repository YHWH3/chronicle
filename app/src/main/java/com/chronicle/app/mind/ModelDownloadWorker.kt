package com.chronicle.app.mind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.chronicle.app.ChronicleApp
import com.chronicle.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Durable unique transfers; tokens are read at execution, never saved in WorkManager data. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val packId = inputData.getString("pack") ?: return@coroutineScope Result.failure()
        val model = SmartModels.forPack(packId) ?: return@coroutineScope Result.failure()
        val container = (applicationContext as ChronicleApp).container
        setForeground(foreground(packId, 0))
        val progress: Job = launch {
            container.modelStore.states.collect { states ->
                val s = states[packId]
                if (s is ModelState.Downloading) {
                    val percent = (s.progress * 100).toInt().coerceIn(0, 100)
                    setProgress(workDataOf("percent" to percent))
                    setForeground(foreground(packId, percent))
                }
            }
        }
        try {
            // Resolve the sealed session at execution time, not in persisted work data.
            val token = if (model.gated) container.hfAuth.accessToken() else ""
            val headers = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
            withContext(Dispatchers.IO) { container.modelStore.installNow(packId, model.url, headers) }
            when (container.modelStore.states.value[packId]) {
                ModelState.Installed -> Result.success()
                else -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (ce: CancellationException) {
            container.modelStore.cancel(packId)
            throw ce
        } finally {
            progress.cancel()
        }
    }

    private fun foreground(pack: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, applicationContext.getString(R.string.model_downloads), NotificationManager.IMPORTANCE_LOW,
        ))
        fun action(cancel: Boolean): PendingIntent {
            val intent = Intent(applicationContext, DownloadActionReceiver::class.java)
                .putExtra("pack", pack).putExtra("discard", cancel)
            return PendingIntent.getBroadcast(applicationContext, pack.hashCode() + if (cancel) 1 else 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.smart_todos))
            .setContentText(applicationContext.getString(R.string.model_progress, percent))
            .setProgress(100, percent, false).setOngoing(true).setSilent(true)
            .addAction(0, applicationContext.getString(R.string.pause), action(false))
            .addAction(0, applicationContext.getString(R.string.cancel_download), action(true))
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(pack.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(pack.hashCode(), notification)
    }
    companion object { const val CHANNEL = "chronicle_downloads" }
}

class ModelDownloads(private val context: Context) {
    val work = WorkManager.getInstance(context)
    fun enqueue(packId: String, allowMobile: Boolean) {
        require(SmartModels.forPack(packId) != null)
        work.enqueueUniqueWork(name(packId), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf("pack" to packId)).addTag(TAG).addTag(packId)
                .setConstraints(constraints(allowMobile))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    suspend fun pause(packId: String) = withContext(Dispatchers.IO) {
        (context.applicationContext as ChronicleApp).container.modelStore.cancel(packId)
        work.cancelUniqueWork(name(packId)).result.get()
        (context.applicationContext as ChronicleApp).container.modelStore.awaitTransferStopped(packId)
    }
    suspend fun cancel(packId: String) {
        pause(packId)
        (context.applicationContext as ChronicleApp).container.modelStore.cancel(packId)
        // Worker cancellation unwinds its streams before a new resume reads the partial.
        (context.applicationContext as ChronicleApp).container.modelStore.awaitTransferStopped(packId)
        (context.applicationContext as ChronicleApp).container.modelStore.discardPartial(packId)
    }
    companion object {
        const val TAG = "chronicle-model-download"
        fun name(packId: String) = "model-$packId"
        fun constraints(allowMobile: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true).build()
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pack = intent.getStringExtra("pack") ?: return
        if (SmartModels.forPack(pack) == null) return
        val pending = goAsync()
        (context.applicationContext as ChronicleApp).container.appScope.launch {
            try {
                val downloads = ModelDownloads(context.applicationContext)
                if (intent.getBooleanExtra("discard", false)) downloads.cancel(pack) else downloads.pause(pack)
            } finally { pending.finish() }
        }
    }
}
