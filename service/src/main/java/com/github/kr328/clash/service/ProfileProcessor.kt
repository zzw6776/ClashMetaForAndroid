package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.PreparedDirectoryRemoval
import com.github.kr328.clash.service.util.PreparedDirectoryReplacement
import com.github.kr328.clash.service.util.deleteRecursivelyChecked
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock process@{
                val snapshot = profileFileLock.withLock {
                    val pending =
                        PendingDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    replaceWorkingDirectory(
                        source = context.pendingDir.resolve(pending.uuid.toString()),
                        target = context.processingDir,
                    )

                    pending
                }

                val force = snapshot.type != Profile.Type.File
                val subscriptionInfo = fetchProfile(
                    context,
                    snapshot.source,
                    snapshot.ageSecretKey,
                    force,
                    snapshot.type == Profile.Type.File,
                    callback
                )

                val target = context.importedDir.resolve(snapshot.uuid.toString())
                val replacement = PreparedDirectoryReplacement.copyOf(context.processingDir, target)
                val applied = profileFileLock.withLock {
                    val installed = try {
                        val database = Database.database
                        val importedDao = database.openImportedDao()
                        val pendingDao = database.openPendingDao()

                        database.withTransaction {
                            if (pendingDao.queryByUUID(snapshot.uuid) != snapshot) {
                                return@withTransaction false
                            }

                            replacement.activate()

                            val old = importedDao.queryByUUID(snapshot.uuid)
                            val updateInterval = subscriptionInfo?.subUpdateInterval
                                ?.takeIf { old == null && snapshot.interval == 0L }
                                ?: snapshot.interval
                            val new = Imported(
                                snapshot.uuid,
                                snapshot.name,
                                snapshot.type,
                                snapshot.source,
                                updateInterval,
                                subscriptionInfo?.subUpload ?: 0,
                                subscriptionInfo?.subDownload ?: 0,
                                subscriptionInfo?.subTotal ?: 0,
                                subscriptionInfo?.subExpire ?: 0,
                                old?.createdAt ?: System.currentTimeMillis(),
                                ageSecretKey = snapshot.ageSecretKey,
                            )
                            if (old != null) {
                                importedDao.update(new)
                            } else {
                                importedDao.insert(new)
                            }

                            pendingDao.remove(snapshot.uuid)
                            true
                        }
                    } catch (e: Throwable) {
                        replacement.rollbackAfter(e)
                        throw e
                    }

                    if (!installed) {
                        replacement.rollback()
                        false
                    } else {
                        replacement.commitAndLog(target)
                        deleteCleanupDirectory(context.pendingDir.resolve(snapshot.uuid.toString()))
                        true
                    }
                }

                if (!applied) return@process
                context.sendProfileChanged(snapshot.uuid)
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withContext(NonCancellable) {
            processLock.withLock process@{
                val snapshot = profileFileLock.withLock {
                    val imported =
                        ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    replaceWorkingDirectory(
                        source = context.importedDir.resolve(imported.uuid.toString()),
                        target = context.processingDir,
                    )

                    imported
                }

                val subscriptionInfo = fetchProfile(
                    context,
                    snapshot.source,
                    snapshot.ageSecretKey,
                    true,
                    snapshot.type == Profile.Type.File,
                    callback
                )

                val target = context.importedDir.resolve(snapshot.uuid.toString())
                val replacement = PreparedDirectoryReplacement.copyOf(context.processingDir, target)
                val updated = profileFileLock.withLock {
                    val installed = try {
                        val database = Database.database
                        val importedDao = database.openImportedDao()

                        database.withTransaction {
                            val imported = importedDao.queryByUUID(snapshot.uuid)
                                ?: return@withTransaction false

                            replacement.activate()

                            val upload = subscriptionInfo?.subUpload
                            if (upload != null) {
                                importedDao.update(
                                    imported.copy(
                                        upload = upload,
                                        download = subscriptionInfo.subDownload ?: 0,
                                        total = subscriptionInfo.subTotal ?: 0,
                                        expire = subscriptionInfo.subExpire ?: 0,
                                    )
                                )
                            }
                            true
                        }
                    } catch (e: Throwable) {
                        replacement.rollbackAfter(e)
                        throw e
                    }

                    if (!installed) {
                        replacement.rollback()
                        false
                    } else {
                        replacement.commitAndLog(target)
                        true
                    }
                }

                if (!updated) return@process
                context.sendProfileChanged(snapshot.uuid)
            }
        }
    }

    private suspend fun fetchProfile(
        context: Context,
        source: String,
        ageSecretKey: String?,
        force: Boolean,
        allowConfigInbounds: Boolean,
        callback: IFetchObserver?,
    ): FetchStatus? {
        var subscriptionInfo: FetchStatus? = null
        var cb = callback

        Clash.fetchAndValid(
            context.processingDir,
            source,
            force,
            ageSecretKey,
            allowConfigInbounds,
        ) {
            if (it.action == FetchStatus.Action.SubscriptionInfo) {
                subscriptionInfo = it
                return@fetchAndValid
            }

            try {
                cb?.updateStatus(it)
            } catch (e: Exception) {
                cb = null

                Log.w("Report fetch status: $e", e)
            }
        }.await()

        return subscriptionInfo
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            val removals = listOf(
                PreparedDirectoryRemoval(context.pendingDir.resolve(uuid.toString())),
                PreparedDirectoryRemoval(context.importedDir.resolve(uuid.toString())),
            )
            try {
                profileFileLock.withLock {
                    val database = Database.database
                    database.withTransaction {
                        removals.forEach(PreparedDirectoryRemoval::activate)
                        database.openImportedDao().remove(uuid)
                        database.openPendingDao().remove(uuid)
                    }
                }
            } catch (e: Throwable) {
                removals.asReversed().forEach { it.rollbackAfter(e) }
                throw e
            }

            removals.forEach { it.commitAndLog(uuid) }
            context.sendProfileChanged(uuid)
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            val removal = PreparedDirectoryRemoval(context.pendingDir.resolve(uuid.toString()))
            try {
                profileFileLock.withLock {
                    val database = Database.database
                    database.withTransaction {
                        removal.activate()
                        database.openPendingDao().remove(uuid)
                    }
                }
            } catch (e: Throwable) {
                removal.rollbackAfter(e)
                throw e
            }

            removal.commitAndLog(uuid)
            true
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileFileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" -> throw IllegalArgumentException(
                "Unsupported url $source"
            )

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }

    private fun replaceWorkingDirectory(source: java.io.File, target: java.io.File) {
        val replacement = PreparedDirectoryReplacement.copyOf(source, target)
        try {
            replacement.activate()
        } catch (e: Throwable) {
            replacement.rollbackAfter(e)
            throw e
        }
        replacement.commitAndLog(target)
    }

    private fun PreparedDirectoryReplacement.commitAndLog(target: java.io.File) {
        commit()?.let {
            Log.w("Unable to remove replaced directory backup for $target", it)
        }
    }

    private fun PreparedDirectoryReplacement.rollbackAfter(cause: Throwable) {
        try {
            rollback()
        } catch (rollbackError: Throwable) {
            cause.addSuppressed(rollbackError)
        }
    }

    private fun PreparedDirectoryRemoval.commitAndLog(uuid: UUID) {
        commit()?.let {
            Log.w("Unable to remove deleted profile directory for $uuid", it)
        }
    }

    private fun PreparedDirectoryRemoval.rollbackAfter(cause: Throwable) {
        try {
            rollback()
        } catch (rollbackError: Throwable) {
            cause.addSuppressed(rollbackError)
        }
    }

    private fun deleteCleanupDirectory(directory: java.io.File) {
        try {
            directory.deleteRecursivelyChecked()
        } catch (e: Exception) {
            Log.w("Unable to remove obsolete profile directory $directory", e)
        }
    }

}
