package com.toadfiles.app.ui.recent

import com.toadfiles.app.data.MediaRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Time buckets for the Recent list — Google Files style headers. Pure logic, no
 * Android dependencies, so it is unit-testable on the JVM (see RecentGroupingTest).
 */
enum class RecentGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    OLDER("Older"),
}

object RecentGrouping {

    /**
     * Buckets a modification timestamp relative to [nowMs] (both epoch millis):
     *  - [RecentGroup.TODAY] — same calendar day,
     *  - [RecentGroup.YESTERDAY] — the previous calendar day,
     *  - [RecentGroup.THIS_WEEK] — within the 7 calendar days before that,
     *  - [RecentGroup.OLDER] — anything earlier.
     * Future timestamps (clock skew, files dated ahead) count as Today so they
     * never sink to the bottom of the list.
     */
    fun groupOf(dateModifiedMs: Long, nowMs: Long = System.currentTimeMillis()): RecentGroup {
        val today = startOfDay(nowMs)
        val ts = startOfDay(dateModifiedMs)
        return when {
            ts >= today -> RecentGroup.TODAY
            ts >= today - DAY_MS -> RecentGroup.YESTERDAY
            ts >= today - 7 * DAY_MS -> RecentGroup.THIS_WEEK
            else -> RecentGroup.OLDER
        }
    }

    /**
     * Splits an already newest-first list into ordered (group, entries) sections.
     * The repository sorts by DATE_MODIFIED DESC, so sections come out in enum
     * order automatically; entries that break the order are still placed into
     * their correct bucket rather than dropped.
     */
    fun grouped(
        entries: List<MediaRepository.MediaEntry>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Pair<RecentGroup, List<MediaRepository.MediaEntry>>> {
        val byGroup = entries.groupBy { groupOf(it.dateModified, nowMs) }
        return RecentGroup.entries.mapNotNull { g -> byGroup[g]?.takeIf { it.isNotEmpty() }?.let { g to it } }
    }

    private fun startOfDay(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)
}
