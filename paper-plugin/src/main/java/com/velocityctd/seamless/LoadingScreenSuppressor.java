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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Stops the "Loading terrain" screen appearing when the client is not actually changing world.
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
 * <p>A real dimension change is left alone. There the client genuinely has to rebuild its world,
 * the screen is honest about what is happening, and hiding it would only show the player an empty
 * void while chunks stream in.</p>
 *
 * <p>Telling the two apart is the whole job, and the packet type cannot do it. A long teleport is
 * not always a bare position update: Folia moves a player between regions by respawning them, so a
 * cross-region teleport arrives as a respawn packet indistinguishable in kind from a nether portal.
 * What separates them is the world each one names, so that is what this tracks -- the world the
 * client was last placed in, against the world the new packet puts it in. Same world means the
 * client keeps its level and the screen is covering terrain it already has.</p>
 *
 * <p>A world-preserving server switch is the one case where even a differing world must be
 * suppressed. This server sends a join game packet like any other join, but the proxy withholds it,
 * so the client never leaves the world it has. The plugin is told about those joins in advance, via
 * the entity ID the proxy asked it to reuse.</p>
 */
final class LoadingScreenSuppressor extends PacketListenerAbstract {

  /**
   * The world each client was last placed in, as named by the join game or respawn packet that
   * placed it there. Absent until the first such packet, which is why a first join is treated as a
   * world change: the client has no level to keep.
   */
  private final Map<UUID, String> clientWorld = new ConcurrentHashMap<>();

  /**
   * Players whose next "start waiting for level chunks" follows a move into a different world, and
   * so is a real world change that should keep its loading screen.
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
   * Whether same-world teleports also have their loading screen hidden. Switches are suppressed
   * regardless, since for those the screen covers a world the client already has.
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
      onPlacedInWorld(event, uuid);
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
      debug("allowing the loading screen for " + uuid + " (moved to another world)");
      return;
    }

    if (!hideTeleportScreen) {
      debug("allowing the loading screen for " + uuid
          + " (same world, and hide-teleport-loading-screen is off)");
      return;
    }

    debug("suppressing the loading screen for " + uuid + " (same-world teleport)");
    event.setCancelled(true);
    acknowledgeLoaded(event.getUser());
  }

  /**
   * Records which world a join game or respawn packet puts the client in, and decides whether the
   * loading request that follows it is covering a genuine rebuild.
   *
   * @param event the join game or respawn packet being sent
   * @param uuid  the player it is being sent to
   */
  private void onPlacedInWorld(final PacketSendEvent event, final UUID uuid) {
    final String world = worldOf(event);
    final String previous = world == null ? clientWorld.remove(uuid) : clientWorld.put(uuid, world);

    if (seamlessArrivals.remove(uuid)) {
      // The proxy is withholding this join packet and leaving the client in the world it has. The
      // loading request that follows would cover a world that never went away.
      changingWorld.remove(uuid);
      suppressNext.add(uuid);
      debug("a world-preserving switch placed " + uuid + " in " + world
          + "; its loading screen will be suppressed");
      return;
    }

    if (world != null && Objects.equals(world, previous)) {
      // Same world. Folia respawns a player to move them across a region boundary, so this is how
      // an ordinary long teleport arrives -- the client keeps its level and needs no screen.
      changingWorld.remove(uuid);
      debug(uuid + " was placed in " + world + " again; treating it as a teleport, not a world "
          + "change");
      return;
    }

    changingWorld.add(uuid);
    debug(uuid + " moved from " + previous + " to " + world
        + "; its loading screen will be allowed through");
  }

  /**
   * Returns the world a join game or respawn packet places the client in.
   *
   * <p>Falls back to {@code null} rather than guessing if the packet cannot be read -- a Minecraft
   * version this build of packetevents does not know, say. A null reads as "this may be a world
   * change", which keeps the loading screen: the outcome without this plugin at all.</p>
   *
   * @param event the join game or respawn packet being sent
   * @return the world's name, or {@code null} if it could not be determined
   */
  private String worldOf(final PacketSendEvent event) {
    try {
      if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
        return new WrapperPlayServerJoinGame(event).getWorldName();
      }
      return new WrapperPlayServerRespawn(event).getWorldName().orElse(null);
    } catch (final RuntimeException | LinkageError failed) {
      debug("could not read the world out of a " + event.getPacketType().getName() + " packet ("
          + failed + "); its loading screen will be allowed through");
      return null;
    }
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
    clientWorld.remove(player);
    changingWorld.remove(player);
    seamlessArrivals.remove(player);
    suppressNext.remove(player);
  }
}
