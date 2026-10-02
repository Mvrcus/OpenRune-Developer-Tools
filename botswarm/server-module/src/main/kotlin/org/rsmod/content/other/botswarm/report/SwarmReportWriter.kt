package org.rsmod.content.other.botswarm.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.michaelbull.logging.InlineLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Writes run artifacts off the world thread. */
class SwarmReportWriter(private val mapper: ObjectMapper, private val root: Path) {
    private val logger = InlineLogger()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun write(report: SwarmReport, onDone: (Path?) -> Unit = {}) {
        io.launch {
            val dir = root.resolve(report.runId)
            val result = runCatching {
                Files.createDirectories(dir)
                mapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve("report.json").toFile(), report)
                Files.writeString(dir.resolve("summary.md"), summary(report))
                dir
            }
            result.onFailure { logger.error(it) { "Bot swarm: could not write report to $dir" } }
            result.onSuccess {
                logger.info { "Bot swarm: report written to ${it.toAbsolutePath()}" }
            }
            onDone(result.getOrNull())
        }
    }

    /** Human/LLM-readable digest; report.json carries the full detail. */
    private fun summary(report: SwarmReport): String = buildString {
        appendLine("# Bot swarm run ${report.runId}")
        appendLine()
        appendLine(
            "Decider `${report.decider}`, ${report.ticks} ticks, stopped: ${report.stopReason}. " +
                "Tick avg ${"%.1f".format(report.tickStats.avgMs)}ms, p95 ${report.tickStats.p95Ms}ms, " +
                "max ${report.tickStats.maxMs}ms, ${report.tickStats.overruns} over 600ms."
        )
        appendLine()
        appendLine("## Goals")
        appendLine()
        appendLine("| Goal | Bots | Completed | Median ticks | XP/hour |")
        appendLine("|---|---|---|---|---|")
        for (goal in report.goals) {
            val xp =
                goal.xpPerHour.entries.joinToString {
                    "${it.key.removePrefix("stat.")} ${it.value}"
                }
            appendLine(
                "| ${goal.id} | ${goal.bots} | ${goal.completed} | " +
                    "${goal.medianTicksToComplete ?: "-"} | ${xp.ifEmpty { "-" }} |"
            )
        }
        appendLine()
        appendLine("## Findings (${report.findings.size})")
        appendLine()
        val ordered = report.findings.sortedWith(compareBy({ it.severity.ordinal }, { -it.count }))
        for (finding in ordered.take(MAX_SUMMARY_FINDINGS)) {
            appendLine(
                "- **${finding.severity} ${finding.kind}** x${finding.count}" +
                    (finding.target?.let { " — `$it`" } ?: "") +
                    (finding.goal?.let { " (goal $it)" } ?: "") +
                    (finding.coords?.let { " at $it" } ?: "")
            )
            appendLine("  ${finding.detail.lineSequence().first()}")
        }
        if (ordered.size > MAX_SUMMARY_FINDINGS) {
            appendLine("- ...and ${ordered.size - MAX_SUMMARY_FINDINGS} more in report.json")
        }
        appendLine()
        appendLine("## Coverage")
        appendLine()
        appendLine(
            "${report.coverage.distinctChosen} of ${report.coverage.distinctOffered} distinct " +
                "options offered were exercised."
        )
    }

    private companion object {
        const val MAX_SUMMARY_FINDINGS = 60
    }
}
