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

/**
 * How a player got onto the server.
 *
 * <p>A backend server behind a proxy cannot work this out alone. It runs in offline mode by
 * definition -- it trusts the identity the proxy forwards to it -- so a paid account and a cracked
 * one arrive looking exactly alike. Each answer here therefore comes from whoever actually knows:
 * the proxy for {@link #PREMIUM} and {@link #OFFLINE}, Floodgate for {@link #BEDROCK}.</p>
 */
public enum AccountType {

  /**
   * Nobody who would know has said. The proxy is not a Velocity-CTD+, or it answered in a format
   * this plugin does not understand, or the player is not on the server any more.
   *
   * <p>This is not the same as {@link #OFFLINE}, and treating it as such is the easy mistake to
   * make here: it means the question went unanswered, not that it was answered "no".</p>
   */
  UNKNOWN(-1),

  /**
   * The player did not authenticate with Mojang. Either the proxy runs in offline mode, or a
   * plugin on it waved them through. Commonly called a cracked account.
   *
   * <p>The name and UUID such a player arrives with are whatever they typed, so nothing tied to a
   * real identity -- a purchase, a rank, a ban by UUID -- can be trusted for them.</p>
   */
  OFFLINE(0),

  /**
   * The player authenticated with Mojang's session servers, so they own the game and the name and
   * UUID they arrived with are genuinely theirs.
   */
  PREMIUM(1),

  /**
   * The player is on Bedrock Edition, connected through Geyser, and Floodgate confirmed it.
   *
   * <p>This is reported only when Floodgate itself says so. Their account is real -- it is an Xbox
   * account rather than a Mojang one -- but it is not a Java account, so a Java-side skin, a
   * Mojang UUID lookup, or anything assuming a Java client will not behave.</p>
   */
  BEDROCK(2);

  private final int wireId;

  AccountType(final int wireId) {
    this.wireId = wireId;
  }

  /**
   * Returns the value this type takes on the wire between proxy and plugin.
   *
   * @return the wire ID, or {@code -1} for {@link #UNKNOWN}, which is never sent
   */
  public int wireId() {
    return wireId;
  }

  /**
   * Reads a wire value back into a type.
   *
   * @param wireId the byte the proxy sent
   * @return the matching type, or {@link #UNKNOWN} if this plugin does not know that value, which
   *         is what a newer proxy reporting something new looks like from here
   */
  public static AccountType fromWireId(final int wireId) {
    for (final AccountType candidate : values()) {
      if (candidate != UNKNOWN && candidate.wireId == wireId) {
        return candidate;
      }
    }
    return UNKNOWN;
  }
}
