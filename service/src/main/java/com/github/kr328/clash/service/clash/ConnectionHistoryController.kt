package com.github.kr328.clash.service.clash

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.ConnectionHistoryRepository
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

object ConnectionHistoryController {
    data class State(
        val enabled: Boolean,
        val sessionId: String?
    )

    private val transitionMutex = Mutex()
    private val state = MutableStateFlow(State(false, null))
    private var appliedEnabled = false
    private var appliedSessionId: String? = null

    suspend fun setEnabled(store: ServiceStore, enabled: Boolean): State {
        return transitionMutex.withLock {
            store.connectionHistoryEnabled = enabled
            applyLocked(store, enabled).also { state.value = it }
        }
    }

    suspend fun synchronize(store: ServiceStore): State {
        return transitionMutex.withLock {
            applyLocked(store, store.connectionHistoryEnabled).also { state.value = it }
        }
    }

    suspend fun awaitChange(current: State): State {
        return state.first { it != current }
    }

    private suspend fun applyLocked(store: ServiceStore, enabled: Boolean): State {
        if (!enabled) {
            val sessionId = store.connectionHistorySessionId
            if (Clash.isConnectionHistoryEnabled()) {
                Clash.setConnectionHistoryEnabled(false, sessionId)
            }
            if (appliedEnabled || sessionId.isNotBlank()) {
                ConnectionHistoryRepository.clearActiveSession()
            }
            if (store.connectionHistorySessionId == sessionId) {
                store.connectionHistorySessionId = ""
            }
            appliedEnabled = false
            appliedSessionId = null
            return State(false, null)
        }

        val sessionId = store.connectionHistorySessionId.ifBlank {
            UUID.randomUUID().toString().also { store.connectionHistorySessionId = it }
        }
        if (!Clash.isConnectionHistoryEnabled() || !appliedEnabled || appliedSessionId != sessionId) {
            ConnectionHistoryRepository.startOrResumeSession(sessionId, markInterrupted = false)
            Clash.setConnectionHistoryEnabled(true, sessionId)
        }
        appliedEnabled = true
        appliedSessionId = sessionId
        return State(true, sessionId)
    }
}
