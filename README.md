# OpenRune-Developer-Tools

Standalone sideloaded plugin for RuneLite-based clients. Runs a local MCP server
(default `http://127.0.0.1:7780/mcp`) so AI tools like Claude can screenshot
interfaces, inspect widgets/NPCs/objects/items, drive input (click, drag, type,
dialogue) and read script/var/projectile/chat history. Live activity dashboard
at `http://127.0.0.1:7780/` shows every AI tool call with args, results and
screenshots.

## Build and deploy

```
gradlew jar
```

That is the whole setup. The jar deploys itself to every client on your
machine, by looking for the `sideloaded-plugins` folder each one creates in your
home directory — `~/.runelite`, `~/.rsprox`, `~/.fluxious`, or a fork's own
directory, with nothing to configure. The build prints where it deployed.

Deploying fails if one of those clients is running, because it holds its jar
open. The other clients are still updated and the error names the ones that
were not. A client only loads a new jar when it starts, so restart it after
building.

Two optional flags, for cases the default doesn't cover:

```
gradlew jar -PsideloadDirs=.runelite,.myclient   # deploy to exactly these
gradlew jar -PclientJar=/path/to/client-shaded.jar   # compile against a client's jar
```

`-PclientJar` is only needed when your client exposes API this plugin uses;
otherwise the build compiles against the upstream RuneLite artifact.

Start the client with `--developer-mode`, enable **OpenRune-DeveloperTools**,
then connect Claude:

```
claude mcp add --transport http flux http://127.0.0.1:7780/mcp
```

The AI calls these tools constantly — skip per-call permission prompts by allowing
the whole server in your project's `.claude/settings.local.json`:

```json
{
	"permissions": {
		"allow": ["mcp__flux"]
	}
}
```

## Dashboard

While the plugin is running, `http://127.0.0.1:7780/` serves a live activity
dashboard — open it in a browser to watch what the AI is doing in real time:

![Live activity dashboard](docs/dashboard.png)

- Every MCP tool call as it happens: tool name, arguments as chips, duration,
  timestamp, with slow calls (>1s) and errors highlighted.
- Results inline: JSON pretty-printed and collapsible, screenshots as thumbnails
  with a click-to-zoom lightbox.
- Header stats for the session: total calls, errors, average duration, live
  connection indicator.
- Filter by tool via dropdown, errors-only toggle, pause/resume, follow
  (auto-scroll) and clear.

The page polls `GET /log?after=<id>` (same port) for new entries; the server keeps
the last 100 calls in memory, so refreshing the page replays recent history.

## Driving the client from a fast decision loop

For automated testing on private servers you can run a loop of your own instead of a chat
model: observe, pick an action, act, a few times per 600 ms game tick. What works:

- **Read cheaply.** `observe` is the per-step read: one client-thread pass (~20 ms) instead of
  a call per tool. `get_combat_state` is the lighter one for tight polls (fighting, moving,
  attackers, prayers, tick).
- **Act directly.** `interact_npc`, `item_action`, `set_prayer` and `pickup_item` invoke the
  client's own menu action: no mouse movement and no tab has to be open, so they are safe to
  fire mid-fight. Hover-based tools (`interact_object`, `widget_action`) need the target on
  screen.
- **One call at a time.** A new call cancels the one in flight, so keep your loop sequential.
  Don't park on `wait_for` while something time-critical still needs polling.
- **Keep reflexes in code.** Anything an if-statement can decide (switch prayer on an attack
  animation from `get_effect_history`, eat below a threshold) should never wait on a model.
- **Give the model choices, not freedom.** A small classifier picking from a short list of
  concrete, labelled actions ("Attack Goblin (3 tiles)", "eat Shark") is fast and can't invent
  an action that doesn't exist. `list_actions` builds that list from the client's own options;
  trim it to what your loop should offer, then `act(id)`.

```
loop:
    menu = list_actions(includeObservation=true)      # one call: snapshot + numbered actions
    if reflex(menu.observation): act on it; continue  # eat, pray, continue dialogue...
    pick = model.choose(state_text, [a.label for a in menu.actions])
    act(id=pick)                                      # refused if the menu went stale
```

## Releases

Pushing to the `prod` branch builds the jar and publishes a GitHub release
automatically.

## Tools

### Inspection

| Tool | Description |
|---|---|
| `screenshot` | PNG of the canvas; crop to a widget (`componentId`), whole interface (`interfaceId`) or region |
| `get_widget` | Widget values by packed id: bounds, position, text, item, sprite, hidden, children |
| `dump_interface` | Every component of an interface with positions/sizes/text — diff two dumps to spot layout changes |
| `list_interfaces` | Interface ids currently loaded and visible |
| `get_widget_at` | Deepest visible widget at a canvas pixel, with parent chain |
| `set_widget` | Live-edit a widget client-side: move, resize, hide, retext, recolor, then revalidate |
| `get_client_state` | Game state, canvas size, mouse, player position/region, energy, weight, camera, menu-open flag |
| `get_skills` | All skills: level, boosted level, xp, total level |
| `get_combat_state` | One cheap call: ticks since you were hit / last hit something, in combat, moving, walk destination, your target, NPCs attacking you, active prayers |
| `observe` | Everything a decision loop reads, in one client-thread pass (~20 ms): vitals, boosts, combat state, inventory with item options, nearest NPCs / interactive objects / ground items, dialogue |

