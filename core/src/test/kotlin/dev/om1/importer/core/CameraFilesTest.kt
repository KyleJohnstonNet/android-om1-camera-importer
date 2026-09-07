package dev.om1.importer.core
import kotlin.test.*
class CameraFilesTest {
    @Test fun `parses DCF directories JPEGs and skips RAW`() {
        assertEquals(CameraFile("/DCIM/100OMSYS",0,true),CameraFiles.parse("WLANSD_FILELIST\r\n/DCIM,100OMSYS,0,16,0,0\r\n","/DCIM").single())
        val files=CameraFiles.parse("VER_100\n/DCIM/100OMSYS,P9070001.JPG,12345,32,1,2\n/DCIM/100OMSYS,P9070001.ORF,30000,32,1,2", "/DCIM/100OMSYS")
        assertEquals(listOf(CameraFile("/DCIM/100OMSYS/P9070001.JPG",12345,false,"1:2")),files)
    }
    @Test fun `rejects paths that could escape the camera boundary`() {
        for(path in listOf("/DCIM/../x.JPG", "//example.com/x.JPG", "/DCIM/100OMSYS/x.JPG?x=1", "/DCIM/100OMSYS/%2e%2e.JPG", "/DCIM/100OMSYS/x.JPG/extra")) assertFalse(CameraFiles.jpeg(path))
        assertFails { CameraFiles.listingUrl("/DCIM?x=1") }
        assertFails { CameraFiles.parse("/OTHER,x.JPG,12,0,0,0","/DCIM") }
        assertFails { CameraFiles.parse("not a listing","/DCIM") }
    }
}
