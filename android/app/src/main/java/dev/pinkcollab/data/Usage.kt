package dev.pinkcollab.data

import org.json.JSONObject

internal data class UsageSnapshot(val generatedAt: Long, val accounts: List<UsageAccount>)
internal data class UsageAccount(
    val id: String, val provider: String, val accountLabel: String, val plan: String?,
    val fetchedAt: Long?, val status: String, val limits: List<UsageLimit>,
)
internal data class UsageLimit(
    val id: String, val label: String, val modelId: String?, val tier: String?,
    val windowLabel: String?, val resetsAt: Long?, val usedFraction: Double?,
    val used: Double?, val limit: Double?, val remaining: Double?, val unit: String?, val status: String,
)
internal fun parseUsage(raw: JSONObject): UsageSnapshot = UsageSnapshot(
    raw.getLong("generatedAt"), raw.getJSONArray("accounts").objects().map { account ->
        UsageAccount(account.getString("id"), account.getString("provider"), account.getString("accountLabel"),
            account.nullableString("plan"), account.nullableLong("fetchedAt"), account.getString("status"),
            account.getJSONArray("limits").objects().map { limit ->
                UsageLimit(limit.getString("id"), limit.getString("label"), limit.nullableString("modelId"),
                    limit.nullableString("tier"), limit.nullableString("windowLabel"),
                    limit.nullableLong("resetsAt"), limit.nullableDouble("usedFraction"), limit.nullableDouble("used"),
                    limit.nullableDouble("limit"), limit.nullableDouble("remaining"), limit.nullableString("unit"),
                    limit.getString("status"))
            })
    },
)
private fun JSONObject.nullableString(key: String): String? = opt(key) as? String
private fun JSONObject.nullableLong(key: String): Long? = (opt(key) as? Number)?.toLong()
private fun JSONObject.nullableDouble(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
