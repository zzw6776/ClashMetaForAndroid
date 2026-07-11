package com.github.kr328.clash.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.TimeUnit

class ProfileWorker : BaseService() {
    private val service: ProfileWorker
        get() = this

    private val workLock = Any()
    private var activeJobs = 0
    private var lastStartId = 0
    private var minimumLifetimeElapsed = false

    override fun onCreate() {
        super.onCreate()

        createChannels()

        foreground()

        launch {
            delay(TimeUnit.SECONDS.toMillis(10))
            stopWhenIdle(minimumLifetimeHasElapsed = true)
        }
    }

    override fun onDestroy() {
        stopForeground(true)

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                intent.uuid?.also {
                    launchWork(startId) {
                        run(it)
                    }
                } ?: stopWhenIdle(startId = startId)
            }
            Intents.ACTION_PROFILE_SCHEDULE_UPDATES -> {
                launchWork(startId) {
                    ProfileReceiver.rescheduleAll(service)

                    delay(TimeUnit.SECONDS.toMillis(30))
                }
            }
            else -> stopWhenIdle(startId = startId)
        }

        return START_NOT_STICKY
    }

    private fun launchWork(startId: Int, block: suspend () -> Unit) {
        synchronized(workLock) {
            lastStartId = maxOf(lastStartId, startId)
            activeJobs++
        }

        launch {
            try {
                block()
            } finally {
                stopWhenIdle(jobCompleted = true)
            }
        }
    }

    private fun stopWhenIdle(
        startId: Int? = null,
        jobCompleted: Boolean = false,
        minimumLifetimeHasElapsed: Boolean = false
    ) {
        val stopStartId = synchronized(workLock) {
            if (startId != null) lastStartId = maxOf(lastStartId, startId)
            if (jobCompleted) activeJobs--
            if (minimumLifetimeHasElapsed) minimumLifetimeElapsed = true

            lastStartId.takeIf { activeJobs == 0 && minimumLifetimeElapsed }
        }

        stopStartId?.let(::stopSelfResult)
    }

    private suspend fun run(uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        try {
            processing(imported.name) {
                ProfileProcessor.update(this, imported.uuid, null)
            }

            completed(imported.uuid, imported.name)

            ProfileReceiver.scheduleNext(this, imported)
        } catch (e: Exception) {
            failed(imported.uuid, imported.name, e.message ?: "Unknown")
        }
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_service_status)).build(),
                NotificationChannelCompat.Builder(
                    STATUS_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_process_status)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.profile_process_result)).build()
            )
        )
    }

    private fun foreground() {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.profile_updater))
            .setContentText(getString(R.string.running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_profile_worker, notification)
    }

    private suspend inline fun processing(name: String, block: () -> Unit) {
        val id = UndefinedIds.next()

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setContentTitle(getString(R.string.profile_updating))
            .setContentText(name)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(STATUS_CHANNEL)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(id, notification)
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                NotificationManagerCompat.from(applicationContext)
                    .cancel(id)
            }
        }
    }

    private fun resultBuilder(id: Int, uuid: UUID): NotificationCompat.Builder {
        val intent = PendingIntent.getActivity(
            this,
            id,
            Intent().setComponent(Components.PROPERTIES_ACTIVITY).setUUID(uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(RESULT_CHANNEL)
    }

    private fun completed(uuid: UUID, name: String) {
        val id = UndefinedIds.next()

        val notification = resultBuilder(id, uuid)
            .setContentTitle(getString(R.string.update_successfully))
            .setContentText(getString(R.string.format_update_complete, name))
            .build()

        NotificationManagerCompat.from(this)
            .notify(id, notification)

        sendProfileUpdateCompleted(uuid)
    }

    private fun failed(uuid: UUID, name: String, reason: String) {
        val id = UndefinedIds.next()

        val content = getString(R.string.format_update_failure, name, reason)

        val notification = resultBuilder(id, uuid)
            .setContentTitle(getString(R.string.update_failure))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()

        NotificationManagerCompat.from(this)
            .notify(id, notification)

        sendProfileUpdateFailed(uuid, reason)
    }

    companion object {
        private const val SERVICE_CHANNEL = "profile_service_channel"
        private const val STATUS_CHANNEL = "profile_status_channel"
        private const val RESULT_CHANNEL = "profile_result_channel"
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
