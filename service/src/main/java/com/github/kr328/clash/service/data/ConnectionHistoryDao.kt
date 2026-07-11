package com.github.kr328.clash.service.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ConnectionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ConnectionSession)

    @Query("SELECT * FROM connection_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun queryActiveSession(): ConnectionSession?

    @Query("SELECT * FROM connection_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun querySession(sessionId: String): ConnectionSession?

    @Query("UPDATE connection_sessions SET endedAt = :endedAt WHERE id = :sessionId")
    suspend fun endSession(sessionId: String, endedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(records: List<ConnectionHistory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryProxies(records: List<ConnectionHistoryProxy>)

    @Transaction
    suspend fun upsertHistoryWithProxies(
        records: List<ConnectionHistory>,
        proxies: List<ConnectionHistoryProxy>
    ) {
        upsertHistory(records)
        if (proxies.isNotEmpty()) upsertHistoryProxies(proxies)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProcessTraffic(records: List<ConnectionProcessTraffic>)

    @Query("SELECT * FROM connection_process_traffic WHERE sessionId = :sessionId")
    suspend fun queryProcessTraffic(sessionId: String): List<ConnectionProcessTraffic>

    @Query(
        "SELECT * FROM connection_history " +
            "WHERE sessionId = :sessionId " +
            "AND ((:includeClosed AND status IN ('CLOSED', 'INTERRUPTED')) " +
            "OR (:includeFailed AND status = 'FAILED')) " +
            "AND (:process = '' OR process = :process) " +
            "AND (:proxy = '' OR EXISTS (SELECT 1 FROM connection_history_proxies p " +
            "WHERE p.sessionId = connection_history.sessionId " +
            "AND p.id = connection_history.id AND p.proxy = :proxy)) " +
            "ORDER BY updatedAt ASC, id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun queryHistoryPage(
        sessionId: String,
        offset: Int,
        limit: Int,
        process: String,
        proxy: String,
        includeClosed: Boolean,
        includeFailed: Boolean
    ): List<ConnectionHistory>

    @Query(
        "SELECT COUNT(*) FROM connection_history " +
            "WHERE sessionId = :sessionId " +
            "AND ((:includeClosed AND status IN ('CLOSED', 'INTERRUPTED')) " +
            "OR (:includeFailed AND status = 'FAILED')) " +
            "AND (:process = '' OR process = :process) " +
            "AND (:proxy = '' OR EXISTS (SELECT 1 FROM connection_history_proxies p " +
            "WHERE p.sessionId = connection_history.sessionId " +
            "AND p.id = connection_history.id AND p.proxy = :proxy))"
    )
    suspend fun countHistory(
        sessionId: String,
        process: String,
        proxy: String,
        includeClosed: Boolean,
        includeFailed: Boolean
    ): Int

    @Query(
        "SELECT h.process AS process, h.status AS status, '' AS proxy, " +
            "COUNT(*) AS totalCount, SUM(h.upload) AS totalUpload, " +
            "SUM(h.download) AS totalDownload, MIN(h.updatedAt) AS oldestUpdatedAt " +
            "FROM connection_history h " +
            "WHERE h.sessionId = :sessionId AND h.status != 'ACTIVE' " +
            "GROUP BY h.process, h.status " +
            "UNION ALL " +
            "SELECT h.process AS process, h.status AS status, p.proxy AS proxy, " +
            "COUNT(*) AS totalCount, SUM(h.upload) AS totalUpload, " +
            "SUM(h.download) AS totalDownload, MIN(h.updatedAt) AS oldestUpdatedAt " +
            "FROM connection_history h " +
            "INNER JOIN connection_history_proxies p " +
            "ON p.sessionId = h.sessionId AND p.id = h.id " +
            "WHERE h.sessionId = :sessionId AND h.status != 'ACTIVE' " +
            "GROUP BY h.process, h.status, p.proxy"
    )
    suspend fun queryHistoryGroups(sessionId: String): List<ConnectionHistoryGroupRow>

    @Query(
        "UPDATE connection_history SET status = 'INTERRUPTED', updatedAt = :interruptedAt " +
            "WHERE sessionId = :sessionId AND status = 'ACTIVE'"
    )
    suspend fun markActiveInterrupted(sessionId: String, interruptedAt: Long)

    @Query("SELECT id FROM connection_history WHERE sessionId = :sessionId AND status = 'ACTIVE'")
    suspend fun queryActiveHistoryIds(sessionId: String): List<String>

    @Query(
        "SELECT id FROM connection_history WHERE sessionId = :sessionId " +
            "AND status != 'ACTIVE' AND id IN (:connectionIds)"
    )
    suspend fun queryTerminalHistoryIds(sessionId: String, connectionIds: List<String>): List<String>

    @Query(
        "UPDATE connection_history SET status = 'INTERRUPTED', updatedAt = :interruptedAt " +
            "WHERE sessionId = :sessionId AND status = 'ACTIVE' AND id IN (:connectionIds)"
    )
    suspend fun markActiveInterrupted(
        sessionId: String,
        connectionIds: List<String>,
        interruptedAt: Long
    )

    @Query("DELETE FROM connection_history WHERE sessionId = :sessionId")
    suspend fun deleteHistory(sessionId: String)

    @Query("DELETE FROM connection_process_traffic WHERE sessionId = :sessionId")
    suspend fun deleteProcessTraffic(sessionId: String)

    @Query("DELETE FROM connection_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM connection_history")
    suspend fun deleteAllHistory()

    @Query("DELETE FROM connection_process_traffic")
    suspend fun deleteAllProcessTraffic()

    @Query("DELETE FROM connection_sessions")
    suspend fun deleteAllSessions()

    @Query(
        "DELETE FROM connection_history WHERE sessionId = :sessionId " +
            "AND status != 'ACTIVE' AND updatedAt < :cutoff"
    )
    suspend fun deleteTerminalHistoryOlderThan(sessionId: String, cutoff: Long): Int

    @Query(
        "DELETE FROM connection_history WHERE sessionId = :sessionId " +
            "AND status != 'ACTIVE' AND id IN (" +
            "SELECT id FROM connection_history WHERE sessionId = :sessionId " +
            "AND status != 'ACTIVE' ORDER BY updatedAt DESC, id DESC " +
            "LIMIT -1 OFFSET :maximumRows)"
    )
    suspend fun deleteTerminalHistoryBeyondLimit(sessionId: String, maximumRows: Int): Int

    @Transaction
    suspend fun pruneTerminalHistory(sessionId: String, cutoff: Long, maximumRows: Int): Int {
        return deleteTerminalHistoryOlderThan(sessionId, cutoff) +
            deleteTerminalHistoryBeyondLimit(sessionId, maximumRows)
    }

    @Transaction
    suspend fun clearSession(sessionId: String) {
        deleteHistory(sessionId)
        deleteProcessTraffic(sessionId)
        deleteSession(sessionId)
    }

    @Transaction
    suspend fun clearAll() {
        deleteAllHistory()
        deleteAllProcessTraffic()
        deleteAllSessions()
    }

    @Transaction
    suspend fun checkpointActive(
        sessionId: String,
        records: List<ConnectionHistory>,
        proxies: List<ConnectionHistoryProxy>,
        checkpointAt: Long
    ): Boolean {
        val activeIds = records.mapTo(HashSet(records.size)) { it.id }
        val interruptedIds = queryActiveHistoryIds(sessionId).filterNot(activeIds::contains)
        interruptedIds.chunked(SQLITE_ID_BATCH_SIZE).forEach { batch ->
            markActiveInterrupted(sessionId, batch, checkpointAt)
        }
        if (records.isNotEmpty()) {
            val terminalIds = records
                .map { it.id }
                .chunked(SQLITE_ID_BATCH_SIZE)
                .flatMapTo(HashSet()) { batch -> queryTerminalHistoryIds(sessionId, batch) }
            val activeRecords = records.filterNot { it.id in terminalIds }
            val activeRecordIds = activeRecords.mapTo(HashSet(activeRecords.size)) { it.id }
            if (activeRecords.isNotEmpty()) {
                upsertHistory(activeRecords)
                val activeProxies = proxies.filter { it.id in activeRecordIds }
                if (activeProxies.isNotEmpty()) upsertHistoryProxies(activeProxies)
            }
        }
        return interruptedIds.isNotEmpty()
    }

    @Transaction
    suspend fun replaceAllWithSession(session: ConnectionSession) {
        clearAll()
        upsertSession(session)
    }

    companion object {
        private const val SQLITE_ID_BATCH_SIZE = 900
    }
}
