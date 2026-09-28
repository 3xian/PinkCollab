package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationStatusTest {
    @Test fun known_and_future_receipt_statuses_have_typed_ui_values() {
        fun receipt(status: String) = JSONObject().put("id", "command").put("kind", "prompt")
            .put("state", status).receipt()
        assertEquals(OperationStatus.OutcomeUnknown, receipt("unknown").status)
        assertEquals(OperationStatus.Unknown("queued_remotely"), receipt("queued_remotely").status)
        assertEquals(AttentionType.Confirm, AttentionType.fromWire("confirm"))
        assertEquals(AttentionType.Other("future_input"), AttentionType.fromWire("future_input"))
    }
}
