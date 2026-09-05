package com.example.pagingdrhoward

import com.example.pagingdrhoward.util.AppUpdateManager
import org.junit.Assert.*
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `test parseBuildNumber parses various GitHub tag formats`() {
        assertEquals(1020, AppUpdateManager.parseBuildNumber("v1.0.0.1020"))
        assertEquals(1020, AppUpdateManager.parseBuildNumber("1.0.0.1020"))
        assertEquals(1025, AppUpdateManager.parseBuildNumber("v1025"))
        assertEquals(1030, AppUpdateManager.parseBuildNumber("1030"))
        assertEquals(0, AppUpdateManager.parseBuildNumber("invalid_tag"))
    }
}
