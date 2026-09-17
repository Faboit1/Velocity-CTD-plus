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

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.net.Inet4Address;
import java.net.InetAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An immutable set of IPv4/IPv6 addresses and CIDR ranges, built once and then only read.
 *
 * <p>Public proxy and VPN feeds are overwhelmingly bare addresses rather than ranges, so plain
 * addresses go into a primitive hash set and only genuine prefixes go into a bitwise trie. Storing
 * a few million single addresses as 32-deep trie paths would cost hundreds of megabytes; this way
 * they cost four bytes each.</p>
 *
 * <p>Instances are built by a {@link Builder} on the loader thread and then published as a whole.
 * Readers never see a partially loaded list, so no locking is needed on the lookup path.</p>
 */
public final class IpBlockList {

  private static final Logger LOGGER = LogManager.getLogger(IpBlockList.class);

  /**
   * An empty list, used before the first load completes and when nothing is configured.
   */
  public static final IpBlockList EMPTY = new IpBlockList(new IntOpenHashSet(0), null, null, 0);

  /**
   * Prefixes broader than these are rejected. A single bad line in a third-party feed saying
   * {@code 0.0.0.0/0} would otherwise lock every player out of the proxy.
   */
  private static final int MIN_PREFIX_V4 = 8;
  private static final int MIN_PREFIX_V6 = 16;

  private final IntOpenHashSet exactV4;
  private final @Nullable Node v4Root;
  private final @Nullable Node v6Root;
  private final int size;

  private IpBlockList(final IntOpenHashSet exactV4, final @Nullable Node v4Root,
      final @Nullable Node v6Root, final int size) {
    this.exactV4 = exactV4;
    this.v4Root = v4Root;
    this.v6Root = v6Root;
    this.size = size;
  }

  /**
   * Returns the number of entries that were accepted into this list.
   *
   * @return the entry count
   */
  public int size() {
    return size;
  }

  /**
   * Returns whether this list has no entries at all.
   *
   * @return {@code true} if empty
   */
  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Tests whether an address is covered by this list.
   *
   * @param address the address to test
   * @return {@code true} if the address is listed, or falls inside a listed range
   */
  public boolean contains(final InetAddress address) {
    if (size == 0) {
      return false;
    }

    final byte[] octets = address.getAddress();
    if (address instanceof Inet4Address) {
      final int bits = ((octets[0] & 0xFF) << 24) | ((octets[1] & 0xFF) << 16)
          | ((octets[2] & 0xFF) << 8) | (octets[3] & 0xFF);
      return exactV4.contains(bits) || matches(v4Root, octets, 32);
    }
    return matches(v6Root, octets, 128);
  }

  private static boolean matches(final @Nullable Node root, final byte[] address, final int bits) {
    Node node = root;
    if (node == null) {
      return false;
    }
    // A terminal root would be a /0, which the builder refuses, but check anyway so the walk below
    // can assume every terminal it sees is a real prefix.
    if (node.terminal) {
      return true;
    }
    for (int i = 0; i < bits; i++) {
      final int bit = (address[i >>> 3] >> (7 - (i & 7))) & 1;
      node = bit == 0 ? node.zero : node.one;
      if (node == null) {
        return false;
      }
      if (node.terminal) {
        return true;
      }
    }
    return false;
  }

  private static final class Node {
    private @Nullable Node zero;
    private @Nullable Node one;
    private boolean terminal;
  }

  /**
   * Accumulates entries for an {@link IpBlockList}. Not thread-safe; build on one thread and
   * publish the result.
   */
  public static final class Builder {

    private final IntOpenHashSet exactV4 = new IntOpenHashSet();
    private @Nullable Node v4Root;
    private @Nullable Node v6Root;
    private int size;
    private int rejected;

    /**
     * Adds an address or CIDR range, for example {@code 1.2.3.4} or {@code 10.0.0.0/24}.
     *
     * <p>Malformed and implausibly broad entries are counted and dropped rather than thrown,
     * because these lists come from third parties and a single bad line should not abort a
     * load.</p>
     *
     * @param entry the address or range
     * @return {@code true} if the entry was accepted
     */
    public boolean add(final String entry) {
      final String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        return false;
      }

