package com.friday.assistant.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineAndReminderTest {

    @Test
    fun `parseRoutineCommand extracts multi-step goodnight macro`() {
        val parsed = RoutineManager.parseRoutineCommand(
            "create routine goodnight that turns off wifi, turns on dnd and locks phone"
        )
        assertNotNull(parsed)
        assertEquals("goodnight", parsed!!.triggerPhrase)
        assertEquals(listOf("turn off wifi", "turn on dnd", "lock phone"), parsed.steps)
    }

    @Test
    fun `parseRoutineCommand supports when i say syntax`() {
        val parsed = RoutineManager.parseRoutineCommand(
            "when i say heading out turn off wifi and play music"
        )
        assertNotNull(parsed)
        assertEquals("heading out", parsed!!.triggerPhrase)
        assertEquals(listOf("turn off wifi", "play music"), parsed.steps)
    }

    @Test
    fun `parseNaturalDateTime handles relative words and half hour`() {
        val now = 1_700_000_000_000L
        val halfHour = ReminderScheduler.parseNaturalDateTime("remind me in half an hour", now)
        assertEquals(now + 30L * 60_000L, halfHour)

        val twoHours = ReminderScheduler.parseNaturalDateTime("in two hours", now)
        assertEquals(now + 2L * 3600_000L, twoHours)

        val aMinute = ReminderScheduler.parseNaturalDateTime("in a minute", now)
        assertEquals(now + 60_000L, aMinute)

        val dotTime = ReminderScheduler.parseNaturalDateTime("tomorrow at 5.30 pm", now)
        assertTrue(dotTime != null && dotTime > now)
    }
}
