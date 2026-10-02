package org.rsmod.content.other.botswarm

import net.rsprot.protocol.game.outgoing.misc.player.MessageGame
import net.rsprot.protocol.message.IncomingGameMessage
import net.rsprot.protocol.message.codec.incoming.MessageConsumer
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player

/**
 * A socket-less client. Messages queued here are consumed in the player input phase of the next
 * tick, exactly where the server consumes real packets (`PlayerInputProcess.readClientInput`), so a
 * bot's click passes through every gate a real click does.
 *
 * Outgoing traffic is dropped except game-chat lines, which the bot reads back as oracle signals
 * ("Nothing interesting happens.", "I can't reach that!").
 */
class BotClient : Client<Any, Any> {
    private val pending = ArrayList<Pending<*>>(2)
    private val chat = ArrayList<MessageGame>()

    fun <T : IncomingGameMessage> queue(
        handler: MessageConsumer<Player, T>,
        message: T,
        afterConsume: (Player) -> Unit = {},
    ) {
        pending += Pending(handler, message, afterConsume)
    }

    /** Chat lines written since the last drain. */
    fun drainChat(): List<MessageGame> {
        if (chat.isEmpty()) return emptyList()
        val copy = chat.toList()
        chat.clear()
        return copy
    }

    override fun read(player: Player) {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        for (entry in batch) {
            entry.consume(player)
        }
    }

    /** When set, sees every outgoing message (capture replays record the full stream). */
    var recorder: ((Any) -> Unit)? = null

    override fun write(message: Any) {
        recorder?.invoke(message)
        if (message is MessageGame && chat.size < MAX_CHAT) {
            chat += message
        }
    }

    override fun close() {}

    override fun flush() {}

    override fun flushHighPriority() {}

    override fun unregister(service: Any, player: Player) {}

    private class Pending<T : IncomingGameMessage>(
        private val handler: MessageConsumer<Player, T>,
        private val message: T,
        private val afterConsume: (Player) -> Unit,
    ) {
        fun consume(player: Player) {
            handler.consume(player, message)
            afterConsume(player)
        }
    }

    private companion object {
        const val MAX_CHAT = 64
    }
}
