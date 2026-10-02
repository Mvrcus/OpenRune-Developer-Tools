package org.rsmod.content.other.botswarm.goal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.michaelbull.logging.InlineLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

class GoalLibrary(private val mapper: ObjectMapper, private val externalDir: Path) {
    private val logger = InlineLogger()

    @Volatile
    var goals: Map<String, BotGoal> = emptyMap()
        private set

    /** Reads bundled + external goals. Called at boot and from `::swarm reload` off-thread. */
    fun load() {
        val loaded = linkedMapOf<String, BotGoal>()
        val bundled = javaClass.getResourceAsStream(BUNDLED)
        if (bundled == null) {
            logger.warn { "Bot swarm: bundled goals missing at $BUNDLED" }
        } else {
            bundled.use { parse(mapper.readTree(it)).forEach { goal -> loaded[goal.id] = goal } }
        }
        if (externalDir.isDirectory()) {
            for (file in externalDir.listDirectoryEntries().filter { it.extension == "json" }) {
                runCatching { Files.newInputStream(file).use { parse(mapper.readTree(it)) } }
                    .onSuccess { list -> list.forEach { goal -> loaded[goal.id] = goal } }
                    .onFailure { logger.warn(it) { "Bot swarm: bad goal file $file" } }
            }
        }
        goals = loaded
        logger.info { "Bot swarm: ${loaded.size} goals loaded (${loaded.keys.joinToString()})" }
    }

    private fun parse(node: JsonNode): List<BotGoal> =
        if (node.isArray) {
            node.map { mapper.treeToValue(it, BotGoal::class.java) }
        } else {
            listOf(mapper.treeToValue(node, BotGoal::class.java))
        }

    private companion object {
        const val BUNDLED = "/botswarm/goals.json"
    }
}
