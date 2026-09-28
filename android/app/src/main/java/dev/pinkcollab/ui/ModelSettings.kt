package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.ModelCatalog

internal fun isSelectedModel(current: ModelInfo?, candidate: ModelInfo): Boolean =
    current != null && current.provider == candidate.provider && current.id == candidate.id

/** A picker draft is created once on open; backend snapshots must not replace it mid-edit. */
internal data class ModelSettingsDraft(
    val model: ModelInfo?,
    val thinkingLevel: String?,
) {
    companion object {
        fun from(current: ModelInfo?) = ModelSettingsDraft(current, current?.thinkingLevel)
    }
}

internal data class ModelSettingsChanges(
    val model: ModelInfo?,
    val thinkingLevel: String?,
) {
    val isEmpty: Boolean get() = model == null && thinkingLevel == null
}

internal fun modelSettingsChanges(current: ModelInfo?, pending: ModelSettingsDraft): ModelSettingsChanges =
    ModelSettingsChanges(
        model = pending.model?.takeUnless { isSelectedModel(current, it) },
        thinkingLevel = pending.thinkingLevel?.takeUnless { it == current?.thinkingLevel },
    )

/** OMP reports the global list for the active model; catalog entries carry each candidate's own list. */
internal fun thinkingLevelsForModel(catalog: ModelCatalog, model: ModelInfo?, current: ModelInfo?): List<String> {
    val listed = model?.let { selected -> catalog.models.firstOrNull { isSelectedModel(selected, it) } }
    return listed?.thinkingLevels ?: model?.thinkingLevels
        ?: catalog.thinkingLevels.takeIf { model == null || isSelectedModel(current, model) }
        ?: emptyList()
}

internal fun selectModelDraft(
    catalog: ModelCatalog,
    current: ModelInfo?,
    pending: ModelSettingsDraft,
    selected: ModelInfo,
): ModelSettingsDraft {
    val levels = thinkingLevelsForModel(catalog, selected, current)
    val level = pending.thinkingLevel?.takeIf(levels::contains)
        ?: current?.thinkingLevel?.takeIf(levels::contains)
    return pending.copy(model = selected, thinkingLevel = level)
}

internal fun filterModels(models: List<ModelInfo>, query: String): List<ModelInfo> {
    val text = query.trim()
    if (text.isEmpty()) return models
    return models.filter { model ->
        model.name.contains(text, ignoreCase = true) ||
            model.id.contains(text, ignoreCase = true) ||
            model.provider.contains(text, ignoreCase = true)
    }
}

/** Linked map iteration preserves the catalog's provider and model order. */
internal fun groupModelsByProvider(models: List<ModelInfo>): Map<String, List<ModelInfo>> =
    models.groupBy { it.provider }

internal fun initiallyExpandedProviders(catalog: ModelCatalog, current: ModelInfo?): Set<String> =
    current?.takeIf { selected -> catalog.models.any { isSelectedModel(selected, it) } }
        ?.let { setOf(it.provider) } ?: emptySet()
