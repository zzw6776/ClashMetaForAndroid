package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.ConnectionHistoryRepository
import kotlinx.coroutines.CancellationException

class CloseModule(service: Service) : Module<CloseModule.RequestClose>(service) {
    object RequestClose

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_CLASH_REQUEST_STOP)
        }

        broadcasts.receive()

        Log.d("User request close")

        enqueueEvent(RequestClose)

        try {
            ConnectionHistoryRepository.endActiveSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Failed to end connection history session", e)
        }
    }
}
