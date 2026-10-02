package org.rsmod.content.other.botswarm

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.parsers.json.Json
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.stat.XpListeners
import org.rsmod.api.realm.Realm
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.content.other.botswarm.action.ActionCatalog
import org.rsmod.content.other.botswarm.action.ActionExecutor
import org.rsmod.content.other.botswarm.decide.BotDecider
import org.rsmod.content.other.botswarm.decide.HttpDecider
import org.rsmod.content.other.botswarm.decide.PriorDecider
import org.rsmod.content.other.botswarm.decide.UniformDecider
import org.rsmod.content.other.botswarm.goal.BotGoal
import org.rsmod.content.other.botswarm.goal.GoalLibrary
import org.rsmod.content.other.botswarm.replay.ReplayController
import org.rsmod.content.other.botswarm.replay.ReplayScenario
import org.rsmod.content.other.botswarm.report.SwarmReportWriter
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.NpcList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Headless bot swarm for automated play-testing. See the bot swarm README.
 *
 * In game (admin): `::swarm start <goal|all> [count] [prior|uniform|laya|<url>]`, `::swarm status`,
 * `::swarm stop`, `::swarm goals`, `::swarm reload`.
 *
 * Unattended (CI / nightly): set `BOTSWARM=goal:count,goal:count` before boot. Optional
 * `BOTSWARM_DECIDER` (same values as above), `BOTSWARM_TICKS` (cap each goal's budget) and
 * `BOTSWARM_EXIT=1` to shut the server down gracefully once the report is written.
 *
 * Only runs on a dev-mode realm, or with `-Dbotswarm.allow=true`: never on the live world.
 */
class BotSwarmScript
@Inject
constructor(
    spawner: BotSpawner,
    catalog: ActionCatalog,
    executor: ActionExecutor,
    mapClock: MapClock,
    npcList: NpcList,
    locRepo: LocRepository,
    npcRepo: NpcRepository,
    @Json private val mapper: ObjectMapper,
    private val realm: Realm,
) : PluginScript() {
    private val logger = InlineLogger()
    private val dataDir = Path.of(".data", "botswarm")
    private val goals = GoalLibrary(mapper, dataDir.resolve("goals"))
    private val controller =
        SwarmController(
            spawner,
            catalog,
            executor,
            mapClock,
            npcList,
            locRepo,
            SwarmReportWriter(mapper, dataDir.resolve("runs")),
        )
    private val replays =
        ReplayController(
            spawner,
            executor,
            catalog,
            mapClock,
            npcRepo,
            mapper,
            dataDir.resolve("replays"),
        )
    private var autostartIn: Int = AUTOSTART_DELAY_CYCLES
    private val replayQueue = ArrayDeque<String>()
    private var replayCooldown = 0
    private var replayParallel = 1
    private var exitAfterReplays = false
    private val pendingReplayWrites = AtomicInteger(0)

    override fun ScriptContext.startup() {
        goals.load()
        XpListeners.register { player, stat, xp -> controller.onXp(player, stat, xp) }
        onEvent<GameLifecycle.StartCycle> {
            autostart()
            controller.onCycle()
            replays.onCycle()
            drainReplayQueue()
        }
        onEvent<GameLifecycle.LateCycle> { replays.onLateCycle() }
        onEvent<GameLifecycle.Shutdown> { controller.stop("server shutdown") }
        onCommand("swarm") {
            internal = "modlevel.admin"
            desc =
                "Headless test bots: start <goal|all> [count] [decider] | replay <scenario> | " +
                    "status | stop | goals"
            cheat { swarmCommand() }
        }
    }

    private fun Cheat.swarmCommand() {
        when (args.firstOrNull()?.lowercase()) {
            "start" -> {
                if (!allowed()) {
                    player.mes("Bot swarm is disabled: this realm is not in dev mode.")
                    return
                }
                val goalArg =
                    args.getOrNull(1)
                        ?: return player.mes("Usage: ::swarm start <goal|all> [count] [decider]")
                val count = args.getOrNull(2)?.toIntOrNull() ?: DEFAULT_BOTS
                val decider =
                    decider(args.getOrNull(3)) ?: return player.mes("Unknown decider '${args[3]}'.")
                val specs =
                    resolve(goalArg, count)
                        ?: return player.mes("Unknown goal '$goalArg'. Try ::swarm goals")
                val id =
                    runCatching { controller.start(specs, decider) }
                        .getOrElse {
                            return player.mes(it.message ?: "$it")
                        }
                player.mes(
                    "Bot swarm run $id: ${specs.sumOf { it.second }} bots, decider ${decider.name}."
                )
            }
            "stop" -> {
                controller.stop("stopped by ${player.username}")
                player.mes("Bot swarm stopping; the report is written once bots log out.")
            }
            "goals" -> goals.goals.values.forEach { player.mes("${it.id}: ${it.description}") }
            "replay" -> {
                if (!allowed()) {
                    player.mes("Bot swarm is disabled: this realm is not in dev mode.")
                    return
                }
                val id = args.getOrNull(1) ?: return player.mes("Usage: ::swarm replay <scenario>")
                val scenario = loadScenario(id) ?: return player.mes("No scenario '$id'.")
                runCatching { replays.start(scenario) }
                    .onSuccess {
                        player.mes("Replaying $id; ours.json lands in .data/botswarm/replays.")
                    }
                    .onFailure { player.mes(it.message ?: "$it") }
            }
            "reload" -> {
                goals.load()
                player.mes("Bot swarm: ${goals.goals.size} goals loaded.")
            }
            else -> controller.status().forEach { player.mes(it) }
        }
    }

    /**
     * Runs queued replays one at a time (`BOTSWARM_REPLAY=a,b,c`), leaving a few ticks between them
     * so the previous bots finish logging out, and exits once every recording is written.
     */
    private fun drainReplayQueue() {
        if (replayCooldown > 0) {
            replayCooldown--
            return
        }
        if (replays.activeCount >= replayParallel) return
        val next = replayQueue.removeFirstOrNull()
        if (next == null) {
            if (exitAfterReplays && replays.activeCount == 0 && pendingReplayWrites.get() == 0) {
                exitAfterReplays = false
                Thread({ System.exit(0) }, "botswarm-exit").start()
            }
            return
        }
        val scenario = loadScenario(next)
        if (scenario == null) {
            logger.warn { "Bot swarm: replay scenario '$next' not found; skipping." }
            return
        }
        pendingReplayWrites.incrementAndGet()
        runCatching {
                replays.start(scenario) { dir ->
                    logger.info { "Bot swarm: replay $next recorded at $dir" }
                    pendingReplayWrites.decrementAndGet()
                }
            }
            .onFailure {
                pendingReplayWrites.decrementAndGet()
                logger.warn(it) { "Bot swarm: replay $next could not start" }
            }
        // Sequential runs leave a gap so the previous bots finish logging out; parallel batches
        // start together.
        replayCooldown = if (replayParallel > 1) 0 else REPLAY_GAP_TICKS
    }

    private fun autostart() {
        if (autostartIn < 0) return
        if (autostartIn-- > 0) return
        val replayIds = System.getenv("BOTSWARM_REPLAY")?.takeIf { it.isNotBlank() }
        if (replayIds != null && allowed()) {
            replayQueue += replayIds.split(',').map(String::trim).filter(String::isNotEmpty)
            replayParallel =
                System.getenv("BOTSWARM_REPLAY_PARALLEL")?.toIntOrNull()?.coerceIn(1, 50) ?: 1
            exitAfterReplays = System.getenv("BOTSWARM_EXIT") == "1"
            return
        }
        val spec = System.getenv("BOTSWARM")?.takeIf { it.isNotBlank() } ?: return
        if (!allowed()) {
            logger.warn {
                "Bot swarm: BOTSWARM is set but this realm is not in dev mode; ignoring."
            }
            return
        }
        val specs =
            spec
                .split(',')
                .mapNotNull { part ->
                    val (id, n) =
                        part.trim().split(':').let {
                            it[0] to (it.getOrNull(1)?.toIntOrNull() ?: DEFAULT_BOTS)
                        }
                    resolve(id, n).also {
                        if (it == null) logger.warn { "Bot swarm: unknown goal '$id'" }
                    }
                }
                .flatten()
                .map { (goal, n) -> goal.withBudgetCap(System.getenv("BOTSWARM_TICKS")) to n }
        if (specs.isEmpty()) return
        val decider = decider(System.getenv("BOTSWARM_DECIDER")) ?: PriorDecider
        val exit = System.getenv("BOTSWARM_EXIT") == "1"
        controller.start(specs, decider) { reportDir ->
            logger.info { "Bot swarm: unattended run done, report at $reportDir" }
            if (exit) Thread({ System.exit(0) }, "botswarm-exit").start()
        }
    }

    /** `BOTSWARM_TICKS` caps every goal's budget, e.g. a 200-tick (2 minute) PR smoke run. */
    private fun BotGoal.withBudgetCap(cap: String?): BotGoal {
        val ticks = cap?.toIntOrNull()?.takeIf { it > 0 } ?: return this
        return copy(budgetTicks = minOf(budgetTicks, ticks))
    }

    /** `.data/botswarm/scenarios/<id>.scenario.json`, else the bundled copy. */
    private fun loadScenario(id: String): ReplayScenario? {
        val file = dataDir.resolve("scenarios").resolve("$id.scenario.json")
        val stream =
            if (Files.isRegularFile(file)) Files.newInputStream(file)
            else javaClass.getResourceAsStream("/botswarm/scenarios/$id.scenario.json")
        return stream?.use {
            runCatching { mapper.readValue(it, ReplayScenario::class.java) }
                .onFailure { e -> logger.warn(e) { "Bot swarm: bad scenario '$id'" } }
                .getOrNull()
        }
    }

    private fun resolve(goalArg: String, count: Int): List<Pair<BotGoal, Int>>? {
        val clamped = count.coerceIn(1, MAX_BOTS_PER_GOAL)
        if (goalArg.equals("all", ignoreCase = true))
            return goals.goals.values.map { it to clamped }
        val goal = goals.goals[goalArg] ?: return null
        return listOf(goal to clamped)
    }

    private fun decider(arg: String?): BotDecider? =
        when (arg?.lowercase()) {
            null,
            "",
            "prior" -> PriorDecider
            "uniform",
            "random" -> UniformDecider
            "laya" -> HttpDecider(URI.create(LAYA_SIDECAR), mapper)
            else -> if (arg.startsWith("http")) HttpDecider(URI.create(arg), mapper) else null
        }

    private fun allowed(): Boolean =
        System.getProperty("botswarm.allow") == "true" ||
            runCatching { realm.config.devMode }.getOrDefault(false)

    private companion object {
        const val DEFAULT_BOTS = 5
        const val MAX_BOTS_PER_GOAL = 100
        const val AUTOSTART_DELAY_CYCLES = 10
        const val REPLAY_GAP_TICKS = 5
        const val LAYA_SIDECAR = "http://127.0.0.1:7790/decide"
    }
}
