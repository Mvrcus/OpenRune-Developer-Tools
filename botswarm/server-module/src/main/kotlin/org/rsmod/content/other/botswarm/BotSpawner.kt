package org.rsmod.content.other.botswarm

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import kotlin.random.Random
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.player.stat.PlayerSkillXP
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.player.isSuccess
import org.rsmod.content.other.botswarm.goal.BotGoal
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.map.collision.isWalkBlocked
import org.rsmod.game.map.collision.isZoneValid
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Logs headless players into the live world the same way `AccountLoadResponseHook.handleGameLogin`
 * does, minus the socket: register (Initialize -> inventories, gameframe), then Login and
 * EngineLogin, so every content login hook runs for bots too.
 *
 * Bots get NEGATIVE account/character ids. That is the `isSyntheticAccount` contract: the logout
 * save, autosave and Central/DB session hooks all skip them, so a bot can never write to (or
 * collide with) a real account row.
 */
class BotSpawner
@Inject
constructor(
    private val playerRegistry: PlayerRegistry,
    private val eventBus: EventBus,
    private val collision: CollisionFlagMap,
    private val mapClock: MapClock,
) {
    private val logger = InlineLogger()
    private var nextId = 1

    /** Must run on the world thread outside player iteration (e.g. `GameLifecycle.StartCycle`). */
    fun spawn(name: String, goal: BotGoal, seed: Long): BotAgent? {
        val slot = playerRegistry.playerList.nextFreeSlot() ?: return null
        val random = Random(seed)
        val anchor = CoordGrid(goal.start.x, goal.start.z, goal.start.level)
        val coords = spawnTile(anchor, goal.start.scatter, random) ?: anchor
        val id = -(nextId++)
        val client = BotClient()
        val player =
            Player().apply {
                this.coords = coords
                uuid = id.toLong()
                observerUUID = id.toLong()
                accountId = id
                characterId = id
                userId = id.toLong()
                accountHash = id.toLong()
                userHash = id.toLong()
                username = name
                displayName = name
                members = true
                modLevel = ServerCacheManager.getModLevel("modlevel.player".asRSCM())!!
                ui.setWindowStatus(765, 503, false)
                this.client = client
                slotId = slot
                lastMapBuildComplete = mapClock.cycle
            }
        // Real accounts load their vars before Initialize (which opens the gameframe and reads
        // e.g. the journal tab), so goal vars go in first.
        applyVarbits(player, goal)
        val added = playerRegistry.add(player)
        if (!added.isSuccess()) {
            logger.warn { "Bot swarm: could not register $name: $added" }
            return null
        }
        eventBus.publish(SessionStateEvent.Login(player))
        eventBus.publish(SessionStateEvent.EngineLogin(player))
        applyLoadout(player, goal)
        return BotAgent(name, player, client, goal, seed, mapClock.cycle, anchor)
    }

    /** Removal goes through the normal logout process; `AccountRegistry` skips the save. */
    fun despawn(agent: BotAgent) {
        agent.despawning = true
        agent.player.forceDisconnect = true
    }

    private fun applyVarbits(player: Player, goal: BotGoal) {
        for ((key, value) in goal.varbits) {
            val type =
                runCatching { key.toIntOrNull() ?: key.asRSCM(RSCMType.VARBIT) }
                    .getOrNull()
                    ?.let(ServerCacheManager::getVarbit)
            if (type == null) {
                logger.warn { "Bot swarm: goal ${goal.id} has unknown varbit '$key'" }
                continue
            }
            VarPlayerIntMapSetter.set(player, type, value)
        }
        for ((key, value) in goal.varps) {
            val type =
                runCatching { key.toIntOrNull() ?: key.asRSCM(RSCMType.VARP) }
                    .getOrNull()
                    ?.let(ServerCacheManager::getVarp)
            if (type == null) {
                logger.warn { "Bot swarm: goal ${goal.id} has unknown varp '$key'" }
                continue
            }
            VarPlayerIntMapSetter.set(player, type, value)
        }
    }

    private fun applyLoadout(player: Player, goal: BotGoal) {
        player.setStatLevel("stat.hitpoints", 10)
        for ((stat, level) in goal.stats) {
            runCatching { player.setStatLevel(stat, level) }
                .onFailure { logger.warn { "Bot swarm: goal ${goal.id} has bad stat '$stat'" } }
        }
        player.appearance.combatLevel = PlayerSkillXP.calculateCombatLevel(player)
        for (item in goal.inventory) {
            val result = runCatching { player.invAdd(player.inv, item.obj, item.count) }
            if (result.isFailure || result.getOrNull()?.success != true) {
                logger.warn { "Bot swarm: goal ${goal.id} could not give ${item.obj}" }
            }
        }
    }

    private fun Player.setStatLevel(stat: String, level: Int) {
        val clamped = level.coerceIn(1, 99)
        statMap.setXP(stat, PlayerSkillXPTable.getXPFromLevel(clamped))
        statMap.setBaseLevel(stat, clamped.toByte())
        statMap.setCurrentLevel(stat, clamped.toByte())
    }

    private fun spawnTile(anchor: CoordGrid, scatter: Int, random: Random): CoordGrid? {
        repeat(SPAWN_ATTEMPTS) {
            val tile =
                anchor.translate(
                    random.nextInt(-scatter, scatter + 1),
                    random.nextInt(-scatter, scatter + 1),
                )
            if (collision.isZoneValid(tile) && !collision.isWalkBlocked(tile)) return tile
        }
        return null
    }

    private companion object {
        const val SPAWN_ATTEMPTS = 12
    }
}
