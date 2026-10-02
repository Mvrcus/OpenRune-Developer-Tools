package org.rsmod.content.other.botswarm

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap
import org.rsmod.api.config.Constants
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.stat.stat
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.content.other.botswarm.action.ActionCatalog
import org.rsmod.content.other.botswarm.action.ActionExecutor
import org.rsmod.content.other.botswarm.action.BotAction
import org.rsmod.content.other.botswarm.action.BotMode
import org.rsmod.content.other.botswarm.action.Candidate
import org.rsmod.content.other.botswarm.decide.BotDecider
import org.rsmod.content.other.botswarm.decide.DecisionOption
import org.rsmod.content.other.botswarm.decide.DecisionRequest
import org.rsmod.content.other.botswarm.decide.normalise
import org.rsmod.content.other.botswarm.goal.BotGoal
import org.rsmod.content.other.botswarm.report.BotSummary
import org.rsmod.content.other.botswarm.report.CoverageEntry
import org.rsmod.content.other.botswarm.report.CoverageSummary
import org.rsmod.content.other.botswarm.report.ErrorCapture
import org.rsmod.content.other.botswarm.report.FindingKind
import org.rsmod.content.other.botswarm.report.GoalSummary
import org.rsmod.content.other.botswarm.report.HeatTile
import org.rsmod.content.other.botswarm.report.SwarmReport
import org.rsmod.content.other.botswarm.report.SwarmReportWriter
import org.rsmod.content.other.botswarm.report.SwarmTelemetry
import org.rsmod.content.other.botswarm.report.TickStats
import org.rsmod.game.MapClock
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.PathingEntity
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

data class SpawnRequest(val goal: BotGoal, val index: Int)

class SwarmRun(
    val id: String,
    val decider: BotDecider,
    val startedAt: Instant,
    val startTick: Int,
    val maxTicks: Int,
) {
    val telemetry = SwarmTelemetry()
    val errors = ErrorCapture()
    val pendingSpawns = ArrayDeque<SpawnRequest>()
    val bots = ArrayList<BotAgent>()
    val byPlayer = IdentityHashMap<Player, BotAgent>()
    var stopReason: String? = null
    var stopTick: Int? = null
    var lastCycleNanos: Long = 0L
    var onFinished: ((String) -> Unit)? = null
}

/**
 * Drives every bot once per game cycle (on `GameLifecycle.StartCycle`, before the input phase, so a
 * queued click is consumed in the same tick). One run at a time.
 */
