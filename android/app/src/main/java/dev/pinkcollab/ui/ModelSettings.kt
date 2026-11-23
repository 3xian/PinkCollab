package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.ModelCatalog

internal fun isSelectedModel(current: ModelInfo?, candidate: ModelInfo): Boolean =
    current != null && current.provider == candidate.provider && current.id == candidate.id

/** Once edited, the target survives catalog replacement; its old capabilities do not. */
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
    val fastModeEnabled: Boolean? = null,
) {
    val isEmpty: Boolean get() = model == null && thinkingLevel == null && fastModeEnabled == null
}

internal fun modelSettingsChanges(current: ModelInfo?, pending: ModelSettingsDraft): ModelSettingsChanges =
    ModelSettingsChanges(
        model = pending.model?.takeUnless { isSelectedModel(current, it) },
        thinkingLevel = pending.thinkingLevel?.takeUnless { it == current?.thinkingLevel },
    )

internal data class ModelDraftResolution(
    val changes: ModelSettingsChanges,
    val thinkingLevels: List<String>,
    val unavailable: String?,
)

private fun ModelCatalog.listedModel(model: ModelInfo?): ModelInfo? =
    model?.let { target -> models.firstOrNull { isSelectedModel(target, it) } }

/** Only the latest catalog describes candidate capabilities. Its global list is for the active model. */
internal fun thinkingLevelsForModel(
    catalog: ModelCatalog,
    model: ModelInfo?,
    current: ModelInfo?,
    listed: ModelInfo? = catalog.listedModel(model ?: current),
): List<String> = listed?.thinkingLevels
    ?: if (model == null || isSelectedModel(current, model)) catalog.thinkingLevels else emptyList()

internal fun resolveModelDraft(
    catalog: ModelCatalog,
    current: ModelInfo?,
    pending: ModelSettingsDraft,
): ModelDraftResolution {
    val target = pending.model
    val listed = catalog.listedModel(target ?: current)
    val levels = thinkingLevelsForModel(catalog, target, current, listed)
    val changes = modelSettingsChanges(current, pending.copy(model = listed ?: target))
    val unavailable = when {
        changes.model != null && listed == null ->
            "${changes.model.name} is no longer available. Choose another model."
        !changes.isEmpty && pending.thinkingLevel != null && pending.thinkingLevel !in levels ->
            "Thinking level ${pending.thinkingLevel} is no longer available. Choose a supported level or reselect a model."
        else -> null
    }
    return ModelDraftResolution(changes, levels, unavailable)
}

internal fun selectModelDraft(
    catalog: ModelCatalog,
    current: ModelInfo?,
    pending: ModelSettingsDraft,
    selected: ModelInfo,
): ModelSettingsDraft {
    val levels = thinkingLevelsForModel(catalog, selected, current, listed = selected)
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
