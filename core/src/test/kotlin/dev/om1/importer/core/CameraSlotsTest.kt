package dev.om1.importer.core

import kotlin.test.*

class CameraSlotsTest {
    @Test fun acceptsOnlyTwoKnownSlotsAndFixedEndpoints() {
        assertEquals("http://192.168.0.10/set_playtargetslot.cgi?targetslot=2",CameraSlots.selectUrl(2))
        assertEquals(1,CameraSlots.parse("<?xml version=\"1.0\"?><response><targetslot> 1 </targetslot></response>"))
        assertEquals(2,CameraSlots.parse("<targetslot>2</targetslot>"))
        for(slot in listOf(-1,0,3,10)) assertFailsWith<IllegalArgumentException> { CameraSlots.selectUrl(slot) }
        for(body in listOf("", "<targetslot>3</targetslot>", "<targetslot>1</targetslot><targetslot>2</targetslot>"))
            assertFailsWith<IllegalArgumentException> { CameraSlots.parse(body) }
    }
    @Test fun identicalFileMetadataOnDifferentCardsHasDifferentIdentity() {
        val legacy=CameraSlots.photoIdentity("camera","/DCIM/100OMSYS/P.JPG",1024,"stamp",0)
        assertEquals("camera\u0000/DCIM/100OMSYS/P.JPG\u00001024\u0000stamp",legacy)
        val first=CameraSlots.photoIdentity("camera","/DCIM/100OMSYS/P.JPG",1024,"stamp",1)
        val second=CameraSlots.photoIdentity("camera","/DCIM/100OMSYS/P.JPG",1024,"stamp",2)
        assertEquals(3,setOf(legacy,first,second).size)
    }
}
