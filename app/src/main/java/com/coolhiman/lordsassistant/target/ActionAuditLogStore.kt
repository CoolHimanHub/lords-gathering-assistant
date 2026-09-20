package com.coolhiman.lordsassistant.target

import android.content.Context
import org.json.JSONObject

/**
 * V0.6 persistent action audit trail.
 *
 * This log is diagnostic provenance, not a dispatch authority. Writing an event
 * never changes the action lifecycle and a logging failure must never open the
 * gesture path.
 */
enum class ActionAuditEventType {
    CANDIDATE_QUEUED,
    CANDIDATE_SELECTED,
    ACTION_REQUESTED,
    ACTION_REVALIDATED,
    DISPATCH_BARRIER_OPENED,
    DISPATCH_SUCCEEDED,
    DISPATCH_FAILED,
    VERIFICATION_SUCCEEDED,
    VERIFICATION_FAILED,
    UNKNOWN_ENTERED,
    RECOVERY_RESET,
    RESTART_QUARANTINE
}

data class ActionAuditEvent(
    val timestampMs: Long,
    val type: ActionAuditEventType,
    val attemptId: Long? = null,
    val recoveryEpoch: Long? = null,
    val target: ActionTargetSnapshot? = null,
    val detail: String? = null
)

class ActionAuditLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("lm_action_audit", Context.MODE_PRIVATE)

    fun append(event: ActionAuditEvent): Boolean {
        val existing = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        val array = runCatching { org.json.JSONArray(existing) }.getOrElse {
            org.json.JSONArray()
        }

        array.put(toJson(event))
        while (array.length() > MAX_EVENTS) {
            array.remove(0)
        }

        return prefs.edit()
            .putString(KEY_EVENTS, array.toString())
            .commit()
    }

    fun readAll(): List<ActionAuditEvent> {
        val existing = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        val array = runCatching { org.json.JSONArray(existing) }.getOrElse {
            org.json.JSONArray()
        }

        return buildList {
            for (index in 0 until array.length()) {
                fromJson(array.optJSONObject(index))?.let(::add)
            }
        }
    }

    fun clear(): Boolean = prefs.edit().remove(KEY_EVENTS).commit()

    private fun toJson(event: ActionAuditEvent): JSONObject =
        JSONObject().apply {
            put("timestampMs", event.timestampMs)
            put("type", event.type.name)
            event.attemptId?.let { put("attemptId", it) }
            event.recoveryEpoch?.let { put("recoveryEpoch", it) }
            event.target?.let {
                put("target", JSONObject().apply {
                    put("x", it.coordinate.x)
                    put("y", it.coordinate.y)
                    put("kingdom", it.coordinate.kingdom)
                    put("kind", it.kind.name)
                    put("level", it.level)
                    put("actionKind", it.actionKind.name)
                    put("pointX", it.point.x)
                    put("pointY", it.point.y)
                })
            }
            event.detail?.let { put("detail", it) }
        }

    private fun fromJson(json: JSONObject?): ActionAuditEvent? {
        if (json == null) return null
        val type = runCatching {
            ActionAuditEventType.valueOf(json.optString("type"))
        }.getOrNull() ?: return null

        return ActionAuditEvent(
            timestampMs = json.optLong("timestampMs"),
            type = type,
            attemptId = json.optLongOrNull("attemptId"),
            recoveryEpoch = json.optLongOrNull("recoveryEpoch"),
            detail = json.optString("detail").takeIf { it.isNotBlank() }
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key)) optLong(key) else null

    companion object {
        private const val KEY_EVENTS = "events"
        private const val MAX_EVENTS = 500
    }
}
