package dev.om1.importer

import kotlin.test.*

class AdaptiveUploadsTest {
    private var now=0L
    private val control=AdaptiveUploads { now }.apply { networkChanged(1) }
    private fun window(bytes:Int,active:Int=control.limit):Int {
        control.tick(active)
        repeat(10) { control.acknowledged(bytes/10,1) }
        now+=10_000
        return control.tick(active)
    }

    @Test fun `useful probes grow beyond three but stop at eight`() {
        var bytes=100_000
        repeat(20) { window(bytes);bytes=(bytes*1.2).toInt() }
        assertEquals(8,control.limit)
    }
    @Test fun `flat throughput rolls back probe and waits before probing again`() {
        assertEquals(4,window(100_000))
        assertEquals(3,window(105_000))
        assertEquals(3,window(100_000))
        assertEquals(3,window(100_000))
        assertEquals(4,window(100_000))
    }
    @Test fun `a sparse queue does not cause a bandwidth probe`() {
        repeat(5) { assertEquals(3,window(100_000,active=1)) }
    }
    @Test fun `priority slot or draining transfers do not train the normal limit`() {
        repeat(5) { assertEquals(3,window(100_000,active=4)) }
    }
    @Test fun `concurrent failures halve once and recover cautiously`() {
        window(100_000)
        control.congested()
        assertEquals(2,control.limit)
        repeat(8) { control.congested() }
        assertEquals(2,control.limit)
        assertEquals(2,window(100_000))
        assertEquals(2,window(100_000))
        assertEquals(3,window(100_000))
    }
    @Test fun `stalled transfers back off to one`() {
        control.tick(3);now+=10_000
        assertEquals(1,control.tick(3))
        now+=10_000
        assertEquals(1,control.tick(1))
    }
    @Test fun `network change resets learning and ignores old responses`() {
        window(100_000)
        control.networkChanged(2)
        assertEquals(3,control.limit)
        repeat(10) { control.acknowledged(100_000,1) }
        now+=10_000
        assertEquals(1,control.tick(3))
    }
    @Test fun `throughput collapse after accepted probe reduces concurrency`() {
        assertEquals(4,window(100_000))
        assertEquals(4,window(150_000))
        assertEquals(2,window(80_000))
    }
}
