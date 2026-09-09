package dev.radiocycle.llmhub.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.radiocycle.llmhub.LlmHubApp
import dev.radiocycle.llmhub.MainActivity
import dev.radiocycle.llmhub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a turn is generating and mirrors [ChatController.status] into an
 * ongoing notification ("Generating…", "Using web_search…"). When the turn ends it swaps the
 * ongoing notification for a dismissible "Response ready" (or failure) one and stops itself.
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: ChatController
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        controller = (application as LlmHubApp).container.chatController
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            return START_NOT_STICKY
        }

        // Must post the foreground notification promptly to avoid an ANR.
        startForegroundCompat(buildOngoing(controller.status.value))

        if (!observing) {
            observing = true
            scope.launch {
                controller.status.collectLatest { status ->
                    if (status.active) {
                        notify(ONGOING_ID, buildOngoing(status))
                    } else {
                        if (status.phase == Phase.Done || status.phase == Phase.Failed) {
                            notify(completionId(), buildCompletion(status))
                        }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        observing = false
        super.onDestroy()
    }

    // --- Notifications --------------------------------------------------------------------

    private fun buildOngoing(status: GenerationStatus): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_generating)
            .setContentTitle(status.title.ifBlank { "LLM Hub" })
            .setContentText(status.summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop", stopIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun buildCompletion(status: GenerationStatus): Notification {
        val failed = status.phase == Phase.Failed
        return NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_generating)
            .setContentTitle(status.title.ifBlank { "LLM Hub" })
            .setContentText(if (failed) status.summary else "Response ready")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun notify(id: Int, notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, intent, pendingFlags())
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, GenerationService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(this, 1, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Generation", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows the agent working in the background" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Responses", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Alerts when a response is ready" }
        )
    }

    private fun completionId(): Int = COMPLETION_BASE + (completionCounter++ % 20)

    companion object {
        private const val CHANNEL_ONGOING = "generation"
        private const val CHANNEL_DONE = "responses"
        private const val ONGOING_ID = 1001
        private const val COMPLETION_BASE = 2000
        private const val ACTION_STOP = "dev.radiocycle.llmhub.STOP"
        private var completionCounter = 0

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GenerationService::class.java),
            )
        }
    }
}