### Decision loops

| Tool | Description |
|---|---|
| `list_actions` | Every action available now as a numbered menu of plain labels ("Attack Goblin (lvl 2, 3 tiles)", "eat Shark", dialogue options), each with the tool + args behind it; optionally with the `observe` snapshot it was built from |
| `act` | Run action `id` from the last menu; refuses a menu older than `maxAgeTicks` (default 3). Object ops are invoked directly, so the object need not be on screen |

### Raw input

| Tool | Description |
|---|---|
| `click` | Click at canvas coordinates (real mouse events) |
| `click_component` | Click the center of a widget by packed id |
| `hover` | Move the mouse to coordinates or a widget center |
| `drag` | Press, drag through interpolated points, release — components or coordinates |
| `type_chat` | Type into the chatbox and send (works for `::` commands) |
| `press_key` | Press one key: char, ENTER, ESCAPE, SPACE, TAB, arrows, F1-F12 |

### World

| Tool | Description |
|---|---|
| `walk_to` | Click a world tile to walk there (viewport, or minimap when off-screen) |
| `list_npcs` | NPCs with id, index, name, actions, location, distance, animation, facing, health |
| `list_players` | Players with name, location, distance, animation, facing, health; local flagged |
| `list_objects` | Scene objects (game/wall/decorative/ground) with id, name, actions, position, orientation |
| `list_ground_items` | Floor items with id, name, quantity, location, distance |
| `pickup_item` | Invoke Take on a ground item at world coordinates |
| `list_projectiles` | Projectiles in flight with target info |
| `get_inventory` | Any item container by id (93 inventory, 94 worn, 95 bank, custom) with slots/names/quantities |
| `list_inventories` | All loaded item containers |

### Interaction (one call, no screenshots)

| Tool | Description |
|---|---|
| `interact_npc` | Invoke a cache-defined NPC option (default Talk-to) |
| `get_npc_menu` | Hover an NPC and read its live right-click menu, incl. server-added options |
| `click_menu_option` | Click an entry of the currently built mini-menu |
| `item_action` | Invoke an inventory item's option (Drop, Eat, Wield...) directly; works with the inventory tab closed |
| `set_prayer` | Turn a prayer on/off by name (`PROTECT_FROM_MAGIC`...) without opening the prayer tab |
| `bank` | With the bank open: `deposit_inventory`, or `withdraw` / `deposit` an item by id (1, 5, 10, all, or any amount via the X prompt) |
| `shop` | With a shop open: `buy` / `sell` an item by id and quantity, using the shop's 1/5/10/50 ops |
| `close_interface` | Press the Close button of every open interface (bank, shop, menus) via its own op; an open bank or shop swallows typed commands |
| `interact_object` | Nearest matching object, hover, click option (Chop down, Mine, Open...) |
| `interact_player` | Hover a player and click an option (Trade with, Follow...) |
| `widget_action` | Right-click any widget and click a menu option (spells, prayers, buttons) |
| `invoke_menu_action` | Raw `menuAction` escape hatch with explicit params |

### Dialogue

| Tool | Description |
|---|---|
| `get_dialogue` | Current dialogue: NPC/player text, options menu, message box, item box, input prompt |
| `continue_dialogue` | Press space, return the new dialogue state as soon as it changes |
| `select_option` | Pick a numbered dialogue option, return the new state |
| `enter_input` | Type into an open chatbox input (string/amount) and press Enter |

### History and vars

| Tool | Description |
|---|---|
| `get_script_history` | Clientscripts fired recently: per-script counts + events with tick and args |
| `get_var_history` | Varbit/varp/varc changes with old/new values, tick, timestamp |
| `get_var` | Current value of a varbit, varp, varc int or varc string |
| `get_projectile_history` | Projectiles launched recently, one entry each, with values and target |
| `get_effect_history` | Animations and graphics played by NPCs/players recently |
| `get_chat_history` | Chatbox messages: type, sender, message, tick, timestamp |
| `get_combat_history` | Hitsplats applied: amount, type, actor hit, remaining health ratio |
| `get_xp_history` | Experience gains: skill, xp gained, new total, level, boosted level |
| `get_container_history` | Item container changes as per-item deltas (was/now/delta) |
| `get_sound_history` | Sound effects played: id, source actor, scene position for area sounds |

### Timeline

| Tool | Description |
|---|---|
| `get_timeline` | Every channel merged into one tick-ordered stream — scripts, vars, projectiles, effects, chat, combat, experience, containers, sounds. Scripts are left out unless requested. Filter by `channels`, `fromTick`/`toTick` |

### Waiting

| Tool | Description |
|---|---|
| `wait_for` | Block until a condition: idle, at_tile, npc_dead, dialogue, interface_open/closed, chat_message — checked every 100ms, returns immediately when met |
