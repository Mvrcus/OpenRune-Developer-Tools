package org.rsmod.content.other.botswarm.report

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap

enum class Severity {
    High,
    Medium,
    Low,
    Info,
}

enum class FindingKind(val severity: Severity, val meaning: String) {
    ServerException(Severity.High, "an exception was logged while processing this bot"),
    ForcedDisconnect(Severity.High, "the server force-disconnected the bot (tryOrDisconnect)"),
    ClickDropped(
        Severity.Medium,
        "an option the client shows was silently dropped by the packet handler",
    ),
    Stuck(Severity.Medium, "no movement, xp or item change for a long stretch while idle"),
    DialogueTrap(Severity.Medium, "the bot sat in a dialogue or input it could not answer"),
    Unreachable(Severity.Info, "\"I can't reach that!\" for a target the client offered"),
    GoalTimeout(Severity.Low, "the goal's tick budget ran out before its success condition"),
    SpawnRelocated(
        Severity.Low,
        "a login hook moved the bot far from its goal's start (e.g. a region lock eviction)",
    ),
    UnimplementedOp(
        Severity.Info,
        "\"Nothing interesting happens.\" engine fallback: op has no script",
    ),
    TickOverrun(Severity.Medium, "a game cycle took longer than the 600ms budget"),
}

data class Finding(
    val kind: FindingKind,
    val severity: Severity,
    val bot: String?,
    val goal: String?,
    val firstTick: Int,
    var lastTick: Int,
    val coords: String?,
    val target: String?,
    val detail: String,
    /** The bot's last few actions before the first occurrence: the repro trail. */
    val recentActions: List<String>,
    var count: Int = 1,
)

data class CoverageStat(
    val label: String,
    var offered: Int = 0,
    var chosen: Int = 0,
    var dropped: Int = 0,
    var unimplemented: Int = 0,
    var unreachable: Int = 0,
)

/** Everything one swarm run observes. Mutated on the world thread only. */
class SwarmTelemetry {
    private val findingsByKey = LinkedHashMap<String, Finding>()
    val coverage = HashMap<String, CoverageStat>()
    val heatmap = Int2IntOpenHashMap()

    /** action label -> obj name -> net count change attributed to that action. */
    val ledger = HashMap<String, HashMap<String, Long>>()
    val cycleMillis = ArrayList<Long>()

    val findings: Collection<Finding>
        get() = findingsByKey.values

    /**
     * Records a finding, folding repeats of the same (kind, target, bot goal) into one entry with a
     * count, so a thousand identical drops read as one finding, not a thousand.
     */
    fun report(
        kind: FindingKind,
        tick: Int,
        bot: String?,
        goal: String?,
        coords: String?,
        target: String?,
        detail: String,
        recentActions: List<String> = emptyList(),
    ) {
        val key = "${kind.name}|$goal|${target ?: coords}"
        val existing = findingsByKey[key]
        if (existing != null) {
            existing.count++
            existing.lastTick = tick
            return
        }
        if (findingsByKey.size >= MAX_FINDINGS) return
        findingsByKey[key] =
            Finding(
                kind = kind,
                severity = kind.severity,
                bot = bot,
                goal = goal,
                firstTick = tick,
                lastTick = tick,
                coords = coords,
                target = target,
                detail = detail,
                recentActions = recentActions,
            )
    }

    fun coverage(key: String, label: String): CoverageStat =
        coverage.getOrPut(key) { CoverageStat(label) }

    fun visit(packedCoord: Int) {
        heatmap.addTo(packedCoord, 1)
    }

    fun ledger(action: String, obj: String, delta: Int) {
        if (delta == 0) return
        val perObj = ledger.getOrPut(action) { HashMap() }
        perObj[obj] = (perObj[obj] ?: 0L) + delta
    }

    private companion object {
        const val MAX_FINDINGS = 2000
    }
}
