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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.velocitypowered.proxy.connection.registry.DimensionInfo;
import org.junit.jupiter.api.Test;

/**
 * Pins what counts as "the same level" to a client, which is what decides whether the proxy may
 * withhold a respawn and spare the player a loading screen.
 *
 * <p>Getting this wrong in the permissive direction is not a missing optimisation, it is a broken
 * session: a withheld nether portal leaves the client reading 16-section nether chunks as a
 * 384-block overworld, and it hangs on the terrain screen forever.</p>
 */
class ClientWorldIdentityTest {

  /**
   * Builds a key the way a respawn packet from the given era would.
   *
   * @param typeIdentifier the dimension type as a string, empty from 1.20.5 where it is a registry
   *                       id instead
   * @param worldName      the level name
   * @param typeId         the dimension type as a registry id, used from 1.20.5
   * @return the level identity
   */
  private static String keyOf(String typeIdentifier, String worldName, int typeId) {
    return ClientPlaySessionHandler.dimensionKey(
        new DimensionInfo(typeIdentifier, worldName, false, false, MINECRAFT_1_21_9), typeId);
  }

  @Test
  void distinguishesWorldsWhenTheTypeIsSentAsRegistryId() {
    // The shape every version from 1.20.5 sends: no type string at all, just a registry id and a
    // level name. A key built only from the identifier compares "" against "" and calls the nether
    // the overworld, which is exactly how this broke.
    assertNotEquals(keyOf("", "minecraft:overworld", 0),
        keyOf("", "minecraft:the_nether", 1));
  }

  @Test
  void keepsRealDimensionChangesApart() {
    // The overworld is 384 blocks tall and the nether 256, so a client still holding the overworld
    // would read the wrong number of sections out of every nether chunk. Pre-1.20.5 shape, where
    // the type does arrive as a string.
    assertNotEquals(keyOf("minecraft:overworld", "minecraft:world", 0),
        keyOf("minecraft:the_nether", "minecraft:world_nether", 0));
    assertNotEquals(keyOf("minecraft:the_nether", "minecraft:world_nether", 0),
        keyOf("minecraft:the_end", "minecraft:world_the_end", 0));
  }

  @Test
  void keepsSeparateWorldsApartEvenWithinOneType() {
    // Deliberately stricter than a client requires: two overworld-type worlds are interchangeable
    // to it, and are still refused. The cost is a loading screen nobody needed; the cost of the
    // opposite mistake is a session that never finishes loading.
    assertNotEquals(keyOf("", "minecraft:world", 0), keyOf("", "minecraft:world_the_second", 0));
  }
}
