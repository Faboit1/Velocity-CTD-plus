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
 * Removes the "Loading terrain" screen where the client is not really rebuilding its world.
 *
 * <p>Two packets put that screen up, and both have to be dealt with or it stays.</p>
 *
 * <p>The <b>respawn packet</b> is the one that draws it. It tells the client to tear down its level
 * and build a new one, and the screen appears the instant it arrives. Folia moves a player across a
 * region boundary by respawning them, so on Folia a long teleport is a respawn -- and no amount of
 * dropping later packets takes that screen away, because it is already up. The only way to remove
 * it is to not send the respawn. That is safe exactly when the respawn would not have changed
 * anything the client holds: same world, and the server asking for all player data to be kept.
 * Then the client keeps the level it has and simply gets moved within it.</p>
 *
 * <p>The <b>game event</b> whose reason is "start waiting for level chunks" is the one that keeps
 * it up. The server sends it to make the client wait, and from 1.21.2 holds the player still until
 * the client reports back that it has loaded. Dropping it alone achieves nothing when a respawn
 * already drew the screen -- worse, the acknowledgement then has to be faked, the server stops
 * treating the player as loading, and the screen the respawn drew lasts <em>longer</em>. Dropped
 * together with the respawn, there is no screen at all.</p>
 *
 * <p>So the rule is all-or-nothing: suppress both, or neither. A genuine world change -- a
 * different world, or a respawn that resets the player, such as after death -- gets neither, and
 * keeps an honest screen that ends as soon as the chunks arrive.</p>
 *
 * <p>A world-preserving server switch reaches the same place by another route: there the proxy
 * withholds the join game packet, so the client is never told to rebuild, and the proxy sends the
 * acknowledgement itself.</p>
 */
final class LoadingScreenSuppressor extends PacketListenerAbstract {

  /**
   * The world each client currently holds, as named by the last join game or respawn it was sent.
   * A respawn naming this same world is not a world change, whatever else it is.
   */
  private final Map<UUID, String> clientWorld = new ConcurrentHashMap<>();

  /**
   * Players who have just been told to rebuild their level. The loading request that follows is
   * honest: the client really is waiting for a world, and the chunk handshake is what ends the
   * wait soonest.
   */
  private final Set<UUID> rebuildingWorld = ConcurrentHashMap.newKeySet();

  /**
   * Players who are arriving on a world-preserving switch, whose loading request must be dropped
   * however this listener is configured. Set before the join game packet is sent; cleared by it.
   */
  private final Set<UUID> seamlessArrivals = ConcurrentHashMap.newKeySet();

  /**
   * Players whose next loading request is to be dropped, carried from the packet that decided it
   * to the game event that follows. The value says whether this listener must also acknowledge the
   * load: true when it withheld the respawn itself, false on a switch, where the proxy sends the
   * acknowledgement and a second would be a packet the server never asked for.
   */
  private final Map<UUID, Boolean> suppressNext = new ConcurrentHashMap<>();

  /**
   * Whether a teleport that does not really change the client's world has its respawn withheld and
   * its loading screen removed. Switches are suppressed regardless.
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

    if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
      onJoinGame(event, uuid);
    } else if (event.getPacketType() == PacketType.Play.Server.RESPAWN) {
      onRespawn(event, uuid);
    } else if (event.getPacketType() == PacketType.Play.Server.CHANGE_GAME_STATE) {
      onGameState(event, uuid);
    }
  }

  private void onJoinGame(final PacketSendEvent event, final UUID uuid) {
    String world = null;
    try {
      world = new WrapperPlayServerJoinGame(event).getWorldName();
    } catch (final RuntimeException | LinkageError unreadable) {
      debug("could not read the world out of a join game packet (" + unreadable + ")");
    }
    remember(uuid, world);

    if (seamlessArrivals.remove(uuid)) {
      // The proxy is withholding this packet, so the client never leaves the world it has.
      rebuildingWorld.remove(uuid);
      suppressNext.put(uuid, Boolean.FALSE);
      return;
    }
    rebuildingWorld.add(uuid);
  }

  private void onRespawn(final PacketSendEvent event, final UUID uuid) {
    String world = null;
    byte keptData = WrapperPlayServerRespawn.KEEP_NOTHING;
    try {
      final WrapperPlayServerRespawn respawn = new WrapperPlayServerRespawn(event);
      world = respawn.getWorldName().orElse(null);
      keptData = respawn.getKeptData();
    } catch (final RuntimeException | LinkageError unreadable) {
      debug("could not read a respawn packet (" + unreadable + "); leaving it alone");
    }

    final String previous = clientWorld.get(uuid);
    remember(uuid, world);

    if (seamlessArrivals.remove(uuid)) {
      rebuildingWorld.remove(uuid);
      suppressNext.put(uuid, Boolean.FALSE);
      return;
    }

    // Withholding a respawn is only safe when it would not have changed anything the client holds.
    // A different world would leave the client in the wrong one; anything short of keeping all
    // player data means the server wants the player reset, as after a death, and the screen is
    // then honest.
    if (hideTeleportScreen && world != null && Objects.equals(world, previous)
        && keptData == WrapperPlayServerRespawn.KEEP_ALL_DATA) {
      debug("withholding a respawn into " + world + " for " + uuid
          + "; the client keeps the world it has and draws no loading screen");
      event.setCancelled(true);
      rebuildingWorld.remove(uuid);
      suppressNext.put(uuid, Boolean.TRUE);
      return;
    }

    rebuildingWorld.add(uuid);
  }

  private void onGameState(final PacketSendEvent event, final UUID uuid) {
    final WrapperPlayServerChangeGameState gameState = new WrapperPlayServerChangeGameState(event);
    if (gameState.getReason() != WrapperPlayServerChangeGameState.Reason.START_LOADING_CHUNKS) {
      return;
    }

    final Boolean acknowledge = suppressNext.remove(uuid);
    if (acknowledge != null) {
      debug("suppressing the loading screen for " + uuid + " (the client kept its world"
          + (acknowledge ? "" : "; the proxy will acknowledge the load") + ")");
      event.setCancelled(true);
      if (acknowledge) {
        acknowledgeLoaded(event.getUser());
      }
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

    // A loading request with no respawn in front of it: the client still holds its level, so the
    // screen would cover terrain that is already there.
    debug("suppressing the loading screen for " + uuid + " (teleport, no respawn)");
    event.setCancelled(true);
    acknowledgeLoaded(event.getUser());
  }

  private void remember(final UUID player, final String world) {
    if (world == null) {
      clientWorld.remove(player);
    } else {
      clientWorld.put(player, world);
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
    rebuildingWorld.remove(player);
    seamlessArrivals.remove(player);
    suppressNext.remove(player);
  }
}
