package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProcessTraffic
import com.github.kr328.clash.service.clash.ConnectionHistoryController
import com.github.kr328.clash.service.data.ConnectionHistoryRepository
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionHistoryModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        val store = ServiceStore(service)
        var sessionId: String? = null
        var previousProcessTraffic = emptyMap<String, ProcessTraffic>()
        var lastActiveCheckpoint = 0L
        var lastProcessCheckpoint = 0L

        try {
            var state = ConnectionHistoryController.synchronize(store)
            while (currentCoroutineContext().isActive) {
                val configuredSessionId = state.sessionId
                if (!state.enabled || configuredSessionId == null) {
                    sessionId = null
                    previousProcessTraffic = emptyMap()
                    lastActiveCheckpoint = 0L
                    lastProcessCheckpoint = 0L
                    state = ConnectionHistoryController.awaitChange(state)
                    continue
                }

                try {
                    if (sessionId != configuredSessionId) {
                        sessionId = configuredSessionId
                        previousProcessTraffic = emptyMap()
                        lastActiveCheckpoint = 0L
                        lastProcessCheckpoint = 0L
                    }
                    val currentSessionId = configuredSessionId

                    Clash.peekConnectionHistoryEvents(EVENT_BATCH_SIZE)?.let { events ->
                        if (events.ackSequence > 0L) {
                            ConnectionHistoryRepository.persistEvents(currentSessionId, events)
                            Clash.ackConnectionHistoryEvents(events.ackToken, events.ackSequence)
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastActiveCheckpoint >= ACTIVE_CHECKPOINT_INTERVAL_MILLIS ||
                        now - lastProcessCheckpoint >= PROCESS_CHECKPOINT_INTERVAL_MILLIS
                    ) {
                        val snapshot = Clash.queryConnectionSnapshot()
                        if (snapshot != null) {
                            if (now - lastActiveCheckpoint >= ACTIVE_CHECKPOINT_INTERVAL_MILLIS) {
                                ConnectionHistoryRepository.checkpointActive(
                                    currentSessionId,
                                    snapshot.connections.orEmpty()
                                )
                                lastActiveCheckpoint = now
                            }
                            if (now - lastProcessCheckpoint >= PROCESS_CHECKPOINT_INTERVAL_MILLIS) {
                                ConnectionHistoryRepository.persistProcessTrafficDelta(
                                    currentSessionId,
                                    snapshot.processTraffic,
                                    previousProcessTraffic
                                )
                                previousProcessTraffic = snapshot.processTraffic
                                lastProcessCheckpoint = now
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Connection history collector retrying", e)
                }

                state = withTimeoutOrNull(COLLECT_INTERVAL_MILLIS) {
                    ConnectionHistoryController.awaitChange(state)
                } ?: state
            }
        } finally {
            withContext(NonCancellable) {
                val flushed = withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MILLIS) {
                    try {
                        val currentSessionId = sessionId
                        if (
                            currentSessionId != null &&
                            store.connectionHistoryEnabled &&
                            store.connectionHistorySessionId == currentSessionId &&
                            Clash.isConnectionHistoryEnabled()
                        ) {
                            var batchCount = 0
                            while (batchCount < MAX_SHUTDOWN_EVENT_BATCHES) {
                                val events = Clash.peekConnectionHistoryEvents(EVENT_BATCH_SIZE)
                                    ?: break
                                if (events.ackSequence <= 0L) break

                                ConnectionHistoryRepository.persistEvents(currentSessionId, events)
                                Clash.ackConnectionHistoryEvents(events.ackToken, events.ackSequence)
                                batchCount++
                            }
                            Clash.queryConnectionSnapshot()?.let { snapshot ->
                                ConnectionHistoryRepository.checkpointActive(
                                    currentSessionId,
                                    snapshot.connections.orEmpty()
                                )
                                ConnectionHistoryRepository.persistProcessTrafficDelta(
                                    currentSessionId,
                                    snapshot.processTraffic,
                                    previousProcessTraffic
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Failed to flush connection history during shutdown", e)
                    }
                    true
                }
                if (flushed != true) {
                    Log.w("Connection history shutdown flush timed out")
                }
            }
        }
    }

    companion object {
        private const val COLLECT_INTERVAL_MILLIS = 500L
        private const val ACTIVE_CHECKPOINT_INTERVAL_MILLIS = 10_000L
        private const val PROCESS_CHECKPOINT_INTERVAL_MILLIS = 1_000L
        private const val EVENT_BATCH_SIZE = 500
        private const val MAX_SHUTDOWN_EVENT_BATCHES = 20
        private const val SHUTDOWN_FLUSH_TIMEOUT_MILLIS = 3_000L
    }
}
