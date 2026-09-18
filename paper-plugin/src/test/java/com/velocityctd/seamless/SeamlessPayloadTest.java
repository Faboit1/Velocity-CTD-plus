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

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire contract with the proxy.
 *
 * <p>The two halves of this feature are built and shipped separately, and a mismatch here would not
 * throw -- it would quietly hand back a wrong entity ID, the proxy's own check would reject it, and
 * players would keep seeing a loading screen with nothing in any log to say why. So the encoding is
 * tested against bytes written the way the proxy writes them.</p>
 */
class SeamlessPayloadTest {

  /**
   * Encodes exactly as {@code ProtocolUtils.writeVarInt} does on the proxy side.
   */
  private static byte[] proxyPayload(final int entityId) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(1); // format version
    int value = entityId;
    while (true) {
      if ((value & ~0x7F) == 0) {
        out.write(value);
        return out.toByteArray();
      }
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
  }

  private static int read(final int entityId) {
    return SeamlessPayload.readEntityId(proxyPayload(entityId));
  }

  @Test
  void readsSingleByteIds() {
    assertEquals(1, read(1));
    assertEquals(42, read(42));
    assertEquals(127, read(127));
  }

  @Test
  void readsMultiByteIds() {
    // Entity IDs on a long-running server comfortably exceed a single VarInt byte.
    assertEquals(128, read(128));
    assertEquals(300, read(300));
    assertEquals(16384, read(16384));
    assertEquals(2097152, read(2097152));
    assertEquals(Integer.MAX_VALUE, read(Integer.MAX_VALUE));
  }

  @Test
  void treatsTruncatedPayloadAsNoId() {
    // Every byte has its continuation bit set and then the payload ends: refuse rather than
    // returning a half-decoded ID that would be applied to a real player.
    assertEquals(0, SeamlessPayload.readEntityId(new byte[] {1, (byte) 0x80}));
    assertEquals(0, SeamlessPayload.readEntityId(new byte[] {1}));
  }

  @Test
  void readsZeroAsNoId() {
    // The proxy sends zero to mean "join normally", so it must survive the round trip as zero.
    assertEquals(0, read(0));
  }
}
