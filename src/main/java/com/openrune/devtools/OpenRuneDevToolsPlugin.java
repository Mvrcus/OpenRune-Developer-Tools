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
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Provides;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Projectile;
import net.runelite.api.ScriptEvent;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;

@PluginDescriptor(
	name = "OpenRune-DeveloperTools",
	description = "Local MCP server letting AI tools screenshot interfaces, inspect widgets by packed id, and click/drag components",
	tags = {"ai", "mcp", "automation", "interface", "dev", "openrune"},
	enabledByDefault = false
)
@Slf4j
public class OpenRuneDevToolsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private OpenRuneDevToolsConfig config;

	private McpHttpServer server;
	private McpEventRecorder recorder;
	private ItemIconTool itemIconTool;

	// ProjectileMoved fires every cycle per projectile; only record first sighting
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new WeakHashMap<>());

	@Override
	protected void startUp() throws Exception
	{
		recorder = new McpEventRecorder();
		itemIconTool = new ItemIconTool(client, clientThread);
		tryStartServer();
		if (config.autoUpdate())
		{
			new UpdateChecker(client, clientThread).checkAsync();
		}
	}

	@Override
	protected void shutDown()
	{
		stopServer();
		recorder = null;
		if (itemIconTool != null)
		{
			itemIconTool.shutDown();
			itemIconTool = null;
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (itemIconTool != null && config.itemIconEditor())
		{
			itemIconTool.onMenuOpened(event);
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (recorder != null)
		{
			ScriptEvent scriptEvent = event.getScriptEvent();
			recorder.recordScript(event.getScriptId(),
				scriptEvent != null ? scriptEvent.getArguments() : null,
				client.getTickCount());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (recorder != null)
		{
			if (event.getVarbitId() != -1)
			{
				recorder.recordVar("varbit", event.getVarbitId(), event.getValue(), client.getTickCount());
			}
			else
			{
				recorder.recordVar("varp", event.getVarpId(), event.getValue(), client.getTickCount());
			}
		}
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (recorder != null)
		{
			recorder.recordVar("varcint", event.getIndex(),
				client.getVarcIntValue(event.getIndex()), client.getTickCount());
		}
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged event)
	{
		if (recorder != null)
		{
			recorder.recordVarStr(event.getIndex(),
				client.getVarcStrValue(event.getIndex()), client.getTickCount());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (recorder != null)
		{
			recorder.recordChat(event.getType().name(), event.getName(), event.getSender(),
				event.getMessage(), client.getTickCount());
		}
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (recorder == null)
		{
			return;
		}
		Projectile projectile = event.getProjectile();
		if (!seenProjectiles.add(projectile))
		{
			return;
		}
		JsonObject detail = new JsonObject();
		detail.addProperty("projectileId", projectile.getId());
		detail.addProperty("startSceneX", projectile.getX1());
		detail.addProperty("startSceneY", projectile.getY1());
		detail.addProperty("floor", projectile.getFloor());
		detail.addProperty("slope", projectile.getSlope());
		detail.addProperty("endHeight", projectile.getEndHeight());
		detail.addProperty("startCycle", projectile.getStartCycle());
		detail.addProperty("endCycle", projectile.getEndCycle());
		WorldPoint target = projectile.getTargetPoint();
		if (target != null)
		{
			detail.addProperty("targetX", target.getX());
			detail.addProperty("targetY", target.getY());
			detail.addProperty("targetPlane", target.getPlane());
		}
		Actor targetActor = projectile.getTargetActor();
		if (targetActor != null)
		{
			detail.addProperty("target", targetActor.getName());
			detail.addProperty("targetType", targetActor instanceof NPC ? "npc" : "player");
		}
		recorder.recordProjectile(detail, client.getTickCount());
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Actor actor = event.getActor();
		if (recorder == null || actor == null)
		{
			return;
		}
		recorder.recordEffect("animation",
			actor instanceof NPC ? "npc" : "player",
			actor.getName(),
			actor instanceof NPC ? ((NPC) actor).getId() : null,
			new JsonPrimitive(actor.getAnimation()),
			client.getTickCount());
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		Actor actor = event.getActor();
		if (recorder == null || actor == null)
		{
			return;
		}
		JsonArray graphics = new JsonArray();
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			graphics.add(spotAnim.getId());
		}
		recorder.recordEffect("graphic",
			actor instanceof NPC ? "npc" : "player",
			actor.getName(),
			actor instanceof NPC ? ((NPC) actor).getId() : null,
			graphics,
			client.getTickCount());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		if (recorder == null || actor == null)
		{
			return;
		}
		Hitsplat hitsplat = event.getHitsplat();
		recorder.recordCombatTick(actor == client.getLocalPlayer(), hitsplat.isMine(), client.getTickCount());
		recorder.recordHitsplat(hitsplat.getHitsplatType(),
			hitsplat.getAmount(),
			actor instanceof NPC ? "npc" : "player",
			actor.getName(),
			actor instanceof NPC ? ((NPC) actor).getId() : null,
			actor.getHealthRatio(),
			actor.getHealthScale(),
			client.getTickCount());
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (recorder != null)
		{
			recorder.recordExperience(event.getSkill().getName(), event.getXp(), event.getLevel(),
				event.getBoostedLevel(), client.getTickCount());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (recorder == null)
		{
			return;
		}
		Map<Integer, Integer> contents = new HashMap<>();
		for (Item item : event.getItemContainer().getItems())
		{
			if (item.getId() != -1 && !isPlaceholder(item.getId()))
			{
				contents.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		recorder.recordContainer(event.getContainerId(), contents, client.getTickCount());
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		if (recorder == null)
		{
			return;
		}
		Actor source = event.getSource();
		recorder.recordSound("sound", event.getSoundId(), null, null,
			source != null ? source.getName() : null, client.getTickCount());
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (recorder == null)
		{
			return;
		}
		Actor source = event.getSource();
		recorder.recordSound("areaSound", event.getSoundId(), event.getSceneX(), event.getSceneY(),
			source != null ? source.getName() : null, client.getTickCount());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (OpenRuneDevToolsConfig.GROUP.equals(event.getGroup()))
		{
			stopServer();
			tryStartServer();
		}
	}

	/** A busy port must not fail startUp, or the plugin never enables and its port can't be changed. */
	private void tryStartServer()
	{
		try
		{
			startServer();
		}
		catch (Exception e)
		{
			log.error("Failed to start MCP server on port {}; pick a free port in the plugin settings", config.port(), e);
		}
	}

	private void startServer() throws Exception
	{
		McpHttpServer started = new McpHttpServer(config.port(), new McpTools(client, clientThread, drawManager, recorder));
		started.start();
		server = started;
		log.info("MCP server listening on http://127.0.0.1:{}/mcp", config.port());
	}

	private boolean isPlaceholder(int itemId)
	{
		ItemComposition item = client.getItemDefinition(itemId);
		return item.getPlaceholderTemplateId() != -1;
	}

	private void stopServer()
	{
		if (server != null)
		{
			server.stop();
			server = null;
		}
	}

	@Provides
	OpenRuneDevToolsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OpenRuneDevToolsConfig.class);
	}
}
