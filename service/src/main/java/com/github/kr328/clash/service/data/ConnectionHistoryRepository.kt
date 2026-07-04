package com.github.kr328.clash.service.data

import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.ConnectionHistoryEvents
import com.github.kr328.clash.core.model.ConnectionHistoryGroup
import com.github.kr328.clash.core.model.ConnectionHistoryOverview
import com.github.kr328.clash.core.model.ConnectionHistoryPage
import com.github.kr328.clash.core.model.FailedConnection
import com.github.kr328.clash.core.model.ProcessTraffic
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

object ConnectionHistoryRepository {
    private val _events = MutableSharedFlow<ConnectionHistoryEvents>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    private val generationCounter = AtomicLong(0L)
    private val revisionCounter = AtomicLong(0L)
    private val mutationMutex = Mutex()
    private var activeSessionIdCache: String? = null
    private var processTrafficSessionId: String? = null
    private val processTrafficCache = mutableMapOf<String, ProcessTraffic>()
    val generation: Long
        get() = generationCounter.get()
    val revision: Long
        get() = revisionCounter.get()

    private val dao: ConnectionHistoryDao
        get() = Database.database.openConnectionHistoryDao()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun startOrResumeSession(sessionId: String, markInterrupted: Boolean = true): String {
        require(sessionId.isNotBlank())
        return mutationMutex.withLock {
            val existing = dao.queryActiveSession()
            if (existing?.id == sessionId) {
                activeSessionIdCache = existing.id
                if (markInterrupted) {
                    dao.markActiveInterrupted(existing.id, System.currentTimeMillis())
                }
                return@withLock existing.id
            }

            dao.replaceAllWithSession(
                ConnectionSession(
                    id = sessionId,
                    startedAt = System.currentTimeMillis()
                )
            )
            activeSessionIdCache = sessionId
            resetProcessTrafficCache()
            generationCounter.incrementAndGet()
            sessionId
        }
    }

    suspend fun persistEvents(sessionId: String, events: ConnectionHistoryEvents) {
        val now = System.currentTimeMillis()
        val emitted = mutableListOf<ConnectionHistoryEvents>()
        mutationMutex.withLock {
            events.closedConnections.chunked(WRITE_BATCH_SIZE).forEach { batch ->
                val closedAt = batch.associate { connection ->
                    connection.id to (events.closedAt[connection.id] ?: now)
                }
                dao.upsertHistoryWithProxies(
                    records = batch.map { connection ->
                        connection.toHistory(
                            sessionId,
                            ConnectionHistory.STATUS_CLOSED,
                            closedAt.getValue(connection.id)
                        )
                    },
                    proxies = batch.flatMap { it.toHistoryProxies(sessionId) }
                )
                emitted += ConnectionHistoryEvents(
                    closedConnections = batch,
                    closedAt = closedAt
                )
            }
            events.failedConnections.chunked(WRITE_BATCH_SIZE).forEach { batch ->
                val failedAt = batch.associate { failed ->
                    failed.id to (events.failedAt[failed.id] ?: now)
                }
                dao.upsertHistoryWithProxies(
                    records = batch.map { failed ->
                        failed.toHistory(sessionId, failedAt.getValue(failed.id))
                    },
                    proxies = batch.flatMap { it.toHistoryProxies(sessionId) }
                )
                emitted += ConnectionHistoryEvents(
                    failedConnections = batch,
                    failedAt = failedAt
                )
            }
            if (emitted.isNotEmpty()) revisionCounter.incrementAndGet()
        }
        emitted.forEach { _events.emit(it) }
    }

    suspend fun checkpointActive(sessionId: String, active: List<Connection>) {
        val now = System.currentTimeMillis()
        val activeById = active.associateBy { it.id }
        val records = active.map { it.toHistory(sessionId, ConnectionHistory.STATUS_ACTIVE, now) }
        mutationMutex.withLock {
            val historyChanged = dao.checkpointActive(
                sessionId = sessionId,
                records = records,
                proxies = records.flatMap { record ->
                    activeById[record.id]?.toHistoryProxies(sessionId).orEmpty()
                },
                checkpointAt = now
            )
            if (historyChanged) revisionCounter.incrementAndGet()
        }
    }

    suspend fun persistProcessTrafficDelta(
        sessionId: String,
        current: Map<String, ProcessTraffic>,
        previous: Map<String, ProcessTraffic>
    ) {
        if (current.isEmpty()) return

        mutationMutex.withLock {
            val stored = loadProcessTrafficCache(sessionId)
            val now = System.currentTimeMillis()
            val updates = current.mapNotNull { (process, traffic) ->
                val before = previous[process]
                val uploadDelta = counterDelta(traffic.upload, before?.upload)
                val downloadDelta = counterDelta(traffic.download, before?.download)
                if (uploadDelta == 0L && downloadDelta == 0L) return@mapNotNull null

                val total = stored[process]
                ConnectionProcessTraffic(
                    sessionId = sessionId,
                    process = process,
                    upload = (total?.upload ?: 0L) + uploadDelta,
                    download = (total?.download ?: 0L) + downloadDelta,
                    updatedAt = now
                )
            }
            if (updates.isNotEmpty()) {
                dao.upsertProcessTraffic(updates)
                updates.forEach { row ->
                    stored[row.process] = ProcessTraffic(upload = row.upload, download = row.download)
                }
            }
        }
    }

