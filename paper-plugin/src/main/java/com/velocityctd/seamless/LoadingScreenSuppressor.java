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
 * Stops the "Loading terrain" screen appearing on a teleport within the same world.
 *
 * <p>The screen is not a side effect of the teleport itself: the server asks for it, with a Game
 * Event packet whose reason is "start waiting for level chunks". It sends that whenever the
 * destination chunks are not already on the client, which on Folia is every teleport that crosses
 * into another region, and on any server is every sufficiently long teleport.</p>
 *
 * <p>Dropping that packet removes the screen. What it must not do is leave the server waiting: from
 * 1.21.2 the server holds the player still until the client reports back that it has loaded, and a
 * client that was never told to wait never reports. So the acknowledgement is injected on the
 * client's behalf, exactly as the proxy does for a server switch.</p>
 *
 * <p>A dimension change is left alone. There the client genuinely has to rebuild its world, the
 * screen is honest about what is happening, and hiding it would only show the player an empty void
 * while chunks stream in. Those are recognised by the join game or respawn packet that precedes
 * them.</p>
 *
 * <p>A world-preserving server switch is the one case where that recognition is exactly backwards.
 * This server sends a join game packet like any other join, but the proxy withholds it, so the
 * client never leaves the world it has -- and the loading request that follows would put a terrain
 * screen over a world that is already there. The plugin is told about those joins in advance, via
 * the entity ID the proxy asked it to reuse, and suppresses them instead of honouring them.</p>
 */
final class LoadingScreenSuppressor extends PacketListenerAbstract {

  /**
   * Players whose next "start waiting for level chunks" follows a join or respawn, and so is a real
   * world change that should keep its loading screen.
   */
  private final Set<UUID> changingWorld = ConcurrentHashMap.newKeySet();

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
   * Whether ordinary same-world teleports also have their loading screen hidden. Switches are
   * suppressed regardless, since for those the screen covers a world the client already has.
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
        // The proxy is withholding this join packet and leaving the client in the world it has.
        // The loading request that follows would cover a world that never went away.
        suppressNext.add(uuid);
      } else {
        changingWorld.add(uuid);
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
      debug("suppressing the loading screen for " + uuid + " (world-preserving switch)");
      event.setCancelled(true);
      acknowledgeLoaded(event.getUser());
      return;
    }

    if (changingWorld.remove(uuid)) {
      // Genuine world change: let the client rebuild, and show it is doing so.
      debug("allowing the loading screen for " + uuid + " (join or respawn: a real world change)");
      return;
    }

    if (!hideTeleportScreen) {
      debug("allowing the loading screen for " + uuid
          + " (teleport, and hide-teleport-loading-screen is off)");
      return;
    }

    debug("suppressing the loading screen for " + uuid + " (same-world teleport)");
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
    changingWorld.remove(player);
    seamlessArrivals.remove(player);
    suppressNext.remove(player);
  }
}
