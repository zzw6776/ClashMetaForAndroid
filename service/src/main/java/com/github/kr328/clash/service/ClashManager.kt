package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.data.ConnectionHistoryRepository
import com.github.kr328.clash.service.clash.ConnectionHistoryController
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.remote.IConnectionObserver
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.ReceiveChannel

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null
    private var connectionObserverJob: Job? = null

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun setConnectionObserver(observer: IConnectionObserver?, intervalMillis: Long) {
        synchronized(this) {
            connectionObserverJob?.cancel()
            connectionObserverJob = null

            if (observer != null && Clash.isConnectionHistoryEnabled()) {
                val pollInterval = intervalMillis.coerceIn(500L, 5000L)
                connectionObserverJob = launch {
                    var lastConnections = emptyMap<String, Connection>()
                    var lastProcessTraffic = emptyMap<String, ProcessTraffic>()
                    var lastHistoryRevision = ConnectionHistoryRepository.revision
                    var activeTrafficIds = emptySet<String>()
                    val persistedEvents = Channel<ConnectionHistoryEvents>(Channel.UNLIMITED)
                    val persistedEventsJob = launch {
                        ConnectionHistoryRepository.events.collect { persistedEvents.send(it) }
                    }
                    try {
                        while (isActive) {
                            try {
                                val json = Clash.queryConnections()
                                if (json != null) {
                                    val snapshot = Clash.parseConnectionSnapshot(json)
                                    val currentConnections = snapshot?.connections?.associateBy { it.id } ?: emptyMap()
                                    val currentProcessTraffic = ConnectionHistoryRepository.queryProcessTraffic()
                                    val currentHistoryRevision = ConnectionHistoryRepository.revision

                                    val newConnections = mutableListOf<Connection>()
                                    val newFailedConnections = mutableListOf<FailedConnection>()
                                    val removedConnections = mutableListOf<String>()
                                    val removedConnectionDetails = mutableListOf<Connection>()
                                    val removedConnectionClosedAt = mutableMapOf<String, Long>()
                                    val updatedTraffics = mutableListOf<ConnectionTraffic>()
                                    val changedTrafficIds = mutableSetOf<String>()
                                    var persistedHistoryChanged = false

                                    while (true) {
                                        val events = persistedEvents.tryReceive().getOrNull() ?: break
                                        persistedHistoryChanged = persistedHistoryChanged ||
                                            events.closedConnections.isNotEmpty() ||
                                            events.failedConnections.isNotEmpty()
                                        removedConnectionDetails.addAll(events.closedConnections)
                                        removedConnections.addAll(events.closedConnections.map { it.id })
                                        removedConnectionClosedAt.putAll(events.closedAt)
                                        newFailedConnections.addAll(events.failedConnections)
                                    }
                                    val removedConnectionDetailIds = removedConnectionDetails.mapTo(mutableSetOf()) { it.id }
                                    val historyOverview = if (
                                        persistedHistoryChanged || currentHistoryRevision != lastHistoryRevision
                                    ) {
                                        ConnectionHistoryRepository.queryOverview()
                                    } else {
                                        null
                                    }

                                    for ((id, conn) in currentConnections) {
                                        val last = lastConnections[id]
                                        if (last == null) {
                                            newConnections.add(conn)
                                        } else {
                                            val hasMetaChanged = conn.copy(upload = 0, download = 0) != last.copy(upload = 0, download = 0)
                                            if (hasMetaChanged) {
                                                newConnections.add(conn)
                                            } else if (conn.upload != last.upload || conn.download != last.download) {
                                                updatedTraffics.add(ConnectionTraffic(id, conn.upload, conn.download))
                                                changedTrafficIds.add(id)
                                            } else if (id in activeTrafficIds) {
                                                updatedTraffics.add(ConnectionTraffic(id, conn.upload, conn.download))
                                            }
                                        }
                                    }

                                    for ((id, conn) in lastConnections) {
                                        if (!currentConnections.containsKey(id)) {
                                            removedConnections.add(id)
                                            if (removedConnectionDetailIds.add(id)) {
                                                removedConnectionDetails.add(conn)
                                            }
                                        }
                                    }

                                    if (
                                        newConnections.isNotEmpty() ||
                                        newFailedConnections.isNotEmpty() ||
                                        removedConnections.isNotEmpty() ||
                                        removedConnectionDetails.isNotEmpty() ||
                                        updatedTraffics.isNotEmpty() ||
                                        historyOverview != null ||
                                        currentProcessTraffic != lastProcessTraffic
                                    ) {
                                        sendConnectionDiffBatched(
                                            observer = observer,
                                            totalUpload = snapshot?.uploadTotal ?: 0L,
                                            totalDownload = snapshot?.downloadTotal ?: 0L,
                                            processTraffic = currentProcessTraffic,
                                            historyOverview = historyOverview,
                                            newConnections = newConnections,
                                            newFailedConnections = newFailedConnections,
                                            removedConnections = removedConnections,
                                            removedConnectionDetails = removedConnectionDetails,
                                            removedConnectionClosedAt = removedConnectionClosedAt,
                                            updatedTraffics = updatedTraffics
                                        )
                                    }
                                    lastConnections = currentConnections
                                    lastProcessTraffic = currentProcessTraffic
                                    lastHistoryRevision = currentHistoryRevision
                                    activeTrafficIds = changedTrafficIds
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("Connection observer poll error, retrying", e)
                            }
                            delay(pollInterval)
                        }
                    } catch (e: CancellationException) {
                        // ignore
                    } finally {
                        persistedEventsJob.cancel()
                    }
                }
            }
        }
    }

    override fun setConnectionHistoryEnabled(enabled: Boolean) {
        if (!enabled) {
            setConnectionObserver(null, 0L)
        }
        runBlocking(Dispatchers.IO) {
            ConnectionHistoryController.setEnabled(store, enabled)
        }
    }

    override fun isConnectionHistoryEnabled(): Boolean {
        return Clash.isConnectionHistoryEnabled()
    }

    override fun queryConnectionHistory(): ConnectionDiff {
        val snapshot = Clash.queryConnectionSnapshot()
        val active = snapshot?.connections.orEmpty()
        val processTraffic = runBlocking(Dispatchers.IO) {
            ConnectionHistoryRepository.queryProcessTraffic()
        }
        return ConnectionDiff(
            timestamp = System.currentTimeMillis(),
            totalUpload = snapshot?.uploadTotal ?: 0L,
            totalDownload = snapshot?.downloadTotal ?: 0L,
            processTraffic = processTraffic,
            newConnections = active,
            newFailedConnections = emptyList(),
            removedConnections = emptyList(),
            removedConnectionDetails = emptyList(),
            updatedTraffics = emptyList()
        )
    }

    override fun queryConnectionHistoryOverview(): ConnectionHistoryOverview {
        return runBlocking(Dispatchers.IO) {
            ConnectionHistoryRepository.queryOverview()
        }
    }

    override fun queryConnectionHistoryPage(
        offset: Int,
        limit: Int,
        process: String,
        proxy: String,
        includeClosed: Boolean,
        includeFailed: Boolean
    ): ConnectionHistoryPage {
        return runBlocking(Dispatchers.IO) {
            ConnectionHistoryRepository.queryPage(
                offset,
                limit,
                process,
                proxy,
                includeClosed,
                includeFailed
            )
        }
    }

    override fun closeConnection(id: String) {
        Clash.closeConnection(id)
    }

    private fun sendConnectionDiffBatched(
        observer: IConnectionObserver,
        totalUpload: Long,
        totalDownload: Long,
        processTraffic: Map<String, ProcessTraffic>,
        historyOverview: ConnectionHistoryOverview?,
        newConnections: List<Connection>,
        newFailedConnections: List<FailedConnection>,
        removedConnections: List<String>,
        removedConnectionDetails: List<Connection>,
        removedConnectionClosedAt: Map<String, Long>,
        updatedTraffics: List<ConnectionTraffic>
    ) {
        val itemCount = maxOf(
            newConnections.size,
            newFailedConnections.size,
            removedConnections.size,
            removedConnectionDetails.size,
            updatedTraffics.size,
            1
        )
        for (offset in 0 until itemCount step CONNECTION_DIFF_BATCH_SIZE) {
            val removedDetailsBatch = removedConnectionDetails
                .drop(offset)
                .take(CONNECTION_DIFF_BATCH_SIZE)
            val diff = ConnectionDiff(
                timestamp = System.currentTimeMillis(),
                totalUpload = totalUpload,
                totalDownload = totalDownload,
                processTraffic = processTraffic,
                historyOverview = if (offset == 0) historyOverview else null,
                newConnections = newConnections.drop(offset).take(CONNECTION_DIFF_BATCH_SIZE),
                newFailedConnections = newFailedConnections.drop(offset).take(CONNECTION_DIFF_BATCH_SIZE),
                removedConnections = removedConnections.drop(offset).take(CONNECTION_DIFF_BATCH_SIZE),
                removedConnectionDetails = removedDetailsBatch,
                removedConnectionClosedAt = removedDetailsBatch.mapNotNull { connection ->
                    removedConnectionClosedAt[connection.id]?.let { connection.id to it }
                }.toMap(),
                updatedTraffics = updatedTraffics.drop(offset).take(CONNECTION_DIFF_BATCH_SIZE)
            )
            try {
                observer.onConnectionDiff(diff)
            } catch (e: Exception) {
                Log.w("Failed to send connection diff via IPC", e)
                break
            }
        }
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    companion object {
        private const val CONNECTION_DIFF_BATCH_SIZE = 100
    }

    override fun patchSelector(group: String, name: String): Boolean {
        return Clash.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }
}
