package com.coolhiman.lordsassistant.target

import android.content.Context
import org.json.JSONObject
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate

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
    SCHEDULER_BLOCKED,
    CANDIDATE_DROPPED,
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

    fun appendIfChanged(event: ActionAuditEvent): Boolean {
        val previous = readAll().lastOrNull()
        if (previous != null && previous.type == event.type && previous.attemptId == event.attemptId &&
            previous.recoveryEpoch == event.recoveryEpoch && previous.target == event.target && previous.detail == event.detail) {
            return true
        }
        return append(event)
    }

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

    fun latest(limit: Int = 20): List<ActionAuditEvent> =
        readAll().takeLast(limit.coerceAtLeast(0))

    fun formatLatest(limit: Int = 20): String =
        latest(limit).asReversed().joinToString("\n") { event ->
            buildString {
                append(event.timestampMs)
                append(" • ").append(event.type.name)
                event.attemptId?.let { append(" • attempt=").append(it) }
                event.recoveryEpoch?.let { append(" • epoch=").append(it) }
                event.target?.let {
                    append(" • K").append(it.coordinate.kingdom)
                    append(" X").append(it.coordinate.x)
                    append(" Y").append(it.coordinate.y)
                    append(" ").append(it.kind.name)
                    append(" L").append(it.level)
                    append(" ").append(it.actionKind.name)
                }
                event.detail?.let { append(" • ").append(it) }
            }
        }

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

        val target = json.optJSONObject("target")?.let { targetJson ->
            runCatching {
                ActionTargetSnapshot(
                    coordinate = WorldCoordinate(
                        targetJson.getInt("kingdom"),
                        targetJson.getInt("x"),
                        targetJson.getInt("y")
                    ),
                    kind = TargetKind.valueOf(targetJson.getString("kind")),
                    level = targetJson.getInt("level"),
                    actionKind = ActionKind.valueOf(targetJson.getString("actionKind")),
                    point = ScreenPoint(
                        targetJson.getDouble("pointX").toFloat(),
                        targetJson.getDouble("pointY").toFloat()
                    )
                )
            }.getOrNull()
        }

        return ActionAuditEvent(
            timestampMs = json.optLong("timestampMs"),
            type = type,
            attemptId = json.optLongOrNull("attemptId"),
            recoveryEpoch = json.optLongOrNull("recoveryEpoch"),
            target = target,
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
