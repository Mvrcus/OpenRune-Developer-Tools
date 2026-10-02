package org.rsmod.content.other.botswarm.replay

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.types.util.UncheckedType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.content.other.botswarm.BotAgent
import org.rsmod.content.other.botswarm.BotSpawner
import org.rsmod.content.other.botswarm.action.ActionCatalog
import org.rsmod.content.other.botswarm.action.ActionExecutor
import org.rsmod.content.other.botswarm.action.ActionProbe
import org.rsmod.content.other.botswarm.action.BotAction
import org.rsmod.content.other.botswarm.goal.BotGoal
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.inv.InvObj
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.stat.PlayerStatMap
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

/**
 * Replays one [ReplayScenario] with a single recorded bot (plus optional unrecorded companions) and
 * writes `<runs>/<scenario>-<time>/ours.json`, the input to `tools/capture-diff/capture_diff.py`.
 */
class ReplayController(
    private val spawner: BotSpawner,
    private val executor: ActionExecutor,
    private val catalog: ActionCatalog,
    private val mapClock: MapClock,
    private val npcRepo: NpcRepository,
    private val mapper: ObjectMapper,
    private val runsDir: Path,
) {
    private val logger = InlineLogger()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Active(
        val scenario: ReplayScenario,
        val main: BotAgent,
        val companions: List<Pair<BotAgent, ReplayCompanion>>,
        val tick0: Int,
        val onDone: (Path?) -> Unit,
    ) {
        var recorder: ReplayRecorder? = null
        var uiAtStart: Map<String, Any?> = emptyMap()
        val stepLog = ArrayList<MutableMap<String, Any?>>()
        /** Step index -> what the handler did with the click (read when the run finishes). */
        val probes = HashMap<Int, ActionProbe>()
    }

    /** Replays in flight. Several can run at once; they share one world, like real players. */
    private val actives = ArrayList<Active>()
    private var runs = 0

    val activeCount: Int
        get() = actives.size

    fun start(scenario: ReplayScenario, onDone: (Path?) -> Unit = {}) {
        val goal =
            BotGoal(
                id = "replay-${scenario.id}",
                description = scenario.description,
                start = scenario.start,
                varbits = scenario.varbits,
                varps = scenario.varps,
                inventory = scenario.inventory,
                budgetTicks = Int.MAX_VALUE,
            )
        val main =
            spawner.spawn("rp${++runs}_main", goal.copy(start = goal.start.copy(scatter = 0)), SEED)
                ?: error("No free player slot for the replay bot.")
        val companions =
            scenario.companions.mapIndexed { index, companion ->
                val agent =
                    spawner.spawn("rp${runs}_comp${index + 1}", goal, SEED + index + 1)
                        ?: error("No free player slot for replay companion ${index + 1}.")
                agent to companion
            }
        seedStats(main, scenario)
        companions.forEach { (agent, _) -> seedStats(agent, scenario) }
        seedInventories(main, scenario)
        companions.forEach { (agent, _) -> seedInventories(agent, scenario) }
        actives += Active(scenario, main, companions, mapClock.cycle + scenario.warmupTicks, onDone)
        logger.info {
            "Bot swarm: replay ${scenario.id} starting in ${scenario.warmupTicks} ticks " +
                "(${scenario.steps.size} steps, ${companions.size} companions)"
        }
    }

    /**
     * Puts the captured inventory and worn items in their exact slots, so item events (e.g. which
     * slot a granted item lands in) line up with live. Written straight into server state: the bot
     * has no client to resync, and recording starts after warmup.
     */
    @OptIn(UncheckedType::class)
    private fun seedInventories(agent: BotAgent, scenario: ReplayScenario) {
        for ((key, slots) in scenario.invSlots) {
            val inv =
                when (key) {
                    "93" -> agent.player.inv
                    "94" -> agent.player.worn
                    "95" -> agent.player.invMap.getOrPut("inv.bank")
                    else -> continue
                }
            inv.objs.fill(null)
            for ((slot, obj, count) in slots) {
                if (slot in inv.objs.indices) inv.objs[slot] = InvObj(obj, count)
            }
        }
    }

    /** Runs on `GameLifecycle.LateCycle`: samples combat before post-tick clears it. */
    fun onLateCycle() {
        for (run in actives) {
            val recorder = run.recorder ?: continue
            recorder.sampleCombat(npcRepo.findAll(ZoneKey.from(run.main.player.coords), 2))
        }
    }

    /** Runs on `GameLifecycle.StartCycle`, before the input phase consumes queued clicks. */
    fun onCycle() {
        for (run in actives.toList()) cycle(run)
    }

    private fun cycle(run: Active) {
        val tick = mapClock.cycle
        for (agent in listOf(run.main) + run.companions.map { it.first }) {
            agent.player.lastMapBuildComplete = tick
        }
        if (tick < run.tick0) {
            // Negative-tick steps are setup clicks during warmup (not recorded, not diffed).
            for (step in run.scenario.steps) {
                if (step.tick < 0 && run.tick0 + step.tick == tick)
                    execute(run, run.main, step, log = true)
            }
            return
        }

        val recorder =
            run.recorder
                ?: ReplayRecorder(run.main.player).also {
                    run.recorder = it
                    run.main.client.recorder = it::record
                    run.uiAtStart = uiSnapshot(run.main)
                }
        val rel = tick - run.tick0
        if (rel > 0) recorder.endTick()
        recorder.tick = rel

        if (rel > run.scenario.durationTicks) {
            finish(run)
            return
        }
        for (step in run.scenario.steps) {
            if (step.tick == rel) execute(run, run.main, step, log = true)
        }
        for ((agent, companion) in run.companions) {
            for (step in companion.steps) {
                if (step.tick == rel) execute(run, agent, step, log = false)
            }
        }
    }

    private fun execute(run: Active, agent: BotAgent, step: ReplayStep, log: Boolean) {
        var probe: ActionProbe? = null
        val action: BotAction? =
            when (step.kind) {
                "button" -> {
                    val (iface, comp) = step.com?.split(':')?.map(String::toInt) ?: listOf(-1, -1)
                    BotAction.Button(
                        iface,
                        comp,
                        step.sub,
                        step.obj,
                        step.op,
                        step.name ?: "${step.com}",
                    )
                }
                "continue" ->
                    step.com?.split(':')?.map(String::toInt)?.let { (iface, comp) ->
                        BotAction.ResumeComponent(iface, comp, step.sub)
                    } ?: catalog.dialogueOptions(agent.player).firstOrNull()?.action
                "count" -> BotAction.CountDialog(step.count)
                "walk" -> BotAction.Walk(step.coords, "to ${step.x},${step.z}")
                "oploc" ->
                    BotAction.OpLoc(
                        step.loc,
                        step.coords,
                        step.op,
                        "op${step.op}",
                        step.name ?: "loc",
                    )
                "take" -> BotAction.TakeObj(step.obj, step.coords, step.name ?: "obj")
                "script" ->
                    step.ids(step.com)?.let { (iface, comp) ->
                        BotAction.ScriptTrigger(
                            iface,
                            comp,
                            step.sub,
                            step.obj,
                            step.crc,
                            ByteArray(step.payload.size) { step.payload[it].toByte() },
                        )
                    }
                "drag",
                "use_on_com" -> {
                    val from = step.ids(step.com)
                    val to = step.ids(step.target)
                    if (from == null || to == null) null
                    else
                        BotAction.ComponentPair(
                            drag = step.kind == "drag",
                            fromInterface = from.first,
                            fromComponent = from.second,
                            fromSub = step.sub,
                            fromObj = step.obj,
                            toInterface = to.first,
                            toComponent = to.second,
                            toSub = step.targetSub,
                            toObj = step.targetObj,
                        )
                }
                "use_on_loc" ->
                    step.ids(step.com)?.let { (iface, comp) ->
                        BotAction.UseOnLoc(step.loc, step.coords, iface, comp, step.sub, step.obj)
                    }
                "use_on_npc" -> {
                    val npc = nearestNpc(step)
                    val selected = step.ids(step.com)
                    if (npc == null || selected == null) null
                    else
                        BotAction.UseOnNpc(
                            npc.slotId,
                            npc.id,
                            selected.first,
                            selected.second,
                            step.sub,
                            step.obj,
                        )
                }
                "opnpc" -> {
                    val npc = nearestNpc(step)
                    if (npc == null) null
                    else
                        BotAction.OpNpc(
                            npc.slotId,
                            npc.id,
                            step.op,
                            "op${step.op}",
                            step.name ?: "npc",
                        )
                }
                else -> null
            }
        val note =
            when {
                action == null && step.kind == "continue" -> "no pause dialogue open"
                action == null && (step.kind == "opnpc" || step.kind == "use_on_npc") ->
                    "npc ${step.npc} not found within $NPC_SEARCH_TILES tiles of ${step.x},${step.z}"
                action == null -> "unknown step kind '${step.kind}'"
                else -> {
                    val gate = (action as? BotAction.Button)?.let { gateNote(agent, it) }
                    probe = executor.execute(agent.player, agent.client, action)
                    if (gate == null) action.label else "${action.label} [$gate]"
                }
            }
        if (log) {
            run.stepLog +=
                linkedMapOf(
                    "tick" to step.tick,
                    "kind" to step.kind,
                    "com" to step.com,
                    "note" to note,
                )
            probe?.let { run.probes[run.stepLog.size - 1] = it }
        }
    }

    private val ReplayStep.coords: CoordGrid
        get() = CoordGrid(x, z, level)

    /** `"iface:comp"` -> (iface, comp). */
    private fun ReplayStep.ids(com: String?): Pair<Int, Int>? =
        com?.split(':')?.takeIf { it.size == 2 }?.let { (a, b) -> a.toInt() to b.toInt() }

    /** Npc indexes differ between servers: find the nearest npc of the recorded type instead. */
    private fun nearestNpc(step: ReplayStep): Npc? {
        val coords = step.coords
        return npcRepo
            .findAll(ZoneKey.from(coords), 1)
            .filter { (it.id == step.npc || it.visType.id == step.npc) && it.isVisible }
            .filter { it.coords.chebyshevDistance(coords) <= NPC_SEARCH_TILES }
            .minByOrNull { it.coords.chebyshevDistance(coords) }
    }

    /**
     * Levels and xp from the capture. Base level comes from total xp; when the transcript's xp is
     * implausibly low for the shown level (RSProx decodes post-login stat xp unreliably) the shown
     * level is used instead.
     */
    private fun seedStats(agent: BotAgent, scenario: ReplayScenario) {
        val player = agent.player
        for ((name, values) in scenario.stats) {
            val level = values.getOrNull(0) ?: continue
            val xp = (values.getOrNull(1) ?: 0).coerceIn(0, PlayerStatMap.MAX_XP)
            val stat = "stat.$name"
            runCatching {
                    var base = PlayerSkillXPTable.getLevelFromXP(xp).coerceIn(1, MAX_BASE_LEVEL)
                    if (base < minOf(level, MAX_BASE_LEVEL) - LEVEL_SLACK) {
                        base = level.coerceIn(1, MAX_BASE_LEVEL)
                    }
                    player.statMap.setXP(stat, maxOf(xp, PlayerSkillXPTable.getXPFromLevel(base)))
                    player.statMap.setBaseLevel(stat, base.toByte())
                    player.statMap.setCurrentLevel(
                        stat,
                        level.coerceIn(0, Byte.MAX_VALUE.toInt()).toByte(),
                    )
                }
                .onFailure { logger.debug { "Bot swarm: replay skipped stat '$name'" } }
        }
    }

    /**
     * Mirrors `If3ButtonHandler`'s gates so a dropped replay click says why: the interface must be
     * open (toplevel, overlay or modal) and the op enabled statically or by `if_setevents`.
     */
    private fun gateNote(agent: BotAgent, button: BotAction.Button): String? {
        val ui = agent.player.ui
        val open =
            ui.topLevel.id == button.interfaceId ||
                button.interfaceId in ui.overlays.values ||
                button.interfaceId in ui.modals.values
        if (!open) return "interface ${button.interfaceId} not open"
        val type =
            runCatching {
                    ServerCacheManager.fromComponent(
                        (button.interfaceId shl 16) or button.component
                    )
                }
                .getOrNull() ?: return "unknown component"
        val event = IfEvent.entries.firstOrNull { it.name == "Op${button.op}" } ?: return null
        val enabled =
            if (button.sub == -1) type.hasEvent(event) || ui.hasEvent(type, -1, event)
            else ui.hasEvent(type, button.sub, event)
        return if (enabled) null else "Op${button.op} not enabled on sub ${button.sub}"
    }

    private fun uiSnapshot(agent: BotAgent): Map<String, Any?> {
        val ui = agent.player.ui
        fun entries(map: Iterable<Map.Entry<Int, Int>>) =
            map.associate { (k, v) -> "${k ushr 16}:${k and 0xFFFF}" to v }
        return linkedMapOf(
            "toplevel" to ui.topLevel.id,
            "overlays" to entries(ui.overlays.entries()),
            "modals" to entries(ui.modals.entries()),
        )
    }

    private fun finish(run: Active) {
        actives -= run
        for ((index, probe) in run.probes) {
            val entry = run.stepLog[index]
            entry["note"] =
                when {
                    !probe.consumed -> "${entry["note"]} [never consumed]"
                    probe.dropped -> "${entry["note"]} [dropped by handler: no interaction set]"
                    else -> entry["note"]
                }
        }
        run.main.client.recorder = null
        spawner.despawn(run.main)
        run.companions.forEach { spawner.despawn(it.first) }
        val events = run.recorder?.events?.toList().orEmpty()
        val stamp = STAMP.format(Instant.now().atOffset(ZoneOffset.UTC))
        val dir = runsDir.resolve("${run.scenario.id}-$stamp-${run.main.name}")
        val payload =
            linkedMapOf(
                "scenario" to run.scenario.id,
                "source" to run.scenario.source,
                "companions" to run.companions.size,
                "ui_at_start" to run.uiAtStart,
                "ui_at_end" to uiSnapshot(run.main),
                "steps" to run.stepLog,
                "events" to events,
            )
        io.launch {
            val written = runCatching {
                Files.createDirectories(dir)
                mapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("ours.json").toFile(), payload)
                dir
            }
            written.onFailure { logger.error(it) { "Bot swarm: could not write replay to $dir" } }
            written.onSuccess {
                logger.info {
                    "Bot swarm: replay ${run.scenario.id} recorded to ${it.toAbsolutePath()}"
                }
            }
            run.onDone(written.getOrNull())
        }
    }

    private companion object {
        const val NPC_SEARCH_TILES = 12
        const val MAX_BASE_LEVEL = 99
        const val LEVEL_SLACK = 10
        const val SEED = 0x5EEDL
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
