package org.rsmod.content.other.botswarm.replay

import dev.openrune.ServerCacheManager
import dev.openrune.definition.type.VarBitType
import dev.openrune.definition.type.widget.IfEvent
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.varp.baseVar
import dev.openrune.types.varp.bits
import net.rsprot.protocol.game.outgoing.interfaces.IfCloseSub
import net.rsprot.protocol.game.outgoing.interfaces.IfOpenSub
import net.rsprot.protocol.game.outgoing.interfaces.IfSetEventsV2
import net.rsprot.protocol.game.outgoing.interfaces.IfSetHide
import net.rsprot.protocol.game.outgoing.interfaces.IfSetObject
import net.rsprot.protocol.game.outgoing.interfaces.IfSetText
import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import net.rsprot.protocol.game.outgoing.varp.VarpSmall
import org.rsmod.api.player.stat.stat
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.Hitmark
import org.rsmod.game.seq.EntitySeq
import org.rsmod.game.spot.EntitySpotanim
import org.rsmod.utils.bits.getBits

/**
 * Turns the server->client stream of one bot into the same event vocabulary `capture_extract.py`
 * produces from an RSProx transcript, so the two can be diffed line for line.
 *
 * Varps are decoded to varbits the way RSProx transcribes them: a varp write whose bits change any
 * defined varbit is reported as those varbits; otherwise as the varp. Inventories are diffed from
 * server state each tick rather than decoded from pooled `UpdateInv*` messages.
 */
class ReplayRecorder(private val player: Player) {
    var tick: Int = 0
    val events: MutableList<Map<String, Any?>> = ArrayList()

    private val varpValues = HashMap<Int, Int>()
    private val openTargets = HashMap<String, Int>()
    private var invSnapshot: List<Pair<Int, Int>> = snapshotInv()
    private var lastCoords = player.coords
    private val statNames: List<String> =
        ServerCacheManager.getStats().values.mapNotNull {
            runCatching { RSCM.getReverseMapping(RSCMType.STAT, it.id) }.getOrNull()
        }
    private val levels = HashMap<String, Int>().apply { statNames.forEach { put(it, level(it)) } }

    init {
        for ((id, type) in ServerCacheManager.getVarps()) {
            varpValues[id] = runCatching { player.vars[type] }.getOrDefault(0)
        }
        for ((key, value) in player.ui.modals.entries()) openTargets[target(key)] = value
        for ((key, value) in player.ui.overlays.entries()) openTargets[target(key)] = value
    }

    fun record(message: Any) {
        when (message) {
            is VarpSmall -> varp(message.id, message.value)
            is VarpLarge -> varp(message.id, message.value)
            is IfOpenSub -> {
                val target = "${message.destinationInterfaceId}:${message.destinationComponentId}"
                openTargets[target] = message.interfaceId
                add(
                    "if_opensub",
                    "target" to target,
                    "iface" to message.interfaceId,
                    "name" to name(RSCMType.INTERFACE, message.interfaceId),
                    "type" to if (message.type == 0) "modal" else "overlay",
                )
            }
            is IfCloseSub -> {
                val target = "${message.interfaceId}:${message.componentId}"
                add(
                    "if_closesub",
                    "target" to target,
                    "iface" to (openTargets.remove(target) ?: -1),
                )
            }
            is IfSetEventsV2 -> {
                val mask =
                    (message.events2.toLong() shl 32) or (message.events1.toLong() and 0xFFFFFFFFL)
                add(
                    "if_setevents",
                    "com" to "${message.interfaceId}:${message.componentId}",
                    "start" to signed16(message.start),
                    "end" to signed16(message.end),
                    "events" to eventNames(mask),
                )
            }
            is RunClientScript ->
                add(
                    "cs2",
                    "id" to message.id,
                    "args" to
                        message.values.joinToString(", ") { if (it is String) "\"$it\"" else "$it" },
                )
            is IfSetText ->
                add(
                    "if_settext",
                    "com" to "${message.interfaceId}:${message.componentId}",
                    "text" to message.text,
                )
            is IfSetObject ->
                add(
                    "if_setobject",
                    "com" to "${message.interfaceId}:${message.componentId}",
                    "obj" to message.obj,
                )
            is IfSetHide ->
                add(
                    "if_sethide",
                    "com" to "${message.interfaceId}:${message.componentId}",
                    "hidden" to message.hidden,
                )
            is MessageGame -> add("message", "text" to message.message)
        }
    }

    private val npcHitpoints = HashMap<Int, Int>()

