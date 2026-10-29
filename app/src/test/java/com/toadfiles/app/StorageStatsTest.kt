package com.toadfiles.app

import com.toadfiles.app.domain.StorageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageStatsTest {

    @Test
    fun `usedBytes is total minus free`() {
        val s = StorageStats(totalBytes = 1000, freeBytes = 250)
        assertEquals(750L, s.usedBytes)
    }

    @Test
    fun `usedBytes never goes negative when free exceeds total`() {
        val s = StorageStats(totalBytes = 100, freeBytes = 500)
        assertEquals(0L, s.usedBytes)
        assertEquals(0f, s.usedFraction)
    }

    @Test
    fun `usedFraction is used over total`() {
        val s = StorageStats(totalBytes = 1000, freeBytes = 500)
        assertEquals(0.5f, s.usedFraction, 0.0001f)
    }

    @Test
    fun `usedFraction guards division by zero`() {
        val s = StorageStats(totalBytes = 0, freeBytes = 0)
        assertEquals(0f, s.usedFraction)
    }

    @Test
    fun `usedFraction guards negative total`() {
        val s = StorageStats(totalBytes = -10, freeBytes = -20)
        assertEquals(0f, s.usedFraction)
        assertTrue(s.usedBytes >= 0)
    }

    @Test
    fun `full disk reports fraction one`() {
        val s = StorageStats(totalBytes = 512, freeBytes = 0)
        assertEquals(512L, s.usedBytes)
        assertEquals(1f, s.usedFraction, 0.0001f)
    }
}
