package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

class SuspendModule(service: Service) : Module<Unit>(service) {
    private val isDeviceIdleMode: Boolean
        get() = service.getSystemService<PowerManager>()?.isDeviceIdleMode ?: false

    private fun updateSuspendState(interactive: Boolean) {
        val suspended = !interactive && isDeviceIdleMode

        Clash.suspendCore(suspended)

        Log.d(if (suspended) "Clash suspended" else "Clash resumed")
    }

    override suspend fun run() {
        val interactive = service.getSystemService<PowerManager>()?.isInteractive ?: true

        updateSuspendState(interactive)

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }

        try {
            while (true) {
                when (screenToggle.receive().action) {
                    Intent.ACTION_SCREEN_ON -> updateSuspendState(true)
                    Intent.ACTION_SCREEN_OFF -> updateSuspendState(false)
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val interactive = service.getSystemService<PowerManager>()?.isInteractive ?: true
                        updateSuspendState(interactive)
                    }
                    else -> Clash.healthCheckAll()
                }
            }
        } finally {
            withContext(NonCancellable) {
                Clash.suspendCore(false)
            }
        }
    }
}
