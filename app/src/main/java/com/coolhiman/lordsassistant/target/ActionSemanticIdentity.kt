package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind

/**
 * Canonical semantic identity used by the automated-action boundary.
 *
 * Generic kind labels such as "MONSTER" are discovery evidence, not a stable
 * target identity. Resource identities must resolve to a known ResourceType;
 * monster identities must contain a concrete non-generic label.
 */
object ActionSemanticIdentity {
    fun fromObservation(observation: MapObservation): String? = when (observation.kind) {
        TargetKind.RESOURCE -> observation.label
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { ResourceType.valueOf(it.uppercase()) }.getOrNull() }
            ?.name

        TargetKind.MONSTER -> observation.label
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("MONSTER", ignoreCase = true) }

        null -> null
    }
}
