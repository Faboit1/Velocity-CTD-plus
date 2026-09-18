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
 */
final class LoadingScreenSuppressor extends PacketListenerAbstract {

  /**
   * Players whose next "start waiting for level chunks" follows a join or respawn, and so is a real
   * world change that should keep its loading screen.
   */
  private final Set<UUID> changingWorld = ConcurrentHashMap.newKeySet();

  LoadingScreenSuppressor() {
    // Low priority: other plugins get to see, and act on, the packet before it is dropped.
    super(PacketListenerPriority.LOW);
  }

  @Override
  public void onPacketSend(final PacketSendEvent event) {
    final UUID uuid = event.getUser().getUUID();
    if (uuid == null) {
      return;
    }

    if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME
        || event.getPacketType() == PacketType.Play.Server.RESPAWN) {
      changingWorld.add(uuid);
      return;
    }

    if (event.getPacketType() != PacketType.Play.Server.CHANGE_GAME_STATE) {
      return;
    }

    final WrapperPlayServerChangeGameState gameState = new WrapperPlayServerChangeGameState(event);
    if (gameState.getReason() != WrapperPlayServerChangeGameState.Reason.START_LOADING_CHUNKS) {
      return;
    }

    if (changingWorld.remove(uuid)) {
      return; // Genuine world change: let the client rebuild, and show it is doing so.
    }

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
  }
}