class SwarmController(
    private val spawner: BotSpawner,
    private val catalog: ActionCatalog,
    private val executor: ActionExecutor,
    private val mapClock: MapClock,
    private val npcList: NpcList,
    private val locRepo: LocRepository,
    private val writer: SwarmReportWriter,
) {
    private val logger = InlineLogger()

    var run: SwarmRun? = null
        private set

    fun start(
        specs: List<Pair<BotGoal, Int>>,
        decider: BotDecider,
        onFinished: ((String) -> Unit)? = null,
    ): String {
        check(run == null) { "A swarm run is already active (${run?.id})." }
        val id = RUN_ID.format(Instant.now().atOffset(ZoneOffset.UTC))
        val maxTicks = specs.maxOf { it.first.budgetTicks } + DESPAWN_GRACE
        val created = SwarmRun(id, decider, Instant.now(), mapClock.cycle, maxTicks)
        created.onFinished = onFinished
        for ((goal, count) in specs) {
            repeat(count) { created.pendingSpawns += SpawnRequest(goal, it) }
        }
        created.errors.install()
        run = created
        logger.info {
            "Bot swarm: run $id started with ${created.pendingSpawns.size} bots, decider ${decider.name}"
        }
        return id
    }

    fun stop(reason: String) {
        val active = run ?: return
        if (active.stopReason == null) {
            active.stopReason = reason
            active.stopTick = mapClock.cycle
            active.pendingSpawns.clear()
            for (agent in active.bots) agent.finish(mapClock.cycle, reason)
        }
    }

    fun onXp(player: Player, stat: String, xp: Int) {
        val agent = run?.byPlayer?.get(player) ?: return
        agent.xpGained.merge(stat, xp, Int::plus)
        agent.lastProgressTick = mapClock.cycle
    }

    fun onCycle() {
        val active = run ?: return
        val tick = mapClock.cycle
        measureCycle(active, tick)
        spawnPending(active, tick)
        attributeErrors(active, tick)
        for (agent in active.bots) {
            step(active, agent, tick)
        }
        if (tick - active.startTick > active.maxTicks) stop("run tick limit")
        val allGone = active.pendingSpawns.isEmpty() && active.bots.all { it.isRemoved() }
        val graceOver = active.stopTick?.let { tick - it > DESPAWN_GRACE } == true
        if (allGone || graceOver) finish(active, tick)
    }

    fun status(): List<String> {
        val active = run ?: return listOf("No swarm run active.")
        val lines = ArrayList<String>()
        lines +=
            "Run ${active.id}: ${active.bots.size} bots, tick ${mapClock.cycle - active.startTick}, " +
                "${active.telemetry.findings.size} findings, decider ${active.decider.name}"
        for (agent in active.bots.take(STATUS_LINES)) {
            val state =
                when {
                    agent.completedTick != null -> "done"
                    agent.finished -> "finished (${agent.finishReason})"
                    else -> "playing"
                }
            lines +=
                "  ${agent.name} [${agent.goal.id}] $state, ${agent.actions} actions, xp ${agent.xpGained}"
        }
        return lines
    }

    private fun measureCycle(active: SwarmRun, tick: Int) {
        val now = System.nanoTime()
        if (active.lastCycleNanos != 0L) {
            val millis = (now - active.lastCycleNanos) / 1_000_000
            active.telemetry.cycleMillis += millis
            if (millis > TICK_OVERRUN_MS) {
                active.telemetry.report(
                    FindingKind.TickOverrun,
                    tick,
                    bot = null,
                    goal = null,
                    coords = null,
                    target = "cycle",
                    detail =
                        "Cycle took ${millis}ms with ${active.bots.count { !it.isRemoved() }} bots online.",
                )
            }
        }
        active.lastCycleNanos = now
    }

    private fun spawnPending(active: SwarmRun, tick: Int) {
        var budget = SPAWNS_PER_TICK
        while (budget-- > 0) {
            val request = active.pendingSpawns.removeFirstOrNull() ?: return
            val name = "sb${active.bots.size + 1}_${request.goal.id.take(NAME_GOAL_CHARS)}"
            val seed = (active.id.hashCode().toLong() shl 20) xor active.bots.size.toLong()
            val agent = spawner.spawn(name, request.goal, seed)
            if (agent == null) {
                logger.warn { "Bot swarm: no free slot for $name; dropping remaining spawns." }
                active.pendingSpawns.clear()
                return
            }
            agent.itemCounts = itemCounts(agent.player)
            for (stat in TRACKED_STATS) agent.startLevels[stat] = agent.player.statBase(stat)
            active.bots += agent
            active.byPlayer[agent.player] = agent
        }
    }

    private fun attributeErrors(active: SwarmRun, tick: Int) {
        for (error in active.errors.drain()) {
            val text = "${error.message}\n${error.throwable ?: ""}"
            val agent = active.bots.firstOrNull { "username=${it.name}," in text }
            active.telemetry.report(
                FindingKind.ServerException,
                tick,
                bot = agent?.name,
                goal = agent?.goal?.id,
                coords = agent?.player?.coords?.let(::coordString),
                target = error.throwable ?: error.message.take(TARGET_CHARS),
                detail =
                    buildString {
                        appendLine(error.message)
                        error.throwable?.let(::appendLine)
                        error.stack.forEach { appendLine("    at $it") }
                    },
                recentActions = agent?.recent?.toList() ?: emptyList(),
            )
        }
    }

    private fun step(active: SwarmRun, agent: BotAgent, tick: Int) {
        val player = agent.player
        if (agent.isRemoved()) {
            agent.finish(tick, agent.finishReason ?: "removed")
            return
        }
        val telemetry = active.telemetry

        if ((player.pendingLogout || player.loggingOut) && !agent.despawning) {
            telemetry.report(
                FindingKind.ForcedDisconnect,
                tick,
                agent.name,
                agent.goal.id,
                coordString(player.coords),
                agent.last?.action?.coverageKey,
                "Server queued a logout for the bot without a swarm request; see ServerException " +
                    "findings at the same tick for the cause.",
                agent.recent.toList(),
            )
            agent.despawning = true
            agent.finish(tick, "server disconnect")
            return
        }

        if (agent.finished) {
            if (!agent.despawning) spawner.despawn(agent)
            return
        }

        if (!agent.spawnChecked && tick > agent.spawnTick) {
            agent.spawnChecked = true
            val drift = player.coords.chebyshevDistance(agent.anchor)
            if (drift > RELOCATION_TILES) {
                telemetry.report(
                    FindingKind.SpawnRelocated,
                    tick,
                    agent.name,
                    agent.goal.id,
                    coordString(player.coords),
                    agent.goal.id,
                    "Spawned at ${coordString(agent.anchor)} but was at ${coordString(player.coords)} " +
                        "after login ($drift tiles). Check login hooks and the goal's varbits.",
                )
            }
        }

        // Keep the map "loaded": real clients ack every rebuild; bots have none to ack.
        player.lastMapBuildComplete = tick
        telemetry.visit(player.coords.packed)
        observeOutcome(active, agent, tick)
        observeProgress(active, agent, tick)

        if (goalReached(agent)) {
            agent.completedTick = tick
            agent.finish(tick, "goal reached")
            return
        }
        if (tick - agent.spawnTick > agent.goal.budgetTicks) {
            telemetry.report(
                FindingKind.GoalTimeout,
                tick,
                agent.name,
                agent.goal.id,
                coordString(player.coords),
                agent.goal.id,
                "Budget of ${agent.goal.budgetTicks} ticks ran out " +
                    "(${agent.goal.success?.describe() ?: "exploration goal"}); xp ${agent.xpGained}.",
                agent.recent.toList(),
            )
            agent.finish(tick, "budget")
            return
        }

        val mode = catalog.mode(player)
        if (mode == BotMode.UnanswerableInput) {
            val since = agent.trappedSince ?: tick.also { agent.trappedSince = it }
            if (tick - since == DIALOGUE_TRAP_TICKS) {
                telemetry.report(
                    FindingKind.DialogueTrap,
                    tick,
                    agent.name,
                    agent.goal.id,
                    coordString(player.coords),
                    "modals=${player.ui.modals.values.joinToString()}",
                    "Suspended on an input the bot cannot answer for $DIALOGUE_TRAP_TICKS ticks.",
                    agent.recent.toList(),
                )
            }
            return
        }
        agent.trappedSince = null

        val pending = agent.pending
        if (pending != null) {
            val timedOut = tick - pending.requestedTick > DECIDER_TIMEOUT_TICKS
            if (pending.future.isDone || timedOut) {
                agent.pending = null
                resolve(active, agent, pending, tick, timedOut)
            }
            return
        }

        val busy =
            player.isDelayed ||
                player.isBusy2 ||
                (mode == BotMode.World && player.activeCoroutine?.isSuspended == true)
        if (busy) return
        val gap = if (mode == BotMode.World) WORLD_ACTION_GAP else DIALOGUE_ACTION_GAP
        if (tick - agent.lastActionTick < gap) return

        val candidates =
            when (mode) {
                BotMode.PauseDialogue -> catalog.dialogueOptions(player)
                BotMode.CountDialogue -> catalog.countOptions()
                else ->
                    catalog.worldOptions(
                        player,
                        agent.goal,
                        agent.anchor,
                        agent.random,
                        tried = { it in agent.tried },
                        failures = { agent.failures[it] ?: 0 },
                    )
            }
        if (candidates.isEmpty()) return
        for (candidate in candidates) {
            telemetry.coverage(candidate.action.coverageKey, candidate.action.label).offered++
        }
        val request =
            DecisionRequest(
                bot = agent.name,
                goal = goalText(agent.goal),
                context = context(agent),
                options = uniqueLabels(candidates),
            )
        val future = active.decider.decide(request)
        val decision = PendingDecision(future, candidates, mode.name, tick)
        if (future.isDone) {
            resolve(active, agent, decision, tick, timedOut = false)
        } else {
            agent.pending = decision
        }
    }

    private fun resolve(
        active: SwarmRun,
        agent: BotAgent,
        decision: PendingDecision,
        tick: Int,
        timedOut: Boolean,
    ) {
        val priors = normalise(decision.candidates.map { it.prior })
        val probabilities =
            if (timedOut) {
                agent.deciderErrors++
                priors
            } else {
                runCatching { decision.future.getNow(null) }.getOrNull()
                    ?: priors.also { agent.deciderErrors++ }
            }
        val source =
            if (probabilities === priors && active.decider.name != "prior") "fallback"
            else active.decider.name
        val index = sample(probabilities, agent)
        val chosen = decision.candidates[index]
        val probe = executor.execute(agent.player, agent.client, chosen.action)
        agent.last = ExecutedAction(chosen.action, tick, probe)
        agent.lastActionTick = tick
        agent.actions++
        agent.tried += chosen.action.coverageKey
        agent.remember(chosen.action.label)
        active.telemetry.coverage(chosen.action.coverageKey, chosen.action.label).chosen++
        val top =
            probabilities.indices
                .sortedByDescending { probabilities[it] }
                .take(TRACE_ALTERNATIVES)
                .map {
                    "${decision.candidates[it].action.label}=${"%.2f".format(probabilities[it])}"
                }
        agent.record(
            TraceEntry(
                tick = tick,
                mode = decision.mode,
                chosen = chosen.action.label,
                probability = probabilities[index],
                top = top,
                source = source,
            )
        )
    }

    /** Reads what the last click led to: dropped by a gate, engine fallback, or unreachable. */
    private fun observeOutcome(active: SwarmRun, agent: BotAgent, tick: Int) {
        val telemetry = active.telemetry
        val last = agent.last
        val chat = agent.client.drainChat()
        if (last == null) return
        val action = last.action
        val key = action.coverageKey
        val coords = coordString(agent.player.coords)

        if (last.probe.consumed && last.probe.dropped && targetStillValid(action)) {
            last.probe.dropped = false
            agent.failures.merge(key, 1, Int::plus)
            telemetry.coverage(key, action.label).dropped++
            telemetry.report(
                FindingKind.ClickDropped,
                tick,
                agent.name,
                agent.goal.id,
                coords,
                "$key (${action.label})",
                "The client shows '${action.label}' but the packet handler set no interaction " +
                    "(hasOp gate or lookup failed) while the target was still present.",
                agent.recent.toList(),
            )
        }
        for (message in chat) {
            when {
                message.message == Constants.dm_default && message.type == ChatType.Engine.id -> {
                    agent.failures.merge(key, 1, Int::plus)
                    telemetry.coverage(key, action.label).unimplemented++
                    telemetry.report(
                        FindingKind.UnimplementedOp,
                        tick,
                        agent.name,
                        agent.goal.id,
                        coords,
                        "$key (${action.label})",
                        "Engine fallback 'Nothing interesting happens.': no script is bound.",
                        agent.recent.toList(),
                    )
                }
                message.message == Constants.dm_reach -> {
                    agent.failures.merge(key, 1, Int::plus)
                    telemetry.coverage(key, action.label).unreachable++
                    telemetry.report(
                        FindingKind.Unreachable,
                        tick,
                        agent.name,
                        agent.goal.id,
                        coords,
                        "$key (${action.label})",
                        "\"I can't reach that!\" for an option offered within 15 tiles.",
                        agent.recent.toList(),
                    )
                }
            }
        }
    }

    private fun observeProgress(active: SwarmRun, agent: BotAgent, tick: Int) {
        val player = agent.player
        val counts = itemCounts(player)
        if (counts != agent.itemCounts) {
            val label = agent.last?.action?.label ?: "spawn"
            for (obj in counts.keys + agent.itemCounts.keys) {
                val delta = (counts[obj] ?: 0) - (agent.itemCounts[obj] ?: 0)
                if (delta != 0) active.telemetry.ledger(label, objName(obj), delta)
            }
            agent.itemCounts = counts
            agent.lastProgressTick = tick
        }
        if (player.coords != agent.lastCoords) {
            agent.lastCoords = player.coords
            agent.lastProgressTick = tick
        }
        if (player.stat("stat.hitpoints") == 0 && agent.recent.lastOrNull() != DEATH_MARK) {
            agent.deaths++
            agent.remember(DEATH_MARK)
        }
        if (!agent.stuckReported && tick - agent.lastProgressTick >= STUCK_TICKS) {
            agent.stuckReported = true
            active.telemetry.report(
                FindingKind.Stuck,
                tick,
                agent.name,
                agent.goal.id,
                coordString(player.coords),
                coordString(player.coords),
                "No movement, xp or item change for $STUCK_TICKS ticks " +
                    "(interaction=${player.interaction}, delayed=${player.isDelayed}, " +
                    "coroutine=${player.activeCoroutine}).",
                agent.recent.toList(),
            )
        }
    }

    private fun goalReached(agent: BotAgent): Boolean {
        val success = agent.goal.success ?: return false
        val player = agent.player
        return when (success.type) {
            "xp_gained" -> (agent.xpGained[success.stat] ?: 0) >= success.xp
            "stat_level" -> success.stat != null && player.statBase(success.stat) >= success.level
            "item_count" -> {
                val id = success.obj?.let { runCatching { it.asRSCM(RSCMType.OBJ) }.getOrNull() }
                id != null && (agent.itemCounts[id] ?: 0) >= success.count
            }
            else -> false
        }
    }

    private fun targetStillValid(action: BotAction): Boolean =
        when (action) {
            is BotAction.OpLoc -> locRepo.findAll(action.coords).any { it.id == action.locId }
            is BotAction.OpNpc -> {
                val npc = npcList[action.slot]
                npc != null && npc.id == action.npcId && npc.isVisible && !npc.isDelayed
            }
            else -> true
        }

    private fun finish(active: SwarmRun, tick: Int) {
        run = null
        active.errors.uninstall()
        val reason = active.stopReason ?: "all bots finished"
        val report = buildReport(active, tick, reason)
        writer.write(report) { dir ->
            active.onFinished?.invoke(dir?.toAbsolutePath()?.toString() ?: "write failed")
        }
        logger.info {
            "Bot swarm: run ${active.id} finished ($reason): ${report.findings.size} findings, " +
                "${report.goals.sumOf { it.completed }}/${report.bots.size} goals completed."
        }
    }

    private fun buildReport(active: SwarmRun, tick: Int, reason: String): SwarmReport {
        val telemetry = active.telemetry
        val goals =
            active.bots
                .groupBy { it.goal.id }
                .map { (id, agents) ->
                    val goal = agents.first().goal
                    val completedTicks =
                        agents
                            .mapNotNull { a -> a.completedTick?.let { it - a.spawnTick } }
                            .sorted()
                    val playedTicks = agents.sumOf { ticksPlayed(it, tick) }.coerceAtLeast(1)
                    val xp = HashMap<String, Long>()
                    for (agent in agents) {
                        for ((stat, gained) in agent.xpGained) xp.merge(
                            stat,
                            gained.toLong(),
                            Long::plus,
                        )
                    }
                    GoalSummary(
                        id = id,
                        description = goal.description,
                        success = goal.success?.describe(),
                        bots = agents.size,
                        completed = completedTicks.size,
                        medianTicksToComplete = completedTicks.getOrNull(completedTicks.size / 2),
                        xpPerHour = xp.mapValues { it.value * TICKS_PER_HOUR / playedTicks },
                    )
                }
        val cycles = telemetry.cycleMillis.sorted()
        val coverageEntries =
            telemetry.coverage.entries
                .map { (key, s) ->
                    CoverageEntry(
                        key,
                        s.label,
                        s.offered,
                        s.chosen,
                        s.dropped,
                        s.unimplemented,
                        s.unreachable,
                    )
                }
                .sortedByDescending { it.offered }
        val heat =
            telemetry.heatmap
                .int2IntEntrySet()
                .sortedByDescending { it.intValue }
                .take(MAX_HEAT_TILES)
                .map {
                    val coords = CoordGrid(it.intKey)
                    HeatTile(coords.x, coords.z, coords.level, it.intValue)
                }
        return SwarmReport(
            runId = active.id,
            startedAt = active.startedAt.toString(),
            finishedAt = Instant.now().toString(),
            stopReason = reason,
            decider = active.decider.name,
            ticks = tick - active.startTick,
            goals = goals,
            findings = telemetry.findings.toList(),
            coverage =
                CoverageSummary(
                    distinctOffered = coverageEntries.size,
                    distinctChosen = coverageEntries.count { it.chosen > 0 },
                    entries = coverageEntries,
                ),
            tickStats =
                TickStats(
                    cycles = cycles.size,
                    avgMs = if (cycles.isEmpty()) 0.0 else cycles.average(),
                    p95Ms = cycles.getOrNull((cycles.size * 95) / 100) ?: 0,
                    maxMs = cycles.lastOrNull() ?: 0,
                    overruns = cycles.count { it > TICK_OVERRUN_MS },
                ),
            ledger = telemetry.ledger,
            heatmap = heat,
            bots = active.bots.map { agent -> summary(agent, ticksPlayed(agent, tick)) },
        )
    }

    private fun summary(agent: BotAgent, ticksPlayed: Int): BotSummary =
        BotSummary(
            name = agent.name,
            goal = agent.goal.id,
            seed = agent.seed,
            completed = agent.completedTick != null,
            ticksToComplete = agent.completedTick?.let { it - agent.spawnTick },
            ticksPlayed = ticksPlayed,
            finishReason = agent.finishReason,
            actions = agent.actions,
            deaths = agent.deaths,
            deciderErrors = agent.deciderErrors,
            xpGained = agent.xpGained.toMap(),
            trace = agent.trace.toList(),
        )

    private fun ticksPlayed(agent: BotAgent, tick: Int): Int =
        (agent.finishedTick ?: tick) - agent.spawnTick

    private fun sample(probabilities: DoubleArray, agent: BotAgent): Int {
        var roll = agent.random.nextDouble()
        for (i in probabilities.indices) {
            roll -= probabilities[i]
            if (roll <= 0.0) return i
        }
        return probabilities.lastIndex
    }

    private fun uniqueLabels(candidates: List<Candidate>): List<DecisionOption> {
        val seen = HashMap<String, Int>()
        return candidates.map { candidate ->
            val base = candidate.action.label
            val n = seen.merge(base, 1, Int::plus)!!
            DecisionOption(if (n == 1) base else "$base ($n)", candidate.prior)
        }
    }

    private fun goalText(goal: BotGoal): String =
        goal.success?.let { "${goal.description}: ${it.describe()}" } ?: goal.description

    /** A short state summary for model deciders; Laya's English checkpoint reads ~512 tokens. */
    private fun context(agent: BotAgent): String {
        val player = agent.player
        val inv =
            agent.itemCounts.entries
                .filter { entry -> player.inv.objs.any { it?.id == entry.key } }
                .take(CONTEXT_ITEMS)
                .joinToString { "${objName(it.key)} x${it.value}" }
        val stats =
            TRACKED_STATS.filter { player.statBase(it) > 1 || (agent.xpGained[it] ?: 0) > 0 }
                .joinToString { "${it.removePrefix("stat.")} ${player.statBase(it)}" }
        val last = agent.last?.action?.label ?: "none"
        return "Goal: ${goalText(agent.goal)}. Progress: xp ${agent.xpGained}. Levels: $stats. " +
            "Hitpoints ${player.stat("stat.hitpoints")}. " +
            "Inventory ${player.inv.occupiedSpace()}/${player.inv.size}: $inv. Last action: $last."
    }

    private fun BotAgent.isRemoved(): Boolean = player.slotId == PathingEntity.INVALID_SLOT

    private fun itemCounts(player: Player): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (obj in player.inv.objs) if (obj != null) counts.merge(obj.id, obj.count, Int::plus)
        for (obj in player.worn.objs) if (obj != null) counts.merge(obj.id, obj.count, Int::plus)
        return counts
    }

    private fun objName(id: Int): String = ServerCacheManager.getItem(id)?.name ?: "obj#$id"

    private fun coordString(coords: CoordGrid): String = "${coords.x},${coords.z},${coords.level}"

    private companion object {
        val RUN_ID: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        const val SPAWNS_PER_TICK = 5
        const val NAME_GOAL_CHARS = 8
        const val WORLD_ACTION_GAP = 2
        const val DIALOGUE_ACTION_GAP = 1
        const val DECIDER_TIMEOUT_TICKS = 10
        const val DIALOGUE_TRAP_TICKS = 25
        const val STUCK_TICKS = 150
        const val RELOCATION_TILES = 64
        const val DESPAWN_GRACE = 100
        const val TICK_OVERRUN_MS = 700L
        const val TICKS_PER_HOUR = 6000L
        const val TRACE_ALTERNATIVES = 3
        const val TARGET_CHARS = 120
        const val CONTEXT_ITEMS = 8
        const val STATUS_LINES = 20
        const val MAX_HEAT_TILES = 2000
        const val DEATH_MARK = "(died)"
        val TRACKED_STATS =
            listOf(
                "stat.attack",
                "stat.strength",
                "stat.defence",
                "stat.hitpoints",
                "stat.ranged",
                "stat.prayer",
                "stat.magic",
                "stat.cooking",
                "stat.woodcutting",
                "stat.fishing",
                "stat.firemaking",
                "stat.crafting",
                "stat.mining",
                "stat.smithing",
            )
    }
}
