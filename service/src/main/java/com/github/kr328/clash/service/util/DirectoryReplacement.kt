package com.github.kr328.clash.service.util

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Prepares a complete directory next to [target], then switches it into place with renames.
 *
 * Callers should activate the replacement inside the same database transaction that records
 * the new directory state. If that transaction fails, [rollback] restores the original target.
 */
internal class PreparedDirectoryReplacement private constructor(
    private val target: File,
    private val staging: File,
    private val backup: File,
) {
    private enum class State {
        Prepared,
        Active,
        Committed,
        RolledBack,
    }

    private var state = State.Prepared
    private var hadOriginal = false

    fun activate() {
        check(state == State.Prepared) { "directory replacement is not prepared" }

        hadOriginal = target.exists()
        if (hadOriginal) {
            moveChecked(target, backup)
        }

        try {
            moveChecked(staging, target)
            state = State.Active
        } catch (activateError: Throwable) {
            if (hadOriginal && backup.exists() && !target.exists()) {
                try {
                    moveChecked(backup, target)
                } catch (restoreError: Throwable) {
                    activateError.addSuppressed(restoreError)
                }
            }
            throw activateError
        }
    }

    /**
     * Marks the replacement as committed. A cleanup failure does not invalidate the installed
     * target, so it is returned to the caller for logging instead of triggering a rollback.
     */
    fun commit(): IOException? {
        check(state == State.Active) { "directory replacement is not active" }
        state = State.Committed

        return runCatching {
            backup.deleteRecursivelyChecked()
        }.exceptionOrNull()?.let {
            it as? IOException ?: IOException("unable to remove directory backup $backup", it)
        }
    }

    fun rollback() {
        when (state) {
            State.Prepared -> {
                staging.deleteRecursivelyChecked()
                state = State.RolledBack
            }

            State.Active -> {
                target.deleteRecursivelyChecked()
                if (hadOriginal) {
                    moveChecked(backup, target)
                }
                state = State.RolledBack
            }

            State.Committed, State.RolledBack -> Unit
        }
    }

    companion object {
        fun copyOf(source: File, target: File): PreparedDirectoryReplacement {
            if (!source.isDirectory) {
                throw IOException("source directory does not exist: $source")
            }

            return prepare(target) { staging ->
                val copied = source.copyRecursively(
                    target = staging,
                    overwrite = false,
                    onError = { _, exception -> throw exception },
                )
                if (!copied) {
                    throw IOException("unable to copy directory $source to $staging")
                }
            }
        }

        fun create(
            target: File,
            populate: (File) -> Unit,
        ): PreparedDirectoryReplacement {
            return prepare(target) { staging ->
                staging.mkdirsChecked()
                populate(staging)
            }
        }

        private fun prepare(
            target: File,
            populate: (File) -> Unit,
        ): PreparedDirectoryReplacement {
            val parent = target.parentFile
                ?: throw IOException("target directory has no parent: $target")
            parent.mkdirsChecked()

            val suffix = UUID.randomUUID().toString()
            val staging = parent.resolve(".${target.name}.staging-$suffix")
            val backup = parent.resolve(".${target.name}.backup-$suffix")

            try {
                populate(staging)
                if (!staging.isDirectory) {
                    throw IOException("staging directory was not created: $staging")
                }
            } catch (prepareError: Throwable) {
                try {
                    staging.deleteRecursivelyChecked()
                } catch (cleanupError: Throwable) {
                    prepareError.addSuppressed(cleanupError)
                }
                throw prepareError
            }

            return PreparedDirectoryReplacement(target, staging, backup)
        }
    }
}

/** Moves a directory out of its live path before the matching database row is removed. */
internal class PreparedDirectoryRemoval(
    private val target: File,
) {
    private enum class State {
        Prepared,
        Active,
        Committed,
        RolledBack,
    }

    private val quarantine = target.parentFile?.resolve(
        ".${target.name}.deleting-${UUID.randomUUID()}",
    ) ?: throw IOException("target directory has no parent: $target")
    private var state = State.Prepared
    private var existed = false

    fun activate() {
        check(state == State.Prepared) { "directory removal is not prepared" }
        existed = target.exists()
        if (existed) {
            target.parentFile!!.mkdirsChecked()
            moveChecked(target, quarantine)
        }
        state = State.Active
    }

    fun commit(): IOException? {
        check(state == State.Active) { "directory removal is not active" }
        state = State.Committed
        return runCatching {
            quarantine.deleteRecursivelyChecked()
        }.exceptionOrNull()?.let {
            it as? IOException ?: IOException("unable to remove quarantined directory $quarantine", it)
        }
    }

    fun rollback() {
        when (state) {
            State.Prepared -> state = State.RolledBack
            State.Active -> {
                if (existed && quarantine.exists()) {
                    if (target.exists()) {
                        throw IOException("unable to restore $target because it already exists")
                    }
                    moveChecked(quarantine, target)
                }
                state = State.RolledBack
            }

            State.Committed, State.RolledBack -> Unit
        }
    }
}

internal fun File.deleteRecursivelyChecked() {
    if (exists() && !deleteRecursively()) {
        throw IOException("unable to delete $this")
    }
}

internal fun File.mkdirsChecked() {
    if (!isDirectory && !mkdirs()) {
        throw IOException("unable to create directory $this")
    }
}

internal fun File.createNewFileChecked() {
    if (!createNewFile()) {
        throw IOException("unable to create file $this")
    }
}

internal fun File.renameToChecked(target: File) {
    if (absoluteFile == target.absoluteFile) {
        return
    }
    if (target.exists()) {
        throw IOException("rename target already exists: $target")
    }
    if (!renameTo(target)) {
        throw IOException("unable to rename $this to $target")
    }
}

private fun moveChecked(source: File, target: File) {
    if (!source.exists()) {
        throw IOException("source does not exist: $source")
    }
    if (target.exists()) {
        throw IOException("move target already exists: $target")
    }
    if (!source.renameTo(target)) {
        throw IOException("unable to move $source to $target")
    }
}
