package com.unciv.logic.llm.runner

data class LlmRunnerSummary(
    val stepsRun: Int,
    val appliedCount: Int,
    val rejectedCount: Int,
    val finalTurn: Int,
    val outputPath: String?,
    val stoppedReason: StoppedReason
)

enum class StoppedReason {
    COMPLETED,
    CIV_DEFEATED,
    DEGENERATE_POLICY,
    ERROR
}
