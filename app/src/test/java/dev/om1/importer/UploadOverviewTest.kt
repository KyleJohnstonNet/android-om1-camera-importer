package dev.om1.importer

import kotlin.test.*

class UploadOverviewTest {
    private val row=PhotoRow("1","camera","photo.jpg",100,"stamp","owner",null,"READY",null,null,null,null,1,null,null,0,10_000)
    private fun describe(saving:Boolean=false,enabled:Boolean=true,connected:Boolean=true,wifi:Boolean=true,cellular:Boolean=false,priority:Long=0,now:Long=0)=
        UploadOverview.describe(listOf(row.copy(priority=priority)),0,3,saving,enabled,true,connected,wifi,cellular,now)
    @Test fun `battery saver and explicit pause take precedence`() {
        assertTrue(describe(saving=true,connected=false).contains("battery saver"))
        assertTrue(describe(enabled=false).contains("disabled"))
    }
    @Test fun `connectivity and retry waits are distinguished`() {
        assertTrue(describe(connected=false).contains("internet"))
        assertTrue(describe(wifi=false).contains("Wi-Fi"))
        assertTrue(describe(wifi=false,priority=1).contains("Retry in 10 s"))
        assertTrue(describe(now=10_000).contains("waiting for the upload worker"))
    }
}
