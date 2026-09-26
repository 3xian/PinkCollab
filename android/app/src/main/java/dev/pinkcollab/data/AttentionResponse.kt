package dev.pinkcollab.data

import org.json.JSONObject

sealed interface AttentionResponse {
    val requestId: String

    data class Value(override val requestId: String, val value: String) : AttentionResponse
    data class Confirmation(override val requestId: String, val confirmed: Boolean) : AttentionResponse
    data class Cancel(override val requestId: String) : AttentionResponse
}

internal fun AttentionResponse.wire(): JSONObject = JSONObject().put("id", requestId).also { body ->
    when (this) {
        is AttentionResponse.Value -> body.put("value", value)
        is AttentionResponse.Confirmation -> body.put("confirmed", confirmed)
        is AttentionResponse.Cancel -> body.put("cancelled", true)
    }
}
