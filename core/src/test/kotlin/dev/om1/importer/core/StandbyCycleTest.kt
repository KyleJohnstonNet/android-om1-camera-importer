package dev.om1.importer.core

import kotlin.test.*

class StandbyCycleTest {
    @Test fun completedOffDoesNotWakeAgain() {
        val gate=StandbyCycle()
        assertTrue(gate.observe(false))
        gate.completed()
        repeat(10) { assertFalse(gate.observe(true)) } // our own controller wake
        repeat(100) { assertFalse(gate.observe(false)) }
        assertFalse(gate.observe(true))
        assertTrue(gate.observe(false))
    }
    @Test fun survivesRestartAndFailedImportsRemainRetryable() {
        val gate=StandbyCycle();assertTrue(gate.observe(false));assertTrue(gate.observe(false))
        gate.completed();gate.observe(false)
        val restored=StandbyCycle(gate.phase)
        assertFalse(restored.observe(false));assertFalse(restored.observe(true));assertTrue(restored.observe(false))
    }
}
