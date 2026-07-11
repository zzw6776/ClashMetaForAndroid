package com.github.kr328.clash.service.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.data.migrations.LEGACY_MIGRATION
import com.github.kr328.clash.service.data.migrations.MIGRATIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.room.Database as DB

@DB(
    version = 6,
    entities = [
        Imported::class,
        Pending::class,
        Selection::class,
        ConnectionSession::class,
        ConnectionHistory::class,
        ConnectionHistoryProxy::class,
        ConnectionProcessTraffic::class
    ],
    exportSchema = false,
)
abstract class Database : RoomDatabase() {
    abstract fun openImportedDao(): ImportedDao
    abstract fun openPendingDao(): PendingDao
    abstract fun openSelectionProxyDao(): SelectionDao
    abstract fun openConnectionHistoryDao(): ConnectionHistoryDao

    companion object {
        val database: Database
            get() = databaseInstance

        private val databaseInstance: Database by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            open(Global.application)
        }

        private fun open(context: Context): Database {
            return Room.databaseBuilder(
                context.applicationContext,
                Database::class.java,
                "profiles"
            ).addMigrations(*MIGRATIONS).build()
        }

        init {
            Global.launch(Dispatchers.IO) {
                LEGACY_MIGRATION(Global.application)
            }
        }
    }
}
