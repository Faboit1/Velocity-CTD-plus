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
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientPluginResponse;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerPluginRequest;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
 * <p>Answers are filed under the player's name. Mid-login there is not much else to file them
 * under: a connection has no {@code Player} yet, and its UUID is not populated until later, so
 * keying on that silently discarded every answer. The name arrives with the login itself, in the
 * very packet that prompts the question.</p>
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
   * Used only when debug logging is on. The login exchange is invisible from either side on its
   * own -- a missing entity ID looks identical whether the request never went, the proxy ignored
   * it, or the answer was genuinely nothing -- so each step of it is narrated. Null when off.
   */
  private final Logger debugLogger;

  /**
   * The name each connection is logging in as, carried from the login start packet to the proxy's
   * answer. Weak keys because the entry's natural lifetime is the connection's: a login that dies
   * before the proxy answers takes its entry with it.
   */
  private final Map<User, String> loggingIn = Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * Entity IDs the proxy reported, keyed by player name, waiting to be applied when the player
   * spawns. Entries are removed when used, and by the plugin when a login does not complete.
   */
  private final Map<String, Integer> pending = new ConcurrentHashMap<>();

  /**
   * Players the proxy answered for, whatever the answer was. Kept apart from {@link #pending} so
   * that "the proxy said this is a first join" can be told from "no proxy answered at all", which
   * are the same absence of an ID but very different things to go and look at.
   */
  private final Set<String> answered = ConcurrentHashMap.newKeySet();

  ProxyEntityIdChannel(final Logger logger, final Logger debugLogger) {
    // Run late enough that the login has a user profile, but this only reads and injects.
    super(PacketListenerPriority.NORMAL);
    this.logger = logger;
    this.debugLogger = debugLogger;
  }

  private void debug(final String message) {
    if (debugLogger != null) {
      debugLogger.info("[debug] " + message);
    }
  }

  private static String key(final String playerName) {
    return playerName.toLowerCase(Locale.ROOT);
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
    try {
      final String username = new WrapperLoginClientLoginStart(event).getUsername();
      if (username != null) {
        loggingIn.put(event.getUser(), username);
      }
      event.getUser().sendPacket(new WrapperLoginServerPluginRequest(
          MESSAGE_ID, CHANNEL, new byte[] {SeamlessPayload.FORMAT_VERSION}));
      debug("asked the proxy on " + CHANNEL + " for " + username + " as message " + MESSAGE_ID);
    } catch (final RuntimeException | LinkageError failed) {
      debug("could not ask the proxy on " + CHANNEL + " (" + failed + ")");
    }
  }

  private void readAnswer(final PacketReceiveEvent event) {
    final WrapperLoginClientPluginResponse response = new WrapperLoginClientPluginResponse(event);
    if (response.getMessageId() != MESSAGE_ID) {
      // Not ours -- the server's own player-info forwarding, most likely. Worth a line anyway:
      // seeing someone else's exchange proves responses reach this listener at all.
      debug("saw a login plugin response that is not ours: message " + response.getMessageId()
          + ", successful=" + response.isSuccessful());
      return;
    }

    // This is our exchange, so the vanilla login handler must never see it -- it did not send the
    // request and would disconnect the player over an unexpected message ID.
    event.setCancelled(true);

    final String username = loggingIn.remove(event.getUser());
    if (username == null) {
      debug("the proxy answered " + CHANNEL + ", but the login start that asked for it named no "
          + "player to file the answer under");
      return;
    }

    if (!response.isSuccessful()) {
      // Proxy does not support this, or has the feature switched off.
      debug("the proxy declined " + CHANNEL + " for " + username + "; it is not a Velocity-CTD+, "
          + "or the channel name does not match the one it answers on");
      return;
    }

    final byte[] data = response.getData();
    if (data != null && data.length >= 1 && data[0] != SeamlessPayload.FORMAT_VERSION) {
      logger.warning("The proxy answered " + CHANNEL + " in format " + data[0] + ", which this "
          + "plugin does not understand; entity ID reuse is off. Update the plugin or the proxy.");
      return;
    }

    answered.add(key(username));

    final int entityId = SeamlessPayload.readEntityId(data);
    debug("the proxy answered " + CHANNEL + " for " + username + " with entity ID " + entityId);
    if (entityId == SeamlessPayload.NO_ENTITY_ID) {
      return; // First join, or the proxy has nothing to preserve.
    }
    pending.put(key(username), entityId);
  }

  /**
   * Says whether the proxy answered this plugin's login-phase question at all.
   *
   * @param playerName the arriving player's name
   * @return {@code true} if an answer came back, whatever entity ID it named
   */
  boolean proxyAnswered(final String playerName) {
    return answered.contains(key(playerName));
  }

  /**
   * Takes the entity ID the proxy reported for a player, if any.
   *
   * @param playerName the arriving player's name
   * @return the entity ID to apply, or {@code 0} if the proxy reported none
   */
  int takeEntityId(final String playerName) {
    final Integer entityId = pending.remove(key(playerName));
    return entityId == null ? 0 : entityId;
  }

  /**
   * Drops any recorded ID for a player whose login did not reach the spawn stage.
   *
   * @param playerName the player's name
   */
  void forget(final String playerName) {
    final String key = key(playerName);
    pending.remove(key);
    answered.remove(key);
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
