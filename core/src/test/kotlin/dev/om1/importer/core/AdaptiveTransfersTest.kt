package dev.om1.importer.core

import kotlin.test.*

class AdaptiveTransfersTest {
    @Test fun cameraCanGrowFromTwoToTenAndBackOffOnErrors() {
        var now=0L
        val controller=AdaptiveTransfers(maximum=10,initial=2) { now }
        controller.networkChanged(1)
        var bytes=100_000
        repeat(24) {
            val width=controller.limit
            controller.tick(width)
            repeat(10) { controller.acknowledged(bytes/10,1) }
            now+=10_000
            controller.tick(width)
            bytes=(bytes*1.2).toInt()
            assertTrue(controller.limit in 1..10)
        }
        assertEquals(10,controller.limit)
        controller.congested();assertEquals(5,controller.limit)
        controller.networkChanged(2);assertEquals(2,controller.limit)
    }
}
