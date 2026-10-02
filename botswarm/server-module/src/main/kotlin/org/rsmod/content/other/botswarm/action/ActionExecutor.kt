package org.rsmod.content.other.botswarm.action

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import io.netty.buffer.Unpooled
import jakarta.inject.Inject
import net.rsprot.protocol.game.incoming.buttons.If3Button
import net.rsprot.protocol.game.incoming.buttons.IfButtonD
import net.rsprot.protocol.game.incoming.buttons.IfButtonT
import net.rsprot.protocol.game.incoming.buttons.IfScriptTrigger
import net.rsprot.protocol.game.incoming.locs.OpLocT
import net.rsprot.protocol.game.incoming.locs.OpLocV2
import net.rsprot.protocol.game.incoming.misc.user.MoveGameClick
import net.rsprot.protocol.game.incoming.npcs.OpNpcT
import net.rsprot.protocol.game.incoming.npcs.OpNpcV2
import net.rsprot.protocol.game.incoming.objs.OpObjV2
import net.rsprot.protocol.game.incoming.resumed.ResumePCountDialog
import net.rsprot.protocol.game.incoming.resumed.ResumePauseButton
import net.rsprot.protocol.util.CombinedId
import org.rsmod.api.net.rsprot.handlers.If3ButtonHandler
import org.rsmod.api.net.rsprot.handlers.IfButtonDHandler
import org.rsmod.api.net.rsprot.handlers.IfButtonTHandler
import org.rsmod.api.net.rsprot.handlers.IfScriptTriggerHandler
import org.rsmod.api.net.rsprot.handlers.MoveGameClickHandler
import org.rsmod.api.net.rsprot.handlers.OpLocHandler
import org.rsmod.api.net.rsprot.handlers.OpLocTHandler
import org.rsmod.api.net.rsprot.handlers.OpNpcHandler
import org.rsmod.api.net.rsprot.handlers.OpNpcTHandler
import org.rsmod.api.net.rsprot.handlers.OpObjHandler
import org.rsmod.api.net.rsprot.handlers.ResumePCountDialogHandler
import org.rsmod.api.net.rsprot.handlers.ResumePauseButtonHandler
import org.rsmod.content.other.botswarm.BotClient
import org.rsmod.game.entity.Player

/** Result the executor learns while the handler runs (input phase of the next tick). */
class ActionProbe {
    /** `true` when an op packet passed no gate: the handler returned without an interaction. */
    var dropped: Boolean = false
    var consumed: Boolean = false
}

/**
 * Turns a [BotAction] into the rsprot message a real client sends and queues it on the bot's
 * [BotClient] against the real handler instance. Nothing here touches game state directly.
 */
