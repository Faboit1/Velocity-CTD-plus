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
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientPluginResponse;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerPluginRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Asks the proxy, during the login phase, which entity ID the arriving player's client already
 * holds.
 *
 * <p>This has to happen in the login phase because the answer is needed before the server builds
 * the join game packet, which is long before an ordinary plugin message channel exists. It mirrors
 * how Velocity's modern player-info forwarding works: the server asks, the proxy answers.</p>
 *
 * <p>A proxy that does not know this channel answers "unsuccessful", and one that is not a
 * Velocity-CTD+ answers nothing at all. Both leave no recorded ID, the plugin does nothing, and
 * the player gets an ordinary join. Nothing here can hold up or break a login.</p>
 */
final class ProxyEntityIdChannel extends PacketListenerAbstract {

  /**
   * Channel the proxy answers on. Must match {@code SEAMLESS_CHANNEL} in the proxy's
   * {@code LoginSessionHandler}.
   */
  private static final String CHANNEL = "velocityctd:seamless";

  /**
   * Login plugin message IDs are only unique per connection, and Paper's own Velocity forwarding
   * uses a low one. Start well clear of it.
   */
  private static final int MESSAGE_ID = 0x5EA11E55;

  private final Logger logger;

  /**
   * Entity IDs the proxy reported, keyed by player, waiting to be applied when the player spawns.
   * Entries are removed when used, and by the plugin when a login does not complete.
   */
  private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();

  ProxyEntityIdChannel(final Logger logger) {
    // Run late enough that the login has a user profile, but this only reads and injects.
    super(PacketListenerPriority.NORMAL);
    this.logger = logger;
  }

  @Override
  public void onPacketReceive(final PacketReceiveEvent event) {
    if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
      askProxy(event);
    } else if (event.getPacketType() == PacketType.Login.Client.LOGIN_PLUGIN_RESPONSE) {
      readAnswer(event);
    }
  }

  private void askProxy(final PacketReceiveEvent event) {
    // Sent as the login starts so the answer is back well before the player spawns. The payload is
    // just our format version, so a future proxy can tell what this plugin understands.
    final WrapperLoginServerPluginRequest request = new WrapperLoginServerPluginRequest(
        MESSAGE_ID, CHANNEL, new byte[] {SeamlessPayload.FORMAT_VERSION});
    event.getUser().sendPacket(request);
  }

  private void readAnswer(final PacketReceiveEvent event) {
    final WrapperLoginClientPluginResponse response = new WrapperLoginClientPluginResponse(event);
    if (response.getMessageId() != MESSAGE_ID) {
      return;
    }

    // This is our exchange, so the vanilla login handler must never see it -- it did not send the
    // request and would disconnect the player over an unexpected message ID.
    event.setCancelled(true);

    if (!response.isSuccessful()) {
      return; // Proxy does not support this, or has the feature switched off.
    }

    final byte[] data = response.getData();
    if (data != null && data.length >= 1 && data[0] != SeamlessPayload.FORMAT_VERSION) {
      logger.warning("The proxy answered " + CHANNEL + " in format " + data[0] + ", which this "
          + "plugin does not understand; entity ID reuse is off. Update the plugin or the proxy.");
      return;
    }

    final int entityId = SeamlessPayload.readEntityId(data);
    if (entityId == SeamlessPayload.NO_ENTITY_ID) {
      return; // First join, or the proxy has nothing to preserve.
    }

    final User user = event.getUser();
    final UUID uuid = user.getUUID();
    if (uuid == null) {
      return;
    }
    pending.put(uuid, entityId);
  }

  /**
   * Takes the entity ID the proxy reported for a player, if any.
   *
   * @param player the player's unique ID
   * @return the entity ID to apply, or {@code 0} if the proxy reported none
   */
  int takeEntityId(final UUID player) {
    final Integer entityId = pending.remove(player);
    return entityId == null ? 0 : entityId;
  }

  /**
   * Drops any recorded ID for a player whose login did not reach the spawn stage.
   *
   * @param player the player's unique ID
   */
  void forget(final UUID player) {
    pending.remove(player);
  }

  /**
   * Returns the channel name, for logging.
   *
   * @return the channel
   */
  static String channel() {
    return CHANNEL;
  }


}
