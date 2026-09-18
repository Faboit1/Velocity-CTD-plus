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
 * The payload the proxy sends on {@code velocityctd:seamless}: a format byte, then the entity ID
 * as a VarInt.
 *
 * <p>This is the contract between two artefacts that are built and shipped separately, so it lives
 * on its own and is tested on its own. A mismatch would not throw -- it would hand back a plausible
 * but wrong entity ID, which the proxy would then silently reject.</p>
 */
final class SeamlessPayload {

  /**
   * Payload format this plugin understands. The proxy sends this as the first byte.
   */
  static final byte FORMAT_VERSION = 1;

  /**
   * Returned when the payload cannot be read, or the proxy has no ID to preserve. Both mean the
   * same thing to the caller: join this player normally.
   */
  static final int NO_ENTITY_ID = 0;

  private SeamlessPayload() {
  }

  /**
   * Reads the entity ID out of a proxy response.
   *
   * @param data the response payload, or {@code null}
   * @return the entity ID, or {@link #NO_ENTITY_ID} if the payload is absent, too short, in a
   *         format this plugin does not know, or truncated mid-VarInt
   */
  static int readEntityId(final byte[] data) {
    if (data == null || data.length < 2 || data[0] != FORMAT_VERSION) {
      return NO_ENTITY_ID;
    }

    int result = 0;
    int shift = 0;
    for (int i = 1; i < data.length && shift < 32; i++) {
      final byte current = data[i];
      result |= (current & 0x7F) << shift;
      if ((current & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    // Ran off the end with the continuation bit still set: refuse rather than apply a
    // half-decoded ID to a real player.
    return NO_ENTITY_ID;
  }
}
