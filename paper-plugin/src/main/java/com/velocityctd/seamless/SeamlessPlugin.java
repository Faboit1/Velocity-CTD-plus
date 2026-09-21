/*
 * Copyright (C) 2026 Velocity-CTD Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocityctd.seamless;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Backend companion to Velocity-CTD+'s seamless switching.
 *
 * <p>Removes the two loading screens a player would otherwise sit through:</p>
 *
 * <ul>
 *   <li><b>Switching servers.</b> The proxy can withhold the join game and respawn packets so the
 *   client keeps the world it already has, but only if this server gives the player the entity ID
 *   their client already holds. The proxy knows that ID and this plugin asks for it during login,
 *   then applies it as the player spawns.</li>
 *   <li><b>Teleporting a long way.</b> On Folia any teleport crossing into another region, and on
 *   any server any sufficiently long teleport, makes the server ask the client to show the terrain
 *   loading screen. That request is dropped, and the acknowledgement the server waits for is
 *   supplied on the client's behalf.</li>
 * </ul>
 *
 * <p>Each half is independent and switched on separately. Both degrade quietly: if this plugin can
 * do nothing, players still join and teleport normally, they just see the loading screen.</p>
 */
public final class SeamlessPlugin extends JavaPlugin implements Listener {

  private boolean debugLogging;
  private ProxyEntityIdChannel entityIdChannel;
  private LoadingScreenSuppressor loadingScreenSuppressor;
  private EntityIdApplier entityIdApplier;

  @Override
  public void onEnable() {
    saveDefaultConfig();

    final boolean reuseEntityId = getConfig().getBoolean("reuse-entity-id-on-switch", true);
    final boolean hideTeleportLoadingScreen =
        getConfig().getBoolean("hide-teleport-loading-screen", false);
    this.debugLogging = getConfig().getBoolean("debug", false);

    if (!reuseEntityId && !hideTeleportLoadingScreen) {
      getLogger().warning("Both features are disabled in config.yml; this plugin will do nothing.");
      return;
    }

    if (reuseEntityId) {
      entityIdApplier = new EntityIdApplier(getLogger());
      if (entityIdApplier.isAvailable()) {
        entityIdChannel = new ProxyEntityIdChannel(getLogger(), debugLogging ? getLogger() : null);
        PacketEvents.getAPI().getEventManager().registerListener(entityIdChannel);
        getLogger().info("Entity ID reuse enabled; asking the proxy on "
            + ProxyEntityIdChannel.channel() + " as each player logs in.");
      } else {
        getLogger().warning("Entity ID reuse is unavailable: " + entityIdApplier.unavailableReason()
            + ". Server switches will keep their loading screen.");
        entityIdApplier = null;
      }
    }

    // The suppressor is not optional for switches. Reusing the entity ID keeps the client's world,
    // but this server still asks the client to wait for chunks on every join, and the proxy relays
    // that ask -- so without the suppressor a "seamless" switch still ends on a terrain screen.
    if (hideTeleportLoadingScreen || entityIdApplier != null) {
      loadingScreenSuppressor = new LoadingScreenSuppressor(
          hideTeleportLoadingScreen, debugLogging ? getLogger() : null);
      PacketEvents.getAPI().getEventManager().registerListener(loadingScreenSuppressor);
      getLogger().info(hideTeleportLoadingScreen
          ? "Removing the terrain loading screen on server switches, and on teleports that keep "
            + "the player's world."
          : "Hiding the terrain loading screen on server switches only.");
    }

    getServer().getPluginManager().registerEvents(this, this);
  }

  /**
   * Applies the entity ID the proxy reported, before the server builds this player's join game
   * packet.
   *
   * <p>Timing is the whole game here. {@link PlayerLoginEvent} fires once the server-side player
   * exists but before it is placed into a world and before any join packet is written, so the ID
   * set here is the one the client is told about. Anything later -- a join event, a scheduled task
   * -- is after the client has already been told, and the two would disagree.</p>
   *
   * @param event the login event
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onPlayerLogin(final PlayerLoginEvent event) {
    if (entityIdChannel == null || entityIdApplier == null) {
      return;
    }
    final String name = event.getPlayer().getName();
    final int entityId = entityIdChannel.takeEntityId(name);
    if (entityId <= 0) {
      if (debugLogging) {
        getLogger().info(entityIdChannel.proxyAnswered(name)
            ? "[debug] the proxy has no entity ID to reuse for " + name
              + ": this is a first join, or the player left a server that did not preserve one"
            : "[debug] nothing answered " + ProxyEntityIdChannel.channel() + " for " + name
              + ": the proxy is not a Velocity-CTD+, or it has keep-client-world-on-switch off");
      }
      return;
    }
    if (!entityIdApplier.apply(event.getPlayer(), entityId)) {
      return;
    }
    getLogger().fine(() -> "Reused entity ID " + entityId + " for " + event.getPlayer().getName());

    // This player is mid-switch with their world intact, so the join game packet about to be sent
    // is not the world change it looks like, and the loading request after it must be dropped.
    if (loadingScreenSuppressor != null) {
      loadingScreenSuppressor.expectSeamlessArrival(event.getPlayer().getUniqueId());
    }
  }

  /**
   * Clears per-player state so a disconnect during login cannot leave an entry behind.
   *
   * @param event the quit event
   */
  @EventHandler
  public void onPlayerQuit(final PlayerQuitEvent event) {
    if (entityIdChannel != null) {
      entityIdChannel.forget(event.getPlayer().getName());
    }
    if (loadingScreenSuppressor != null) {
      loadingScreenSuppressor.forget(event.getPlayer().getUniqueId());
    }
  }

  @Override
  public void onDisable() {
    if (entityIdChannel != null) {
      PacketEvents.getAPI().getEventManager().unregisterListener(entityIdChannel);
    }
    if (loadingScreenSuppressor != null) {
      PacketEvents.getAPI().getEventManager().unregisterListener(loadingScreenSuppressor);
    }
  }
}
