package org.rsmod.content.other.botswarm.action

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ObjectServerType
import jakarta.inject.Inject
import kotlin.math.max
import kotlin.random.Random
import org.rsmod.api.player.input.ResumePCountDialogInput
import org.rsmod.api.player.input.ResumePauseButtonInput
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.content.other.botswarm.decide.BotDecider
import org.rsmod.content.other.botswarm.goal.BotGoal
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.map.collision.isWalkBlocked
import org.rsmod.game.map.collision.isZoneValid
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap

/** An action plus the catalog's prior weight for it. */
data class Candidate(val action: BotAction, val prior: Double)

/** What the bot needs to answer right now, if anything other than "pick something to do". */
enum class BotMode {
    World,
    PauseDialogue,
    CountDialogue,
    UnanswerableInput,
}

/**
 * Lists what a player could click right now, mirroring the options a real client would show: loc
 * and npc ops come from the type the player actually sees (multiloc/multinpc resolved through the
 * player's vars), inventory ops from the item's interface options.
 *
 * Priors encode the goal ("prefer"), curiosity (options this run has not tried) and distance; the
 * decider turns them into a choice. The list is trimmed to [BotDecider.MAX_OPTIONS].
 */
class ActionCatalog
@Inject
constructor(
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val locInteractions: LocInteractions,
    private val npcInteractions: NpcInteractions,
    private val collision: CollisionFlagMap,
) {
    fun mode(player: Player): BotMode {
        val coroutine = player.activeCoroutine ?: return BotMode.World
        if (!coroutine.isSuspended) return BotMode.World
        return when {
            coroutine.isAwaiting(ResumePauseButtonInput::class) -> BotMode.PauseDialogue
            coroutine.isAwaiting(ResumePCountDialogInput::class) -> BotMode.CountDialogue
            player.ui.modals.isNotEmpty() -> BotMode.UnanswerableInput
            else -> BotMode.World
        }
    }

    fun dialogueOptions(player: Player): List<Candidate> {
        val open = player.ui.modals.values.toSet() + player.ui.overlays.values.toSet()
        val out = ArrayList<Candidate>()
        for (internal in PAUSE_COMPONENTS) {
            val type =
                runCatching {
                        ServerCacheManager.fromComponent(internal.asRSCM(RSCMType.COMPONENT))
                    }
                    .getOrNull() ?: continue
            if (type.interfaceId !in open) continue
            if (internal == CHAT_MENU) {
                for (sub in 1..5) {
                    val events = player.ui.events[type, sub]
                    if (events != 0L) {
                        out +=
                            Candidate(BotAction.ResumeDialogue(internal, sub, "Option $sub"), 1.0)
                    }
                }
            } else {
                // Live enables pause buttons over a sub range (objectbox:universe is 0..1); a
                // static-only component takes -1.
                val sub = if (player.ui.events[type, 0] != 0L) 0 else -1
                out += Candidate(BotAction.ResumeDialogue(internal, sub, "Continue"), 1.0)
            }
        }
        return out
    }

    fun countOptions(): List<Candidate> =
        listOf(1, 5, 10, 28).map { Candidate(BotAction.CountDialog(it), 1.0) }

    fun worldOptions(
        player: Player,
        goal: BotGoal,
        anchor: CoordGrid,
        random: Random,
        tried: (String) -> Boolean,
        failures: (String) -> Int,
    ): List<Candidate> {
        val origin = player.coords
        val out = ArrayList<Candidate>()
        val invFull = player.inv.isFull()
        val keep =
            goal.inventory.mapNotNull { runCatching { it.obj.asRSCM(RSCMType.OBJ) }.getOrNull() }

        // Inventory.
        for (slot in player.inv.indices) {
            val obj = player.inv[slot] ?: continue
            val type = ServerCacheManager.getItem(obj.id) ?: continue
            for (index in HELD_BUTTON_OPS.indices) {
                val text = type.interfaceOptions.getOrNull(index)?.takeIf(::isRealOp) ?: continue
                val isDrop = text.equals("Drop", ignoreCase = true)
                val base =
                    when {
                        isDrop && obj.id in keep -> continue
                        isDrop && invFull -> 3.0
                        isDrop -> 0.1
                        else -> 0.4
                    }
                val action = BotAction.HeldOp(slot, obj.id, HELD_BUTTON_OPS[index], text, type.name)
                out += Candidate(action, base)
            }
        }

        val zone = ZoneKey.from(origin)
        // Locs.
        for (dx in -1..1) {
            for (dz in -1..1) {
                for (loc in locRepo.findAll(zone.translate(dx, dz))) {
                    val distance = loc.coords.chebyshevDistance(origin)
                    if (distance > SCAN_RADIUS || loc.coords.level != origin.level) continue
                    val type = ServerCacheManager.getObject(loc.id) ?: continue
                    val seen = resolveLoc(player, BoundLocInfo(loc, type), type)
                    for (op in 1..5) {
                        val text = seen.actions.getOpOrNull(op - 1)?.takeIf(::isRealOp) ?: continue
                        val name = seen.name.takeIf { it.isNotBlank() && it != "null" } ?: "loc"
                        val action = BotAction.OpLoc(loc.id, loc.coords, op, text, name)
                        out += Candidate(action, distancePrior(distance))
                    }
                }
            }
        }

        // Npcs.
        for (npc in npcRepo.findAll(zone, 1)) {
            if (!npc.isVisible || npc.coords.level != origin.level) continue
            val distance = npc.coords.chebyshevDistance(origin)
            if (distance > SCAN_RADIUS) continue
            val seen = npcInteractions.multiNpc(npc.visType, player.vars) ?: npc.visType
            for (op in 1..5) {
                val text = seen.actions.getOpOrNull(op - 1)?.takeIf(::isRealOp) ?: continue
                val action = BotAction.OpNpc(npc.slotId, npc.id, op, text, seen.name)
                out += Candidate(action, distancePrior(distance))
            }
        }

        // Ground items.
        for (dx in -1..1) {
            for (dz in -1..1) {
                for (obj in objRepo.findAll(zone.translate(dx, dz))) {
                    if (!obj.isVisibleTo(player)) continue
                    val distance = obj.coords.chebyshevDistance(origin)
                    if (distance > SCAN_RADIUS || obj.coords.level != origin.level) continue
                    val type = ServerCacheManager.getItem(obj.type) ?: continue
                    val action = BotAction.TakeObj(obj.type, obj.coords, type.name)
                    out += Candidate(action, distancePrior(distance) * if (invFull) 0.05 else 1.0)
                }
            }
        }

        // Walking: a few random nearby tiles, plus a pull home when wandering off.
        repeat(WALK_SAMPLES) {
            val dest = origin.translate(random.nextInt(-8, 9), random.nextInt(-8, 9))
            val walkable = collision.isZoneValid(dest) && !collision.isWalkBlocked(dest)
            if (dest.chebyshevDistance(anchor) <= goal.roamRadius && walkable) {
                out += Candidate(BotAction.Walk(dest, "nearby"), 0.35)
            }
        }
        if (origin.chebyshevDistance(anchor) > goal.roamRadius) {
            out += Candidate(BotAction.Walk(anchor, "back to goal area"), 25.0)
        }

        val preferred = goal.prefer.map(String::lowercase)
        val avoided = goal.avoid.map(String::lowercase)
        val scored =
            out.asSequence()
                .filterNot { c -> avoided.any { it in c.action.label.lowercase() } }
                .map { c ->
                    val label = c.action.label.lowercase()
                    var prior = c.prior
                    if (preferred.any { it in label }) prior *= PREFER_BOOST
                    if (!tried(c.action.coverageKey)) prior *= 1.0 + goal.novelty * NOVELTY_BOOST
                    prior /= 1.0 + failures(c.action.coverageKey) * FAILURE_PENALTY
                    c.copy(prior = prior)
                }
                .sortedByDescending { it.prior }
                .take(BotDecider.MAX_OPTIONS)
                .toList()
        return scored
    }

    private fun resolveLoc(
        player: Player,
        loc: BoundLocInfo,
        type: ObjectServerType,
    ): ObjectServerType {
        val multi = locInteractions.multiLoc(loc, type, player.vars) ?: return type
        return ServerCacheManager.getObject(multi.id) ?: type
    }

    private fun distancePrior(distance: Int): Double = 1.0 / (1.0 + max(0, distance - 1) / 6.0)

    private fun isRealOp(text: String?): Boolean =
        !text.isNullOrBlank() &&
            !text.equals("hidden", ignoreCase = true) &&
            !text.equals("null", ignoreCase = true) &&
            !text.equals("Examine", ignoreCase = true)

    private companion object {
        const val SCAN_RADIUS = 15
        const val WALK_SAMPLES = 3
        const val PREFER_BOOST = 12.0
        const val NOVELTY_BOOST = 4.0
        const val FAILURE_PENALTY = 2.0

        /** `interfaceOptions[i]` -> `If3Button` op; see `HeldOpScript.toHeldOp`. */
        val HELD_BUTTON_OPS = intArrayOf(2, 3, 4, 6, 7)

        const val CHAT_MENU = "component.chatmenu:options"

        /** Every pause component `ProtectedAccess` resumes on. */
        val PAUSE_COMPONENTS =
            listOf(
                "component.chat_left:continue",
                "component.chat_right:continue",
                "component.messagebox:continue",
                "component.objectbox:universe",
                "component.objectbox_double:pausebutton",
                "component.confirmdestroy:universe",
                CHAT_MENU,
            )
    }
}
