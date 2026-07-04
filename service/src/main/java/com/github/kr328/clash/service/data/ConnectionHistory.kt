package com.github.kr328.clash.service.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "connection_sessions", primaryKeys = ["id"])
data class ConnectionSession(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "startedAt") val startedAt: Long,
    @ColumnInfo(name = "endedAt") val endedAt: Long? = null
)

@Entity(
    tableName = "connection_history",
    primaryKeys = ["sessionId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ConnectionSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId", "status", "updatedAt"]),
        Index(value = ["sessionId", "process"])
    ]
)
data class ConnectionHistory(
    @ColumnInfo(name = "sessionId") val sessionId: String,
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "process") val process: String,
    @ColumnInfo(name = "upload") val upload: Long,
    @ColumnInfo(name = "download") val download: Long,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_CLOSED = "CLOSED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
    }
}

@Entity(
    tableName = "connection_history_proxies",
    primaryKeys = ["sessionId", "id", "proxy"],
    foreignKeys = [
        ForeignKey(
            entity = ConnectionHistory::class,
            parentColumns = ["sessionId", "id"],
            childColumns = ["sessionId", "id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId", "id"]),
        Index(value = ["sessionId", "proxy"])
    ]
)
data class ConnectionHistoryProxy(
    @ColumnInfo(name = "sessionId") val sessionId: String,
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "proxy") val proxy: String
)

data class ConnectionHistoryGroupRow(
    @ColumnInfo(name = "process") val process: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "proxy") val proxy: String,
    @ColumnInfo(name = "totalCount") val totalCount: Int,
    @ColumnInfo(name = "totalUpload") val totalUpload: Long,
    @ColumnInfo(name = "totalDownload") val totalDownload: Long,
    @ColumnInfo(name = "oldestUpdatedAt") val oldestUpdatedAt: Long
)

@Entity(
    tableName = "connection_process_traffic",
    primaryKeys = ["sessionId", "process"],
    foreignKeys = [
        ForeignKey(
            entity = ConnectionSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ConnectionProcessTraffic(
    @ColumnInfo(name = "sessionId") val sessionId: String,
    @ColumnInfo(name = "process") val process: String,
    @ColumnInfo(name = "upload") val upload: Long,
    @ColumnInfo(name = "download") val download: Long,
    @ColumnInfo(name = "updatedAt") val updatedAt: Long
)