class ActionExecutor
@Inject
constructor(
    private val opLoc: OpLocHandler,
    private val opNpc: OpNpcHandler,
    private val opObj: OpObjHandler,
    private val ifButton: If3ButtonHandler,
    private val move: MoveGameClickHandler,
    private val pauseButton: ResumePauseButtonHandler,
    private val countDialog: ResumePCountDialogHandler,
    private val scriptTrigger: IfScriptTriggerHandler,
    private val buttonDrag: IfButtonDHandler,
    private val buttonTarget: IfButtonTHandler,
    private val opLocT: OpLocTHandler,
    private val opNpcT: OpNpcTHandler,
) {
    fun execute(player: Player, client: BotClient, action: BotAction): ActionProbe {
        val probe = ActionProbe()
        val opProbe: (Player) -> Unit = { p ->
            probe.consumed = true
            // Gates that drop a click leave no interaction behind; a delayed player is expected
            // to drop it, so only an undelayed drop is a finding.
            probe.dropped = p.interaction == null && !p.isDelayed
        }
        val plainProbe: (Player) -> Unit = { probe.consumed = true }
        when (action) {
            is BotAction.Walk -> {
                val message = MoveGameClick(action.dest.x, action.dest.z, 0)
                client.queue(move, message, plainProbe)
            }
            is BotAction.OpLoc -> {
                val message =
                    OpLocV2(action.locId, action.coords.x, action.coords.z, false, action.op, -1)
                client.queue(opLoc, message, opProbe)
            }
            is BotAction.OpNpc -> {
                client.queue(opNpc, OpNpcV2(action.slot, false, action.op, -1), opProbe)
            }
            is BotAction.TakeObj -> {
                val message = OpObjV2(action.objId, action.coords.x, action.coords.z, false, 3, -1)
                client.queue(opObj, message, opProbe)
            }
            is BotAction.HeldOp -> {
                val items = inventoryItems ?: return probe
                val message =
                    If3Button(
                        CombinedId(items.interfaceId, items.component),
                        action.slot,
                        action.objId,
                        action.buttonOp,
                    )
                client.queue(ifButton, message, plainProbe)
            }
            is BotAction.ResumeDialogue -> {
                val type =
                    runCatching {
                            ServerCacheManager.fromComponent(
                                action.component.asRSCM(RSCMType.COMPONENT)
                            )
                        }
                        .getOrNull() ?: return probe
                val message =
                    ResumePauseButton(CombinedId(type.interfaceId, type.component), action.sub)
                client.queue(pauseButton, message, plainProbe)
            }
            is BotAction.Button -> {
                val message =
                    If3Button(
                        CombinedId(action.interfaceId, action.component),
                        action.sub,
                        action.obj,
                        action.op,
                    )
                client.queue(ifButton, message, plainProbe)
            }
            is BotAction.ResumeComponent -> {
                val message =
                    ResumePauseButton(CombinedId(action.interfaceId, action.component), action.sub)
                client.queue(pauseButton, message, plainProbe)
            }
            is BotAction.ScriptTrigger -> {
                val message =
                    IfScriptTrigger(
                        CombinedId(action.interfaceId, action.component),
                        action.sub,
                        action.obj,
                        action.crc,
                        Unpooled.wrappedBuffer(action.payload),
                    )
                client.queue(scriptTrigger, message, plainProbe)
            }
            is BotAction.ComponentPair -> {
                val from = CombinedId(action.fromInterface, action.fromComponent)
                val to = CombinedId(action.toInterface, action.toComponent)
                if (action.drag) {
                    val message =
                        IfButtonD(
                            from,
                            action.fromSub,
                            action.fromObj,
                            to,
                            action.toSub,
                            action.toObj,
                        )
                    client.queue(buttonDrag, message, plainProbe)
                } else {
                    val message =
                        IfButtonT(
                            from,
                            action.fromSub,
                            action.fromObj,
                            to,
                            action.toSub,
                            action.toObj,
                        )
                    client.queue(buttonTarget, message, plainProbe)
                }
            }
            is BotAction.UseOnLoc -> {
                val message =
                    OpLocT(
                        action.locId,
                        action.coords.x,
                        action.coords.z,
                        false,
                        CombinedId(action.interfaceId, action.component),
                        action.sub,
                        action.obj,
                    )
                client.queue(opLocT, message, plainProbe)
            }
            is BotAction.UseOnNpc -> {
                val message =
                    OpNpcT(
                        action.slot,
                        false,
                        CombinedId(action.interfaceId, action.component),
                        action.sub,
                        action.obj,
                    )
                client.queue(opNpcT, message, plainProbe)
            }
            is BotAction.CountDialog -> {
                client.queue(countDialog, ResumePCountDialog(action.count), plainProbe)
            }
            BotAction.Wait -> probe.consumed = true
        }
        return probe
    }

    // Resolved on first use: resolving a gameval at class init throws on an unknown key.
    private val inventoryItems by lazy {
        runCatching {
                ServerCacheManager.fromComponent(
                    "component.inventory:items".asRSCM(RSCMType.COMPONENT)
                )
            }
            .getOrNull()
    }
}
