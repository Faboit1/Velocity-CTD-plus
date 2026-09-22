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
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Asks the proxy, during the login phase, whether the arriving player authenticated with Mojang.
 *
 * <p>The question has to be asked during login because that is the only moment the proxy is
 * willing to answer it: a login plugin request is the one channel that exists before the player
 * joins. It also happens to be the right moment for the answer, since anything that acts on it --
 * refusing a cracked account a rank, sending a Bedrock player a different form of a menu -- wants
 * to have decided before the player is in the world.</p>
 *
 * <p>Answers are filed under the player's name, because mid-login there is nothing else to file
 * them under: packetevents' {@code User} has no UUID yet at this point.</p>
 *
 * <p>A proxy that does not know this channel answers "unsuccessful", and anything that is not a
 * Velocity-CTD+ answers nothing at all. Either way the player is recorded as
 * {@link AccountType#UNKNOWN} and joins normally. Nothing here can hold up or break a login.</p>
 */
final class AccountTypeChannel extends PacketListenerAbstract {

  /**
   * Channel the proxy answers on. Must match {@code ACCOUNT_TYPE_CHANNEL} in the proxy's
   * {@code LoginSessionHandler}.
   */
  private static final String CHANNEL = "velocityctd:accounttype";

  /**
   * Login plugin message IDs are only unique per connection. This one has to avoid both Paper's
   * own forwarding exchange and the seamless channel's.
   */
  private static final int MESSAGE_ID = 0x0ACC7B4E;

  private final Logger logger;

  /**
   * Used only when debug logging is on; null when off.
   */
  private final Logger debugLogger;

  /**
   * The name each connection is logging in as, carried from the login start packet to the proxy's
   * answer. Weak keys, so a login that dies before the answer arrives takes its entry with it.
   */
  private final Map<User, String> loggingIn = Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * What the proxy said, keyed by player name, until the player logs in and it can be filed under
   * their UUID instead.
   */
  private final Map<String, AccountType> pending = new ConcurrentHashMap<>();

  AccountTypeChannel(final Logger logger, final Logger debugLogger) {
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
    try {
      final String username = new WrapperLoginClientLoginStart(event).getUsername();
      if (username != null) {
        loggingIn.put(event.getUser(), username);
      }
      event.getUser().sendPacket(new WrapperLoginServerPluginRequest(
          MESSAGE_ID, CHANNEL, new byte[] {AccountTypePayload.FORMAT_VERSION}));
      debug("asked the proxy on " + CHANNEL + " for " + username);
    } catch (final RuntimeException | LinkageError failed) {
      debug("could not ask the proxy on " + CHANNEL + " (" + failed + ")");
    }
  }

  private void readAnswer(final PacketReceiveEvent event) {
    final WrapperLoginClientPluginResponse response = new WrapperLoginClientPluginResponse(event);
    if (response.getMessageId() != MESSAGE_ID) {
      return; // Someone else's exchange -- the seamless channel's, or the server's own forwarding.
    }

    // Ours, so the vanilla login handler must never see it: it did not send this request and would
    // disconnect the player over an unexpected message ID.
    event.setCancelled(true);

    final String username = loggingIn.remove(event.getUser());
    if (username == null) {
      debug("the proxy answered " + CHANNEL + ", but the login start that asked for it named no "
          + "player to file the answer under");
      return;
    }

    if (!response.isSuccessful()) {
      debug("the proxy declined " + CHANNEL + " for " + username + "; it is not a Velocity-CTD+, "
          + "or it is too old to know this channel");
      return;
    }

    final byte[] data = response.getData();
    if (data != null && data.length >= 1 && data[0] != AccountTypePayload.FORMAT_VERSION) {
      logger.warning("The proxy answered " + CHANNEL + " in format " + data[0] + ", which this "
          + "plugin does not understand. Update the plugin or the proxy.");
      return;
    }

    final AccountType type = AccountTypePayload.read(data);
    debug("the proxy says " + username + " is " + type);
    if (type != AccountType.UNKNOWN) {
      pending.put(key(username), type);
    }
  }

  /**
   * Takes what the proxy reported for a player.
   *
   * @param playerName the arriving player's name
   * @return the reported type, or {@link AccountType#UNKNOWN} if nothing answered
   */
  AccountType take(final String playerName) {
    final AccountType type = pending.remove(key(playerName));
    return type == null ? AccountType.UNKNOWN : type;
  }

  /**
   * Drops any recorded answer for a player whose login did not complete.
   *
   * @param playerName the player's name
   */
  void forget(final String playerName) {
    pending.remove(key(playerName));
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
