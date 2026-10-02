package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelInfoTest {
    @Test fun fast_preference_and_effective_state_are_parsed_separately() {
        val model = JSONObject("""{"provider":"openai","id":"model","name":"Model","fastModeEnabled":true,"fastModeActive":false}""").modelInfo()
        assertEquals(true, model.fastModeEnabled)
        assertEquals(false, model.fastModeActive)
    }

    @Test fun old_gateway_snapshot_does_not_claim_fast_support() {
        val model = JSONObject("""{"provider":"openai","id":"model","name":"Model"}""").modelInfo()
        assertNull(model.fastModeEnabled)
        assertNull(model.fastModeActive)
    }
}
