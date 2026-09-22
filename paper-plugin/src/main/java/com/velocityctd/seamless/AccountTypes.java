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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * How each online player got onto this server: with a real Minecraft account, without one, or from
 * Bedrock Edition through Geyser.
 *
 * <p>This is the half of the feature other plugins use. Add VelocitySeamless to your plugin's
 * {@code softdepend} and call it from anywhere:</p>
 *
 * <pre>{@code
 * switch (AccountTypes.of(player)) {
 *   case PREMIUM -> // owns the game; their UUID and name are genuinely theirs
 *   case OFFLINE -> // cracked account; trust nothing tied to identity
 *   case BEDROCK -> // Bedrock Edition via Geyser
 *   case UNKNOWN -> // nothing authoritative answered; do not assume
 * }
 * }</pre>
 *
 * <p>Each player's type is settled before they enter the world -- during
 * {@link org.bukkit.event.player.PlayerLoginEvent}, at
 * {@link org.bukkit.event.EventPriority#LOWEST} -- so it is already available to every later login
 * listener, to {@code PlayerJoinEvent}, and to anything running afterwards. Asking about a player
 * who is not online, or asking before that point, gives {@link AccountType#UNKNOWN}.</p>
 *
 * <p>{@link AccountType#UNKNOWN} means the question went unanswered, not that the answer was no.
 * Code that gates something on a real account should require {@link AccountType#PREMIUM} rather
 * than test for {@link AccountType#OFFLINE}, so that a proxy which never answered fails
 * closed.</p>
 */
public final class AccountTypes {

  private static final Map<UUID, AccountType> TYPES = new ConcurrentHashMap<>();

  private AccountTypes() {
  }

  /**
   * Returns how a player got onto this server.
   *
   * @param player the player, which may be {@code null}
   * @return their account type, or {@link AccountType#UNKNOWN} if it is not known
   */
  public static @NotNull AccountType of(final OfflinePlayer player) {
    return player == null ? AccountType.UNKNOWN : of(player.getUniqueId());
  }

  /**
   * Returns how the player with a given UUID got onto this server.
   *
   * @param uuid the player's UUID on this server, which may be {@code null}
   * @return their account type, or {@link AccountType#UNKNOWN} if it is not known
   */
  public static @NotNull AccountType of(final UUID uuid) {
    if (uuid == null) {
      return AccountType.UNKNOWN;
    }
    final AccountType type = TYPES.get(uuid);
    return type == null ? AccountType.UNKNOWN : type;
  }

  /**
   * Says whether a player owns the game.
   *
   * <p>Deliberately false when nothing answered: something gated on a real account should stay
   * shut rather than swing open because the proxy did not reply.</p>
   *
   * @param player the player, which may be {@code null}
   * @return {@code true} only if the proxy confirmed a Mojang-authenticated account
   */
  public static boolean isPremium(final Player player) {
    return of(player) == AccountType.PREMIUM;
  }

  /**
   * Says whether a player is on Bedrock Edition.
   *
   * @param player the player, which may be {@code null}
   * @return {@code true} only if Floodgate or Geyser confirmed it
   */
  public static boolean isBedrock(final Player player) {
    return of(player) == AccountType.BEDROCK;
  }

  /**
   * Records a resolved type. Called by the plugin as the player logs in.
   *
   * @param uuid the player's UUID
   * @param type the resolved type
   */
  static void record(final UUID uuid, final AccountType type) {
    if (uuid != null && type != AccountType.UNKNOWN) {
      TYPES.put(uuid, type);
    }
  }

  /**
   * Drops a player's recorded type when they leave.
   *
   * @param uuid the player's UUID
   */
  static void forget(final UUID uuid) {
    if (uuid != null) {
      TYPES.remove(uuid);
    }
  }

  /**
   * Drops every recorded type, so a disabled plugin leaves nothing behind to answer with.
   */
  static void clear() {
    TYPES.clear();
  }
}
