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

    @Test
    fun `reminder keywords rescue misclassified or low-confidence intents`() {
        val addRem = PostClassificationValidator.validateRules(
            intent = "search_google",
            confidence = 0.41f,
            preprocessed = InputPreprocessor.preprocess("add a reminder to buy milk")
        )
        assertEquals("set_reminder", addRem.intent)
        assertEquals(false, addRem.routeToLlm)

        val searchRem = PostClassificationValidator.validateRules(
            intent = "web_search",
            confidence = 0.80f,
            preprocessed = InputPreprocessor.preprocess("remind me to search for flights tomorrow")
        )
        assertEquals("set_reminder", searchRem.intent)
        assertEquals(false, searchRem.routeToLlm)

        val listRem = PostClassificationValidator.validateRules(
            intent = "unknown",
            confidence = 0.20f,
            preprocessed = InputPreprocessor.preprocess("what reminders do i have")
        )
        assertEquals("reminder_list", listRem.intent)
        assertEquals(false, listRem.routeToLlm)
    }

    @Test
    fun `web search is strictly locked to explicit search keyword`() {
        val nonSearch = PostClassificationValidator.validateRules(
            intent = "search_google",
            confidence = 0.91f,
            preprocessed = InputPreprocessor.preprocess("who is the president of france")
        )
        assertEquals("unknown", nonSearch.intent)
        assertEquals(true, nonSearch.routeToLlm)

        val googleOnly = PostClassificationValidator.validateRules(
            intent = "web_search",
            confidence = 0.85f,
            preprocessed = InputPreprocessor.preprocess("google weather today")
        )
        assertEquals("unknown", googleOnly.intent)

        val explicitSearch = PostClassificationValidator.validateRules(
            intent = "unknown",
            confidence = 0.30f,
            preprocessed = InputPreprocessor.preprocess("search for quantum computing")
        )
        assertEquals("search_google", explicitSearch.intent)
        assertEquals(false, explicitSearch.routeToLlm)
    }

    @Test
    fun `command rescues fix volume alarm and screenshot misclassifications`() {
        val vol = PostClassificationValidator.validateRules(
            intent = "pause_media",
            confidence = 0.59f,
            preprocessed = InputPreprocessor.preprocess("turn down the music")
        )
        assertEquals("volume_down", vol.intent)

        val alarm = PostClassificationValidator.validateRules(
            intent = "calendar_add",
            confidence = 0.46f,
            preprocessed = InputPreprocessor.preprocess("set alarm called workout at 5 am")
        )
        assertEquals("set_alarm", alarm.intent)

        val snap = PostClassificationValidator.validateRules(
            intent = "open_camera",
            confidence = 0.43f,
            preprocessed = InputPreprocessor.preprocess("take a snap of the screen")
        )
        assertEquals("take_screenshot", snap.intent)
    }
}
