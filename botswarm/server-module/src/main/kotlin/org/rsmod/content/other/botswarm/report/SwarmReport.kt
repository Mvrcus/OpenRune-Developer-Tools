package org.rsmod.content.other.botswarm.report

import org.rsmod.content.other.botswarm.TraceEntry

/**
 * The run artifact: `.data/botswarm/runs/<run-id>/report.json`. It is the input to triage (a
 * reasoning model dedupes findings and files issues) and to tuning (xp/hour per goal).
 */
data class SwarmReport(
    val runId: String,
    val startedAt: String,
    val finishedAt: String,
    val stopReason: String,
    val decider: String,
    val ticks: Int,
    val goals: List<GoalSummary>,
    val findings: List<Finding>,
    val coverage: CoverageSummary,
    val tickStats: TickStats,
    /** action label -> obj -> net count: where items enter and leave the world. */
    val ledger: Map<String, Map<String, Long>>,
    val heatmap: List<HeatTile>,
    val bots: List<BotSummary>,
)

data class GoalSummary(
    val id: String,
    val description: String,
    val success: String?,
    val bots: Int,
    val completed: Int,
    val medianTicksToComplete: Int?,
    /** stat -> xp per hour of bot play, averaged over this goal's bots (6000 ticks = 1 hour). */
    val xpPerHour: Map<String, Long>,
)

data class BotSummary(
    val name: String,
    val goal: String,
    val seed: Long,
    val completed: Boolean,
    val ticksToComplete: Int?,
    val ticksPlayed: Int,
    val finishReason: String?,
    val actions: Int,
    val deaths: Int,
    val deciderErrors: Int,
    val xpGained: Map<String, Int>,
    val trace: List<TraceEntry>,
)

data class CoverageSummary(
    val distinctOffered: Int,
    val distinctChosen: Int,
    val entries: List<CoverageEntry>,
)

data class CoverageEntry(
    val key: String,
    val label: String,
    val offered: Int,
    val chosen: Int,
    val dropped: Int,
    val unimplemented: Int,
    val unreachable: Int,
)

data class TickStats(
    val cycles: Int,
    val avgMs: Double,
    val p95Ms: Long,
    val maxMs: Long,
    val overruns: Int,
)

data class HeatTile(val x: Int, val z: Int, val level: Int, val visits: Int)
