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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the wire contract with the proxy.
 *
 * <p>Proxy and plugin ship separately, and a mismatch here would not throw. It would report a
 * confident, wrong answer about whether someone owns the game -- and other plugins go on to gate
 * ranks and purchases on that -- so every byte the proxy can send is checked, along with every way
 * a payload can be wrong.</p>
 */
class AccountTypePayloadTest {

  /**
   * Shaped exactly as the proxy's {@code accountTypeHandshake} writes it: a format byte, then the
   * account type.
   */
  private static byte[] proxyPayload(final int formatVersion, final int accountType) {
    return new byte[] {(byte) formatVersion, (byte) accountType};
  }

  @Test
  void readsEveryTypeTheProxyCanSend() {
    // The two the proxy actually sends today. These numbers are the contract: changing either
    // silently mislabels every player on a server whose two halves were updated apart.
    assertEquals(AccountType.OFFLINE, AccountTypePayload.read(proxyPayload(1, 0)));
    assertEquals(AccountType.PREMIUM, AccountTypePayload.read(proxyPayload(1, 1)));
    // Reserved for a proxy that learns to recognise Bedrock itself. Today that answer comes from
    // Floodgate on this side, but the value is claimed so the two can never disagree about it.
    assertEquals(AccountType.BEDROCK, AccountTypePayload.read(proxyPayload(1, 2)));
  }

  @Test
  void treatsAnUnrecognisedTypeAsUnknown() {
    // A newer proxy reporting something this plugin has never heard of. It must not be guessed at,
    // and above all must not land on OFFLINE, which is what a zero-default would do.
    assertEquals(AccountType.UNKNOWN, AccountTypePayload.read(proxyPayload(1, 7)));
  }

  @Test
  void refusesPayloadsInAnotherFormat() {
    assertEquals(AccountType.UNKNOWN, AccountTypePayload.read(proxyPayload(2, 1)));
  }

  @Test
  void refusesAnAbsentOrTruncatedPayload() {
    assertEquals(AccountType.UNKNOWN, AccountTypePayload.read(null));
    assertEquals(AccountType.UNKNOWN, AccountTypePayload.read(new byte[0]));
    assertEquals(AccountType.UNKNOWN, AccountTypePayload.read(new byte[] {1}));
  }

  @Test
  void roundTripsEveryTypeThroughItsWireValue() {
    for (final AccountType type : AccountType.values()) {
      if (type == AccountType.UNKNOWN) {
        // Never sent, and its placeholder value must not be mistakeable for a real one.
        assertNotEquals(AccountType.UNKNOWN, AccountType.fromWireId(0));
        continue;
      }
      assertEquals(type, AccountType.fromWireId(type.wireId()));
    }
  }
}
