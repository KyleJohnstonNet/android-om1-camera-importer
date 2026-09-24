package dev.om1.importer

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CameraImportPipelineTest {
    @Test fun `safety cap overrides even a ten slot controller`() = runBlocking {
        var active=0;var peak=0
        val controller=AdaptiveUploads(maximum=10,initial=10) { System.nanoTime()/1_000_000 }
        val result=CameraImportPipeline.run((1..23).toList(),{ _,_,_,_-> },{},controller) {
            active++;peak=maxOf(peak,active)
            try { delay(10);true } finally { active-- }
        }
        assertEquals(1,peak);assertEquals(CameraImportPipeline.Result(23,0),result)
    }
    @Test fun `later photos wait for a slower photo to finish`() = runBlocking {
        var firstFinished=false
        val result=CameraImportPipeline.run(listOf(1,2,3),{ _,_,_,_-> },{}) { item ->
            when(item) {
                1 -> { delay(400);firstFinished=true }
                2 -> delay(10)
                3 -> assertTrue(firstFinished)
            }
            true
        }
        assertEquals(3,result.imported)
    }
    @Test fun `default camera imports are serial`() = runBlocking {
        val active=AtomicInteger();val peak=AtomicInteger()
        val result=CameraImportPipeline.run((1..7).toList(),{ _,_,_,_-> },{ fail("Unexpected fallback") }) {
            peak.updateAndGet { maxOf(it,active.incrementAndGet()) }
            try { delay(10);true } finally { active.decrementAndGet() }
        }
        assertEquals(1,peak.get());assertEquals(CameraImportPipeline.Result(7,0),result)
    }
    @Test fun `failed pair drains then retries only failures serially`() = runBlocking {
        val attempts=mutableMapOf<Int,Int>();var fallback=false;var active=0
        val result=CameraImportPipeline.run((1..5).toList(),{ _,_,_,_-> },{
            assertEquals(0,active);fallback=true
        }) { photo ->
            active++
            if(fallback) assertEquals(1,active)
            try {
                delay(10)
                attempts[photo]=(attempts[photo] ?: 0)+1
                photo!=1 || attempts[photo]!!>1
            } finally { active-- }
        }
        assertTrue(fallback);assertEquals(2,attempts[1]);assertEquals(1,attempts[2])
        assertEquals(CameraImportPipeline.Result(5,0),result)
    }
    @Test fun `cancellation does not trigger a serial retry`() = runBlocking {
        assertFailsWith<CancellationException> {
            CameraImportPipeline.run(listOf(1,2),{ _,_,_,_-> },{ fail("No retry on cancellation") }) {
                throw CancellationException("Battery saver")
            }
        }
        Unit
    }
    @Test fun `persistent failures stop the batch after three photos`() = runBlocking {
        val result=CameraImportPipeline.run((1..20).toList(),{ _,_,_,_-> },{}) { false }
        assertEquals(CameraImportPipeline.Result(0,3),result)
    }
}
