package dev.pinkcollab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AttentionResponseTest {
    @Test fun typed_responses_preserve_gateway_fields() {
        val value = AttentionResponse.Value("request", "yes").wire()
        assertEquals("request", value.getString("id"))
        assertEquals("yes", value.getString("value"))
        assertFalse(value.has("confirmed"))
        assertEquals(false, AttentionResponse.Confirmation("request", false).wire().getBoolean("confirmed"))
        assertEquals(true, AttentionResponse.Cancel("request").wire().getBoolean("cancelled"))
    }
}
