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

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerLoaded;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Stops the "Loading terrain" screen appearing when the client is not actually rebuilding its
 * world.
 *
 * <p>The screen has two independent causes, and only one of them is ours to remove.</p>
 *
 * <p>The one we can remove is the Game Event packet whose reason is "start waiting for level
 * chunks". The server sends it when the destination chunks are not already on the client, and it
 * asks the client to wait. Drop it and the wait does not happen -- but then the server is left
 * waiting instead, because from 1.21.2 it holds the player still until the client reports it has
 * loaded, and a client that was never told to wait never reports. So the acknowledgement is
 * injected on the client's behalf.</p>
 *
 * <p>The one we cannot remove is a respawn packet. That tells the client to tear down its level and
 * build a new one, and the screen goes up the moment it arrives -- before any game event. Dropping
 * the game event afterwards does not take the screen away; it only cancels the chunk handshake, so
 * the server stops treating the player as still loading and the screen that the respawn drew stays
 * up <em>longer</em> while chunks arrive at their leisure.</p>
 *
 * <p>That distinction matters most on Folia, which moves a player across a region boundary by
 * respawning them. There a long teleport is a respawn, so its screen is not suppressible from here,
 * and trying makes it worse. So the rule is: suppress only when the client was never told to
 * rebuild.</p>
 *
 * <p>The exception is a world-preserving server switch. The destination sends a join game packet
 * like any other join, but the proxy withholds it, so the client never sees it and never leaves the
 * world it has. There the loading request would cover a world that never went away, and the proxy
 * supplies the acknowledgement itself.</p>
 */
final class LoadingScreenSuppressor extends PacketListenerAbstract {

  /**
   * Players who have just been told to rebuild their level, by a join game or respawn packet. The
   * loading request that follows is honest: the client really is waiting for a world, and the
   * server's chunk handshake is what ends the wait soonest.
   */
  private final Set<UUID> rebuildingWorld = ConcurrentHashMap.newKeySet();

  /**
   * Players who are arriving on a world-preserving switch, whose loading request must be dropped
   * however this listener is configured. Set before the join game packet is sent; cleared by it.
   */
  private final Set<UUID> seamlessArrivals = ConcurrentHashMap.newKeySet();

  /**
   * Players whose next loading request is to be dropped, carried from the join game packet to the
   * game event that follows it.
   */
  private final Set<UUID> suppressNext = ConcurrentHashMap.newKeySet();

  /**
   * Whether a teleport that did not respawn the player also has its loading screen hidden. Switches
   * are suppressed regardless, since for those the screen covers a world the client already has.
   */
  private final boolean hideTeleportScreen;

  /**
   * Used only when debug logging is on, so an operator can see what was decided for each loading
   * request rather than inferring it from whether a screen appeared. Null when off.
   */
  private final Logger debugLogger;

  LoadingScreenSuppressor(final boolean hideTeleportScreen, final Logger debugLogger) {
    // Low priority: other plugins get to see, and act on, the packet before it is dropped.
    super(PacketListenerPriority.LOW);
    this.hideTeleportScreen = hideTeleportScreen;
    this.debugLogger = debugLogger;
  }

  private void debug(final String message) {
    if (debugLogger != null) {
      debugLogger.info("[debug] " + message);
    }
  }

  /**
   * Notes that a player is arriving on a world-preserving switch, so the join game packet about to
   * be sent is not the world change it looks like.
   *
   * @param player the arriving player
   */
  void expectSeamlessArrival(final UUID player) {
    seamlessArrivals.add(player);
    debug("expecting a world-preserving arrival for " + player);
  }

  @Override
  public void onPacketSend(final PacketSendEvent event) {
    final UUID uuid = event.getUser().getUUID();
    if (uuid == null) {
      return;
    }

    if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME
        || event.getPacketType() == PacketType.Play.Server.RESPAWN) {
      if (seamlessArrivals.remove(uuid)) {
        // The proxy is withholding this packet and leaving the client in the world it has, so the
        // client is not rebuilding anything and the request that follows covers nothing.
        rebuildingWorld.remove(uuid);
        suppressNext.add(uuid);
      } else {
        // The client has been told to rebuild. Its screen is already up, drawn by this packet, and
        // nothing we drop afterwards takes it away -- only the chunk handshake ends it.
        rebuildingWorld.add(uuid);
      }
      return;
    }

    if (event.getPacketType() != PacketType.Play.Server.CHANGE_GAME_STATE) {
      return;
    }

    final WrapperPlayServerChangeGameState gameState = new WrapperPlayServerChangeGameState(event);
    if (gameState.getReason() != WrapperPlayServerChangeGameState.Reason.START_LOADING_CHUNKS) {
      return;
    }

    if (suppressNext.remove(uuid)) {
      // No acknowledgement from here: the proxy sends one itself when it withholds a join game,
      // and a second would be a packet the server never asked for.
      debug("suppressing the loading screen for " + uuid + " (world-preserving switch)");
      event.setCancelled(true);
      return;
    }

    if (rebuildingWorld.remove(uuid)) {
      debug("allowing the loading screen for " + uuid
          + " (the client was told to rebuild its world; suppressing would only make it longer)");
      return;
    }

    if (!hideTeleportScreen) {
      debug("allowing the loading screen for " + uuid
          + " (teleport, and hide-teleport-loading-screen is off)");
      return;
    }

    debug("suppressing the loading screen for " + uuid + " (teleport, no rebuild)");
    event.setCancelled(true);
    acknowledgeLoaded(event.getUser());
  }

  /**
   * Tells the server the player has finished loading, since the client will never say so itself
   * after the request to wait was dropped.
   *
   * @param user the player's connection
   */
  private static void acknowledgeLoaded(final User user) {
    final ClientVersion version = user.getClientVersion();
    // The loaded handshake only exists from 1.21.2. Older servers never wait for it, so there is
    // nothing to answer and the packet would not even encode.
    if (version == null || !version.isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
      return;
    }
    user.receivePacketSilently(new WrapperPlayClientPlayerLoaded());
  }

  /**
   * Drops tracking for a player who has left.
   *
   * @param player the player's unique ID
   */
  void forget(final UUID player) {
    rebuildingWorld.remove(player);
    seamlessArrivals.remove(player);
    suppressNext.remove(player);
  }
}
