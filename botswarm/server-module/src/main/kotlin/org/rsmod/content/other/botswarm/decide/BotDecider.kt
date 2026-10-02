package org.rsmod.content.other.botswarm.decide

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * The "System 1" that picks a bot's next action: given the goal, a short state summary and up to
 * [MAX_OPTIONS] labelled options, it returns one probability per option. The bot samples from that
 * distribution with its own seeded random, so a run's choices are reproducible from its seed and
 * the recorded distributions.
 *
 * Deciders never block the world thread: [decide] returns a future, and the bot idles until it
 * completes (falling back to the priors after a timeout).
 */
interface BotDecider {
    val name: String

    fun decide(request: DecisionRequest): CompletableFuture<DoubleArray>

    companion object {
        /** Laya degrades past ~20 options (see the bot swarm README); the catalog trims to this. */
        const val MAX_OPTIONS: Int = 20
    }
}

data class DecisionRequest(
    val bot: String,
    val goal: String,
    val context: String,
    val options: List<DecisionOption>,
)

data class DecisionOption(val label: String, val prior: Double)

/** Samples from the catalog's own priors (goal preference, novelty, distance). Free and instant. */
object PriorDecider : BotDecider {
    override val name: String = "prior"

    override fun decide(request: DecisionRequest): CompletableFuture<DoubleArray> =
        CompletableFuture.completedFuture(normalise(request.options.map { it.prior }))
}

/** Uniform random: pure fuzzing, ignores the goal entirely. */
object UniformDecider : BotDecider {
    override val name: String = "uniform"

    override fun decide(request: DecisionRequest): CompletableFuture<DoubleArray> {
        val size = request.options.size
        return CompletableFuture.completedFuture(DoubleArray(size) { 1.0 / size })
    }
}

/**
 * Asks an HTTP sidecar (`tools/bot-decider`, which wraps Laya or any other decision model).
 *
 * Request JSON fields: `bot`, `goal`, `context`, `options` (labels) and `priors` (numbers).
 * Response JSON: `probabilities`, one number per option, in option order.
 */
class HttpDecider(
    private val endpoint: URI,
    private val mapper: ObjectMapper,
    private val timeout: Duration = Duration.ofSeconds(5),
) : BotDecider {
    override val name: String = "http:$endpoint"

    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    override fun decide(request: DecisionRequest): CompletableFuture<DoubleArray> {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "bot" to request.bot,
                    "goal" to request.goal,
                    "context" to request.context,
                    "options" to request.options.map { it.label },
                    "priors" to request.options.map { it.prior },
                )
            )
        val http =
            HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return client.sendAsync(http, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            check(response.statusCode() == 200) { "Decider HTTP ${response.statusCode()}" }
            val probs = mapper.readTree(response.body()).path("probabilities")
            check(probs.size() == request.options.size) {
                "Decider returned ${probs.size()} probabilities for ${request.options.size} options"
            }
            normalise(probs.map { it.asDouble() })
        }
    }
}

fun normalise(weights: List<Double>): DoubleArray {
    val clean = weights.map { if (it.isFinite() && it > 0.0) it else 0.0 }
    val sum = clean.sum()
    if (sum <= 0.0) return DoubleArray(clean.size) { 1.0 / clean.size }
    return DoubleArray(clean.size) { clean[it] / sum }
}
