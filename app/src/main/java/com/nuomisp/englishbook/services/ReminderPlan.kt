package com.nuomisp.englishbook.services

import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

data class ReminderSlot(val minuteOfDay: Int, val recap: Boolean) {
    val time: LocalTime get() = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}

/** Pure scheduling rules, independent of Android timing guarantees. */
object ReminderPlan {
    const val START_MINUTE = 9 * 60
    const val RECAP_MINUTE = 21 * 60 + 30
    const val END_MINUTE = 22 * 60
    const val MIN_RANDOM_GAP = 90

    fun forDate(date: LocalDate, installationSeed: Int): List<ReminderSlot> {
        val random = Random(date.toEpochDay() xor installationSeed.toLong())
        // Four order statistics + three 90-minute gaps fit into 09:00–21:00.
        // The last random prompt is at least 30 minutes before the fixed recap.
        val slack = (RECAP_MINUTE - 30 - START_MINUTE) - 3 * MIN_RANDOM_GAP
        val offsets = List(4) { random.nextInt(slack + 1) }.sorted()
        return offsets.mapIndexed { index, offset -> ReminderSlot(START_MINUTE + offset + index * MIN_RANDOM_GAP, false) } +
            ReminderSlot(RECAP_MINUTE, true)
    }

    fun canDeliver(slot: ReminderSlot, plannedDate: LocalDate, nowDate: LocalDate, now: LocalTime): Boolean {
        if (plannedDate != nowDate) return false
        val minute = now.hour * 60 + now.minute
        if (minute < START_MINUTE || minute >= END_MINUTE) return false
        if (slot.recap) return minute >= RECAP_MINUTE
        if (minute >= RECAP_MINUTE - 30) return false
        return minute >= slot.minuteOfDay && minute <= slot.minuteOfDay + 45
    }
}
