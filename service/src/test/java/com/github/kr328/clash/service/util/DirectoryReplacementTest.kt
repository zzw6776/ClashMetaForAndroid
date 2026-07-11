package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DirectoryReplacementTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commitInstallsCompleteStagingDirectory() {
        val root = temporaryFolder.root
        val source = root.resolve("source").apply {
            resolve("providers").mkdirsChecked()
            resolve("config.yaml").writeText("new")
            resolve("providers/provider.yaml").writeText("provider")
        }
        val target = root.resolve("target").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("old")
        }

        val replacement = PreparedDirectoryReplacement.copyOf(source, target)
        replacement.activate()

        assertEquals("new", target.resolve("config.yaml").readText())
        assertEquals("provider", target.resolve("providers/provider.yaml").readText())
        assertNull(replacement.commit())
        assertEquals("new", source.resolve("config.yaml").readText())
    }

    @Test
    fun rollbackRestoresOriginalDirectory() {
        val root = temporaryFolder.root
        val source = root.resolve("source").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("new")
        }
        val target = root.resolve("target").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("old")
        }

        val replacement = PreparedDirectoryReplacement.copyOf(source, target)
        replacement.activate()
        replacement.rollback()

        assertEquals("old", target.resolve("config.yaml").readText())
    }

    @Test
    fun rollbackBeforeActivationLeavesTargetUntouched() {
        val root = temporaryFolder.root
        val source = root.resolve("source").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("new")
        }
        val target = root.resolve("target").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("old")
        }

        val replacement = PreparedDirectoryReplacement.copyOf(source, target)
        replacement.rollback()

        assertEquals("old", target.resolve("config.yaml").readText())
        assertFalse(root.listFiles().orEmpty().any { it.name.contains(".staging-") })
    }

    @Test
    fun removalRollbackRestoresDirectory() {
        val target = temporaryFolder.root.resolve("target").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("content")
        }
        val removal = PreparedDirectoryRemoval(target)

        removal.activate()
        assertFalse(target.exists())
        removal.rollback()

        assertEquals("content", target.resolve("config.yaml").readText())
    }

    @Test
    fun removalCommitDeletesQuarantinedDirectory() {
        val root = temporaryFolder.root
        val target = root.resolve("target").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("content")
        }
        val removal = PreparedDirectoryRemoval(target)

        removal.activate()
        assertNull(removal.commit())

        assertFalse(target.exists())
        assertFalse(root.listFiles().orEmpty().any { it.name.contains(".deleting-") })
    }

    @Test
    fun missingSourceFailsWithoutChangingTarget() {
        val target = temporaryFolder.root.resolve("target").apply {
            mkdirsChecked()
            resolve("config.yaml").writeText("old")
        }

        assertThrows(IOException::class.java) {
            PreparedDirectoryReplacement.copyOf(temporaryFolder.root.resolve("missing"), target)
        }
        assertEquals("old", target.resolve("config.yaml").readText())
    }

    @Test
    fun renameRejectsExistingTarget() {
        val source = temporaryFolder.newFile("source")
        val target = temporaryFolder.newFile("target")

        assertThrows(IOException::class.java) {
            source.renameToChecked(target)
        }
        assertTrue(source.exists())
        assertTrue(target.exists())
    }
}
