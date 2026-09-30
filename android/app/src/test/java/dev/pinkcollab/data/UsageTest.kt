package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageTest {
    @Test fun missing_usage_is_distinct_from_zero() {
        val snapshot = parseUsage(JSONObject("""{"generatedAt":123,"accounts":[
          {"id":"a","provider":"test","accountLabel":"a***","status":"available","fetchedAt":null,"limits":[
            {"id":"zero","label":"Weekly","usedFraction":0,"status":"ok"},
            {"id":"unknown","label":"Monthly","usedFraction":null,"status":"unknown"}]},
          {"id":"b","provider":"other","accountLabel":"b***","status":"unavailable","limits":[]}]}
        """))
        assertEquals(0.0, snapshot.accounts[0].limits[0].usedFraction!!, 0.0)
        assertNull(snapshot.accounts[0].limits[1].usedFraction)
        assertNull(snapshot.accounts[0].fetchedAt)
        assertEquals("unavailable", snapshot.accounts[1].status)
    }
}
