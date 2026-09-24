package dev.om1.importer.core

import kotlin.test.*

class ActivityStatusTest {
    @Test fun countdownRoundsUpAndDoesNotClaimCompletion() {
        val status=ActivityStatus("Scanning","Listening",25_000)
        assertEquals("25 s remaining",status.countdown(1))
        assertEquals("1 s remaining",status.countdown(24_001))
        assertEquals("Waiting for the next update…",status.countdown(26_000))
        assertNull(ActivityStatus("Idle","Nothing scheduled").countdown(26_000))
    }
}
