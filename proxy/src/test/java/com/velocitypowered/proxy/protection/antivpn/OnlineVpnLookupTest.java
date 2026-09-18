/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.velocitypowered.proxy.protection.antivpn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OnlineVpnLookupTest {

  @Test
  void detectsTopLevelBooleanFlag() {
    assertTrue(OnlineVpnLookup.parse("{\"vpn\": true}"));
    assertTrue(OnlineVpnLookup.parse("{\"is_datacenter\": true}"));
    assertFalse(OnlineVpnLookup.parse("{\"vpn\": false}"));
  }

  @Test
  void detectsProxycheckStyleNestedYesNo() {
    // proxycheck.io keys the answer by the queried address and answers with "yes"/"no".
    assertTrue(OnlineVpnLookup.parse(
        "{\"status\":\"ok\",\"1.2.3.4\":{\"proxy\":\"yes\",\"type\":\"VPN\"}}"));
    assertFalse(OnlineVpnLookup.parse(
        "{\"status\":\"ok\",\"1.2.3.4\":{\"proxy\":\"no\",\"country\":\"Germany\"}}"));
  }

  @Test
  void detectsHostingCompanyType() {
    assertTrue(OnlineVpnLookup.parse(
        "{\"company\":{\"name\":\"Some Host\",\"type\":\"hosting\"}}"));
    assertTrue(OnlineVpnLookup.parse("{\"asn\":{\"asn\":1234,\"type\":\"hosting\"}}"));
    assertFalse(OnlineVpnLookup.parse(
        "{\"company\":{\"name\":\"Some ISP\",\"type\":\"isp\"}}"));
  }

  @Test
  void treatsNumericFlagsAsBooleans() {
    assertTrue(OnlineVpnLookup.parse("{\"proxy\": 1}"));
    assertFalse(OnlineVpnLookup.parse("{\"proxy\": 0}"));
  }

  @Test
  void failsClosedToAllowingOnGarbage() {
    // Anything we cannot make sense of must mean "not a VPN", never "block the player".
    assertFalse(OnlineVpnLookup.parse(""));
    assertFalse(OnlineVpnLookup.parse(null));
    assertFalse(OnlineVpnLookup.parse("not json at all"));
    assertFalse(OnlineVpnLookup.parse("[1, 2, 3]"));
    assertFalse(OnlineVpnLookup.parse("{\"error\":\"rate limited\"}"));
    assertFalse(OnlineVpnLookup.parse("<html>502 Bad Gateway</html>"));
  }
}
