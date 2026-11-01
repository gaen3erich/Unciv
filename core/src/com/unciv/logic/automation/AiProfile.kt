package com.unciv.logic.automation

import yairm210.purity.annotations.Readonly

/**
 * A switchable piece of AI behaviour.
 *
 * Every AI change that we want to measure gets its own entry here and is guarded in code with
 * `if (civ.aiProfile.has(AiFeature.X))`. This lets the same build run the old and the new behaviour
 * side by side in one game (see [com.unciv.logic.simulation.AiExperiment]).
 *
 * Lifecycle of a feature:
 * 1. Added with [enabledByDefault] = false, measured with an AI experiment against `default`.
 * 2. If it wins, flip [enabledByDefault] to true so normal games get it.
 * 3. Once nobody needs the old behaviour any more, delete the entry and the `else` branch.
 */
enum class AiFeature(val enabledByDefault: Boolean, val description: String) {
    GreatPersonEscort(false, "Military units escort great people, not only settlers"),
    StrategicPlan(false, "Long-term goal and phase (expansion, development, war preparation, war, defense) that tilt tech, policy, production and war decisions"),
    ;

    companion object {
        @Readonly
        fun fromName(name: String): AiFeature = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown AI feature '$name'. Known: ${entries.joinToString { it.name }}")
    }
}

/** A named set of [AiFeature]s that one civilization plays with. */
class AiProfile(val name: String, val features: Set<AiFeature>) {

    @Readonly fun has(feature: AiFeature) = feature in features

    override fun toString() = name
    override fun equals(other: Any?) = other is AiProfile && other.features == features
    override fun hashCode() = features.hashCode()

    companion object {
        /** What every civilization plays with in normal games */
        val DEFAULT = AiProfile("default", AiFeature.entries.filter { it.enabledByDefault }.toSet())

        /**
         * Parses a profile spec.
         *
         * A spec starts with a base and adds or removes features:
         * - `default`: features enabled in normal games
         * - `none`: no switchable features at all
         * - `all`: every feature, including experimental ones
         * - `default+GreatPersonEscort`, `all-GreatPersonEscort`, `none+A+B`
         *
         * A bare list such as `GreatPersonEscort` means `default+GreatPersonEscort`.
         */
        fun parse(spec: String): AiProfile {
            val trimmed = spec.trim()
            require(trimmed.isNotEmpty()) { "Empty AI profile spec" }
            // Split into tokens, keeping the +/- sign attached to each one
            val tokens = Regex("[+-]?[^+-]+").findAll(trimmed).map { it.value.trim() }.toList()
            val features = mutableSetOf<AiFeature>()
            var startIndex = 0
            when (tokens.first().lowercase()) {
                "default" -> { features += DEFAULT.features; startIndex = 1 }
                "none" -> startIndex = 1
                "all" -> { features += AiFeature.entries; startIndex = 1 }
                else -> features += DEFAULT.features
            }
            for (token in tokens.drop(startIndex)) {
                when {
                    token.startsWith("-") -> features -= AiFeature.fromName(token.drop(1).trim())
                    token.startsWith("+") -> features += AiFeature.fromName(token.drop(1).trim())
                    else -> features += AiFeature.fromName(token)
                }
            }
            return AiProfile(trimmed, features)
        }
    }
}
