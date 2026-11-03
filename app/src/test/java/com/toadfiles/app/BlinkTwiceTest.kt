package com.toadfiles.app

import com.toadfiles.app.ui.components.blinkTwice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "Show in folder" pinpoint flash must blink exactly twice — on/off/on/off —
 * so the row always settles UNLIT. A stray trailing "on" would leave the file
 * highlighted forever; a missing pulse would undersell the pinpoint.
 */
class BlinkTwiceTest {

    @Test
    fun `pulses exactly on-off-on-off and settles off`() = runBlocking {
        val pulses = mutableListOf<Boolean>()
        blinkTwice(pulseMs = 20, gapMs = 10) { pulses += it }
        assertEquals(listOf(true, false, true, false), pulses)
    }
}