    /**
     * Combat sampled on `GameLifecycle.LateCycle`, before post-tick clears the pending masks: the
     * bot's hitmarks, animation and spot animations, and for npcs within [COMBAT_RADIUS] their
     * animation, spot animations and hitpoint drops (npc hitmarks go straight to the info protocol
     * and are not readable back, so a drop in hitpoints stands in for "hit on npc").
     */
    fun sampleCombat(npcs: Sequence<Npc>) {
        val seq = player.pendingSequence
        if (seq != EntitySeq.NULL && seq != EntitySeq.ZERO && seq.id != NO_ID) {
            add("anim", "actor" to "player", "id" to seq.id)
        }
        for (i in 0 until player.pendingSpotanims.size) {
            val spot = EntitySpotanim(player.pendingSpotanims.getLong(i))
            if (spot.id != NO_ID) add("spotanim", "actor" to "player", "id" to spot.id)
        }
        for (i in 0 until player.activeHitmarks.size) {
            val hit = Hitmark(player.activeHitmarks.getLong(i))
            add("hit", "actor" to "player", "type" to hit.self, "value" to hit.damage)
        }
        val origin = player.coords
        for (npc in npcs) {
            if (npc.coords.chebyshevDistance(origin) > COMBAT_RADIUS) continue
            val type = npc.visType.id
            val npcSeq = npc.pendingSequence
            if (npcSeq != EntitySeq.NULL && npcSeq != EntitySeq.ZERO && npcSeq.id != NO_ID) {
                add("anim", "actor" to type, "id" to npcSeq.id)
            }
            for (i in 0 until npc.pendingSpotanims.size) {
                val spot = EntitySpotanim(npc.pendingSpotanims.getLong(i))
                if (spot.id != NO_ID) add("spotanim", "actor" to type, "id" to spot.id)
            }
            val before = npcHitpoints.put(npc.slotId, npc.hitpoints)
            if (before != null && npc.hitpoints < before) {
                add("hit", "actor" to type, "value" to before - npc.hitpoints)
            }
        }
    }

    /** Called once per tick after the tick's processing: emits inventory slot changes. */
    fun endTick() {
        if (player.coords != lastCoords) {
            lastCoords = player.coords
            add("pos", "x" to lastCoords.x, "z" to lastCoords.z, "level" to lastCoords.level)
        }
        for (stat in statNames) {
            val level = level(stat)
            if (levels.put(stat, level) != level) {
                add("stat", "stat" to stat.substringAfter('.'), "level" to level)
            }
        }
        val now = snapshotInv()
        for (slot in now.indices) {
            if (now[slot] != invSnapshot.getOrNull(slot)) {
                add(
                    "inv",
                    "inv" to INV_ID,
                    "slot" to slot,
                    "obj" to now[slot].first,
                    "count" to now[slot].second,
                )
            }
        }
        invSnapshot = now
    }

    private fun varp(id: Int, value: Int) {
        val old = varpValues[id] ?: 0
        // The server re-sends some varps unchanged; RSProx only shows changes, so neither do we.
        if (old == value) return
        varpValues[id] = value
        val changed =
            varbitsByVarp[id].orEmpty().filter { old.getBits(it.bits) != value.getBits(it.bits) }
        if (changed.isEmpty()) {
            add("varp", "id" to id, "name" to name(RSCMType.VARP, id), "old" to old, "new" to value)
            return
        }
        for (varbit in changed) {
            add(
                "varbit",
                "id" to varbit.id,
                "name" to name(RSCMType.VARBIT, varbit.id),
                "old" to old.getBits(varbit.bits),
                "new" to value.getBits(varbit.bits),
            )
        }
    }

    private fun add(kind: String, vararg fields: Pair<String, Any?>) {
        events += linkedMapOf<String, Any?>("tick" to tick, "kind" to kind).apply { putAll(fields) }
    }

    private fun level(stat: String): Int = runCatching { player.stat(stat) }.getOrDefault(0)

    private fun snapshotInv(): List<Pair<Int, Int>> =
        player.inv.objs.map { obj -> if (obj == null) -1 to 0 else obj.id to obj.count }

    /** Event ranges travel as unsigned shorts; RSProx (and our scripts) say -1, not 65535. */
    private fun signed16(value: Int): Int = if (value == 0xFFFF) -1 else value

    private fun target(packed: Int): String = "${packed ushr 16}:${packed and 0xFFFF}"

    private fun eventNames(mask: Long): List<String> =
        IfEvent.entries
            .filter {
                !it.name.startsWith("Depth") &&
                    it.bitmask != 0L &&
                    (mask and it.bitmask) == it.bitmask
            }
            .map { it.name }

    private fun name(type: RSCMType, id: Int): String? =
        runCatching { RSCM.getReverseMapping(type, id) }.getOrNull()?.substringAfter('.')

    private companion object {
        const val INV_ID = 93
        const val COMBAT_RADIUS = 15
        const val NO_ID = 0xFFFF

        val varbitsByVarp: Map<Int, List<VarBitType>> by lazy {
            ServerCacheManager.getVarbits().values.groupBy { it.baseVar.id }
        }
    }
}
