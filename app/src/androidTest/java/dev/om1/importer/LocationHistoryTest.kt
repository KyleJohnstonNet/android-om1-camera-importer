package dev.om1.importer

import androidx.test.platform.app.InstrumentationRegistry
import dev.om1.importer.core.GeoFix
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class LocationHistoryTest {
    @Test fun historyIsBoundedAccurateAndCanBeCleared() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="location-test-${UUID.randomUUID()}.db"
        val history=LocationHistory(context,name)
        try {
            val now=System.currentTimeMillis()
            val good=GeoFix(now,45.0,10.0,5.0)
            history.add(good,now)
            history.add(good.copy(time=now+1,accuracy=101.0),now)
            history.add(good.copy(time=now-60_001),now)
            assertEquals(good,history.nearest(now))
            assertNull(history.nearest(now-120_001))
            history.prune(now+LocationHistory.RETENTION+1)
            assertNull(history.nearest(now))
            history.add(good,now);history.clear();assertNull(history.nearest(now))
        } finally { history.close();context.deleteDatabase(name) }
    }
}
