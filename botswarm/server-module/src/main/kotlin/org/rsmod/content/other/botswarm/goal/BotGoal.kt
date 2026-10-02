package org.rsmod.content.other.botswarm.goal

/**
 * A test goal: where bots start, what they carry, how long they get and what "done" means.
 *
 * Goals are data. Bundled ones live in `resources/botswarm/goals.json`; drop extra `*.json` files
 * (one goal or an array) into `.data/botswarm/goals/` to add more without a rebuild.
 */
data class BotGoal(
    val id: String,
    val description: String,
    val start: StartSpec,
    /** Base levels to set before play, keyed by stat gameval (`"stat.fishing"`). */
    val stats: Map<String, Int> = emptyMap(),
    val inventory: List<ItemSpec> = emptyList(),
    /**
     * Varbits set before login, keyed by gameval (`"varbit.x"`) or numeric id. Lets a goal start
     * past gates a fresh account has not cleared, e.g. Leagues region unlocks: without one, the
     * region-lock login script evicts the bot to the starter area.
     */
    val varbits: Map<String, Int> = emptyMap(),
    /** Varps set before login, keyed by gameval (`"varp.x"`) or numeric id. */
    val varps: Map<String, Int> = emptyMap(),
    val budgetTicks: Int = 3000,
    /** `null` means an open-ended exploration goal: it never succeeds, it only runs out. */
    val success: SuccessSpec? = null,
    /** Case-insensitive keywords; options whose label contains one are strongly preferred. */
    val prefer: List<String> = emptyList(),
    /** Case-insensitive keywords; options whose label contains one are never offered. */
    val avoid: List<String> = emptyList(),
    /** Bots wandering further than this from [start] are steered back. */
    val roamRadius: Int = 20,
    /** 0..1: share of prior weight given to options this run has not tried yet. */
    val novelty: Double = 0.25,
)

data class StartSpec(val x: Int, val z: Int, val level: Int = 0, val scatter: Int = 2)

data class ItemSpec(val obj: String, val count: Int = 1)

data class SuccessSpec(
    /** `xp_gained`, `stat_level` or `item_count`. */
    val type: String,
    val stat: String? = null,
    val xp: Int = 0,
    val level: Int = 0,
    val obj: String? = null,
    val count: Int = 0,
) {
    fun describe(): String =
        when (type) {
            "xp_gained" -> "gain $xp xp in $stat"
            "stat_level" -> "reach level $level in $stat"
            "item_count" -> "hold $count x $obj"
            else -> type
        }
}
