/*
 * Copyright (c) 2026, Mark7625
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.openrune.devtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Canvas;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;

/**
 * Implements the MCP tools exposed by {@link OpenRuneDevToolsPlugin}: screenshots,
 * widget inspection by packed component id, and mouse control on the game canvas.
 */
class McpTools
{
	private static final long CLIENT_THREAD_TIMEOUT_MS = 5_000L;
	private static final long FRAME_TIMEOUT_MS = 5_000L;
	private static final int MAX_COMPONENTS_PER_INTERFACE = 2048;
	private static final int COMPONENT_SCAN_GAP = 64;
	private static final Set<String> DEFAULT_TIMELINE_CHANNELS = Set.of(
		"var", "projectile", "effect", "chat", "combat", "experience", "container", "sound");

	private final Client client;
	private final ClientThread clientThread;
	private final DrawManager drawManager;
	private final McpEventRecorder recorder;
	private final Map<String, ToolDef> tools = new LinkedHashMap<>();

	McpTools(Client client, ClientThread clientThread, DrawManager drawManager, McpEventRecorder recorder)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.drawManager = drawManager;
		this.recorder = recorder;
		registerTools();
	}

	/** Produces the final MCP result object for one tool call. */
	@FunctionalInterface
	private interface ToolHandler
	{
		JsonObject run(JsonObject args) throws Exception;
	}

	private static final class ToolDef
	{
		final String description;
		final JsonObject inputSchema;
		final ToolHandler handler;

		ToolDef(String description, JsonObject inputSchema, ToolHandler handler)
		{
			this.description = description;
			this.inputSchema = inputSchema;
			this.handler = handler;
		}
	}

	JsonArray listTools()
	{
		JsonArray arr = new JsonArray();
		tools.forEach((name, def) -> arr.add(tool(name, def.description, def.inputSchema)));
		return arr;
	}

	JsonObject callTool(String name, JsonObject args) throws Exception
	{
		ToolDef def = tools.get(name);
		if (def == null)
		{
			throw new IllegalArgumentException("Unknown tool: " + name);
		}
		return def.handler.run(args);
	}

	private void register(String name, String description, JsonObject inputSchema, ToolHandler handler)
	{
		tools.put(name, new ToolDef(description, inputSchema, handler));
	}

	private void registerTools()
	{
		// ---- inspection ----

		register("screenshot",
			"Capture a PNG of the game canvas. No args = full canvas. Crop with componentId (single widget), interfaceId (whole interface, tight), or x/y/width/height (region).",
			schema(props(
				"componentId", "integer", "Packed component id to crop to (interfaceId << 16 | childId)",
				"childIndex", "integer", "Dynamic child index of componentId to crop to",
				"interfaceId", "integer", "Interface (group) id to crop to: union of its visible component bounds",
				"padding", "integer", "Extra pixels around the widget/interface crop (default 0)",
				"x", "integer", "Region crop x (canvas pixels)",
				"y", "integer", "Region crop y",
				"width", "integer", "Region crop width",
				"height", "integer", "Region crop height")),
			this::screenshot);

		register("get_widget",
			"Get a widget's values by packed component id: bounds, relative/original position, text, item, sprite, hidden state and children.",
			schema(props(
				"componentId", "integer", "Packed component id (interfaceId << 16 | childId)",
				"childIndex", "integer", "Optional dynamic child index",
				"depth", "integer", "How many levels of dynamic children to include (default 1, max 5)"),
				"componentId"),
			args -> textResult(getWidget(args)));

		register("dump_interface",
			"Dump every component of an interface with positions/sizes/text. Call twice and diff the JSON to spot layout changes.",
			schema(props(
				"interfaceId", "integer", "Interface (group) id to dump",
				"includeHidden", "boolean", "Include hidden components (default false)"),
				"interfaceId"),
			args -> textResult(dumpInterface(args)));

		register("list_interfaces",
			"List interface (group) ids currently loaded and visible, so you know what can be inspected.",
			schema(props()),
			args -> textResult(listInterfaces()));

		register("get_widget_at",
			"Find the deepest visible widget at a canvas pixel: its packed component id, bounds, text etc plus the parent chain. Use to identify what is at a spot on a screenshot.",
			schema(props(
				"x", "integer", "Canvas x",
				"y", "integer", "Canvas y"),
				"x", "y"),
			args -> textResult(getWidgetAt(args)));

		register("set_widget",
			"Live-edit a widget client-side and revalidate: move, resize, hide, retext, recolor etc. Great for prototyping interface layout changes before touching server code. Server scripts may overwrite the change on the next interface update.",
			schema(props(
				"componentId", "integer", "Packed component id of the widget to modify",
				"childIndex", "integer", "Optional dynamic child index",
				"hidden", "boolean", "Show/hide the widget",
				"text", "string", "New text",
				"x", "integer", "New original X",
				"y", "integer", "New original Y",
				"width", "integer", "New original width",
				"height", "integer", "New original height",
				"spriteId", "integer", "New sprite id",
				"opacity", "integer", "New opacity 0-255 (0 = solid)",
				"textColor", "integer", "New text color as 0xRRGGBB int",
				"itemId", "integer", "New item id",
				"itemQuantity", "integer", "New item quantity"),
				"componentId"),
			args -> textResult(setWidget(args)));

		register("get_client_state",
			"Get game state, canvas size, resized/fixed mode, mouse position, player world position/region, run energy, weight, camera yaw/pitch/zoom and whether a right-click menu is open.",
			schema(props()),
			args -> textResult(getClientState()));

		register("get_combat_state",
			"One cheap call for whether the local player is fighting, moving or idle: ticks since a hitsplat landed on you and since one you dealt landed, inCombat (either within 8 ticks, Idle Notifier's rule), moving/animating, walk destination, and the NPC you are attacking plus every NPC attacking you.",
			schema(props()),
			args -> textResult(getCombatState()));

		register("observe",
			"Everything a decision loop reads each step, in ONE client-thread pass: tick, position, hp/prayer, run, combat state, inventory with each item's options, nearest NPCs/interactive objects/ground items, dialogue. Use instead of calling get_client_state, get_skills, get_inventory, list_* and get_dialogue separately.",
			schema(props(
				"maxDistance", "integer", "Only things within this many tiles (default 15)",
				"maxNpcs", "integer", "Nearest NPCs to include (default 10)",
				"maxObjects", "integer", "Nearest interactive objects to include (default 10)",
				"maxItems", "integer", "Nearest ground items to include (default 6)")),
			args -> textResult(observe(args)));

		register("list_actions",
			"Every action available right now as a numbered menu with plain-language labels (\"Attack Goblin (lvl 2, 3 tiles)\", \"eat Shark\", dialogue options), each with the tool + args that perform it. Built for small decision models that pick from a list. Run one with act(id). While a dialogue options menu is open, only its options are listed.",
			schema(props(
				"maxDistance", "integer", "Only things within this many tiles (default 15)",
				"maxActions", "integer", "Cap on menu length (default 40)",
				"dedupe", "boolean", "Keep only the nearest of identical actions, e.g. one 'Attack Goblin' (default true)",
				"includeObservation", "boolean", "Also return the observe snapshot the menu was built from")),
			args -> textResult(listActions(args)));

		register("act",
			"Run action `id` from the last list_actions menu. Fails if that menu is older than maxAgeTicks, so a stale choice is never executed.",
			schema(props(
				"id", "integer", "Action id from list_actions",
				"maxAgeTicks", "integer", "Refuse menus older than this many game ticks (default 3)"),
				"id"),
			args -> textResult(act(args)));

		register("close_interface",
			"Press the Close button of every open interface that has one (bank, shop, menus) via its own op. Use before typing commands: an open bank or shop swallows them.",
			schema(props()),
			args -> textResult(closeInterfaces()));

		register("bank",
			"Use the open bank in ONE call: action deposit_inventory, or withdraw / deposit with itemId and quantity (1, 5, 10, all, or any number via the X amount prompt). Invokes the slot's own op, no hovering.",
			schema(props(
				"action", "string", "deposit_inventory, withdraw or deposit",
				"itemId", "integer", "Item id (withdraw/deposit)",
				"quantity", "string", "1, 5, 10, all, or a number (default 1)"),
				"action"),
			args -> textResult(bank(args)));

		register("shop", "Buy from or sell to the open shop: action buy or sell, itemId, quantity. Uses the shop's Buy/Sell 1/5/10/50 ops largest-first; reports how many were clicked.",
			schema(props(
				"action", "string", "buy or sell",
				"itemId", "integer", "Item id",
				"quantity", "integer", "How many (default 1)"),
				"action", "itemId"),
			args -> textResult(shop(args)));

		register("set_prayer",
			"Turn a prayer on or off in ONE call, without opening the prayer tab: e.g. prayer PROTECT_FROM_MAGIC, on true. Names are RuneLite Prayer enum names (PROTECT_FROM_MISSILES, PROTECT_FROM_MELEE, EAGLE_EYE, RIGOUR...). No-op when it is already in that state. Returns the active prayers afterwards.",
			schema(props(
				"prayer", "string", "Prayer enum name, e.g. PROTECT_FROM_MAGIC",
				"on", "boolean", "true to activate (default), false to deactivate"),
				"prayer"),
			args -> textResult(setPrayer(args)));

		register("get_skills",
			"Local player skills: level, boosted level and xp per skill, plus total level. Hitpoints boosted level = current HP.",
			schema(props()),
			args -> textResult(getSkills()));

		// ---- raw input ----

		register("click",
			"Click at canvas coordinates by dispatching real mouse events.",
			schema(props(
				"x", "integer", "Canvas x",
				"y", "integer", "Canvas y",
				"button", "integer", "Mouse button: 1=left (default), 2=middle, 3=right"),
				"x", "y"),
			this::click);

		register("click_component",
			"Click the center of a widget resolved by packed component id. Fails if the widget is missing or hidden.",
			schema(props(
				"componentId", "integer", "Packed component id to click",
				"childIndex", "integer", "Optional dynamic child index",
				"button", "integer", "Mouse button: 1=left (default), 2=middle, 3=right"),
				"componentId"),
			this::clickComponent);

		register("hover",
			"Move the mouse to canvas coordinates or a widget's center without clicking.",
			schema(props(
				"x", "integer", "Canvas x",
				"y", "integer", "Canvas y",
				"componentId", "integer", "Packed component id to hover (used instead of x/y)",
				"childIndex", "integer", "Optional dynamic child index")),
			this::hover);

		register("drag",
			"Press at the source, drag through intermediate points, release at the target. Source/target are either packed component ids or canvas coordinates.",
			schema(props(
				"fromComponentId", "integer", "Packed component id to drag from",
				"fromChildIndex", "integer", "Optional dynamic child index for the source",
				"fromX", "integer", "Source canvas x (used if fromComponentId absent)",
				"fromY", "integer", "Source canvas y",
				"toComponentId", "integer", "Packed component id to drag to",
				"toChildIndex", "integer", "Optional dynamic child index for the target",
				"toX", "integer", "Target canvas x (used if toComponentId absent)",
				"toY", "integer", "Target canvas y",
				"steps", "integer", "Number of intermediate drag moves (default 8)",
				"stepDelayMs", "integer", "Delay between drag moves in ms (default 20)")),
			this::drag);

		register("type_chat",
			"Type text into the game chatbox by dispatching key events, then press Enter to send. Works for :: commands.",
			schema(props(
				"text", "string", "Text to type into the chatbox, e.g. a chat message or a :: command",
				"send", "boolean", "Press Enter after typing (default true)"),
				"text"),
			this::typeChat);

		register("press_key",
			"Press a single key on the game canvas (e.g. ESCAPE to close an interface).",
			schema(props(
				"key", "string", "Single character, or ENTER, ESCAPE, SPACE, TAB, BACKSPACE, UP, DOWN, LEFT, RIGHT, F1-F12"),
				"key"),
			this::pressKey);

		// ---- world ----

		register("walk_to",
			"Click the tile at world coordinates to walk there. Clicks the viewport when the tile is on screen, otherwise the minimap. Tile must be in the loaded scene.",
			schema(props(
				"x", "integer", "World (map) x coordinate",
				"y", "integer", "World (map) y coordinate",
				"plane", "integer", "Plane 0-3 (default: current plane)"),
				"x", "y"),
			this::walkTo);

		register("path_to",
			"Plan a route through the loaded scene to a tile, treating closed doors, gates and other obstacles (Open, Climb-over, Squeeze-through, ...) as passable. "
				+ "Returns the obstacles the route crosses, in order, so you can open each one and then walk on; an open route always wins over one through an obstacle. "
				+ "found:false means the tile is walled off even with every obstacle open (a content bug, or it needs stairs, a ladder or a teleport). Pure computation, ~1 ms; never moves the player.",
			schema(props(
				"x", "integer", "World (map) x coordinate",
				"y", "integer", "World (map) y coordinate",
				"width", "integer", "Target width in tiles (default 1); a blocked target is reached from any tile beside it",
				"height", "integer", "Target height in tiles (default 1)"),
				"x", "y"),
			this::pathTo);

		register("list_npcs",
			"List NPCs with id, index, name, combat level, actions, world location, distance, animation, facing, health (when visible) and canvas position, sorted by distance.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on NPC name",
				"maxDistance", "integer", "Only NPCs within this many tiles of the player",
				"maxResults", "integer", "Cap on returned NPCs (default 50)")),
			args -> textResult(listNpcs(args)));

		register("list_players",
			"List players with name, combat level, world location, distance, animation, facing, health (when visible) and canvas position, sorted by distance. Local player is flagged isLocal.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on player name",
				"maxDistance", "integer", "Only players within this many tiles of the local player",
				"maxResults", "integer", "Cap on returned players (default 50)")),
			args -> textResult(listPlayers(args)));

		register("list_objects",
			"List scene objects (game, wall, decorative, ground) with id, name, actions, world position, face direction (orientation in jau 0-2047, degrees and compass) and size, sorted by distance.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on object name",
				"objectId", "integer", "Only objects with this id",
				"maxDistance", "integer", "Only objects within this many tiles of the player",
				"maxResults", "integer", "Cap on returned objects (default 50)")),
			args -> textResult(listObjects(args)));

		register("list_ground_items",
			"List items on the floor: itemId, name, quantity, world location and distance, sorted by distance. Use pickup_item to take one.",
			schema(props(
				"nameFilter", "string", "Case-insensitive substring filter on item name",
				"maxDistance", "integer", "Only items within this many tiles of the player",
				"maxResults", "integer", "Cap on returned items (default 50)")),
			args -> textResult(listGroundItems(args)));

		register("pickup_item",
			"Invoke Take on a ground item at world coordinates. Player walks to the tile and picks it up.",
			schema(props(
				"itemId", "integer", "Item id from list_ground_items",
				"x", "integer", "World x of the tile the item is on",
				"y", "integer", "World y of the tile",
				"plane", "integer", "Plane (default current)"),
				"itemId", "x", "y"),
			args -> textResult(pickupItem(args)));

		register("list_projectiles",
			"List projectiles currently in flight: id, remaining cycles, start scene position and target point/actor.",
			schema(props()),
			args -> textResult(listProjectiles()));

		register("get_inventory",
			"Read an item container by id: every slot with itemId, name and quantity. 93=inventory, 94=worn, 95=bank; server-made containers work too.",
			schema(props(
				"inventoryId", "integer", "Item container id, e.g. 93 inventory, 94 worn equipment, 95 bank",
				"includeEmpty", "boolean", "Include empty slots as itemId -1 (default false)"),
				"inventoryId"),
			args -> textResult(getInventory(args)));

		register("list_inventories",
			"List all item containers currently loaded in the client: container id, slot count and non-empty item count. Use get_inventory for contents.",
			schema(props()),
			args -> textResult(listInventories()));

		// ---- interaction ----

		register("interact_npc",
			"Invoke a cache-defined right-click option on an NPC (default Talk-to). Only sees options from the NPC definition; for server-added or runtime menu options use get_npc_menu + click_menu_option instead.",
			schema(props(
				"npcIndex", "integer", "NPC index from list_npcs (exact match)",
				"npcName", "string", "Case-insensitive NPC name substring; nearest match wins (used if npcIndex absent)",
				"option", "string", "Right-click menu option to invoke, e.g. Talk-to, Trade, Attack (default Talk-to)")),
			args -> textResult(interactNpc(args)));

		register("get_npc_menu",
			"Hover an NPC and read its live right-click mini-menu: every entry including server-added quick options, top entry first (index 0 = default left-click). Follow with click_menu_option.",
			schema(props(
				"npcIndex", "integer", "NPC index from list_npcs (exact match)",
				"npcName", "string", "Case-insensitive NPC name substring; nearest match wins")),
			args -> textResult(getNpcMenu(args)));

		register("click_menu_option",
			"Click an entry of the currently built mini-menu (hover something first, e.g. via get_npc_menu or hover). Invokes the entry exactly as a right-click menu click would.",
			schema(props(
				"option", "string", "Case-insensitive substring of the menu option text, e.g. Vote, Trade, Quick-start",
				"target", "string", "Optional case-insensitive substring of the entry target to disambiguate"),
				"option"),
			args -> textResult(clickMenuOption(args)));

		register("item_action",
			"Right-click an inventory item and click an option in ONE call - no screenshot needed. Finds the item slot, hovers it, invokes the matching menu entry (e.g. Drop). Inventory tab must be visible.",
			schema(props(
				"itemId", "integer", "Item id in the inventory",
				"itemName", "string", "Case-insensitive item name substring (used if itemId absent)",
				"option", "string", "Right-click option to invoke, e.g. Drop, Eat, Wield, Use, Bury"),
				"option"),
			args -> textResult(itemAction(args)));

		register("interact_object",
			"Interact with a scene object in ONE call - no screenshot needed. Finds the nearest matching object, hovers it and clicks the option. e.g. nameFilter 'tree' + option 'Chop down'. list_objects shows available actions.",
			schema(props(
				"objectId", "integer", "Object id to interact with",
				"nameFilter", "string", "Case-insensitive object name substring; nearest match wins (used if objectId absent)",
				"option", "string", "Menu option to click, e.g. Chop down, Mine, Bank, Open. Default: the object's first action")),
			args -> textResult(interactObject(args)));

		register("interact_player",
			"Interact with another player in ONE call - no screenshot needed. Hovers the player and clicks the matching right-click option, e.g. Trade with.",
			schema(props(
				"playerName", "string", "Case-insensitive player name substring; nearest match wins",
				"option", "string", "Menu option to click, e.g. Trade with, Follow, Challenge, Attack"),
				"playerName", "option"),
			args -> textResult(interactPlayer(args)));

		register("widget_action",
			"Right-click any widget and click a menu option in ONE call: hovers the widget, then invokes the matching mini-menu entry. Works for spells, prayers, equipment, buttons.",
			schema(props(
				"componentId", "integer", "Packed component id of the widget",
				"childIndex", "integer", "Optional dynamic child index",
				"option", "string", "Case-insensitive substring of the menu option to invoke"),
				"componentId", "option"),
			args -> textResult(widgetAction(args)));

		register("invoke_menu_action",
			"Low-level escape hatch: invoke client.menuAction with raw parameters. Use only when the higher-level interact tools cannot express the action (params visible in get_npc_menu entries or script history).",
			schema(props(
				"param0", "integer", "Menu param0 (often slot or scene x)",
				"param1", "integer", "Menu param1 (often packed widget id or scene y)",
				"type", "string", "MenuAction enum name, e.g. CC_OP, WIDGET_TARGET, GAME_OBJECT_FIRST_OPTION",
				"identifier", "integer", "Menu identifier (op index, npc index, object id...)",
				"itemId", "integer", "Item id (default -1)",
				"option", "string", "Option text (default empty)",
				"target", "string", "Target text (default empty)"),
				"param0", "param1", "type", "identifier"),
			args -> textResult(invokeRawMenuAction(args)));

		// ---- dialogue ----

		register("get_dialogue",
			"Read the current dialogue state: NPC/player dialogue text and speaker, numbered options menu, message box, item dialog, or open chatbox input prompt.",
			schema(props()),
			args -> textResult(onClientThread(this::readDialogue)));

		register("continue_dialogue",
			"Press space to advance the current dialogue and return the new dialogue state as soon as it changes.",
			schema(props()),
			args ->
			{
				String before = dialogueFingerprint();
				pressNamedKey("SPACE");
				return textResult(waitDialogueChange(before));
			});

		register("select_option",
			"Choose a numbered dialogue option by pressing its number key and return the new dialogue state as soon as it changes.",
			schema(props(
				"option", "integer", "Option number as shown by get_dialogue (1-based)"),
				"option"),
			this::selectOption);

		register("enter_input",
			"Type a value into the open chatbox input (string or amount prompt) and press Enter, then return the new dialogue state.",
			schema(props(
				"text", "string", "Value to type: a string or a number depending on the prompt"),
				"text"),
			this::enterInput);

		// ---- history and vars ----

		register("get_script_history",
			"Report clientscripts fired recently: per-script fire counts plus individual events with game tick, timestamp and root-script arguments.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 5000)",
				"scriptId", "integer", "Only this clientscript id",
				"limit", "integer", "Max events returned, newest kept (default 200)")),
			args -> textResult(recorder.scriptHistory(
				optInt(args, "sinceMs", 5000),
				args.has("scriptId") ? args.get("scriptId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_var_history",
			"Report varbit, varp and varc changes in a recent window, in order, with old and new values, game tick and timestamp.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 5000)",
				"type", "string", "Filter: varbit, varp, varcint or varcstr",
				"id", "integer", "Only this varbit/varp/varc id",
				"limit", "integer", "Max events returned, newest kept (default 200)")),
			args -> textResult(recorder.varHistory(
				optInt(args, "sinceMs", 5000),
				args.has("type") ? args.get("type").getAsString() : null,
				args.has("id") ? args.get("id").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_var",
			"Read the current value of a varbit, varp, varc int or varc string.",
			schema(props(
				"type", "string", "One of: varbit, varp, varcint, varcstr",
				"id", "integer", "The varbit/varp/varc id"),
				"type", "id"),
			args -> textResult(getVar(args)));

		register("get_projectile_history",
			"Report projectiles launched recently: id, start position, slope/height values, cycles and target, one entry per projectile.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"projectileId", "integer", "Only this projectile (gfx) id",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.projectileHistory(
				optInt(args, "sinceMs", 10000),
				args.has("projectileId") ? args.get("projectileId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_effect_history",
			"Report animations and graphics (spotanims) played by NPCs/players recently, with actor name, npc id, value and game tick.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"kind", "string", "Filter: animation or graphic",
				"actorName", "string", "Case-insensitive substring filter on the actor name",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.effectHistory(
				optInt(args, "sinceMs", 10000),
				args.has("kind") ? args.get("kind").getAsString() : null,
				args.has("actorName") ? args.get("actorName").getAsString() : null,
				optInt(args, "limit", 200))));

		register("get_chat_history",
			"Read chatbox messages received recently: type, sender, message, game tick and timestamp. Covers game messages, public/private chat etc (not NPC dialogue - use get_dialogue).",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 60000)",
				"type", "string", "Filter by ChatMessageType name, e.g. GAMEMESSAGE, PUBLICCHAT, PRIVATECHAT, SPAM",
				"nameFilter", "string", "Case-insensitive substring filter on the sender name",
				"limit", "integer", "Max messages returned (default 100)")),
			args -> textResult(recorder.chatHistory(
				optInt(args, "sinceMs", 60000),
				args.has("type") ? args.get("type").getAsString() : null,
				args.has("nameFilter") ? args.get("nameFilter").getAsString() : null,
				optInt(args, "limit", 100))));

		register("get_combat_history",
			"Report hitsplats applied recently: damage amount, hitsplat type, the actor hit, its remaining health ratio and game tick. Use this for max hit, accuracy and splash checks instead of watching damage numbers on screen.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"actorName", "string", "Case-insensitive substring filter on the actor name",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.combatHistory(
				optInt(args, "sinceMs", 10000),
				args.has("actorName") ? args.get("actorName").getAsString() : null,
				optInt(args, "limit", 200))));

		register("get_xp_history",
			"Report experience gains recently: skill, xp gained, new total xp, level and boosted level, with game tick. Use to check xp per action against the expected rate.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 60000)",
				"skill", "string", "Only this skill, e.g. ATTACK, WOODCUTTING",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.experienceHistory(
				optInt(args, "sinceMs", 60000),
				args.has("skill") ? args.get("skill").getAsString() : null,
				optInt(args, "limit", 200))));

		register("get_container_history",
			"Report item container changes recently as per-item deltas (was, now, delta), so you can see exactly what was gained, consumed or dropped and on which tick. 93 inventory, 94 worn, 95 bank.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 60000)",
				"containerId", "integer", "Only this container id",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.containerHistory(
				optInt(args, "sinceMs", 60000),
				args.has("containerId") ? args.get("containerId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_sound_history",
			"Report sound effects played recently: sound id, source actor, and for area sounds the scene position, with game tick.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"soundId", "integer", "Only this sound id",
				"limit", "integer", "Max events returned (default 200)")),
			args -> textResult(recorder.soundHistory(
				optInt(args, "sinceMs", 10000),
				args.has("soundId") ? args.get("soundId").getAsInt() : null,
				optInt(args, "limit", 200))));

		register("get_timeline",
			"Every recorded channel merged into one tick-ordered stream: vars, projectiles, effects, chat, combat, experience, containers and sounds. Scripts fire many times a second, so they are left out unless requested via channels. Read this to see what happened around an action, instead of correlating the per-channel history tools by hand.",
			schema(props(
				"sinceMs", "integer", "Window in milliseconds looking back from now (default 10000)",
				"channels", "array", "Only these channels: script, var, projectile, effect, chat, combat, experience, container, sound (default all but script)",
				"fromTick", "integer", "Only events on or after this game tick",
				"toTick", "integer", "Only events on or before this game tick",
				"limit", "integer", "Max events returned, newest kept (default 300)")),
			args -> textResult(recorder.timeline(
				optInt(args, "sinceMs", 10000),
				Objects.requireNonNullElse(optStringSet(args, "channels"), DEFAULT_TIMELINE_CHANNELS),
				args.has("fromTick") ? args.get("fromTick").getAsInt() : null,
				args.has("toTick") ? args.get("toTick").getAsInt() : null,
				optInt(args, "limit", 300))));

		// ---- waiting ----

		register("wait_for",
			"Block until a game condition is met, checked every 100ms - returns the moment it happens. Use this after walk_to/interact/attack instead of taking screenshots or guessing delays. Returns met:false with current state on timeout.",
			schema(props(
				"condition", "string", "What to wait for: idle (player stopped animating/moving), at_tile (arrived at x/y), npc_dead (npcIndex/npcName), dialogue (any dialogue opened), interface_open / interface_closed (interfaceId), chat_message (textContains appeared)",
				"timeoutMs", "integer", "Give up after this long (default 10000, max 60000)",
				"x", "integer", "at_tile: world x",
				"y", "integer", "at_tile: world y",
				"range", "integer", "at_tile: max tiles away to count as arrived (default 1)",
				"npcIndex", "integer", "npc_dead: NPC index from list_npcs",
				"npcName", "string", "npc_dead: NPC name substring",
				"interfaceId", "integer", "interface_open/closed: interface group id",
				"textContains", "string", "chat_message: case-insensitive substring to watch for"),
				"condition"),
			this::waitFor);
	}

	/** Build a JSON Schema properties object from (name, type, description) triples. */
	private static JsonObject props(String... triples)
	{
		JsonObject o = new JsonObject();
		for (int i = 0; i < triples.length; i += 3)
		{
			o.add(triples[i], prop(triples[i + 1], triples[i + 2]));
		}
		return o;
	}

	// --- tools ---

	private JsonObject screenshot(JsonObject args) throws Exception
	{
		Rectangle crop = null;
		if (args.has("componentId"))
		{
			int padding = optInt(args, "padding", 0);
			Rectangle bounds = onClientThread(() ->
			{
				Widget w = resolveWidget(args, "componentId", "childIndex");
				return w == null || w.isHidden() ? null : w.getBounds();
			});
			if (bounds == null)
			{
				throw new IllegalArgumentException("Widget not found or hidden: " + describeId(args, "componentId", "childIndex"));
			}
			crop = pad(bounds, padding);
		}
		else if (args.has("interfaceId"))
		{
			int interfaceId = args.get("interfaceId").getAsInt();
			int padding = optInt(args, "padding", 0);
			Rectangle bounds = onClientThread(() -> interfaceBounds(interfaceId));
			if (bounds == null)
			{
				throw new IllegalArgumentException("Interface " + interfaceId + " is not loaded or has no visible components");
			}
			crop = pad(bounds, padding);
		}
		else if (args.has("x") && args.has("y") && args.has("width") && args.has("height"))
		{
			crop = new Rectangle(args.get("x").getAsInt(), args.get("y").getAsInt(),
				args.get("width").getAsInt(), args.get("height").getAsInt());
		}

		BufferedImage frame = captureFrame();
		if (crop != null)
		{
			Rectangle clamped = crop.intersection(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
			if (clamped.isEmpty())
			{
				throw new IllegalArgumentException("Crop region " + crop + " is outside the canvas ("
					+ frame.getWidth() + "x" + frame.getHeight() + ")");
			}
			frame = frame.getSubimage(clamped.x, clamped.y, clamped.width, clamped.height);
			crop = clamped;
		}

		JsonObject image = new JsonObject();
		image.addProperty("type", "image");
		image.addProperty("data", pngBase64(frame));
		image.addProperty("mimeType", "image/png");

		JsonArray content = new JsonArray();
		content.add(image);
		JsonObject result = new JsonObject();
		result.add("content", content);
		return result;
	}

	private JsonElement getWidget(JsonObject args) throws Exception
	{
		int depth = Math.min(optInt(args, "depth", 1), 5);
		JsonElement result = onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			return w == null ? null : describeWidget(w, depth, true);
		});
		if (result == null)
		{
			throw new IllegalArgumentException("Widget not found: " + describeId(args, "componentId", "childIndex"));
		}
		return result;
	}

	private JsonElement dumpInterface(JsonObject args) throws Exception
	{
		int interfaceId = args.get("interfaceId").getAsInt();
		boolean includeHidden = args.has("includeHidden") && args.get("includeHidden").getAsBoolean();
		return onClientThread(() ->
		{
			JsonArray components = new JsonArray();
			int found = 0;
			int gap = 0;
			for (int child = 0; child < MAX_COMPONENTS_PER_INTERFACE && gap < COMPONENT_SCAN_GAP; child++)
			{
				Widget w = client.getWidget(WidgetUtil.packComponentId(interfaceId, child));
				if (w == null)
				{
					gap++;
					continue;
				}
				gap = 0;
				found++;
				if (includeHidden || !w.isHidden())
				{
					components.add(describeWidget(w, 1, includeHidden));
				}
			}
			JsonObject result = new JsonObject();
			result.addProperty("interfaceId", interfaceId);
			result.addProperty("componentCount", found);
			result.add("components", components);
			return result;
		});
	}

	private JsonElement listInterfaces() throws Exception
	{
		return onClientThread(() ->
		{
			Set<Integer> visible = new HashSet<>();
			Set<Integer> loaded = new HashSet<>();
			Widget[] roots = client.getWidgetRoots();
			if (roots != null)
			{
				Deque<Widget> queue = new ArrayDeque<>();
				for (Widget root : roots)
				{
					if (root != null)
					{
						queue.add(root);
					}
				}
				while (!queue.isEmpty())
				{
					Widget w = queue.poll();
					int group = WidgetUtil.componentToInterface(w.getId());
					loaded.add(group);
					if (!w.isHidden())
					{
						visible.add(group);
					}
					enqueueChildren(queue, w.getStaticChildren());
					enqueueChildren(queue, w.getNestedChildren());
				}
			}
			JsonObject result = new JsonObject();
			result.add("visibleInterfaceIds", toArray(visible));
			result.add("loadedInterfaceIds", toArray(loaded));
			return result;
		});
	}

	/**
	 * Press the Close button of every open interface that has one (bank, shop, menus), via its
	 * own op. An open modal blocks typed commands and teleports, and ESC only closes it when the
	 * player has that setting on.
	 */
	private JsonElement closeInterfaces() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray closed = new JsonArray();
			Widget[] roots = client.getWidgetRoots();
			Deque<Widget> queue = new ArrayDeque<>();
			if (roots != null)
			{
				for (Widget root : roots)
				{
					if (root != null)
					{
						queue.add(root);
					}
				}
			}
			while (!queue.isEmpty())
			{
				Widget w = queue.poll();
				try
				{
					if (w.isHidden())
					{
						continue;
					}
					String[] ops = w.getActions();
					for (int i = 0; ops != null && i < ops.length; i++)
					{
						if (ops[i] != null && Text.removeTags(ops[i]).equalsIgnoreCase("Close"))
						{
							client.menuAction(w.getIndex(), w.getId(), MenuAction.CC_OP, i + 1, -1, ops[i], "");
							closed.add(WidgetUtil.componentToInterface(w.getId()));
							break;
						}
					}
					enqueueChildren(queue, w.getStaticChildren());
					enqueueChildren(queue, w.getNestedChildren());
					enqueueChildren(queue, w.getDynamicChildren());
				}
				catch (RuntimeException e)
				{
					// Some widgets throw while their interface is mid-rebuild; skip them.
				}
			}
			JsonObject result = new JsonObject();
			result.add("closedInterfaces", closed);
			return result;
		});
	}

	private JsonElement getClientState() throws Exception
	{
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("gameState", client.getGameState().name());
			result.addProperty("canvasWidth", client.getCanvasWidth());
			result.addProperty("canvasHeight", client.getCanvasHeight());
			result.addProperty("resized", client.isResized());
			net.runelite.api.Point mouse = client.getMouseCanvasPosition();
			if (mouse != null)
			{
				result.addProperty("mouseX", mouse.getX());
				result.addProperty("mouseY", mouse.getY());
			}
			Player local = client.getLocalPlayer();
			if (local != null && local.getWorldLocation() != null)
			{
				WorldPoint wp = local.getWorldLocation();
				result.addProperty("worldX", wp.getX());
				result.addProperty("worldY", wp.getY());
				result.addProperty("plane", wp.getPlane());
				result.addProperty("regionId", wp.getRegionID());
			}
			result.addProperty("runEnergy", client.getEnergy());
			result.addProperty("weight", client.getWeight());
			result.addProperty("menuOpen", client.isMenuOpen());
			result.addProperty("cameraYaw", client.getCameraYaw());
			result.addProperty("cameraPitch", client.getCameraPitch());
			result.addProperty("cameraZoom", client.get3dZoom());
			return result;
		});
	}

	private JsonElement setWidget(JsonObject args) throws Exception
	{
		return onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			if (w == null)
			{
				throw new IllegalArgumentException("Widget not found: " + describeId(args, "componentId", "childIndex"));
			}
			if (args.has("hidden"))
			{
				w.setHidden(args.get("hidden").getAsBoolean());
			}
			if (args.has("text"))
			{
				w.setText(args.get("text").getAsString());
			}
			if (args.has("x"))
			{
				w.setOriginalX(args.get("x").getAsInt());
			}
			if (args.has("y"))
			{
				w.setOriginalY(args.get("y").getAsInt());
			}
			if (args.has("width"))
			{
				w.setOriginalWidth(args.get("width").getAsInt());
			}
			if (args.has("height"))
			{
				w.setOriginalHeight(args.get("height").getAsInt());
			}
			if (args.has("spriteId"))
			{
				w.setSpriteId(args.get("spriteId").getAsInt());
			}
			if (args.has("opacity"))
			{
				w.setOpacity(args.get("opacity").getAsInt());
			}
			if (args.has("textColor"))
			{
				w.setTextColor(args.get("textColor").getAsInt());
			}
			if (args.has("itemId"))
			{
				w.setItemId(args.get("itemId").getAsInt());
			}
			if (args.has("itemQuantity"))
			{
				w.setItemQuantity(args.get("itemQuantity").getAsInt());
			}
			w.revalidate();
			return describeWidget(w, 0, true);
		});
	}

	private JsonElement getWidgetAt(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		return onClientThread(() ->
		{
			Widget best = null;
			Widget[] roots = client.getWidgetRoots();
			if (roots != null)
			{
				for (Widget root : roots)
				{
					best = deepestWidgetAt(root, x, y, best);
				}
			}
			if (best == null)
			{
				throw new IllegalArgumentException("No visible widget at " + x + "," + y);
			}
			JsonObject o = describeWidget(best, 0, false);
			JsonArray parentChain = new JsonArray();
			for (Widget parent = best.getParent(); parent != null; parent = parent.getParent())
			{
				parentChain.add(parent.getId());
			}
			o.add("parentChain", parentChain);
			return o;
		});
	}

	/**
	 * Depth-first search for the deepest visible widget containing the point;
	 * later siblings win like the renderer. Must be called on the client thread.
	 */
	private Widget deepestWidgetAt(Widget w, int x, int y, Widget best)
	{
		if (w == null || w.isHidden())
		{
			return best;
		}
		Rectangle b = w.getBounds();
		if (b != null && b.contains(x, y))
		{
			best = w;
		}
		Widget[][] childGroups = {w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()};
		for (Widget[] children : childGroups)
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				best = deepestWidgetAt(child, x, y, best);
			}
		}
		return best;
	}

	private JsonElement invokeRawMenuAction(JsonObject args) throws Exception
	{
		MenuAction type = MenuAction.valueOf(args.get("type").getAsString());
		int param0 = args.get("param0").getAsInt();
		int param1 = args.get("param1").getAsInt();
		int identifier = args.get("identifier").getAsInt();
		int itemId = optInt(args, "itemId", -1);
		String option = args.has("option") ? args.get("option").getAsString() : "";
		String target = args.has("target") ? args.get("target").getAsString() : "";
		return onClientThread(() ->
		{
			client.menuAction(param0, param1, type, identifier, itemId, option, target);
			JsonObject result = new JsonObject();
			result.addProperty("invoked", type.name());
			result.addProperty("identifier", identifier);
			return result;
		});
	}

	private JsonObject click(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		int button = optInt(args, "button", MouseEvent.BUTTON1);
		clickAt(x, y, button);
		return textResult("Clicked button " + button + " at " + x + "," + y);
	}

	private JsonObject clickComponent(JsonObject args) throws Exception
	{
		int button = optInt(args, "button", MouseEvent.BUTTON1);
		Rectangle bounds = onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			return w == null || w.isHidden() ? null : w.getBounds();
		});
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			throw new IllegalArgumentException("Widget not found, hidden or has no bounds: "
				+ describeId(args, "componentId", "childIndex"));
		}
		int x = bounds.x + bounds.width / 2;
		int y = bounds.y + bounds.height / 2;
		clickAt(x, y, button);
		return textResult("Clicked " + describeId(args, "componentId", "childIndex")
			+ " at " + x + "," + y + " (bounds " + bounds.x + "," + bounds.y + " "
			+ bounds.width + "x" + bounds.height + ")");
	}

	private JsonObject hover(JsonObject args) throws Exception
	{
		Point p = resolvePoint(args, "componentId", "childIndex", "x", "y");
		dispatchMouse(MouseEvent.MOUSE_MOVED, p.x, p.y, MouseEvent.NOBUTTON, 0);
		return textResult("Hovering at " + p.x + "," + p.y);
	}

	private JsonObject drag(JsonObject args) throws Exception
	{
		Point from = resolvePoint(args, "fromComponentId", "fromChildIndex", "fromX", "fromY");
		Point to = resolvePoint(args, "toComponentId", "toChildIndex", "toX", "toY");
		int steps = Math.max(1, optInt(args, "steps", 8));
		long stepDelay = optInt(args, "stepDelayMs", 20);

		dispatchMouse(MouseEvent.MOUSE_MOVED, from.x, from.y, MouseEvent.NOBUTTON, 0);
		Thread.sleep(30);
		dispatchMouse(MouseEvent.MOUSE_PRESSED, from.x, from.y, MouseEvent.BUTTON1, 1);
		Thread.sleep(100);
		for (int i = 1; i <= steps; i++)
		{
			int x = from.x + (to.x - from.x) * i / steps;
			int y = from.y + (to.y - from.y) * i / steps;
			dispatchMouse(MouseEvent.MOUSE_DRAGGED, x, y, MouseEvent.BUTTON1, 0);
			Thread.sleep(stepDelay);
		}
		dispatchMouse(MouseEvent.MOUSE_RELEASED, to.x, to.y, MouseEvent.BUTTON1, 1);
		return textResult("Dragged from " + from.x + "," + from.y + " to " + to.x + "," + to.y);
	}

	private JsonObject typeChat(JsonObject args) throws Exception
	{
		String text = args.get("text").getAsString();
		boolean send = !args.has("send") || args.get("send").getAsBoolean();
		for (char c : text.toCharArray())
		{
			typeChar(c);
		}
		if (send)
		{
			pressNamedKey("ENTER");
		}
		return textResult((send ? "Typed and sent: " : "Typed: ") + text);
	}

	private JsonObject pressKey(JsonObject args) throws Exception
	{
		String key = args.get("key").getAsString();
		if (key.length() == 1)
		{
			typeChar(key.charAt(0));
		}
		else
		{
			pressNamedKey(key.toUpperCase());
		}
		return textResult("Pressed " + key);
	}

	private JsonObject walkTo(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		Integer planeArg = args.has("plane") ? args.get("plane").getAsInt() : null;
		JsonObject target = onClientThread(() ->
		{
			int plane = planeArg != null ? planeArg : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				return null;
			}
			net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
			JsonObject t = new JsonObject();
			if (p != null && p.getX() >= 0 && p.getY() >= 0
				&& p.getX() < client.getCanvasWidth() && p.getY() < client.getCanvasHeight())
			{
				t.addProperty("x", p.getX());
				t.addProperty("y", p.getY());
				t.addProperty("via", "viewport");
				return t;
			}
			net.runelite.api.Point mp = Perspective.localToMinimap(client, lp);
			if (mp != null)
			{
				t.addProperty("x", mp.getX());
				t.addProperty("y", mp.getY());
				t.addProperty("via", "minimap");
				return t;
			}
			return null;
		});
		if (target == null)
		{
			throw new IllegalArgumentException("Tile " + x + "," + y
				+ " is not in the loaded scene or not visible on the viewport/minimap");
		}
		int cx = target.get("x").getAsInt();
		int cy = target.get("y").getAsInt();
		String via = target.get("via").getAsString();
		clickAt(cx, cy, MouseEvent.BUTTON1);
		return textResult("Clicked " + via + " at " + cx + "," + cy + " to walk to " + x + "," + y);
	}

	/** Options that get a player through or over an obstacle; such objects count as passable. */
	private static final Set<String> OBSTACLE_OPS = Set.of("open", "climb-over", "climb-through", "squeeze-through",
		"crawl-through", "jump-over", "go-through", "pass", "walk-across", "cross", "jump-across", "balance-across");
	/** Extra cost of crossing an obstacle, so an open route of up to this many more tiles wins. */
	private static final int OBSTACLE_COST = 8;
	private static final int BLOCK_FULL = 0x100 | 0x40000 | 0x200000;
	private static final int WALL_NW = 0x1, WALL_N = 0x2, WALL_NE = 0x4, WALL_E = 0x8;
	private static final int WALL_SE = 0x10, WALL_S = 0x20, WALL_SW = 0x40, WALL_W = 0x80;

	private JsonObject pathTo(JsonObject args) throws Exception
	{
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		int width = Math.max(1, optInt(args, "width", 1));
		int height = Math.max(1, optInt(args, "height", 1));
		long start = System.nanoTime();
		JsonObject result = onClientThread(() -> findPath(x, y, width, height));
		result.addProperty("searchMs", (System.nanoTime() - start) / 1_000_000.0);
		return result;
	}

	private JsonObject findPath(int tx, int ty, int width, int height)
	{
		Player local = client.getLocalPlayer();
		if (local == null || client.getCollisionMaps() == null)
		{
			throw new IllegalStateException("Not logged in");
		}
		int plane = client.getPlane();
		int baseX = client.getBaseX();
		int baseY = client.getBaseY();
		int[][] live = client.getCollisionMaps()[plane].getFlags();
		int w = live.length;
		int h = live[0].length;
		int gx = tx - baseX;
		int gy = ty - baseY;
		if (gx < 0 || gy < 0 || gx >= w || gy >= h)
		{
			throw new IllegalArgumentException("Tile " + tx + "," + ty + " is not in the loaded scene");
		}

		// The same map with every obstacle's collision removed; obstacleAt remembers which object
		// a cleared tile belongs to, so a step that only the open map allows names its obstacle.
		int[][] open = new int[w][];
		for (int i = 0; i < w; i++)
		{
			open[i] = live[i].clone();
		}
		Map<Integer, JsonObject> obstacleAt = new LinkedHashMap<>();
		Set<GameObject> seen = new HashSet<>();
		for (Tile[] column : client.getScene().getTiles()[plane])
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (TileObject obj : tileObjects(tile, seen))
				{
					JsonObject obstacle = describeObstacle(obj);
					if (obstacle == null)
					{
						continue;
					}
					int sx = tile.getSceneLocation().getX();
					int sy = tile.getSceneLocation().getY();
					if (obj instanceof WallObject)
					{
						WallObject wall = (WallObject) obj;
						clearWall(open, sx, sy, wall.getOrientationA() | wall.getOrientationB(), obstacleAt, obstacle);
					}
					else if (obj instanceof GameObject)
					{
						net.runelite.api.Point min = ((GameObject) obj).getSceneMinLocation();
						net.runelite.api.Point max = ((GameObject) obj).getSceneMaxLocation();
						for (int ix = Math.max(0, min.getX()); ix <= Math.min(w - 1, max.getX()); ix++)
						{
							for (int iy = Math.max(0, min.getY()); iy <= Math.min(h - 1, max.getY()); iy++)
							{
								open[ix][iy] &= ~BLOCK_FULL;
								obstacleAt.putIfAbsent(ix * h + iy, obstacle);
							}
						}
					}
				}
			}
		}

		boolean targetBlocked = (live[gx][gy] & BLOCK_FULL) != 0;
		LocalPoint here = local.getLocalLocation();
		int from = here.getSceneX() * h + here.getSceneY();
		int[] dist = new int[w * h];
		int[] prev = new int[w * h];
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
		java.util.Arrays.fill(prev, -1);
		dist[from] = 0;
		java.util.PriorityQueue<int[]> queue = new java.util.PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
		queue.add(new int[]{0, from});
		int goal = -1;
		while (!queue.isEmpty())
		{
			int[] head = queue.poll();
			int u = head[1];
			if (head[0] > dist[u])
			{
				continue;
			}
			int ux = u / h;
			int uy = u % h;
			if (reached(open, ux, uy, gx, gy, width, height, targetBlocked))
			{
				goal = u;
				break;
			}
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if ((dx == 0 && dy == 0) || !canMove(open, ux, uy, dx, dy))
					{
						continue;
					}
					int v = (ux + dx) * h + uy + dy;
					int d = head[0] + 1 + (canMove(live, ux, uy, dx, dy) ? 0 : OBSTACLE_COST);
					if (d < dist[v])
					{
						dist[v] = d;
						prev[v] = u;
						queue.add(new int[]{d, v});
					}
				}
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("found", goal >= 0);
		if (goal < 0)
		{
			o.addProperty("message", "No route to " + tx + "," + ty + " even with every door and obstacle open: "
				+ "it is walled off from here (a content bug, or it needs stairs, a ladder or a teleport)");
			return o;
		}
		List<Integer> steps = new ArrayList<>();
		for (int t = goal; t >= 0; t = prev[t])
		{
			steps.add(0, t);
		}
		JsonArray obstacles = new JsonArray();
		JsonArray path = new JsonArray();
		JsonObject last = null;
		for (int i = 1; i < steps.size(); i++)
		{
			int a = steps.get(i - 1);
			int b = steps.get(i);
			int ax = a / h, ay = a % h, bx = b / h, by = b % h;
			if (!canMove(live, ax, ay, bx - ax, by - ay))
			{
				JsonObject obstacle = obstacleAt.getOrDefault(b, obstacleAt.get(a));
				if (obstacle != null && obstacle != last)
				{
					JsonObject crossing = obstacle.deepCopy();
					crossing.addProperty("standX", ax + baseX);
					crossing.addProperty("standY", ay + baseY);
					obstacles.add(crossing);
					last = obstacle;
				}
			}
			// Waypoints: every turn, and the end.
			boolean end = i == steps.size() - 1;
			if (end || (steps.get(i + 1) - b) != (b - a))
			{
				JsonArray point = new JsonArray();
				point.add(bx + baseX);
				point.add(by + baseY);
				path.add(point);
			}
		}
		o.addProperty("direct", obstacles.size() == 0);
		o.addProperty("length", steps.size() - 1);
		o.add("obstacles", obstacles);
		o.add("path", path);
		return o;
	}

	/** The object's first obstacle option, as name/objectId/x/y/option, or null when it has none. */
	private JsonObject describeObstacle(TileObject obj)
	{
		if (!(obj instanceof WallObject) && !(obj instanceof GameObject))
		{
			return null;
		}
		ObjectComposition comp = client.getObjectDefinition(obj.getId());
		if (comp != null && comp.getImpostorIds() != null && comp.getImpostor() != null)
		{
			comp = comp.getImpostor();
		}
		if (comp == null || comp.getActions() == null)
		{
			return null;
		}
		for (String action : comp.getActions())
		{
			if (action != null && OBSTACLE_OPS.contains(action.toLowerCase()))
			{
				JsonObject o = new JsonObject();
				o.addProperty("name", comp.getName());
				o.addProperty("objectId", comp.getId());
				o.addProperty("x", obj.getWorldLocation().getX());
				o.addProperty("y", obj.getWorldLocation().getY());
				o.addProperty("option", action);
				return o;
			}
		}
		return null;
	}

	/**
	 * Clears a wall's flags on its tile and the mirrored flag on the neighbour behind it.
	 * Orientation bits: 1 W, 2 N, 4 E, 8 S, then the four diagonals; a diagonal wall blocks like an object.
	 */
	private static void clearWall(int[][] flags, int x, int y, int orientation, Map<Integer, JsonObject> at, JsonObject obstacle)
	{
		int[][] sides = {{1, -1, 0, WALL_W, WALL_E}, {2, 0, 1, WALL_N, WALL_S}, {4, 1, 0, WALL_E, WALL_W}, {8, 0, -1, WALL_S, WALL_N}};
		int h = flags[0].length;
		at.putIfAbsent(x * h + y, obstacle);
		if (orientation == 0 || (orientation & 0xF0) != 0)
		{
			flags[x][y] &= ~(BLOCK_FULL | WALL_NW | WALL_NE | WALL_SE | WALL_SW);
		}
		for (int[] side : sides)
		{
			if ((orientation & side[0]) == 0 && orientation != 0)
			{
				continue;
			}
			flags[x][y] &= ~side[3];
			int nx = x + side[1];
			int ny = y + side[2];
			if (nx >= 0 && ny >= 0 && nx < flags.length && ny < h)
			{
				flags[nx][ny] &= ~side[4];
				at.putIfAbsent(nx * h + ny, obstacle);
			}
		}
	}

	/** On the target, or (for a blocked target) on a tile beside it with no wall in between. */
	private static boolean reached(int[][] flags, int x, int y, int gx, int gy, int width, int height, boolean blocked)
	{
		boolean inside = x >= gx && x < gx + width && y >= gy && y < gy + height;
		if (inside)
		{
			return true;
		}
		if (!blocked)
		{
			return false;
		}
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : dirs)
		{
			int nx = x + d[0];
			int ny = y + d[1];
			if (nx >= gx && nx < gx + width && ny >= gy && ny < gy + height && !wallBetween(flags, x, y, d[0], d[1]))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean wallBetween(int[][] flags, int x, int y, int dx, int dy)
	{
		int nx = x + dx;
		int ny = y + dy;
		if (nx < 0 || ny < 0 || nx >= flags.length || ny >= flags[0].length)
		{
			return true;
		}
		int facing = dx == 1 ? WALL_W : dx == -1 ? WALL_E : dy == 1 ? WALL_S : WALL_N;
		return (flags[nx][ny] & facing) != 0;
	}

	/** The game's own step rule: the destination is free, and a diagonal also needs both of its sides. */
	private static boolean canMove(int[][] flags, int x, int y, int dx, int dy)
	{
		int nx = x + dx;
		int ny = y + dy;
		if (nx < 0 || ny < 0 || nx >= flags.length || ny >= flags[0].length)
		{
			return false;
		}
		int dest = flags[nx][ny];
		if (dx == 0 || dy == 0)
		{
			int facing = dx == 1 ? WALL_W : dx == -1 ? WALL_E : dy == 1 ? WALL_S : WALL_N;
			return (dest & (facing | BLOCK_FULL)) == 0;
		}
		int sideX = dx == 1 ? WALL_W : WALL_E;
		int sideY = dy == 1 ? WALL_S : WALL_N;
		int corner = dx == 1 ? (dy == 1 ? WALL_SW : WALL_NW) : (dy == 1 ? WALL_SE : WALL_NE);
		return (dest & (sideX | sideY | corner | BLOCK_FULL)) == 0
			&& (flags[nx][y] & (sideX | BLOCK_FULL)) == 0
			&& (flags[x][ny] & (sideY | BLOCK_FULL)) == 0;
	}

	private JsonElement listNpcs(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			List<JsonObject> list = new ArrayList<>();
			for (NPC npc : client.getNpcs())
			{
				if (npc == null || !matchesName(npc.getName(), filter))
				{
					continue;
				}
				JsonObject o = describeActor(npc, me, maxDistance);
				if (o == null)
				{
					continue;
				}
				o.addProperty("npcId", npc.getId());
				o.addProperty("index", npc.getIndex());
				NPCComposition comp = npc.getTransformedComposition() != null
					? npc.getTransformedComposition()
					: npc.getComposition();
				if (comp != null && comp.getActions() != null)
				{
					JsonArray actions = new JsonArray();
					for (String action : comp.getActions())
					{
						if (action != null)
						{
							actions.add(action);
						}
					}
					if (actions.size() > 0)
					{
						o.add("actions", actions);
					}
				}
				list.add(o);
			}
			return actorListResult("npcs", list, maxResults);
		});
	}

	private JsonElement listPlayers(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			Player local = client.getLocalPlayer();
			WorldPoint me = local != null ? local.getWorldLocation() : null;
			List<JsonObject> list = new ArrayList<>();
			for (Player player : client.getPlayers())
			{
				if (player == null || !matchesName(player.getName(), filter))
				{
					continue;
				}
				JsonObject o = describeActor(player, me, maxDistance);
				if (o == null)
				{
					continue;
				}
				if (player == local)
				{
					o.addProperty("isLocal", true);
				}
				list.add(o);
			}
			return actorListResult("players", list, maxResults);
		});
	}

	private static final MenuAction[] NPC_MENU_ACTIONS = {
		MenuAction.NPC_FIRST_OPTION,
		MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION,
	};

	private JsonElement interactNpc(JsonObject args) throws Exception
	{
		String option = args.has("option") ? args.get("option").getAsString() : "Talk-to";
		return onClientThread(() ->
		{
			NPC npc = findNpc(args);
			if (npc == null)
			{
				throw new IllegalArgumentException("NPC not found; use list_npcs to see what is nearby");
			}
			NPCComposition comp = npc.getTransformedComposition() != null
				? npc.getTransformedComposition()
				: npc.getComposition();
			String[] actions = comp != null ? comp.getActions() : null;
			int actionIndex = -1;
			if (actions != null)
			{
				for (int i = 0; i < actions.length && i < NPC_MENU_ACTIONS.length; i++)
				{
					if (actions[i] != null && actions[i].equalsIgnoreCase(option))
					{
						actionIndex = i;
						break;
					}
				}
			}
			if (actionIndex == -1)
			{
				throw new IllegalArgumentException("NPC " + npc.getName() + " has no option '" + option
					+ "'. Available: " + availableActions(actions));
			}
			client.menuAction(0, 0, NPC_MENU_ACTIONS[actionIndex], npc.getIndex(), -1,
				actions[actionIndex], npc.getName());
			JsonObject result = new JsonObject();
			result.addProperty("invoked", actions[actionIndex]);
			result.addProperty("npc", npc.getName());
			result.addProperty("npcIndex", npc.getIndex());
			result.addProperty("note", "Player may need to walk to the NPC; poll get_dialogue for the conversation");
			return result;
		});
	}

	private JsonElement getNpcMenu(JsonObject args) throws Exception
	{
		Point hover = onClientThread(() ->
		{
			NPC npc = findNpc(args);
			if (npc == null)
			{
				throw new IllegalArgumentException("NPC not found; use list_npcs to see what is nearby");
			}
			Point p = actorHoverPoint(npc);
			if (p == null)
			{
				throw new IllegalArgumentException("NPC " + npc.getName() + " is not visible on screen");
			}
			return p;
		});
		dispatchMouse(MouseEvent.MOUSE_MOVED, hover.x, hover.y, MouseEvent.NOBUTTON, 0);
		// Let the client rebuild the mini-menu for the hovered target
		Thread.sleep(150);
		return onClientThread(() ->
		{
			JsonObject result = new JsonObject();
			result.addProperty("hoverX", hover.x);
			result.addProperty("hoverY", hover.y);
			result.add("entries", menuEntriesJson());
			return result;
		});
	}

	private JsonElement clickMenuOption(JsonObject args) throws Exception
	{
		String option = args.get("option").getAsString().toLowerCase();
		String target = args.has("target") ? args.get("target").getAsString().toLowerCase() : null;
		return onClientThread(() -> invokeMenuEntry(option, target));
	}

	private JsonElement itemAction(JsonObject args) throws Exception
	{
		String option = args.get("option").getAsString().toLowerCase();
		Integer itemId = args.has("itemId") ? args.get("itemId").getAsInt() : null;
		String itemName = args.has("itemName") ? args.get("itemName").getAsString().toLowerCase() : null;
		if (itemId == null && itemName == null)
		{
			throw new IllegalArgumentException("Provide itemId or itemName");
		}
		Object found = onClientThread(() ->
		{
			Widget inventory = client.getWidget(InterfaceID.Inventory.ITEMS);
			if (inventory == null)
			{
				throw new IllegalStateException("Inventory widget is not loaded");
			}
			Widget[] slots = inventory.getDynamicChildren();
			if (slots != null)
			{
				for (Widget slot : slots)
				{
					if (slot == null || slot.getItemId() <= 0)
					{
						continue;
					}
					if (itemId != null && slot.getItemId() != itemId)
					{
						continue;
					}
					if (itemName != null)
					{
						ItemComposition composition = client.getItemDefinition(slot.getItemId());
						String name = composition != null ? composition.getName() : null;
						if (name == null || !name.toLowerCase().contains(itemName))
						{
							continue;
						}
					}
					// Invoke the slot's own op directly (CC_OP, identifier = op index + 1, as the client
					// builds it), so this works with the inventory tab closed and never moves the mouse.
					String[] ops = slot.getActions();
					for (int i = 0; ops != null && i < ops.length; i++)
					{
						if (ops[i] != null && Text.removeTags(ops[i]).equalsIgnoreCase(option))
						{
							client.menuAction(slot.getIndex(), InterfaceID.Inventory.ITEMS,
								i < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY, i + 1,
								slot.getItemId(), ops[i], slot.getName());
							JsonObject result = new JsonObject();
							result.addProperty("invoked", ops[i]);
							result.addProperty("slot", slot.getIndex());
							result.addProperty("itemId", slot.getItemId());
							return result;
						}
					}
					if (inventory.isHidden())
					{
						throw new IllegalStateException("Item has no '" + option + "' op and the inventory tab is closed");
					}
					Rectangle b = slot.getBounds();
					if (b != null && b.width > 0 && b.height > 0)
					{
						return new Point(b.x + b.width / 2, b.y + b.height / 2);
					}
				}
			}
			throw new IllegalArgumentException("Item not found in inventory: "
				+ (itemId != null ? "id " + itemId : itemName));
		});
		if (found instanceof JsonObject)
		{
			return (JsonObject) found;
		}
		// Ops the slot doesn't list (e.g. server-added): fall back to hovering and reading the menu.
		return hoverAndInvoke((Point) found, option, null);
	}

	private JsonElement widgetAction(JsonObject args) throws Exception
	{
		String option = args.get("option").getAsString().toLowerCase();
		Rectangle bounds = onClientThread(() ->
		{
			Widget w = resolveWidget(args, "componentId", "childIndex");
			return w == null || w.isHidden() ? null : w.getBounds();
		});
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
		{
			throw new IllegalArgumentException("Widget not found, hidden or has no bounds: "
				+ describeId(args, "componentId", "childIndex"));
		}
		return hoverAndInvoke(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2),
			option, null);
	}

	private JsonElement interactObject(JsonObject args) throws Exception
	{
		Integer objectId = args.has("objectId") ? args.get("objectId").getAsInt() : null;
		String nameFilter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		if (objectId == null && nameFilter == null)
		{
			throw new IllegalArgumentException("Provide objectId or nameFilter");
		}
		JsonObject found = onClientThread(() -> findObjectHover(objectId, nameFilter));
		String option = args.has("option")
			? args.get("option").getAsString().toLowerCase()
			: found.get("defaultOption").getAsString().toLowerCase();
		JsonElement clicked = hoverAndInvoke(
			new Point(found.get("hoverX").getAsInt(), found.get("hoverY").getAsInt()), option, null);
		JsonObject result = clicked.getAsJsonObject();
		result.addProperty("object", found.get("name").getAsString());
		result.addProperty("objectId", found.get("objectId").getAsInt());
		return result;
	}

	/**
	 * Nearest visible scene object matching the filters, with a hover point and its
	 * default action. Must be called on the client thread.
	 */
	private JsonObject findObjectHover(Integer objectId, String nameFilter)
	{
		Scene scene = client.getScene();
		if (scene == null)
		{
			throw new IllegalStateException("No scene is loaded");
		}
		WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		Tile[][] tiles = scene.getTiles()[client.getPlane()];
		Set<GameObject> seen = new HashSet<>();
		TileObject best = null;
		String bestName = null;
		String bestDefault = null;
		int bestDistance = Integer.MAX_VALUE;
		Point bestHover = null;
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				List<TileObject> candidates = new ArrayList<>();
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null)
				{
					for (GameObject go : gameObjects)
					{
						if (go != null && seen.add(go))
						{
							candidates.add(go);
						}
					}
				}
				if (tile.getWallObject() != null)
				{
					candidates.add(tile.getWallObject());
				}
				if (tile.getGroundObject() != null)
				{
					candidates.add(tile.getGroundObject());
				}
				if (tile.getDecorativeObject() != null)
				{
					candidates.add(tile.getDecorativeObject());
				}
				for (TileObject obj : candidates)
				{
					if (objectId != null && obj.getId() != objectId)
					{
						continue;
					}
					ObjectComposition composition = client.getObjectDefinition(obj.getId());
					String name = composition != null ? composition.getName() : null;
					if (nameFilter != null && (name == null || !name.toLowerCase().contains(nameFilter)))
					{
						continue;
					}
					int distance = me != null && obj.getWorldLocation() != null
						? me.distanceTo(obj.getWorldLocation())
						: 0;
					if (distance >= bestDistance)
					{
						continue;
					}
					Point hover = objectHoverPoint(obj);
					if (hover == null)
					{
						continue;
					}
					String defaultAction = null;
					if (composition != null && composition.getActions() != null)
					{
						for (String action : composition.getActions())
						{
							if (action != null)
							{
								defaultAction = action;
								break;
							}
						}
					}
					best = obj;
					bestName = name;
					bestDefault = defaultAction;
					bestDistance = distance;
					bestHover = hover;
				}
			}
		}
		if (best == null)
		{
			throw new IllegalArgumentException("No visible object matching "
				+ (objectId != null ? "id " + objectId : "'" + nameFilter + "'")
				+ "; use list_objects to see what is nearby");
		}
		if (bestDefault == null)
		{
			bestDefault = "";
		}
		JsonObject o = new JsonObject();
		o.addProperty("objectId", best.getId());
		o.addProperty("name", bestName != null ? bestName : "");
		o.addProperty("defaultOption", bestDefault);
		o.addProperty("hoverX", bestHover.x);
		o.addProperty("hoverY", bestHover.y);
		return o;
	}

	private JsonElement interactPlayer(JsonObject args) throws Exception
	{
		String playerName = args.get("playerName").getAsString().toLowerCase();
		String option = args.get("option").getAsString().toLowerCase();
		Point hover = onClientThread(() ->
		{
			Player local = client.getLocalPlayer();
			WorldPoint me = local != null ? local.getWorldLocation() : null;
			Player best = null;
			int bestDistance = Integer.MAX_VALUE;
			for (Player player : client.getPlayers())
			{
				if (player == null || player == local || player.getName() == null
					|| !player.getName().toLowerCase().contains(playerName))
				{
					continue;
				}
				int distance = me != null && player.getWorldLocation() != null
					? me.distanceTo(player.getWorldLocation())
					: 0;
				if (distance < bestDistance)
				{
					best = player;
					bestDistance = distance;
				}
			}
			if (best == null)
			{
				throw new IllegalArgumentException("Player not found: " + playerName
					+ "; use list_players to see who is nearby");
			}
			Point p = actorHoverPoint(best);
			if (p == null)
			{
				throw new IllegalArgumentException("Player " + best.getName() + " is not visible on screen");
			}
			return p;
		});
		return hoverAndInvoke(hover, option, null);
	}

	/**
	 * Move the mouse to a point, wait for the client to rebuild the mini-menu,
	 * then invoke the entry matching the option.
	 */
	private JsonElement hoverAndInvoke(Point hover, String optionLower, String targetLower) throws Exception
	{
		dispatchMouse(MouseEvent.MOUSE_MOVED, hover.x, hover.y, MouseEvent.NOBUTTON, 0);
		Thread.sleep(150);
		return onClientThread(() -> invokeMenuEntry(optionLower, targetLower));
	}

	/**
	 * Find and invoke a mini-menu entry, top-down like the rendered menu.
	 * Must be called on the client thread.
	 */
	private JsonObject invokeMenuEntry(String optionLower, String targetLower)
	{
		MenuEntry[] entries = client.getMenuEntries();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (entry == null || entry.getOption() == null
				|| !entry.getOption().toLowerCase().contains(optionLower))
			{
				continue;
			}
			if (targetLower != null && (entry.getTarget() == null
				|| !entry.getTarget().toLowerCase().contains(targetLower)))
			{
				continue;
			}
			client.menuAction(entry.getParam0(), entry.getParam1(), entry.getType(),
				entry.getIdentifier(), entry.getItemId(), entry.getOption(), entry.getTarget());
			JsonObject result = new JsonObject();
			result.addProperty("clicked", entry.getOption());
			result.addProperty("target", entry.getTarget());
			return result;
		}
		throw new IllegalArgumentException("No menu entry matching '" + optionLower
			+ "'. Current menu: " + McpHttpServer.GSON.toJson(menuEntriesJson()));
	}

	/**
	 * Current mini-menu entries, top entry first (index 0 = default left-click action).
	 * Must be called on the client thread.
	 */
	private JsonArray menuEntriesJson()
	{
		MenuEntry[] entries = client.getMenuEntries();
		JsonArray arr = new JsonArray();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (entry == null)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("index", entries.length - 1 - i);
			o.addProperty("option", entry.getOption());
			o.addProperty("target", entry.getTarget());
			o.addProperty("type", entry.getType().name());
			o.addProperty("identifier", entry.getIdentifier());
			arr.add(o);
		}
		return arr;
	}

	/**
	 * A canvas point over the actor's model for hovering. Must be called on the client thread.
	 */
	private Point actorHoverPoint(Actor actor)
	{
		Shape hull = actor.getConvexHull();
		if (hull != null)
		{
			Rectangle b = hull.getBounds();
			if (b.width > 0 && b.height > 0)
			{
				return new Point(b.x + b.width / 2, b.y + b.height / 2);
			}
		}
		Polygon poly = actor.getCanvasTilePoly();
		if (poly != null)
		{
			Rectangle b = poly.getBounds();
			if (b.width > 0 && b.height > 0)
			{
				return new Point(b.x + b.width / 2, b.y + b.height / 2);
			}
		}
		return null;
	}

	/**
	 * A canvas point over a scene object for hovering. Must be called on the client thread.
	 */
	private Point objectHoverPoint(TileObject obj)
	{
		Shape clickbox = obj.getClickbox();
		if (clickbox != null)
		{
			Rectangle b = clickbox.getBounds();
			if (b.width > 0 && b.height > 0)
			{
				return new Point(b.x + b.width / 2, b.y + b.height / 2);
			}
		}
		net.runelite.api.Point p = obj.getCanvasLocation();
		return p != null ? new Point(p.getX(), p.getY()) : null;
	}

	private NPC findNpc(JsonObject args)
	{
		Integer index = args.has("npcIndex") ? args.get("npcIndex").getAsInt() : null;
		String name = args.has("npcName") ? args.get("npcName").getAsString().toLowerCase() : null;
		if (index == null && name == null)
		{
			throw new IllegalArgumentException("Provide npcIndex or npcName");
		}
		WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
		NPC best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : client.getNpcs())
		{
			if (npc == null)
			{
				continue;
			}
			if (index != null)
			{
				if (npc.getIndex() == index)
				{
					return npc;
				}
				continue;
			}
			String npcName = npc.getName();
			if (npcName == null || !npcName.toLowerCase().contains(name))
			{
				continue;
			}
			int distance = me != null && npc.getWorldLocation() != null
				? me.distanceTo(npc.getWorldLocation())
				: 0;
			if (distance < bestDistance)
			{
				best = npc;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static String availableActions(String[] actions)
	{
		if (actions == null)
		{
			return "(none)";
		}
		StringBuilder sb = new StringBuilder();
		for (String action : actions)
		{
			if (action != null)
			{
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				sb.append(action);
			}
		}
		return sb.length() == 0 ? "(none)" : sb.toString();
	}

	private JsonElement getVar(JsonObject args) throws Exception
	{
		String type = args.get("type").getAsString();
		int id = args.get("id").getAsInt();
		return onClientThread(() ->
		{
			JsonObject o = new JsonObject();
			o.addProperty("type", type);
			o.addProperty("id", id);
			switch (type)
			{
				case "varbit":
					o.addProperty("value", client.getVarbitValue(id));
					break;
				case "varp":
					o.addProperty("value", client.getVarpValue(id));
					break;
				case "varcint":
					o.addProperty("value", client.getVarcIntValue(id));
					break;
				case "varcstr":
					o.addProperty("value", client.getVarcStrValue(id));
					break;
				default:
					throw new IllegalArgumentException("Unknown var type: " + type
						+ " (use varbit, varp, varcint or varcstr)");
			}
			return o;
		});
	}

	// --- dialogue ---

	private JsonObject selectOption(JsonObject args) throws Exception
	{
		int option = args.get("option").getAsInt();
		if (option < 1 || option > 9)
		{
			throw new IllegalArgumentException("Option must be 1-9");
		}
		String before = dialogueFingerprint();
		typeChar((char) ('0' + option));
		return textResult(waitDialogueChange(before));
	}

	private JsonObject enterInput(JsonObject args) throws Exception
	{
		String text = args.get("text").getAsString();
		String before = dialogueFingerprint();
		for (char c : text.toCharArray())
		{
			typeChar(c);
		}
		pressNamedKey("ENTER");
		return textResult(waitDialogueChange(before));
	}

	private String dialogueFingerprint() throws Exception
	{
		return McpHttpServer.GSON.toJson(onClientThread(this::readDialogue));
	}

	/**
	 * Poll the dialogue state until it differs from the given fingerprint, returning
	 * as soon as it changes instead of sleeping a fixed game tick. Falls through
	 * with the latest state after 2s so unchanged dialogues still return promptly.
	 */
	private JsonObject waitDialogueChange(String beforeFingerprint) throws Exception
	{
		long deadline = System.currentTimeMillis() + 2_000L;
		JsonObject current = null;
		while (System.currentTimeMillis() < deadline)
		{
			Thread.sleep(100);
			current = onClientThread(this::readDialogue);
			if (!McpHttpServer.GSON.toJson(current).equals(beforeFingerprint))
			{
				return current;
			}
		}
		return current;
	}

	/**
	 * Read whichever dialogue widget is currently up. Must be called on the client thread.
	 */
	private JsonObject readDialogue()
	{
		JsonObject o = new JsonObject();

		Widget npcText = client.getWidget(InterfaceID.ChatLeft.TEXT);
		if (visible(npcText))
		{
			o.addProperty("type", "npc");
			Widget name = client.getWidget(InterfaceID.ChatLeft.NAME);
			if (visible(name))
			{
				o.addProperty("speaker", name.getText());
			}
			o.addProperty("text", npcText.getText());
			o.addProperty("canContinue", visible(client.getWidget(InterfaceID.ChatLeft.CONTINUE)));
			return o;
		}

		Widget playerText = client.getWidget(InterfaceID.ChatRight.TEXT);
		if (visible(playerText))
		{
			o.addProperty("type", "player");
			Widget name = client.getWidget(InterfaceID.ChatRight.NAME);
			if (visible(name))
			{
				o.addProperty("speaker", name.getText());
			}
			o.addProperty("text", playerText.getText());
			o.addProperty("canContinue", visible(client.getWidget(InterfaceID.ChatRight.CONTINUE)));
			return o;
		}

		Widget optionsWidget = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (visible(optionsWidget))
		{
			o.addProperty("type", "options");
			Widget[] children = optionsWidget.getDynamicChildren();
			JsonArray options = new JsonArray();
			int number = 0;
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child == null || child.getText() == null || child.getText().isEmpty())
					{
						continue;
					}
					if (number == 0)
					{
						// First text child is the title, e.g. "Select an Option"
						o.addProperty("title", child.getText());
					}
					else
					{
						JsonObject opt = new JsonObject();
						opt.addProperty("option", number);
						opt.addProperty("text", child.getText());
						options.add(opt);
					}
					number++;
				}
			}
			o.add("options", options);
			return o;
		}

		Widget message = client.getWidget(InterfaceID.Messagebox.TEXT);
		if (visible(message))
		{
			o.addProperty("type", "message");
			o.addProperty("text", message.getText());
			o.addProperty("canContinue", visible(client.getWidget(InterfaceID.Messagebox.CONTINUE)));
			return o;
		}

		Widget itemText = client.getWidget(InterfaceID.Objectbox.TEXT);
		if (visible(itemText))
		{
			o.addProperty("type", "item");
			o.addProperty("text", itemText.getText());
			Widget item = client.getWidget(InterfaceID.Objectbox.ITEM);
			if (visible(item) && item.getItemId() != -1)
			{
				o.addProperty("itemId", item.getItemId());
			}
			return o;
		}

		Widget inputPrompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (visible(inputPrompt))
		{
			o.addProperty("type", "input");
			o.addProperty("prompt", inputPrompt.getText());
			Widget current = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
			if (visible(current))
			{
				o.addProperty("current", current.getText());
			}
			return o;
		}

		o.addProperty("type", "none");
		return o;
	}

	private static boolean visible(Widget w)
	{
		return w != null && !w.isHidden();
	}

	private JsonElement listProjectiles() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray arr = new JsonArray();
			for (Projectile projectile : client.getProjectiles())
			{
				if (projectile == null)
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("projectileId", projectile.getId());
				o.addProperty("remainingCycles", projectile.getRemainingCycles());
				o.addProperty("startSceneX", projectile.getX1());
				o.addProperty("startSceneY", projectile.getY1());
				o.addProperty("slope", projectile.getSlope());
				o.addProperty("endHeight", projectile.getEndHeight());
				WorldPoint target = projectile.getTargetPoint();
				if (target != null)
				{
					o.addProperty("targetX", target.getX());
					o.addProperty("targetY", target.getY());
					o.addProperty("targetPlane", target.getPlane());
				}
				Actor targetActor = projectile.getTargetActor();
				if (targetActor != null)
				{
					o.addProperty("target", targetActor.getName());
					o.addProperty("targetType", targetActor instanceof NPC ? "npc" : "player");
				}
				arr.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", arr.size());
			result.add("projectiles", arr);
			return result;
		});
	}

	private JsonElement listGroundItems(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			Scene scene = client.getScene();
			if (scene == null)
			{
				throw new IllegalStateException("No scene is loaded");
			}
			WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			int plane = client.getPlane();
			Tile[][] tiles = scene.getTiles()[plane];
			List<JsonObject> list = new ArrayList<>();
			for (Tile[] column : tiles)
			{
				for (Tile tile : column)
				{
					if (tile == null || tile.getGroundItems() == null)
					{
						continue;
					}
					WorldPoint wp = tile.getWorldLocation();
					int distance = me != null && wp != null ? me.distanceTo(wp) : -1;
					if (distance >= 0 && distance > maxDistance)
					{
						continue;
					}
					for (TileItem item : tile.getGroundItems())
					{
						if (item == null)
						{
							continue;
						}
						ItemComposition composition = client.getItemDefinition(item.getId());
						String name = composition != null ? composition.getName() : null;
						if (filter != null && (name == null || !name.toLowerCase().contains(filter)))
						{
							continue;
						}
						JsonObject o = new JsonObject();
						o.addProperty("itemId", item.getId());
						if (name != null)
						{
							o.addProperty("name", name);
						}
						o.addProperty("quantity", item.getQuantity());
						if (wp != null)
						{
							o.addProperty("worldX", wp.getX());
							o.addProperty("worldY", wp.getY());
							o.addProperty("plane", wp.getPlane());
						}
						if (distance >= 0)
						{
							o.addProperty("distance", distance);
						}
						list.add(o);
					}
				}
			}
			return actorListResult("items", list, maxResults);
		});
	}

	private JsonElement getInventory(JsonObject args) throws Exception
	{
		int inventoryId = args.get("inventoryId").getAsInt();
		boolean includeEmpty = args.has("includeEmpty") && args.get("includeEmpty").getAsBoolean();
		return onClientThread(() ->
		{
			ItemContainer container = client.getItemContainer(inventoryId);
			if (container == null)
			{
				throw new IllegalArgumentException("Item container " + inventoryId
					+ " is not loaded; use list_inventories to see what exists");
			}
			Item[] items = container.getItems();
			JsonArray slots = new JsonArray();
			int nonEmpty = 0;
			for (int slot = 0; slot < items.length; slot++)
			{
				Item item = items[slot];
				boolean empty = item == null || item.getId() == -1;
				if (empty && !includeEmpty)
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("slot", slot);
				o.addProperty("itemId", empty ? -1 : item.getId());
				o.addProperty("quantity", empty ? 0 : item.getQuantity());
				if (!empty)
				{
					nonEmpty++;
					ItemComposition composition = client.getItemDefinition(item.getId());
					if (composition != null)
					{
						o.addProperty("name", composition.getName());
						JsonArray actions = new JsonArray();
						for (String action : composition.getInventoryActions())
						{
							if (action != null && !action.isEmpty())
							{
								actions.add(action);
							}
						}
						o.add("inventoryActions", actions);
					}
				}
				slots.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("inventoryId", inventoryId);
			result.addProperty("size", items.length);
			result.addProperty("itemCount", nonEmpty);
			result.add("items", slots);
			return result;
		});
	}

	private JsonElement listInventories() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray arr = new JsonArray();
			for (ItemContainer container : client.getItemContainers())
			{
				if (container == null)
				{
					continue;
				}
				int nonEmpty = 0;
				for (Item item : container.getItems())
				{
					if (item != null && item.getId() != -1)
					{
						nonEmpty++;
					}
				}
				JsonObject o = new JsonObject();
				o.addProperty("inventoryId", container.getId());
				o.addProperty("size", container.size());
				o.addProperty("itemCount", nonEmpty);
				arr.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("count", arr.size());
			result.add("inventories", arr);
			return result;
		});
	}

	/** Ticks a monster may take between attacks before combat counts as over (Idle Notifier). */
	private static final int COMBAT_WINDOW_TICKS = 8;

	private JsonElement getCombatState() throws Exception
	{
		return onClientThread(this::combatStateJson);
	}

	/** The get_combat_state payload. Must be called on the client thread. */
	private JsonObject combatStateJson()
	{
		JsonObject o = new JsonObject();
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			o.addProperty("loggedIn", false);
			return o;
		}
		int tick = client.getTickCount();
		int onMe = recorder.lastHitOnMeTick();
		int mine = recorder.lastMyHitTick();
		int sinceOnMe = onMe < 0 ? -1 : tick - onMe;
		int sinceMine = mine < 0 ? -1 : tick - mine;
		o.addProperty("tick", tick);
		o.addProperty("ticksSinceHitOnMe", sinceOnMe);
		o.addProperty("ticksSinceMyHit", sinceMine);
		o.addProperty("inCombat", (sinceOnMe >= 0 && sinceOnMe <= COMBAT_WINDOW_TICKS)
			|| (sinceMine >= 0 && sinceMine <= COMBAT_WINDOW_TICKS));
		o.addProperty("animating", local.getAnimation() != -1);
		o.addProperty("moving", local.getPoseAnimation() != local.getIdlePoseAnimation());
		LocalPoint dest = client.getLocalDestinationLocation();
		if (dest != null)
		{
			WorldPoint d = WorldPoint.fromLocal(client, dest);
			o.addProperty("destination", d.getX() + "," + d.getY() + "," + d.getPlane());
		}
		Actor target = local.getInteracting();
		if (target != null)
		{
			o.addProperty("target", target.getName());
			if (target instanceof NPC)
			{
				NPC npc = (NPC) target;
				o.addProperty("targetIndex", npc.getIndex());
				o.addProperty("targetDead", npc.isDead());
				if (npc.getHealthScale() > 0)
				{
					o.addProperty("targetHealthPercent", npc.getHealthRatio() * 100 / npc.getHealthScale());
				}
			}
		}
		JsonArray attackers = new JsonArray();
		for (NPC npc : client.getNpcs())
		{
			if (npc.getInteracting() == local && !npc.isDead())
			{
				JsonObject a = new JsonObject();
				a.addProperty("name", npc.getName());
				a.addProperty("index", npc.getIndex());
				a.addProperty("combatLevel", npc.getCombatLevel());
				attackers.add(a);
			}
		}
		o.add("attackers", attackers);
		o.add("prayers", activePrayers());
		return o;
	}

	// --- decision-loop tools: observe, list_actions, act ---

	private static final MenuAction[] OBJECT_MENU_ACTIONS = {
		MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION,
		MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION,
	};

	/** Ops list_actions never offers: they need a second target, or only inform. */
	private static final Set<String> UNLISTED_OPS = Set.of("examine", "drop", "destroy", "use", "walk here", "cancel");

	/** The runners behind the last list_actions menu, by action id, and the tick it was built on. */
	private final List<Callable<JsonObject>> menuRunners = new ArrayList<>();
	private int menuTick = -1;

	private JsonElement observe(JsonObject args) throws Exception
	{
		int maxDistance = optInt(args, "maxDistance", 15);
		int maxNpcs = optInt(args, "maxNpcs", 10);
		int maxObjects = optInt(args, "maxObjects", 10);
		int maxItems = optInt(args, "maxItems", 6);
		return onClientThread(() -> observeNow(maxDistance, maxNpcs, maxObjects, maxItems));
	}

	/**
	 * One compact snapshot of what a decision loop reads each step, built in a single client-thread
	 * pass: vitals, combat state, inventory with item ops, nearest NPCs, interactive objects and
	 * ground items, and dialogue. Must be called on the client thread.
	 */
	private JsonObject observeNow(int maxDistance, int maxNpcs, int maxObjects, int maxItems)
	{
		JsonObject o = new JsonObject();
		Player local = client.getLocalPlayer();
		if (client.getGameState() != GameState.LOGGED_IN || local == null)
		{
			o.addProperty("loggedIn", false);
			return o;
		}
		WorldPoint me = local.getWorldLocation();
		o.addProperty("loggedIn", true);
		o.addProperty("tick", client.getTickCount());
		o.addProperty("x", me.getX());
		o.addProperty("y", me.getY());
		o.addProperty("plane", me.getPlane());
		o.addProperty("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
		o.addProperty("hpMax", client.getRealSkillLevel(Skill.HITPOINTS));
		o.addProperty("prayer", client.getBoostedSkillLevel(Skill.PRAYER));
		o.addProperty("prayerMax", client.getRealSkillLevel(Skill.PRAYER));
		o.addProperty("runEnergy", client.getEnergy() / 100);
		o.addProperty("runOn", client.getVarpValue(VarPlayerID.OPTION_RUN) == 1);
		JsonObject boosts = new JsonObject();
		for (Skill skill : Skill.values())
		{
			int delta = client.getBoostedSkillLevel(skill) - client.getRealSkillLevel(skill);
			if (delta != 0)
			{
				boosts.addProperty(skill.getName(), delta);
			}
		}
		o.add("boosts", boosts);
		o.add("combat", combatStateJson());

		JsonArray inventory = new JsonArray();
		ItemContainer container = client.getItemContainer(InventoryID.INV);
		Item[] items = container != null ? container.getItems() : new Item[0];
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() == -1)
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(item.getId());
			JsonObject row = new JsonObject();
			row.addProperty("slot", slot);
			row.addProperty("itemId", item.getId());
			row.addProperty("name", composition.getName());
			row.addProperty("quantity", item.getQuantity());
			row.add("actions", nonNull(composition.getInventoryActions()));
			inventory.add(row);
		}
		o.add("inventory", inventory);

		List<JsonObject> npcs = new ArrayList<>();
		for (NPC npc : client.getNpcs())
		{
			JsonObject row = npc != null ? describeActor(npc, me, maxDistance) : null;
			if (row == null)
			{
				continue;
			}
			NPCComposition comp = npc.getTransformedComposition() != null
				? npc.getTransformedComposition()
				: npc.getComposition();
			row.addProperty("index", npc.getIndex());
			row.addProperty("npcId", npc.getId());
			row.add("actions", nonNull(comp != null ? comp.getActions() : null));
			npcs.add(row);
		}
		o.add("npcs", nearest(npcs, maxNpcs));

		Scene scene = client.getScene();
		Tile[][] tiles = scene.getTiles()[client.getPlane()];
		Set<GameObject> seen = new HashSet<>();
		List<JsonObject> objects = new ArrayList<>();
		List<JsonObject> ground = new ArrayList<>();
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (TileObject obj : tileObjects(tile, seen))
				{
					JsonObject row = describeTileObject("object", obj, me, null, null, maxDistance);
					if (row != null && row.has("name") && row.has("actions") && row.getAsJsonArray("actions").size() > 0)
					{
						objects.add(row);
					}
				}
				if (tile.getGroundItems() == null)
				{
					continue;
				}
				WorldPoint wp = tile.getWorldLocation();
				int distance = me.distanceTo(wp);
				if (distance > maxDistance)
				{
					continue;
				}
				for (TileItem item : tile.getGroundItems())
				{
					JsonObject row = new JsonObject();
					row.addProperty("itemId", item.getId());
					row.addProperty("name", client.getItemDefinition(item.getId()).getName());
					row.addProperty("quantity", item.getQuantity());
					row.addProperty("worldX", wp.getX());
					row.addProperty("worldY", wp.getY());
					row.addProperty("distance", distance);
					ground.add(row);
				}
			}
		}
		o.add("objects", nearest(objects, maxObjects));
		o.add("groundItems", nearest(ground, maxItems));
		o.add("dialogue", readDialogue());
		return o;
	}

	/** Game, wall, ground and decorative objects on a tile; multi-tile game objects once. */
	private static List<TileObject> tileObjects(Tile tile, Set<GameObject> seen)
	{
		List<TileObject> out = new ArrayList<>();
		if (tile.getGameObjects() != null)
		{
			for (GameObject go : tile.getGameObjects())
			{
				if (go != null && seen.add(go))
				{
					out.add(go);
				}
			}
		}
		if (tile.getWallObject() != null)
		{
			out.add(tile.getWallObject());
		}
		if (tile.getGroundObject() != null)
		{
			out.add(tile.getGroundObject());
		}
		if (tile.getDecorativeObject() != null)
		{
			out.add(tile.getDecorativeObject());
		}
		return out;
	}

	private static JsonArray nonNull(String[] values)
	{
		JsonArray out = new JsonArray();
		if (values != null)
		{
			for (String v : values)
			{
				if (v != null && !v.isEmpty() && !"null".equals(v))
				{
					out.add(v);
				}
			}
		}
		return out;
	}

	private static JsonArray nearest(List<JsonObject> rows, int max)
	{
		rows.sort(Comparator.comparingInt(r -> r.has("distance") ? r.get("distance").getAsInt() : Integer.MAX_VALUE));
		JsonArray out = new JsonArray();
		rows.stream().limit(max).forEach(out::add);
		return out;
	}

	/**
	 * Every action available right now as a numbered menu, built from one observe pass. Each entry
	 * is a plain-language label (what a small decision model picks from) plus the tool and arguments
	 * that perform it; act(id) runs one.
	 */
	private JsonElement listActions(JsonObject args) throws Exception
	{
		boolean dedupe = !args.has("dedupe") || args.get("dedupe").getAsBoolean();
		int maxActions = optInt(args, "maxActions", 40);
		JsonObject obs = (JsonObject) observe(args);
		JsonObject result = new JsonObject();
		if (!obs.get("loggedIn").getAsBoolean())
		{
			result.addProperty("loggedIn", false);
			return result;
		}
		JsonArray actions = new JsonArray();
		List<Callable<JsonObject>> runners = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		MenuBuilder menu = (label, kind, target, tool, toolArgs) ->
		{
			if (actions.size() >= maxActions || (dedupe && !seen.add(label.replaceAll(" \\(.*", ""))))
			{
				return;
			}
			JsonObject entry = new JsonObject();
			entry.addProperty("id", actions.size());
			entry.addProperty("label", label);
			entry.addProperty("kind", kind);
			entry.addProperty("target", target);
			entry.addProperty("tool", tool);
			entry.add("args", toolArgs);
			actions.add(entry);
			runners.add("object_op".equals(tool)
				? () -> invokeObjectOp(toolArgs)
				: () -> callTool(tool, toolArgs));
		};

		JsonObject dialogue = obs.getAsJsonObject("dialogue");
		String dialogueType = dialogue.get("type").getAsString();
		if ("options".equals(dialogueType))
		{
			// An options menu blocks everything else until it is answered.
			for (JsonElement e : dialogue.getAsJsonArray("options"))
			{
				JsonObject opt = e.getAsJsonObject();
				menu.add(Text.removeTags(opt.get("text").getAsString()), "dialogue", null, "select_option",
					argsOf("option", opt.get("option")));
			}
		}
		else
		{
			if (dialogue.has("canContinue") && dialogue.get("canContinue").getAsBoolean())
			{
				menu.add("continue the dialogue", "dialogue", null, "continue_dialogue", new JsonObject());
			}
			for (JsonElement e : obs.getAsJsonArray("npcs"))
			{
				JsonObject npc = e.getAsJsonObject();
				if (npc.has("isDead"))
				{
					continue;
				}
				String name = npc.get("name").getAsString();
				String detail = " (lvl " + npc.get("combatLevel").getAsInt() + ", " + npc.get("distance").getAsInt()
					+ " tiles" + (npc.has("targetsMe") ? ", attacking you" : "") + ")";
				for (JsonElement op : npc.getAsJsonArray("actions"))
				{
					if (!UNLISTED_OPS.contains(op.getAsString().toLowerCase()))
					{
						menu.add(op.getAsString() + " " + name + detail, "npc", name, "interact_npc",
							argsOf("npcIndex", npc.get("index"), "option", op));
					}
				}
			}
			for (JsonElement e : obs.getAsJsonArray("objects"))
			{
				JsonObject obj = e.getAsJsonObject();
				for (JsonElement op : obj.getAsJsonArray("actions"))
				{
					if (!UNLISTED_OPS.contains(op.getAsString().toLowerCase()))
					{
						menu.add(op.getAsString() + " " + obj.get("name").getAsString()
								+ " (" + obj.get("distance").getAsInt() + " tiles)", "object", obj.get("name").getAsString(), "object_op",
							argsOf("objectId", obj.get("objectId"), "x", obj.get("worldX"), "y", obj.get("worldY"),
								"option", op));
					}
				}
			}
			for (JsonElement e : obs.getAsJsonArray("groundItems"))
			{
				JsonObject item = e.getAsJsonObject();
				menu.add("take " + item.get("name").getAsString() + " (" + item.get("distance").getAsInt() + " tiles)",
					"ground", item.get("name").getAsString(), "pickup_item",
					argsOf("itemId", item.get("itemId"), "x", item.get("worldX"), "y", item.get("worldY")));
			}
			for (JsonElement e : obs.getAsJsonArray("inventory"))
			{
				JsonObject item = e.getAsJsonObject();
				for (JsonElement op : item.getAsJsonArray("actions"))
				{
					if (!UNLISTED_OPS.contains(op.getAsString().toLowerCase()))
					{
						menu.add(op.getAsString().toLowerCase() + " " + item.get("name").getAsString(), "item", null,
							"item_action", argsOf("itemId", item.get("itemId"), "option", op));
					}
				}
			}
		}

		int tick = obs.get("tick").getAsInt();
		synchronized (menuRunners)
		{
			menuRunners.clear();
			menuRunners.addAll(runners);
			menuTick = tick;
		}
		result.addProperty("tick", tick);
		result.add("actions", actions);
		if (args.has("includeObservation") && args.get("includeObservation").getAsBoolean())
		{
			result.add("observation", obs);
		}
		return result;
	}

	@FunctionalInterface
	private interface MenuBuilder
	{
		void add(String label, String kind, String target, String tool, JsonObject toolArgs);
	}

	private static JsonObject argsOf(Object... pairs)
	{
		JsonObject o = new JsonObject();
		for (int i = 0; i < pairs.length; i += 2)
		{
			o.add((String) pairs[i], (JsonElement) pairs[i + 1]);
		}
		return o;
	}

	private JsonElement act(JsonObject args) throws Exception
	{
		int id = args.get("id").getAsInt();
		int maxAge = optInt(args, "maxAgeTicks", 3);
		Callable<JsonObject> runner;
		int age;
		synchronized (menuRunners)
		{
			if (id < 0 || id >= menuRunners.size())
			{
				throw new IllegalArgumentException("No action " + id + " in the last list_actions menu ("
					+ menuRunners.size() + " actions)");
			}
			runner = menuRunners.get(id);
			age = client.getTickCount() - menuTick;
		}
		if (age > maxAge)
		{
			throw new IllegalStateException("The action menu is " + age + " ticks old; call list_actions again");
		}
		JsonObject result = runner.call();
		result.addProperty("actionId", id);
		return result;
	}

	/**
	 * Invoke a scene object's op directly (the menu entry the client would build: scene x/y, object
	 * id), so the object need not be on screen and the mouse never moves.
	 */
	private JsonObject invokeObjectOp(JsonObject args) throws Exception
	{
		int objectId = args.get("objectId").getAsInt();
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		String option = args.get("option").getAsString();
		return onClientThread(() ->
		{
			int plane = client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				throw new IllegalArgumentException("Tile " + x + "," + y + " is not in the loaded scene");
			}
			Tile tile = client.getScene().getTiles()[plane][lp.getSceneX()][lp.getSceneY()];
			TileObject obj = tile == null ? null : tileObjects(tile, new HashSet<>()).stream()
				.filter(o -> o.getId() == objectId).findFirst().orElse(null);
			if (obj == null)
			{
				throw new IllegalArgumentException("Object " + objectId + " is no longer at " + x + "," + y);
			}
			ObjectComposition comp = client.getObjectDefinition(objectId);
			if (comp.getImpostorIds() != null && comp.getImpostor() != null)
			{
				comp = comp.getImpostor();
			}
			String[] ops = comp.getActions();
			int index = -1;
			for (int i = 0; ops != null && i < ops.length && i < OBJECT_MENU_ACTIONS.length; i++)
			{
				if (ops[i] != null && ops[i].equalsIgnoreCase(option))
				{
					index = i;
				}
			}
			if (index < 0)
			{
				throw new IllegalArgumentException(comp.getName() + " has no option '" + option + "'");
			}
			int sceneX = lp.getSceneX();
			int sceneY = lp.getSceneY();
			if (obj instanceof GameObject)
			{
				net.runelite.api.Point min = ((GameObject) obj).getSceneMinLocation();
				sceneX = min.getX();
				sceneY = min.getY();
			}
			client.menuAction(sceneX, sceneY, OBJECT_MENU_ACTIONS[index], objectId, -1, ops[index], comp.getName());
			JsonObject result = new JsonObject();
			result.addProperty("invoked", ops[index]);
			result.addProperty("object", comp.getName());
			result.addProperty("objectId", objectId);
			return result;
		});
	}

	// --- bank and shop ---

	/**
	 * Invoke `option` (case-insensitive, exact) on the slot holding `itemId` in a container widget,
	 * as the client's own CC_OP entry. Returns null when the item is there but has no such op.
	 * Must be called on the client thread.
	 */
	private JsonObject invokeSlotOp(int containerWidget, int itemId, String option)
	{
		Widget container = client.getWidget(containerWidget);
		if (container == null || container.isHidden())
		{
			throw new IllegalStateException("That interface is not open");
		}
		Widget[] slots = container.getDynamicChildren();
		for (Widget slot : slots == null ? new Widget[0] : slots)
		{
			if (slot == null || slot.getItemId() != itemId)
			{
				continue;
			}
			String[] ops = slot.getActions();
			for (int i = 0; ops != null && i < ops.length; i++)
			{
				if (ops[i] != null && Text.removeTags(ops[i]).equalsIgnoreCase(option))
				{
					client.menuAction(slot.getIndex(), containerWidget,
						i < 5 ? MenuAction.CC_OP : MenuAction.CC_OP_LOW_PRIORITY, i + 1, itemId, ops[i], slot.getName());
					JsonObject result = new JsonObject();
					result.addProperty("invoked", Text.removeTags(ops[i]));
					result.addProperty("itemId", itemId);
					return result;
				}
			}
			return null;
		}
		throw new IllegalArgumentException("Item " + itemId + " is not in that interface");
	}

	/** Invoke a slot op, or fall back to `fallback` ("Withdraw-X") and type the amount. */
	private JsonObject slotOpWithAmount(int containerWidget, int itemId, String option, String fallback, int amount)
		throws Exception
	{
		JsonObject done = onClientThread(() -> invokeSlotOp(containerWidget, itemId, option));
		if (done != null)
		{
			return done;
		}
		JsonObject x = onClientThread(() -> invokeSlotOp(containerWidget, itemId, fallback));
		if (x == null)
		{
			throw new IllegalArgumentException("Item " + itemId + " has neither '" + option + "' nor '" + fallback + "'");
		}
		JsonObject input = new JsonObject();
		input.addProperty("text", String.valueOf(amount));
		callTool("wait_for", argsOf("condition", new com.google.gson.JsonPrimitive("dialogue"),
			"timeoutMs", new com.google.gson.JsonPrimitive(3000)));
		x.add("entered", callTool("enter_input", input));
		return x;
	}

	private JsonElement bank(JsonObject args) throws Exception
	{
		String action = args.get("action").getAsString().toLowerCase();
		JsonObject result;
		switch (action)
		{
			case "deposit_inventory":
				result = onClientThread(() ->
				{
					Widget button = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
					if (button == null || button.isHidden())
					{
						throw new IllegalStateException("The bank is not open");
					}
					client.menuAction(-1, InterfaceID.Bankmain.DEPOSITINV, MenuAction.CC_OP, 1, -1,
						"Deposit inventory", "");
					JsonObject o = new JsonObject();
					o.addProperty("invoked", "Deposit inventory");
					return o;
				});
				break;
			case "withdraw":
			case "deposit":
			{
				int itemId = args.get("itemId").getAsInt();
				String qty = args.has("quantity") ? args.get("quantity").getAsString().toLowerCase() : "1";
				boolean withdraw = action.equals("withdraw");
				String verb = withdraw ? "Withdraw-" : "Deposit-";
				int widget = withdraw ? InterfaceID.Bankmain.ITEMS : InterfaceID.Bankside.ITEMS;
				String option = verb + ("all".equals(qty) ? "All" : qty);
				int amount = "all".equals(qty) ? Integer.MAX_VALUE : Integer.parseInt(qty);
				result = slotOpWithAmount(widget, itemId, option, verb + "X", amount);
				break;
			}
			default:
				throw new IllegalArgumentException("action must be deposit_inventory, withdraw or deposit");
		}
		JsonObject contents = onClientThread(() ->
		{
			JsonObject o = new JsonObject();
			ItemContainer bankInv = client.getItemContainer(InventoryID.BANK);
			o.addProperty("bankItems", bankInv == null ? -1 : (int) java.util.Arrays.stream(bankInv.getItems())
				.filter(i -> i != null && i.getId() != -1).count());
			return o;
		});
		result.add("bank", contents);
		return result;
	}

	private JsonElement shop(JsonObject args) throws Exception
	{
		String action = args.get("action").getAsString().toLowerCase();
		int itemId = args.get("itemId").getAsInt();
		int quantity = optInt(args, "quantity", 1);
		boolean buy = action.equals("buy");
		if (!buy && !action.equals("sell"))
		{
			throw new IllegalArgumentException("action must be buy or sell");
		}
		int widget = buy ? InterfaceID.Shopmain.ITEMS : InterfaceID.Shopside.ITEMS;
		String verb = buy ? "Buy " : "Sell ";
		// Shops offer fixed amounts (1/5/10/50): spend the quantity largest-first.
		JsonArray clicks = new JsonArray();
		int left = quantity;
		for (int step : new int[]{50, 10, 5, 1})
		{
			while (left >= step)
			{
				JsonObject done = onClientThread(() -> invokeSlotOp(widget, itemId, verb + step));
				if (done == null)
				{
					break;
				}
				clicks.add(done.get("invoked"));
				left -= step;
			}
		}
		JsonObject result = new JsonObject();
		result.addProperty("requested", quantity);
		result.addProperty("clicked", quantity - left);
		result.add("ops", clicks);
		return result;
	}

	/** Active prayers as enum names. Must be called on the client thread. */
	private JsonArray activePrayers()
	{
		JsonArray active = new JsonArray();
		for (Prayer prayer : Prayer.values())
		{
			if (client.isPrayerActive(prayer))
			{
				active.add(prayer.name());
			}
		}
		return active;
	}

	private JsonElement setPrayer(JsonObject args) throws Exception
	{
		Prayer prayer = Prayer.valueOf(args.get("prayer").getAsString().toUpperCase());
		boolean on = !args.has("on") || args.get("on").getAsBoolean();
		// "PROTECT_FROM_MAGIC" -> "protect from magic", matched against the prayer widgets' names.
		String wanted = prayer.name().replace('_', ' ').toLowerCase();
		return onClientThread(() ->
		{
			if (client.isPrayerActive(prayer) != on)
			{
				Widget button = null;
				for (int i = 0; i < 30 && button == null; i++)
				{
					Widget w = client.getWidget(InterfaceID.Prayerbook.PRAYER1 + i);
					String name = w != null && w.getName() != null ? Text.removeTags(w.getName()).toLowerCase() : "";
					if (name.equals(wanted))
					{
						button = w;
					}
				}
				if (button == null)
				{
					throw new IllegalArgumentException("No prayer button named '" + wanted + "' in the prayer book");
				}
				client.menuAction(-1, button.getId(), MenuAction.CC_OP, 1, -1,
					on ? "Activate" : "Deactivate", button.getName());
			}
			JsonObject result = new JsonObject();
			result.addProperty("prayer", prayer.name());
			result.addProperty("requested", on);
			result.add("prayers", activePrayers());
			return result;
		});
	}

	private JsonObject waitFor(JsonObject args) throws Exception
	{
		String condition = args.get("condition").getAsString();
		long timeout = Math.min(optInt(args, "timeoutMs", 10_000), 60_000);
		long start = System.currentTimeMillis();
		long deadline = start + timeout;
		while (true)
		{
			JsonObject state = onClientThread(() -> checkCondition(condition, args, start));
			if (state.get("met").getAsBoolean() || System.currentTimeMillis() >= deadline)
			{
				state.addProperty("condition", condition);
				state.addProperty("waitedMs", System.currentTimeMillis() - start);
				return textResult(state);
			}
			Thread.sleep(100);
		}
	}

	/**
	 * Evaluate a wait_for condition. Must be called on the client thread.
	 */
	private JsonObject checkCondition(String condition, JsonObject args, long startMs)
	{
		JsonObject o = new JsonObject();
		boolean met;
		switch (condition)
		{
			case "idle":
			{
				Player local = client.getLocalPlayer();
				met = local != null && local.getAnimation() == -1
					&& local.getPoseAnimation() == local.getIdlePoseAnimation();
				if (local != null)
				{
					o.addProperty("animation", local.getAnimation());
					o.addProperty("poseAnimation", local.getPoseAnimation());
				}
				break;
			}
			case "at_tile":
			{
				int x = args.get("x").getAsInt();
				int y = args.get("y").getAsInt();
				int range = optInt(args, "range", 1);
				Player local = client.getLocalPlayer();
				WorldPoint wp = local != null ? local.getWorldLocation() : null;
				int distance = wp != null ? wp.distanceTo(new WorldPoint(x, y, wp.getPlane())) : Integer.MAX_VALUE;
				met = distance <= range;
				o.addProperty("distance", distance);
				break;
			}
			case "npc_dead":
			{
				NPC npc = findNpc(args);
				met = npc == null || npc.isDead();
				o.addProperty("despawned", npc == null);
				if (npc != null && npc.getHealthRatio() > -1)
				{
					o.addProperty("healthRatio", npc.getHealthRatio());
					o.addProperty("healthScale", npc.getHealthScale());
				}
				break;
			}
			case "dialogue":
			{
				JsonObject dialogue = readDialogue();
				met = !"none".equals(dialogue.get("type").getAsString());
				o.add("dialogue", dialogue);
				break;
			}
			case "interface_open":
			case "interface_closed":
			{
				int interfaceId = args.get("interfaceId").getAsInt();
				boolean open = interfaceBounds(interfaceId) != null;
				met = "interface_open".equals(condition) == open;
				o.addProperty("open", open);
				break;
			}
			case "chat_message":
			{
				String contains = args.get("textContains").getAsString().toLowerCase();
				JsonObject history = recorder.chatHistory(
					System.currentTimeMillis() - startMs, null, null, 100);
				met = false;
				for (JsonElement e : history.getAsJsonArray("events"))
				{
					String message = e.getAsJsonObject().get("message").getAsString();
					if (message.toLowerCase().contains(contains))
					{
						met = true;
						o.addProperty("message", message);
						break;
					}
				}
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown condition: " + condition
					+ " (idle, at_tile, npc_dead, dialogue, interface_open, interface_closed, chat_message)");
		}
		o.addProperty("met", met);
		return o;
	}

	private JsonElement getSkills() throws Exception
	{
		return onClientThread(() ->
		{
			JsonArray skills = new JsonArray();
			for (Skill skill : Skill.values())
			{
				JsonObject o = new JsonObject();
				o.addProperty("skill", skill.getName());
				o.addProperty("level", client.getRealSkillLevel(skill));
				o.addProperty("boostedLevel", client.getBoostedSkillLevel(skill));
				o.addProperty("xp", client.getSkillExperience(skill));
				skills.add(o);
			}
			JsonObject result = new JsonObject();
			result.addProperty("totalLevel", client.getTotalLevel());
			result.add("skills", skills);
			return result;
		});
	}

	private JsonElement listObjects(JsonObject args) throws Exception
	{
		String filter = args.has("nameFilter") ? args.get("nameFilter").getAsString().toLowerCase() : null;
		Integer objectId = args.has("objectId") ? args.get("objectId").getAsInt() : null;
		int maxDistance = optInt(args, "maxDistance", Integer.MAX_VALUE);
		int maxResults = optInt(args, "maxResults", 50);
		return onClientThread(() ->
		{
			Scene scene = client.getScene();
			if (scene == null)
			{
				throw new IllegalStateException("No scene is loaded");
			}
			WorldPoint me = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
			Tile[][] tiles = scene.getTiles()[client.getPlane()];
			// Multi-tile GameObjects appear on every tile they cover; report each once
			Set<GameObject> seen = new HashSet<>();
			List<JsonObject> list = new ArrayList<>();
			for (Tile[] column : tiles)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject go : gameObjects)
						{
							if (go == null || !seen.add(go))
							{
								continue;
							}
							JsonObject o = describeTileObject("game", go, me, filter, objectId, maxDistance);
							if (o != null)
							{
								o.addProperty("orientation", go.getOrientation());
								o.addProperty("orientationDegrees", go.getOrientation() * 360 / 2048);
								o.addProperty("facing", facing(go.getOrientation()));
								o.addProperty("sizeX", go.sizeX());
								o.addProperty("sizeY", go.sizeY());
								list.add(o);
							}
						}
					}
					WallObject wall = tile.getWallObject();
					if (wall != null)
					{
						JsonObject o = describeTileObject("wall", wall, me, filter, objectId, maxDistance);
						if (o != null)
						{
							o.addProperty("orientationA", wall.getOrientationA());
							o.addProperty("orientationB", wall.getOrientationB());
							list.add(o);
						}
					}
					DecorativeObject deco = tile.getDecorativeObject();
					if (deco != null)
					{
						JsonObject o = describeTileObject("decorative", deco, me, filter, objectId, maxDistance);
						if (o != null)
						{
							list.add(o);
						}
					}
					GroundObject ground = tile.getGroundObject();
					if (ground != null)
					{
						JsonObject o = describeTileObject("ground", ground, me, filter, objectId, maxDistance);
						if (o != null)
						{
							list.add(o);
						}
					}
				}
			}
			return actorListResult("objects", list, maxResults);
		});
	}

	/**
	 * Base description of a scene object, or null when it fails the filters.
	 * Must be called on the client thread.
	 */
	private JsonObject describeTileObject(String type, TileObject obj, WorldPoint me,
		String lowercaseFilter, Integer objectId, int maxDistance)
	{
		if (objectId != null && obj.getId() != objectId)
		{
			return null;
		}
		ObjectComposition composition = client.getObjectDefinition(obj.getId());
		String name = composition != null ? composition.getName() : null;
		if (lowercaseFilter != null && (name == null || !name.toLowerCase().contains(lowercaseFilter)))
		{
			return null;
		}
		WorldPoint wp = obj.getWorldLocation();
		int distance = me != null && wp != null ? me.distanceTo(wp) : -1;
		if (distance >= 0 && distance > maxDistance)
		{
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("objectId", obj.getId());
		if (name != null && !"null".equals(name))
		{
			o.addProperty("name", name);
		}
		if (composition != null && composition.getActions() != null)
		{
			JsonArray actions = new JsonArray();
			for (String action : composition.getActions())
			{
				if (action != null)
				{
					actions.add(action);
				}
			}
			if (actions.size() > 0)
			{
				o.add("actions", actions);
			}
		}
		if (wp != null)
		{
			o.addProperty("worldX", wp.getX());
			o.addProperty("worldY", wp.getY());
			o.addProperty("plane", wp.getPlane());
		}
		if (distance >= 0)
		{
			o.addProperty("distance", distance);
		}
		return o;
	}

	private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	/**
	 * Convert a jagex angle unit orientation (0-2047, 0 = south, counter-clockwise) to a compass direction.
	 */
	private static String facing(int jau)
	{
		return COMPASS[((jau + 128) & 2047) / 256];
	}

	private JsonElement pickupItem(JsonObject args) throws Exception
	{
		int itemId = args.get("itemId").getAsInt();
		int x = args.get("x").getAsInt();
		int y = args.get("y").getAsInt();
		Integer planeArg = args.has("plane") ? args.get("plane").getAsInt() : null;
		return onClientThread(() ->
		{
			int plane = planeArg != null ? planeArg : client.getPlane();
			LocalPoint lp = LocalPoint.fromWorld(client, new WorldPoint(x, y, plane));
			if (lp == null)
			{
				throw new IllegalArgumentException("Tile " + x + "," + y + " is not in the loaded scene");
			}
			client.menuAction(lp.getSceneX(), lp.getSceneY(), MenuAction.GROUND_ITEM_THIRD_OPTION,
				itemId, -1, "Take", "");
			JsonObject result = new JsonObject();
			result.addProperty("invoked", "Take");
			result.addProperty("itemId", itemId);
			result.addProperty("tile", x + "," + y + "," + plane);
			result.addProperty("note", "Player may need to walk to the tile; check list_ground_items again to confirm pickup");
			return result;
		});
	}

	// --- actor helpers ---

	private static boolean matchesName(String name, String lowercaseFilter)
	{
		return lowercaseFilter == null || (name != null && name.toLowerCase().contains(lowercaseFilter));
	}

	/**
	 * Describe an actor, or null when it is farther than maxDistance.
	 * Must be called on the client thread.
	 */
	private JsonObject describeActor(Actor actor, WorldPoint me, int maxDistance)
	{
		WorldPoint wp = actor.getWorldLocation();
		int distance = me != null && wp != null ? me.distanceTo(wp) : -1;
		if (distance >= 0 && distance > maxDistance)
		{
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", actor.getName());
		o.addProperty("combatLevel", actor.getCombatLevel());
		if (wp != null)
		{
			o.addProperty("worldX", wp.getX());
			o.addProperty("worldY", wp.getY());
			o.addProperty("plane", wp.getPlane());
		}
		if (distance >= 0)
		{
			o.addProperty("distance", distance);
		}
		if (actor.getAnimation() != -1)
		{
			o.addProperty("animation", actor.getAnimation());
		}
		o.addProperty("poseAnimation", actor.getPoseAnimation());
		o.addProperty("orientation", actor.getCurrentOrientation());
		o.addProperty("facing", facing(actor.getCurrentOrientation()));
		// Health bar is only populated while it is rendered (in/just after combat)
		if (actor.getHealthRatio() > -1)
		{
			o.addProperty("healthRatio", actor.getHealthRatio());
			o.addProperty("healthScale", actor.getHealthScale());
			if (actor.getHealthScale() > 0)
			{
				o.addProperty("healthPercent", actor.getHealthRatio() * 100 / actor.getHealthScale());
			}
		}
		if (actor.isDead())
		{
			o.addProperty("isDead", true);
		}
		Actor interacting = actor.getInteracting();
		if (interacting != null)
		{
			o.addProperty("interacting", interacting.getName());
			o.addProperty("interactingType", interacting instanceof NPC ? "npc" : "player");
			if (interacting instanceof NPC)
			{
				o.addProperty("interactingIndex", ((NPC) interacting).getIndex());
			}
			if (interacting == client.getLocalPlayer() && actor != interacting)
			{
				o.addProperty("targetsMe", true);
			}
		}
		JsonArray spotAnims = new JsonArray();
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			spotAnims.add(spotAnim.getId());
		}
		if (spotAnims.size() > 0)
		{
			o.add("spotAnims", spotAnims);
		}
		LocalPoint lp = actor.getLocalLocation();
		if (lp != null && wp != null)
		{
			net.runelite.api.Point p = Perspective.localToCanvas(client, lp, wp.getPlane());
			if (p != null)
			{
				o.addProperty("canvasX", p.getX());
				o.addProperty("canvasY", p.getY());
			}
		}
		return o;
	}

	private static JsonObject actorListResult(String key, List<JsonObject> list, int maxResults)
	{
		list.sort(Comparator.comparingInt(o -> o.has("distance") ? o.get("distance").getAsInt() : Integer.MAX_VALUE));
		JsonArray arr = new JsonArray();
		for (int i = 0; i < list.size() && i < maxResults; i++)
		{
			arr.add(list.get(i));
		}
		JsonObject result = new JsonObject();
		result.addProperty("count", list.size());
		result.add(key, arr);
		return result;
	}

	// --- widget helpers ---

	/**
	 * Union of the visible component bounds of an interface, or null if none.
	 * Must be called on the client thread.
	 */
	private Rectangle interfaceBounds(int interfaceId)
	{
		Rectangle union = null;
		int gap = 0;
		for (int child = 0; child < MAX_COMPONENTS_PER_INTERFACE && gap < COMPONENT_SCAN_GAP; child++)
		{
			Widget w = client.getWidget(WidgetUtil.packComponentId(interfaceId, child));
			if (w == null)
			{
				gap++;
				continue;
			}
			gap = 0;
			if (w.isHidden())
			{
				continue;
			}
			Rectangle b = w.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				continue;
			}
			union = union == null ? new Rectangle(b) : union.union(b);
		}
		return union;
	}

	private static Rectangle pad(Rectangle r, int padding)
	{
		return new Rectangle(r.x - padding, r.y - padding, r.width + padding * 2, r.height + padding * 2);
	}

	private Widget resolveWidget(JsonObject args, String idKey, String indexKey)
	{
		Widget w = client.getWidget(args.get(idKey).getAsInt());
		if (w != null && args.has(indexKey))
		{
			w = w.getChild(args.get(indexKey).getAsInt());
		}
		return w;
	}

	private JsonObject describeWidget(Widget w, int depth, boolean includeHidden)
	{
		JsonObject o = new JsonObject();
		o.addProperty("componentId", w.getId());
		o.addProperty("interfaceId", WidgetUtil.componentToInterface(w.getId()));
		o.addProperty("childId", WidgetUtil.componentToId(w.getId()));
		if (w.getIndex() != -1)
		{
			o.addProperty("childIndex", w.getIndex());
		}
		o.addProperty("type", w.getType());
		if (w.getContentType() != 0)
		{
			o.addProperty("contentType", w.getContentType());
		}
		Rectangle bounds = w.getBounds();
		if (bounds != null)
		{
			JsonObject b = new JsonObject();
			b.addProperty("x", bounds.x);
			b.addProperty("y", bounds.y);
			b.addProperty("width", bounds.width);
			b.addProperty("height", bounds.height);
			o.add("bounds", b);
		}
		o.addProperty("relativeX", w.getRelativeX());
		o.addProperty("relativeY", w.getRelativeY());
		o.addProperty("originalX", w.getOriginalX());
		o.addProperty("originalY", w.getOriginalY());
		o.addProperty("width", w.getWidth());
		o.addProperty("height", w.getHeight());
		o.addProperty("hidden", w.isHidden());
		if (w.isSelfHidden())
		{
			o.addProperty("selfHidden", true);
		}
		String text = w.getText();
		if (text != null && !text.isEmpty())
		{
			o.addProperty("text", text);
			o.addProperty("textColor", String.format("%06x", w.getTextColor()));
		}
		String name = w.getName();
		if (name != null && !name.isEmpty())
		{
			o.addProperty("name", name);
		}
		if (w.getOpacity() != 0)
		{
			o.addProperty("opacity", w.getOpacity());
		}
		if (w.getItemId() != -1)
		{
			o.addProperty("itemId", w.getItemId());
			o.addProperty("itemQuantity", w.getItemQuantity());
		}
		if (w.getSpriteId() != -1)
		{
			o.addProperty("spriteId", w.getSpriteId());
		}
		if (w.getModelId() != -1)
		{
			o.addProperty("modelId", w.getModelId());
		}
		if (depth > 0)
		{
			Widget[] dynamic = w.getDynamicChildren();
			if (dynamic != null && dynamic.length > 0)
			{
				JsonArray children = new JsonArray();
				for (Widget child : dynamic)
				{
					if (child != null && (includeHidden || !child.isHidden()))
					{
						children.add(describeWidget(child, depth - 1, includeHidden));
					}
				}
				o.add("dynamicChildren", children);
			}
			appendChildIds(o, "staticChildIds", w.getStaticChildren());
			appendChildIds(o, "nestedChildIds", w.getNestedChildren());
		}
		return o;
	}

	private static void appendChildIds(JsonObject o, String key, Widget[] children)
	{
		if (children == null || children.length == 0)
		{
			return;
		}
		JsonArray ids = new JsonArray();
		for (Widget child : children)
		{
			if (child != null)
			{
				ids.add(child.getId());
			}
		}
		if (ids.size() > 0)
		{
			o.add(key, ids);
		}
	}

	private static void enqueueChildren(Deque<Widget> queue, Widget[] children)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			if (child != null)
			{
				queue.add(child);
			}
		}
	}

	private static JsonArray toArray(Set<Integer> values)
	{
		JsonArray arr = new JsonArray();
		values.stream().sorted().forEach(arr::add);
		return arr;
	}

	private Point resolvePoint(JsonObject args, String idKey, String indexKey, String xKey, String yKey) throws Exception
	{
		if (args.has(idKey))
		{
			Rectangle bounds = onClientThread(() ->
			{
				Widget w = resolveWidget(args, idKey, indexKey);
				return w == null || w.isHidden() ? null : w.getBounds();
			});
			if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
			{
				throw new IllegalArgumentException("Widget not found, hidden or has no bounds: "
					+ describeId(args, idKey, indexKey));
			}
			return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
		}
		if (args.has(xKey) && args.has(yKey))
		{
			return new Point(args.get(xKey).getAsInt(), args.get(yKey).getAsInt());
		}
		throw new IllegalArgumentException("Provide either " + idKey + " or " + xKey + "/" + yKey);
	}

	private static String describeId(JsonObject args, String idKey, String indexKey)
	{
		int id = args.get(idKey).getAsInt();
		String s = (id >> 16) + "." + (id & 0xFFFF) + " (" + id + ")";
		if (args.has(indexKey))
		{
			s += "[" + args.get(indexKey).getAsInt() + "]";
		}
		return s;
	}

	// --- input dispatch ---

	private void clickAt(int x, int y, int button) throws Exception
	{
		dispatchMouse(MouseEvent.MOUSE_MOVED, x, y, MouseEvent.NOBUTTON, 0);
		Thread.sleep(15);
		dispatchMouse(MouseEvent.MOUSE_PRESSED, x, y, button, 1);
		Thread.sleep(20);
		dispatchMouse(MouseEvent.MOUSE_RELEASED, x, y, button, 1);
		dispatchMouse(MouseEvent.MOUSE_CLICKED, x, y, button, 1);
	}

	private void dispatchMouse(int id, int x, int y, int button, int clickCount) throws Exception
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			throw new IllegalStateException("Client canvas is not available");
		}
		int modifiers = 0;
		if (button != MouseEvent.NOBUTTON
			&& (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED))
		{
			modifiers = InputEvent.getMaskForButton(button);
		}
		// MOUSE_DRAGGED carries the pressed button in the modifier mask, not the button field
		int eventButton = id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : button;
		MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), modifiers,
			x, y, clickCount, false, eventButton);
		SwingUtilities.invokeAndWait(() -> canvas.dispatchEvent(event));
	}

	private void typeChar(char c) throws Exception
	{
		int code = KeyEvent.getExtendedKeyCodeForChar(c);
		dispatchKeyBatch(
			keyEvent(KeyEvent.KEY_PRESSED, code, c),
			keyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c),
			keyEvent(KeyEvent.KEY_RELEASED, code, c));
		Thread.sleep(5);
	}

	private void pressNamedKey(String name) throws Exception
	{
		int keyCode;
		char keyChar = KeyEvent.CHAR_UNDEFINED;
		switch (name)
		{
			case "ENTER":
				keyCode = KeyEvent.VK_ENTER;
				keyChar = '\n';
				break;
			case "ESCAPE":
			case "ESC":
				keyCode = KeyEvent.VK_ESCAPE;
				keyChar = (char) 27;
				break;
			case "SPACE":
				keyCode = KeyEvent.VK_SPACE;
				keyChar = ' ';
				break;
			case "TAB":
				keyCode = KeyEvent.VK_TAB;
				keyChar = '\t';
				break;
			case "BACKSPACE":
				keyCode = KeyEvent.VK_BACK_SPACE;
				keyChar = '\b';
				break;
			case "UP":
				keyCode = KeyEvent.VK_UP;
				break;
			case "DOWN":
				keyCode = KeyEvent.VK_DOWN;
				break;
			case "LEFT":
				keyCode = KeyEvent.VK_LEFT;
				break;
			case "RIGHT":
				keyCode = KeyEvent.VK_RIGHT;
				break;
			default:
				if (name.matches("F([1-9]|1[0-2])"))
				{
					keyCode = KeyEvent.VK_F1 + Integer.parseInt(name.substring(1)) - 1;
					break;
				}
				throw new IllegalArgumentException("Unknown key: " + name);
		}
		if (keyChar != KeyEvent.CHAR_UNDEFINED)
		{
			dispatchKeyBatch(
				keyEvent(KeyEvent.KEY_PRESSED, keyCode, keyChar),
				keyEvent(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, keyChar),
				keyEvent(KeyEvent.KEY_RELEASED, keyCode, keyChar));
		}
		else
		{
			dispatchKeyBatch(
				keyEvent(KeyEvent.KEY_PRESSED, keyCode, keyChar),
				keyEvent(KeyEvent.KEY_RELEASED, keyCode, keyChar));
		}
	}

	private KeyEvent keyEvent(int id, int keyCode, char keyChar)
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			throw new IllegalStateException("Client canvas is not available");
		}
		return new KeyEvent(canvas, id, System.currentTimeMillis(), 0, keyCode, keyChar);
	}

	private void dispatchKeyBatch(KeyEvent... events) throws Exception
	{
		Canvas canvas = client.getCanvas();
		SwingUtilities.invokeAndWait(() ->
		{
			for (KeyEvent event : events)
			{
				canvas.dispatchEvent(event);
			}
		});
	}

	// --- capture ---

	private BufferedImage captureFrame() throws Exception
	{
		CompletableFuture<Image> future = new CompletableFuture<>();
		drawManager.requestNextFrameListener(future::complete);
		Image image = future.get(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		if (image instanceof BufferedImage)
		{
			return (BufferedImage) image;
		}
		BufferedImage copy = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return copy;
	}

	private static String pngBase64(BufferedImage image) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	// --- plumbing ---

	private <T> T onClientThread(Supplier<T> supplier) throws Exception
	{
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<RuntimeException> error = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			try
			{
				result.set(supplier.get());
			}
			catch (RuntimeException e)
			{
				error.set(e);
			}
			finally
			{
				latch.countDown();
			}
		});
		if (!latch.await(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS))
		{
			throw new IllegalStateException("Timed out waiting for the client thread; is the client running?");
		}
		if (error.get() != null)
		{
			throw error.get();
		}
		return result.get();
	}

	private static JsonObject textResult(String text)
	{
		JsonObject item = new JsonObject();
		item.addProperty("type", "text");
		item.addProperty("text", text);
		JsonArray content = new JsonArray();
		content.add(item);
		JsonObject result = new JsonObject();
		result.add("content", content);
		return result;
	}

	private static JsonObject textResult(JsonElement json)
	{
		return textResult(McpHttpServer.GSON.toJson(json));
	}

	private static int optInt(JsonObject args, String key, int def)
	{
		return args.has(key) ? args.get(key).getAsInt() : def;
	}

	/** Optional string-array argument, as a set; null when absent so callers read it as "no filter". */
	private static Set<String> optStringSet(JsonObject args, String key)
	{
		if (!args.has(key) || !args.get(key).isJsonArray())
		{
			return null;
		}
		Set<String> values = new HashSet<>();
		for (JsonElement element : args.get(key).getAsJsonArray())
		{
			values.add(element.getAsString());
		}
		return values.isEmpty() ? null : values;
	}

	private static JsonObject tool(String name, String description, JsonObject inputSchema)
	{
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("description", description);
		o.add("inputSchema", inputSchema);
		return o;
	}

	private static JsonObject schema(JsonObject properties, String... required)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", properties);
		if (required.length > 0)
		{
			JsonArray req = new JsonArray();
			for (String r : required)
			{
				req.add(r);
			}
			o.add("required", req);
		}
		return o;
	}

	private static JsonObject prop(String type, String description)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("description", description);
		return o;
	}
}