    suspend fun queryProcessTraffic(): Map<String, ProcessTraffic> {
        return mutationMutex.withLock {
            val sessionId = activeSessionIdCache
                ?: dao.queryActiveSession()?.id?.also { activeSessionIdCache = it }
                ?: return@withLock emptyMap()
            loadProcessTrafficCache(sessionId).toMap()
        }
    }

    suspend fun queryOverview(): ConnectionHistoryOverview {
        val sessionId = dao.queryActiveSession()?.id ?: return ConnectionHistoryOverview()
        return ConnectionHistoryOverview(
            groups = dao.queryHistoryGroups(sessionId).map { row ->
                ConnectionHistoryGroup(
                    process = row.process,
                    status = row.status,
                    proxy = row.proxy,
                    totalCount = row.totalCount,
                    totalUpload = row.totalUpload,
                    totalDownload = row.totalDownload,
                    oldestUpdatedAt = row.oldestUpdatedAt
                )
            }
        )
    }

    suspend fun queryPage(
        offset: Int,
        limit: Int,
        process: String,
        proxy: String,
        includeClosed: Boolean,
        includeFailed: Boolean
    ): ConnectionHistoryPage {
        val sessionId = dao.queryActiveSession()?.id ?: return ConnectionHistoryPage()
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val records = dao.queryHistoryPage(
            sessionId,
            offset.coerceAtLeast(0),
            safeLimit,
            process,
            proxy,
            includeClosed,
            includeFailed
        )
        val total = dao.countHistory(sessionId, process, proxy, includeClosed, includeFailed)
        val nextOffset = offset.coerceAtLeast(0) + records.size
        val closed = mutableListOf<Connection>()
        val failed = mutableListOf<FailedConnection>()
        val closedAt = mutableMapOf<String, Long>()

        records.forEach { record ->
            try {
                if (record.status == ConnectionHistory.STATUS_FAILED) {
                    failed.add(json.decodeFromString(FailedConnection.serializer(), record.payload))
                } else {
                    closed.add(json.decodeFromString(Connection.serializer(), record.payload))
                    closedAt[record.id] = record.updatedAt
                }
            } catch (_: Exception) {
                // A malformed historical row should not block the remaining page.
            }
        }

        return ConnectionHistoryPage(
            closedConnections = closed,
            failedConnections = failed,
            closedAt = closedAt,
            nextOffset = nextOffset,
            hasMore = nextOffset < total,
            totalCount = total
        )
    }

    suspend fun clearActiveSession() {
        mutationMutex.withLock {
            dao.queryActiveSession()?.let { dao.clearSession(it.id) }
            activeSessionIdCache = null
            resetProcessTrafficCache()
            generationCounter.incrementAndGet()
            revisionCounter.incrementAndGet()
        }
    }

    suspend fun endActiveSession() {
        mutationMutex.withLock {
            dao.queryActiveSession()?.let { dao.endSession(it.id, System.currentTimeMillis()) }
            activeSessionIdCache = null
            resetProcessTrafficCache()
        }
    }

    private suspend fun loadProcessTrafficCache(sessionId: String): MutableMap<String, ProcessTraffic> {
        if (processTrafficSessionId != sessionId) {
            processTrafficCache.clear()
            dao.queryProcessTraffic(sessionId).associateTo(processTrafficCache) { row ->
                row.process to ProcessTraffic(upload = row.upload, download = row.download)
            }
            processTrafficSessionId = sessionId
        }
        return processTrafficCache
    }

    private fun resetProcessTrafficCache() {
        processTrafficSessionId = null
        processTrafficCache.clear()
    }

    private fun Connection.toHistory(sessionId: String, status: String, now: Long): ConnectionHistory {
        return ConnectionHistory(
            sessionId = sessionId,
            id = id,
            status = status,
            process = metadata.process?.substringBefore(":").orEmpty().ifBlank { "Unknown" },
            upload = upload,
            download = download,
            payload = json.encodeToString(Connection.serializer(), this),
            updatedAt = now
        )
    }

    private fun FailedConnection.toHistory(sessionId: String, now: Long): ConnectionHistory {
        return ConnectionHistory(
            sessionId = sessionId,
            id = id,
            status = ConnectionHistory.STATUS_FAILED,
            process = metadata.process?.substringBefore(":").orEmpty().ifBlank { "Unknown" },
            upload = 0,
            download = 0,
            payload = json.encodeToString(FailedConnection.serializer(), this),
            updatedAt = now
        )
    }

    private fun Connection.toHistoryProxies(sessionId: String): List<ConnectionHistoryProxy> {
        return buildSet {
            addAll(chains.orEmpty().filter { it.isNotBlank() })
            metadata.specialProxy.takeIf { it.isNotBlank() }?.let(::add)
        }.map { proxy -> ConnectionHistoryProxy(sessionId, id, proxy) }
    }

    private fun FailedConnection.toHistoryProxies(sessionId: String): List<ConnectionHistoryProxy> {
        return buildSet {
            addAll(chains.orEmpty().filter { it.isNotBlank() })
            proxy.takeIf { it.isNotBlank() }?.let(::add)
            metadata.specialProxy.takeIf { it.isNotBlank() }?.let(::add)
        }.map { proxyName -> ConnectionHistoryProxy(sessionId, id, proxyName) }
    }

    private fun counterDelta(current: Long, previous: Long?): Long {
        if (previous == null) return current.coerceAtLeast(0L)
        return if (current >= previous) current - previous else current.coerceAtLeast(0L)
    }

    private const val WRITE_BATCH_SIZE = 100
    private const val MAX_PAGE_SIZE = 200
}
