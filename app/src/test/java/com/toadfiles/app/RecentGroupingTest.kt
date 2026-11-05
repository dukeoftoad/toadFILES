package com.toadfiles.app

import com.toadfiles.app.ui.recent.RecentGroup
import com.toadfiles.app.ui.recent.RecentGrouping
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Time-bucket rules for the Recent list headers (Today / Yesterday / This week / Older).
 * Pure JVM — [RecentGrouping.groupOf] touches no Android types, so no Robolectric needed.
 */
class RecentGroupingTest {

    private val now = System.currentTimeMillis()

    private fun at(offsetMs: Long): Long = now + offsetMs

    private fun startOfDay(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `same calendar day is Today`() {
        assertEquals(RecentGroup.TODAY, RecentGrouping.groupOf(at(0), now))
        assertEquals(RecentGroup.TODAY, RecentGrouping.groupOf(at(-1), now))
        // Just after midnight of the current day still counts.
        assertEquals(RecentGroup.TODAY, RecentGrouping.groupOf(startOfDay(now) + 1, now))
    }

    // ---- Audit A13: DST-transition days must not shift the bucket boundaries ----

    /** Epoch millis for a wall-clock time in the CURRENT default timezone. */
    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun withTimeZone(name: String, block: () -> Unit) {
        val old = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(name))
            block()
        } finally {
            java.util.TimeZone.setDefault(old)
        }
    }

    @Test
    fun `fall-back day - the early hours of the 25-hour day are Yesterday - A13`() {
        // America/New_York, Nov 1 2026: 02:00 EDT falls back to 01:00 EST — the day
        // is 25 hours long. Now = Nov 2, 01:00 EST; a file from Nov 1, 00:30 EDT is
        // YESTERDAY. The old fixed 24-hour subtraction put the boundary at
        // Nov 1 01:00 EDT and pushed that file into THIS_WEEK.
        withTimeZone("America/New_York") {
            val nowMs = calendarAt(2026, Calendar.NOVEMBER, 2, 1, 0)
            val earlyLongDay = calendarAt(2026, Calendar.NOVEMBER, 1, 0, 30)
            assertEquals(RecentGroup.YESTERDAY, RecentGrouping.groupOf(earlyLongDay, nowMs))
        }
    }

    @Test
    fun `spring-forward day - the evening before the 23-hour day is This week - A13`() {
        // America/New_York, Mar 8 2026: 02:00 EST springs to 03:00 EDT — 23 hours.
        // Now = Mar 9, 02:00 EDT; a file from Mar 7, 23:30 EST is two calendar days
        // back → THIS_WEEK. The old fixed subtraction landed the YESTERDAY boundary
        // at Mar 7 23:00 EST, wrongly pulling the file into YESTERDAY.
        withTimeZone("America/New_York") {
            val nowMs = calendarAt(2026, Calendar.MARCH, 9, 2, 0)
            val eveningBefore = calendarAt(2026, Calendar.MARCH, 7, 23, 30)
            assertEquals(RecentGroup.THIS_WEEK, RecentGrouping.groupOf(eveningBefore, nowMs))
        }
    }

    @Test
    fun `seven calendar days back stays This week across a fall-back - A13`() {
        // Now = Nov 2 2026, 12:00 EST. Exactly seven calendar days back is
        // Oct 26 00:00 EDT (the transition happened in between). A file from
        // Oct 26, 00:30 EDT must be THIS_WEEK — the old 168-hour subtraction put
        // the boundary at Oct 26 01:00 EDT and sank the file into OLDER.
        withTimeZone("America/New_York") {
            val nowMs = calendarAt(2026, Calendar.NOVEMBER, 2, 12, 0)
            val sevenBack = calendarAt(2026, Calendar.OCTOBER, 26, 0, 30)
            assertEquals(RecentGroup.THIS_WEEK, RecentGrouping.groupOf(sevenBack, nowMs))
        }
    }

    @Test
    fun `previous calendar day is Yesterday`() {
        // One millisecond before local midnight = yesterday evening.
        assertEquals(RecentGroup.YESTERDAY, RecentGrouping.groupOf(startOfDay(now) - 1, now))
        assertEquals(RecentGroup.YESTERDAY, RecentGrouping.groupOf(startOfDay(now) - 1_000, now))
    }

    @Test
    fun `two to seven days back is This week`() {
        for (days in 2..7L) {
            val ts = startOfDay(now) - TimeUnit.DAYS.toMillis(days) + 1
            assertEquals("day $days back", RecentGroup.THIS_WEEK, RecentGrouping.groupOf(ts, now))
        }
    }

    @Test
    fun `eight or more days back is Older`() {
        val ts = startOfDay(now) - TimeUnit.DAYS.toMillis(8) + 1
        assertEquals(RecentGroup.OLDER, RecentGrouping.groupOf(ts, now))
        assertEquals(RecentGroup.OLDER, RecentGrouping.groupOf(0L, now))
    }

    @Test
    fun `future timestamps count as Today`() {
        // Clock skew / files dated ahead must not sink to the bottom of the list.
        assertEquals(RecentGroup.TODAY, RecentGrouping.groupOf(at(TimeUnit.HOURS.toMillis(5)), now))
    }

    @Test
    fun `every group has a user-facing label`() {
        // The header text is user-visible; none may be blank.
        RecentGroup.entries.forEach { g -> assert(g.label.isNotBlank()) { "${g.name} has no label" } }
    }
}
