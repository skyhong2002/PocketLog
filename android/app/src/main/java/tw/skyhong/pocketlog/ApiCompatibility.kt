package tw.skyhong.pocketlog

import org.json.JSONException
import org.json.JSONObject

/** Fixed messages only: never retain a response body or JSON parser exception. */
class ApiProtocolFailure(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val message: String) {
        WRONG_SERVICE("這個網址回傳網頁，或不是 PocketLog API。請確認伺服器網址與連接埠。"),
        INVALID_RESPONSE("PocketLog API 回應不完整或格式不正確。請確認伺服器與反向代理設定。"),
        UNSUPPORTED_VERSION("這個伺服器使用此版本應用程式不支援的 API。請更新 PocketLog 應用程式，或使用支援 API v1 的伺服器。")
    }
}

object ApiCompatibility {
    fun decode(contentType: String?, text: String): JSONObject {
        // Legacy JSON responses may omit Content-Type. HTML is never a journal response.
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if ((type != null && type != "application/json" && !(type.startsWith("application/") && type.endsWith("+json"))) ||
            text.trimStart().startsWith("<")) throw ApiProtocolFailure(ApiProtocolFailure.Reason.WRONG_SERVICE)
        return try { JSONObject(text) } catch (_: JSONException) {
            throw ApiProtocolFailure(ApiProtocolFailure.Reason.INVALID_RESPONSE)
        }
    }

    fun status(value: JSONObject) {
        if (!positiveInteger(value.opt("schema_version")) || value.opt("analysis") !is String ||
            !nonnegativeInteger(value.opt("pending_context"))) invalid()
        // schema_version describes storage, not API compatibility. Older /v1 servers omit api.
        if (!value.has("api")) return
        val api = value.optJSONObject("api") ?: invalid()
        val version = api.opt("version")
        if (!positiveInteger(version)) invalid()
        if ((version as Number).toLong() != 1L) throw ApiProtocolFailure(ApiProtocolFailure.Reason.UNSUPPORTED_VERSION)
        // Unknown fields/capabilities are additive; no exact app/server release match is required.
    }

    fun timeline(value: JSONObject) {
        if (value.optJSONArray("activities") == null || value.optJSONArray("cards") == null) invalid()
    }

    private fun positiveInteger(value: Any?) = nonnegativeInteger(value) && (value as Number).toLong() > 0
    private fun nonnegativeInteger(value: Any?) = (value is Int || value is Long) && (value as Number).toLong() >= 0
    private fun invalid(): Nothing = throw ApiProtocolFailure(ApiProtocolFailure.Reason.INVALID_RESPONSE)
}
