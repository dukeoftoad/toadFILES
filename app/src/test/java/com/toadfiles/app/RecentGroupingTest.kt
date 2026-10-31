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