      final int slash = trimmed.indexOf('/');
      final String addressPart = slash < 0 ? trimmed : trimmed.substring(0, slash);
      final boolean ipv6 = addressPart.indexOf(':') >= 0;

      int prefix;
      if (slash < 0) {
        prefix = ipv6 ? 128 : 32;
      } else {
        try {
          prefix = Integer.parseInt(trimmed.substring(slash + 1));
        } catch (final NumberFormatException e) {
          rejected++;
          return false;
        }
      }

      final byte[] octets = ipv6 ? parseIpv6(addressPart) : parseIpv4(addressPart);
      if (octets == null) {
        rejected++;
        return false;
      }

      final int maxPrefix = octets.length * 8;
      if (prefix < 0 || prefix > maxPrefix) {
        rejected++;
        return false;
      }
      if (prefix < (octets.length == 4 ? MIN_PREFIX_V4 : MIN_PREFIX_V6)) {
        rejected++;
        return false;
      }

      if (octets.length == 4 && prefix == 32) {
        final int bits = ((octets[0] & 0xFF) << 24) | ((octets[1] & 0xFF) << 16)
            | ((octets[2] & 0xFF) << 8) | (octets[3] & 0xFF);
        if (exactV4.add(bits)) {
          size++;
        }
        return true;
      }

      if (octets.length == 4) {
        if (v4Root == null) {
          v4Root = new Node();
        }
        insert(v4Root, octets, prefix);
      } else {
        if (v6Root == null) {
          v6Root = new Node();
        }
        insert(v6Root, octets, prefix);
      }
      size++;
      return true;
    }

    private static void insert(final Node root, final byte[] address, final int prefix) {
      Node node = root;
      for (int i = 0; i < prefix; i++) {
        final int bit = (address[i >>> 3] >> (7 - (i & 7))) & 1;
        if (bit == 0) {
          if (node.zero == null) {
            node.zero = new Node();
          }
          node = node.zero;
        } else {
          if (node.one == null) {
            node.one = new Node();
          }
          node = node.one;
        }
      }
      node.terminal = true;
    }

    /**
     * Returns the number of entries accepted so far.
     *
     * @return the accepted entry count
     */
    public int size() {
      return size;
    }

    /**
     * Builds the immutable list.
     *
     * @return the finished list
     */
    public IpBlockList build() {
      if (rejected > 0) {
        LOGGER.debug("Anti-VPN: dropped {} malformed or overly broad list entries", rejected);
      }
      if (size == 0) {
        return EMPTY;
      }
      exactV4.trim();
      return new IpBlockList(exactV4, v4Root, v6Root, size);
    }
  }

  /**
   * Parses a dotted-quad IPv4 literal without touching the resolver.
   *
   * @param text the literal to parse
   * @return the four octets, or {@code null} if {@code text} is not a dotted quad
   */
  static byte @Nullable [] parseIpv4(final String text) {
    final byte[] out = new byte[4];
    int octet = 0;
    int value = -1;
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '.') {
        if (value < 0 || octet == 3) {
          return null;
        }
        out[octet++] = (byte) value;
        value = -1;
      } else if (c >= '0' && c <= '9') {
        value = (value < 0 ? 0 : value) * 10 + (c - '0');
        if (value > 255) {
          return null;
        }
      } else {
        return null;
      }
    }
    if (octet != 3 || value < 0) {
      return null;
    }
    out[3] = (byte) value;
    return out;
  }

  /**
   * Parses an IPv6 literal. Only hex digits, colons and an embedded IPv4 tail are accepted, so
   * this never triggers a DNS lookup.
   *
   * @param text the literal to parse
   * @return the sixteen octets, or {@code null} if {@code text} is not an IPv6 literal
   */
  static byte @Nullable [] parseIpv6(final String text) {
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      final boolean allowed = c == ':' || c == '.'
          || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!allowed) {
        return null;
      }
    }
    try {
      final byte[] octets = InetAddress.getByName(text).getAddress();
      return octets.length == 16 ? octets : null;
    } catch (final Exception e) {
      return null;
    }
  }
}
