package com.github.kr328.clash.service.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.core.model.FailedConnection
import kotlinx.serialization.json.Json

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE imported ADD COLUMN ageSecretKey TEXT")
        database.execSQL("ALTER TABLE pending ADD COLUMN ageSecretKey TEXT")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS connection_sessions " +
                "(id TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, PRIMARY KEY(id))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS connection_history " +
                "(sessionId TEXT NOT NULL, id TEXT NOT NULL, status TEXT NOT NULL, process TEXT NOT NULL, " +
                "upload INTEGER NOT NULL, download INTEGER NOT NULL, payload TEXT NOT NULL, updatedAt INTEGER NOT NULL, " +
                "PRIMARY KEY(sessionId, id), FOREIGN KEY(sessionId) REFERENCES connection_sessions(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_connection_history_sessionId_status_updatedAt " +
                "ON connection_history(sessionId, status, updatedAt)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_connection_history_sessionId_process " +
                "ON connection_history(sessionId, process)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS connection_process_traffic " +
                "(sessionId TEXT NOT NULL, process TEXT NOT NULL, upload INTEGER NOT NULL, " +
                "download INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(sessionId, process), " +
                "FOREIGN KEY(sessionId) REFERENCES connection_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS connection_history_proxies " +
                "(sessionId TEXT NOT NULL, id TEXT NOT NULL, proxy TEXT NOT NULL, " +
                "PRIMARY KEY(sessionId, id, proxy), " +
                "FOREIGN KEY(sessionId, id) REFERENCES connection_history(sessionId, id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_connection_history_proxies_sessionId_id " +
                "ON connection_history_proxies(sessionId, id)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_connection_history_proxies_sessionId_proxy " +
                "ON connection_history_proxies(sessionId, proxy)"
        )
        backfillConnectionHistoryProxies(database)
    }
}

private fun backfillConnectionHistoryProxies(database: SupportSQLiteDatabase) {
    val json = Json { ignoreUnknownKeys = true }
    val insert = database.compileStatement(
        "INSERT OR IGNORE INTO connection_history_proxies(sessionId, id, proxy) VALUES (?, ?, ?)"
    )
    database.query("SELECT sessionId, id, status, payload FROM connection_history").use { cursor ->
        while (cursor.moveToNext()) {
            val sessionId = cursor.getString(0)
            val id = cursor.getString(1)
            val status = cursor.getString(2)
            val payload = cursor.getString(3)
            val proxies = runCatching {
                if (status == "FAILED") {
                    val failed = json.decodeFromString(FailedConnection.serializer(), payload)
                    buildSet {
                        addAll(failed.chains.orEmpty().filter { it.isNotBlank() })
                        failed.proxy.takeIf { it.isNotBlank() }?.let(::add)
                        failed.metadata.specialProxy.takeIf { it.isNotBlank() }?.let(::add)
                    }
                } else {
                    val connection = json.decodeFromString(Connection.serializer(), payload)
                    buildSet {
                        addAll(connection.chains.orEmpty().filter { it.isNotBlank() })
                        connection.metadata.specialProxy.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.getOrDefault(emptySet())

            proxies.forEach { proxy ->
                insert.clearBindings()
                insert.bindString(1, sessionId)
                insert.bindString(2, id)
                insert.bindString(3, proxy)
                insert.executeInsert()
            }
        }
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
)

val LEGACY_MIGRATION = ::migrationFromLegacy
