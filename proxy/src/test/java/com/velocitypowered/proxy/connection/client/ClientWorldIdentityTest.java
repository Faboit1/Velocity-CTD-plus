/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.velocitypowered.proxy.connection.client;

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_21_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.velocitypowered.proxy.connection.registry.DimensionInfo;
import org.junit.jupiter.api.Test;

/**
 * Pins what counts as "the same level" to a client, which is what decides whether the proxy may
 * withhold a respawn and spare the player a loading screen.
 */
class ClientWorldIdentityTest {

  private static String keyOf(String dimensionType, String worldName) {
    return ClientPlaySessionHandler.dimensionKey(
        new DimensionInfo(dimensionType, worldName, false, false, MINECRAFT_1_21_9), 0);
  }

  @Test
  void treatsTwoWorldsOfOneTypeAsTheSameLevel() {
    // A client's level is built from the dimension type, so two worlds sharing one are identical
    // to it bar which chunks arrive. Moving between them needs no rebuild and no loading screen.
    assertEquals(keyOf("minecraft:overworld", "minecraft:world"),
        keyOf("minecraft:overworld", "minecraft:world_the_second"));
  }

  @Test
  void keepsRealDimensionChangesApart() {
    // Not merely cosmetic: the overworld is 384 blocks tall and the nether 256, so a client still
    // holding the overworld would read the wrong number of sections out of every nether chunk.
    assertNotEquals(keyOf("minecraft:overworld", "minecraft:world"),
        keyOf("minecraft:the_nether", "minecraft:world_nether"));
    assertNotEquals(keyOf("minecraft:the_nether", "minecraft:world_nether"),
        keyOf("minecraft:the_end", "minecraft:world_the_end"));
  }
}
