package org.rsmod.content.other.botswarm.action

import org.rsmod.map.CoordGrid

/**
 * One thing a bot can do this decision. Each action is executed by queueing the same rsprot message
 * a real client would send, so it runs through the real packet handler and its gates.
 *
 * [label] is what the decider sees ("Net Fishing spot"); [coverageKey] groups attempts for the op
 * coverage report ("npc:1518:op1").
 */
sealed interface BotAction {
    val label: String
    val coverageKey: String

    data class Walk(val dest: CoordGrid, val reason: String) : BotAction {
        override val label: String = "Walk $reason"
        override val coverageKey: String = "walk"
    }

    data class OpLoc(
        val locId: Int,
        val coords: CoordGrid,
        val op: Int,
        val opText: String,
        val name: String,
    ) : BotAction {
        override val label: String = "$opText $name"
        override val coverageKey: String = "loc:$locId:op$op"
    }

    data class OpNpc(
        val slot: Int,
        val npcId: Int,
        val op: Int,
        val opText: String,
        val name: String,
    ) : BotAction {
        override val label: String = "$opText $name"
        override val coverageKey: String = "npc:$npcId:op$op"
    }

    data class TakeObj(val objId: Int, val coords: CoordGrid, val name: String) : BotAction {
        override val label: String = "Take $name"
        override val coverageKey: String = "obj:$objId:take"
    }

    /** Inventory click. [buttonOp] is the `If3Button` op (2,3,4,6,7 map to held ops 1-5). */
    data class HeldOp(
        val slot: Int,
        val objId: Int,
        val buttonOp: Int,
        val opText: String,
        val name: String,
    ) : BotAction {
        override val label: String = "$opText $name"
        override val coverageKey: String = "held:$objId:op$buttonOp"
    }

    /** Dialogue "click here to continue" or a chat-menu option ([sub] is the option index). */
    data class ResumeDialogue(val component: String, val sub: Int, val text: String) : BotAction {
        override val label: String = text
        override val coverageKey: String = "dialogue:$component"
    }

    /** Any `If3Button` click, addressed by interface and component id (capture replays). */
    data class Button(
        val interfaceId: Int,
        val component: Int,
        val sub: Int,
        val obj: Int,
        val op: Int,
        val name: String,
    ) : BotAction {
        override val label: String = "Click $name"
        override val coverageKey: String = "button:$interfaceId:$component:op$op"
    }

    /** Pause-button resume addressed by component id (capture replays know the exact one). */
    data class ResumeComponent(val interfaceId: Int, val component: Int, val sub: Int) : BotAction {
        override val label: String = "Continue $interfaceId:$component/$sub"
        override val coverageKey: String = "dialogue:$interfaceId:$component"
    }

    /** Interface script trigger: raw argument bytes, decoded server-side by the handler. */
    data class ScriptTrigger(
        val interfaceId: Int,
        val component: Int,
        val sub: Int,
        val obj: Int,
        val crc: Int,
        val payload: ByteArray,
    ) : BotAction {
        override val label: String = "Script trigger $interfaceId:$component"
        override val coverageKey: String = "script:$interfaceId:$component"
    }

    /** Drag ([drag] = true, `IfButtonD`) or use-on (`IfButtonT`) from one component to another. */
    data class ComponentPair(
        val drag: Boolean,
        val fromInterface: Int,
        val fromComponent: Int,
        val fromSub: Int,
        val fromObj: Int,
        val toInterface: Int,
        val toComponent: Int,
        val toSub: Int,
        val toObj: Int,
    ) : BotAction {
        override val label: String =
            "${if (drag) "Drag" else "Use"} $fromInterface:$fromComponent/$fromSub -> " +
                "$toInterface:$toComponent/$toSub"
        override val coverageKey: String =
            "${if (drag) "drag" else "use"}:$fromInterface:$fromComponent:$toInterface:$toComponent"
    }

    /** Item or spell (selected component) used on a loc. */
    data class UseOnLoc(
        val locId: Int,
        val coords: CoordGrid,
        val interfaceId: Int,
        val component: Int,
        val sub: Int,
        val obj: Int,
    ) : BotAction {
        override val label: String = "Use $interfaceId:$component/$obj on loc $locId"
        override val coverageKey: String = "loc:$locId:use"
    }

    /** Item or spell (selected component) used on an npc. */
    data class UseOnNpc(
        val slot: Int,
        val npcId: Int,
        val interfaceId: Int,
        val component: Int,
        val sub: Int,
        val obj: Int,
    ) : BotAction {
        override val label: String = "Use $interfaceId:$component/$obj on npc $npcId"
        override val coverageKey: String = "npc:$npcId:use"
    }

    data class CountDialog(val count: Int) : BotAction {
        override val label: String = "Enter amount $count"
        override val coverageKey: String = "dialogue:count"
    }

    data object Wait : BotAction {
        override val label: String = "Wait"
        override val coverageKey: String = "wait"
    }
}
