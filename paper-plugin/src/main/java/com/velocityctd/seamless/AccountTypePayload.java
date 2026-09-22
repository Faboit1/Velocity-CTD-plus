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
 * The payload the proxy sends on {@code velocityctd:accounttype}: a format byte, then the account
 * type as a byte.
 *
 * <p>Proxy and plugin are built and shipped separately, so the encoding lives on its own and is
 * tested on its own. Getting it wrong would not throw; it would report a confident, wrong answer
 * about whether a player owns the game, which is exactly the kind of thing other plugins go on to
 * gate purchases and ranks on.</p>
 */
final class AccountTypePayload {

  /**
   * Payload format this plugin understands. The proxy sends this as the first byte.
   */
  static final byte FORMAT_VERSION = 1;

  private AccountTypePayload() {
  }

  /**
   * Reads the account type out of a proxy response.
   *
   * @param data the response payload, or {@code null}
   * @return the reported type, or {@link AccountType#UNKNOWN} if the payload is absent, too short,
   *         in a format this plugin does not know, or names a type it has never heard of
   */
  static AccountType read(final byte[] data) {
    if (data == null || data.length < 2 || data[0] != FORMAT_VERSION) {
      return AccountType.UNKNOWN;
    }
    return AccountType.fromWireId(data[1]);
  }
}
