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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class IpBlockListTest {

  private static InetAddress address(final String text) throws UnknownHostException {
    return InetAddress.getByName(text);
  }

  private static IpBlockList listOf(final String... entries) {
    final IpBlockList.Builder builder = new IpBlockList.Builder();
    for (final String entry : entries) {
      builder.add(entry);
    }
    return builder.build();
  }

  @Test
  void emptyListMatchesNothing() throws Exception {
    assertFalse(IpBlockList.EMPTY.contains(address("1.2.3.4")));
    assertTrue(IpBlockList.EMPTY.isEmpty());
  }

  @Test
  void matchesExactIpv4Address() throws Exception {
    final IpBlockList list = listOf("1.2.3.4", "8.8.8.8");
    assertEquals(2, list.size());
    assertTrue(list.contains(address("1.2.3.4")));
    assertTrue(list.contains(address("8.8.8.8")));
    assertFalse(list.contains(address("1.2.3.5")));
  }

  @Test
  void matchesIpv4Cidr() throws Exception {
    final IpBlockList list = listOf("10.20.0.0/16");
    assertTrue(list.contains(address("10.20.0.1")));
    assertTrue(list.contains(address("10.20.255.254")));
    assertFalse(list.contains(address("10.21.0.1")));
  }

  @Test
  void cidrWithHostBitsSetStillCoversTheWholeRange() throws Exception {
    // Feeds are not always careful to zero the host bits.
    final IpBlockList list = listOf("192.0.2.77/24");
    assertTrue(list.contains(address("192.0.2.1")));
    assertTrue(list.contains(address("192.0.2.254")));
    assertFalse(list.contains(address("192.0.3.1")));
  }

  @Test
  void rejectsOverlyBroadPrefixes() throws Exception {
    // A feed containing 0.0.0.0/0 must not lock every player out.
    final IpBlockList list = listOf("0.0.0.0/0", "1.0.0.0/4", "::/0");
    assertTrue(list.isEmpty());
    assertFalse(list.contains(address("1.2.3.4")));
  }

  @Test
  void ignoresMalformedEntries() throws Exception {
    final IpBlockList.Builder builder = new IpBlockList.Builder();
    assertFalse(builder.add("not an address"));
    assertFalse(builder.add("999.1.1.1"));
    assertFalse(builder.add("1.2.3"));
    assertFalse(builder.add("1.2.3.4.5"));
    assertFalse(builder.add("1.2.3.4/x"));
    assertFalse(builder.add("1.2.3.4/33"));
    assertFalse(builder.add(""));
    assertTrue(builder.build().isEmpty());
  }

  @Test
  void matchesIpv6() throws Exception {
    final IpBlockList list = listOf("2001:db8::1", "2001:db8:1::/48");
    assertTrue(list.contains(address("2001:db8::1")));
    assertTrue(list.contains(address("2001:db8:1::dead:beef")));
    assertFalse(list.contains(address("2001:db8:2::1")));
  }

  @Test
  void ipv4AndIpv6EntriesDoNotCrossMatch() throws Exception {
    final IpBlockList list = listOf("2001:db8::/32");
    assertFalse(list.contains(address("1.2.3.4")));
  }

  @Test
  void parsesDottedQuadsWithoutResolving() {
    assertArrayEquals(new byte[] {1, 2, 3, 4}, IpBlockList.parseIpv4("1.2.3.4"));
    assertArrayEquals(new byte[] {(byte) 255, 0, 0, 1}, IpBlockList.parseIpv4("255.0.0.1"));
    assertNull(IpBlockList.parseIpv4("1.2.3."));
    assertNull(IpBlockList.parseIpv4(".1.2.3"));
    assertNull(IpBlockList.parseIpv4("1.2.3.256"));
    assertNull(IpBlockList.parseIpv4("example.com"));
  }

  @Test
  void rejectsHostnamesAsIpv6() {
    assertNull(IpBlockList.parseIpv6("example.com"));
    assertNull(IpBlockList.parseIpv6("not:a:host name"));
  }
}
