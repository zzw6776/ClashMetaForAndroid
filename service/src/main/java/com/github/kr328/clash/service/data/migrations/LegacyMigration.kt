@file:Suppress("BlockingMethodInNonBlockingContext")

package com.github.kr328.clash.service.data.migrations

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.text.isDigitsOnly
import androidx.room.withTransaction
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.profileFileLock
import com.github.kr328.clash.service.util.PreparedDirectoryReplacement
import com.github.kr328.clash.service.util.createNewFileChecked
import com.github.kr328.clash.service.util.deleteRecursivelyChecked
import com.github.kr328.clash.service.util.mkdirsChecked
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.UUID

private data class LegacyProfileMigration(
    val pending: Pending,
    val configurationSource: File?,
)

private data class LegacyMigrationPlan(
    val profiles: List<LegacyProfileMigration>,
    val obsoleteFiles: List<File>,
)

internal suspend fun migrationFromLegacy(context: Context) {
    val legacyDatabase = context.getDatabasePath(LEGACY_DATABASE_NAME)
    val installedMarker = context.filesDir.resolve(LEGACY_INSTALLED_MARKER)
    if (!legacyDatabase.exists()) {
        deleteObsoleteFile(installedMarker)
        return
    }

    if (installedMarker.isFile) {
        val deleted = context.deleteDatabase(LEGACY_DATABASE_NAME)
        if (!deleted && legacyDatabase.exists()) {
            Log.w("Legacy profiles are installed, but the old database still could not be removed")
            return
        }
        deleteObsoleteFile(installedMarker)
        Log.i("Removed legacy database left by a previous completed migration")
        return
    }

    Log.i("Migration from legacy database")

    try {
        val plan = SQLiteDatabase.openDatabase(
            legacyDatabase.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            val version = database.version
            Log.i("Legacy database version = $version")

            when (version) {
                1 -> readLegacyVersion1(context, database)
                2, 3, 4 -> readLegacyVersion234(context, database, version)
                else -> throw IllegalStateException("Unsupported legacy database version $version")
            }
        }

        val deleted = profileFileLock.withLock {
            installLegacyProfiles(context, plan.profiles)
            installedMarker.createNewFileChecked()
            context.deleteDatabase(LEGACY_DATABASE_NAME)
        }
        if (!deleted && legacyDatabase.exists()) {
            Log.w("Legacy profiles were copied, but the old database could not be removed; cleanup will retry")
            return
        }

        deleteObsoleteFile(installedMarker)
        plan.obsoleteFiles.forEach(::deleteObsoleteFile)
        plan.profiles.forEach { migration ->
            context.sendProfileChanged(migration.pending.uuid)
            Log.i("${migration.pending.name} migrated")
        }
        Log.i("Legacy database migrated")
    } catch (e: Exception) {
        Log.w("Migration legacy database: $e", e)
    }
}

private fun readLegacyVersion234(
    context: Context,
    legacy: SQLiteDatabase,
    version: Int,
): LegacyMigrationPlan {
    val profiles = mutableListOf<LegacyProfileMigration>()
    legacy.query(
        "profiles",
        arrayOf("id", "name", "type", "uri", if (version == 2) "update_interval" else "interval"),
        null,
        null,
        null,
        null,
        "id",
    ).use { cursor ->
        val id = cursor.getColumnIndexOrThrow("id")
        val name = cursor.getColumnIndexOrThrow("name")
        val type = cursor.getColumnIndexOrThrow("type")
        val uri = cursor.getColumnIndexOrThrow("uri")
        val interval = cursor.getColumnIndexOrThrow(if (version == 2) "update_interval" else "interval")

        while (cursor.moveToNext()) {
            val idValue = cursor.getInt(id)
            val newType = when (val legacyType = cursor.getInt(type)) {
                1 -> Profile.Type.File
                2 -> Profile.Type.Url
                3 -> Profile.Type.External
                else -> throw IllegalStateException("Unsupported legacy profile type $legacyType for id $idValue")
            }
            val configurationSource = if (newType == Profile.Type.File) {
                context.filesDir.resolve("profiles/$idValue.yaml").also {
                    if (!it.isFile) {
                        throw FileNotFoundException("Legacy profile configuration does not exist: $it")
                    }
                }
            } else {
                null
            }

            profiles += LegacyProfileMigration(
                pending = Pending(
                    uuid = legacyProfileUUID(version, idValue.toString()),
                    name = cursor.getString(name)
                        ?: throw IllegalStateException("Legacy profile $idValue has no name"),
                    type = newType,
                    source = if (newType != Profile.Type.File) cursor.getString(uri).orEmpty() else "",
                    interval = cursor.getLong(interval).let {
                        if (version == 2) it * 1000 else it
                    },
                    upload = 0,
                    download = 0,
                    total = 0,
                    expire = 0,
                ),
                configurationSource = configurationSource,
            )
        }
    }

    val obsoleteFiles = buildList {
        add(context.filesDir.resolve("profiles"))
        context.filesDir.resolve("clash").listFiles()?.forEach {
            if (it.name.isDigitsOnly()) add(it)
        }
    }
    return LegacyMigrationPlan(profiles, obsoleteFiles)
}

