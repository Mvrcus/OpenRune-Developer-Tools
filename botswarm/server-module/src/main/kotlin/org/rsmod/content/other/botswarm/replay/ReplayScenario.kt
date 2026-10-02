package org.rsmod.content.other.botswarm.replay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import org.rsmod.content.other.botswarm.goal.ItemSpec
import org.rsmod.content.other.botswarm.goal.StartSpec

/**
 * A client-side flow cut from a live RSProx capture by `tools/capture-diff/capture_extract.py`: the
 * player's state before the flow, and the clicks they made, with ticks relative to the first click.
 * Replaying it on our server and diffing the recorded stream against the capture's `<id>.live.json`
 * shows every place we differ from live.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReplayScenario(
    val id: String,
    val description: String = "",
    val source: JsonNode? = null,
    val start: StartSpec,
    val varbits: Map<String, Int> = emptyMap(),
    val varps: Map<String, Int> = emptyMap(),
    val inventory: List<ItemSpec> = emptyList(),
    /** Skill name (`"attack"`) -> `[current level, total xp]`, from the capture's stat updates. */
    val stats: Map<String, List<Int>> = emptyMap(),
    /**
     * `"93"` (inventory) / `"94"` (worn) / `"95"` (bank) -> `[slot, obj, count]` triples, from the
     * capture.
     */
    val invSlots: Map<String, List<List<Int>>> = emptyMap(),
    val steps: List<ReplayStep>,
    val durationTicks: Int,
    /**
     * Ticks between login and step 0, so login hooks settle before recording starts. Steps with a
     * negative tick run during this window as unrecorded setup.
     */
    val warmupTicks: Int = 5,
    /**
     * Extra bots that log in with the same state and run their own steps in the same ticks. Their
     * traffic is not recorded; they exist to expose cross-player state (shared script fields).
     */
    val companions: List<ReplayCompanion> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true) data class ReplayCompanion(val steps: List<ReplayStep>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReplayStep(
    val tick: Int,
    /**
     * `button`, `continue`, `count`, `walk`, `opnpc`, `oploc`, `take`, `script`, `drag`,
     * `use_on_com`, `use_on_loc` or `use_on_npc`.
     */
    val kind: String,
    /** `"interface:component"` for buttons. */
    val com: String? = null,
    val name: String? = null,
    val sub: Int = -1,
    val obj: Int = -1,
    val op: Int = 1,
    val count: Int = 0,
    /** Target tile for `walk` / `opnpc` / `oploc` / `take`. */
    val x: Int = 0,
    val z: Int = 0,
    val level: Int = 0,
    /** Npc type for `opnpc` (re-resolved near x/z: npc indexes differ between servers). */
    val npc: Int = -1,
    /** Loc type for `oploc` / `use_on_loc`. */
    val loc: Int = -1,
    /** Target component (`"iface:comp"`) for `drag` / `use_on_com`, with its sub and obj. */
    val target: String? = null,
    val targetSub: Int = -1,
    val targetObj: Int = -1,
    /** `script` (if_script_trigger): the client's crc and raw argument bytes. */
    val crc: Int = 0,
    val payload: List<Int> = emptyList(),
)
