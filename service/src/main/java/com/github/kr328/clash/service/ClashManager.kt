package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IConnectionObserver
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
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

            if (observer != null) {
                val pollInterval = intervalMillis.coerceIn(500L, 5000L)
                connectionObserverJob = launch {
                    var lastConnections = emptyMap<String, Connection>()
                    var lastProcessTraffic = emptyMap<String, ProcessTraffic>()
                    var activeTrafficIds = emptySet<String>()
                    try {
                        while (isActive) {
                            try {
                                val json = Clash.queryConnections()
                                if (json != null) {
                                    val snapshot = Clash.parseConnectionSnapshot(json)
                                    val currentConnections = snapshot?.connections?.associateBy { it.id } ?: emptyMap()
                                    val currentProcessTraffic = snapshot?.processTraffic ?: emptyMap()
                                    
                                    val newConnections = mutableListOf<Connection>()
                                    val removedConnections = mutableListOf<String>()
                                    val removedConnectionDetails = mutableListOf<Connection>()
                                    val updatedTraffics = mutableListOf<ConnectionTraffic>()
                                    val changedTrafficIds = mutableSetOf<String>()
                                    
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
                                            removedConnectionDetails.add(conn)
                                        }
                                    }
                                    
                                    if (
                                        newConnections.isNotEmpty() ||
                                        removedConnections.isNotEmpty() ||
                                        removedConnectionDetails.isNotEmpty() ||
                                        updatedTraffics.isNotEmpty() ||
                                        currentProcessTraffic != lastProcessTraffic
                                    ) {
                                        val diff = ConnectionDiff(
                                            timestamp = System.currentTimeMillis(),
                                            totalUpload = snapshot?.uploadTotal ?: 0L,
                                            totalDownload = snapshot?.downloadTotal ?: 0L,
                                            processTraffic = currentProcessTraffic,
                                            newConnections = newConnections,
                                            removedConnections = removedConnections,
                                            removedConnectionDetails = removedConnectionDetails,
                                            updatedTraffics = updatedTraffics
                                        )

                                        try {
                                            observer.onConnectionDiff(diff)
                                        } catch (e: Exception) {
                                            Log.w("Failed to send connection diff via IPC", e)
                                        }
                                    }
                                    lastConnections = currentConnections
                                    lastProcessTraffic = currentProcessTraffic
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
                    }
                }
            }
        }
    }

    override fun closeConnection(id: String) {
        Clash.closeConnection(id)
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
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