private fun readLegacyVersion1(
    context: Context,
    legacy: SQLiteDatabase,
): LegacyMigrationPlan {
    val profiles = mutableListOf<LegacyProfileMigration>()
    val obsoleteFiles = mutableListOf<File>()
    legacy.query(
        "profiles",
        arrayOf("name", "token", "id", "file"),
        null,
        null,
        null,
        null,
        "id",
    ).use { cursor ->
        val id = cursor.getColumnIndexOrThrow("id")
        val name = cursor.getColumnIndexOrThrow("name")
        val token = cursor.getColumnIndexOrThrow("token")
        val file = cursor.getColumnIndexOrThrow("file")

        while (cursor.moveToNext()) {
            val idValue = cursor.getString(id)
                ?: throw IllegalStateException("Legacy profile has no id")
            val legacyToken = cursor.getString(token)
                ?: throw IllegalStateException("Legacy profile $idValue has no token")
            val newType = when {
                legacyToken.startsWith("file|") -> Profile.Type.File
                legacyToken.startsWith("url|") -> Profile.Type.Url
                else -> throw IllegalStateException("Unsupported legacy profile token for id $idValue")
            }
            val legacyFile = cursor.getString(file)?.let(::File)
            val configurationSource = if (newType == Profile.Type.File) {
                legacyFile?.takeIf(File::isFile)
                    ?: throw FileNotFoundException("Legacy profile configuration does not exist: $legacyFile")
            } else {
                null
            }

            profiles += LegacyProfileMigration(
                pending = Pending(
                    uuid = legacyProfileUUID(1, idValue),
                    name = cursor.getString(name)
                        ?: throw IllegalStateException("Legacy profile $idValue has no name"),
                    type = newType,
                    source = if (newType == Profile.Type.Url) legacyToken.removePrefix("url|") else "",
                    interval = 0,
                    upload = 0,
                    download = 0,
                    total = 0,
                    expire = 0,
                ),
                configurationSource = configurationSource,
            )
            legacyFile?.let(obsoleteFiles::add)
        }
    }

    return LegacyMigrationPlan(profiles, obsoleteFiles)
}

private suspend fun installLegacyProfiles(
    context: Context,
    profiles: List<LegacyProfileMigration>,
) {
    val replacements = profiles.map { migration ->
        val target = context.pendingDir.resolve(migration.pending.uuid.toString())
        PreparedDirectoryReplacement.create(target) { staging ->
            val configuration = staging.resolve("config.yaml")
            val source = migration.configurationSource
            if (source != null) {
                source.copyTo(configuration, overwrite = false)
            } else {
                configuration.createNewFileChecked()
            }
            staging.resolve("providers").mkdirsChecked()
        }
    }

    try {
        val database = Database.database
        database.withTransaction {
            val pendingDao = database.openPendingDao()
            replacements.forEach(PreparedDirectoryReplacement::activate)
            profiles.forEach { pendingDao.insert(it.pending) }
        }
    } catch (e: Throwable) {
        replacements.asReversed().forEach { replacement ->
            try {
                replacement.rollback()
            } catch (rollbackError: Throwable) {
                e.addSuppressed(rollbackError)
            }
        }
        throw e
    }

    replacements.forEach { replacement ->
        replacement.commit()?.let {
            Log.w("Unable to remove legacy profile directory backup", it)
        }
    }
}

private fun deleteObsoleteFile(file: File) {
    try {
        file.deleteRecursivelyChecked()
    } catch (e: Exception) {
        Log.w("Unable to remove obsolete legacy file $file", e)
    }
}

internal fun legacyProfileUUID(version: Int, id: String): UUID {
    val identity = "com.github.kr328.clash.legacy:$version:$id"
    return UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8))
}

private const val LEGACY_DATABASE_NAME = "clash-config"
private const val LEGACY_INSTALLED_MARKER = ".legacy-profile-migration-installed"
