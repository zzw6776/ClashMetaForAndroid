package com.github.kr328.clash.service.data.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LegacyMigrationTest {
    @Test
    fun legacyUuidIsStableAcrossRetries() {
        assertEquals(
            legacyProfileUUID(4, "42"),
            legacyProfileUUID(4, "42"),
        )
    }

    @Test
    fun legacyUuidSeparatesVersionsAndRows() {
        assertNotEquals(legacyProfileUUID(3, "42"), legacyProfileUUID(4, "42"))
        assertNotEquals(legacyProfileUUID(4, "41"), legacyProfileUUID(4, "42"))
    }
}
