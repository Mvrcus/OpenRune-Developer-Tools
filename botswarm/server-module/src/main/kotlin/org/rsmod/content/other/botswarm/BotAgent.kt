package org.rsmod.content.other.botswarm

import java.util.concurrent.CompletableFuture
import kotlin.random.Random
import org.rsmod.content.other.botswarm.action.ActionProbe
import org.rsmod.content.other.botswarm.action.BotAction
import org.rsmod.content.other.botswarm.action.Candidate
import org.rsmod.content.other.botswarm.goal.BotGoal
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

/** One decision as recorded in the run trace. */
data class TraceEntry(
    val tick: Int,
    val mode: String,
    val chosen: String,
    val probability: Double,
    /** Top alternatives as `label=p`, so a reviewer can see what else the decider considered. */
    val top: List<String>,
    val source: String,
)

class PendingDecision(
    val future: CompletableFuture<DoubleArray>,
    val candidates: List<Candidate>,
    val mode: String,
    val requestedTick: Int,
)

class ExecutedAction(val action: BotAction, val tick: Int, val probe: ActionProbe)

/** Per-bot state. Lives on the world thread; never touched from decider threads. */
class BotAgent(
    val name: String,
    val player: Player,
    val client: BotClient,
    val goal: BotGoal,
    val seed: Long,
    val spawnTick: Int,
    val anchor: CoordGrid,
) {
    val random: Random = Random(seed)
    var pending: PendingDecision? = null
    var last: ExecutedAction? = null
    var lastActionTick: Int = spawnTick
    val recent: ArrayDeque<String> = ArrayDeque()
    val trace: ArrayList<TraceEntry> = ArrayList()

    val xpGained: HashMap<String, Int> = HashMap()
    val startLevels: HashMap<String, Int> = HashMap()
    var lastProgressTick: Int = spawnTick
    var lastCoords: CoordGrid = player.coords
    var itemCounts: Map<Int, Int> = emptyMap()

    val tried: HashSet<String> = HashSet()
    val failures: HashMap<String, Int> = HashMap()

    var completedTick: Int? = null
    var finishedTick: Int? = null
    var finishReason: String? = null
    var despawning: Boolean = false
    var trappedSince: Int? = null
    var stuckReported: Boolean = false
    var spawnChecked: Boolean = false
    var deaths: Int = 0
    var actions: Int = 0
    var deciderErrors: Int = 0

    val finished: Boolean
        get() = finishedTick != null

    fun remember(label: String) {
        recent.addLast(label)
        while (recent.size > RECENT) recent.removeFirst()
    }

    fun record(entry: TraceEntry) {
        if (trace.size < MAX_TRACE) trace += entry
    }

    fun finish(tick: Int, reason: String) {
        if (finishedTick == null) {
            finishedTick = tick
            finishReason = reason
        }
    }

    private companion object {
        const val RECENT = 8
        const val MAX_TRACE = 600
    }
}
