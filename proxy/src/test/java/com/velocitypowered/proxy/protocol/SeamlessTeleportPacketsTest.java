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

package com.velocitypowered.proxy.protocol;

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_20_3;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_21_9;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_26_1;
import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_26_2;
import static com.velocitypowered.proxy.protocol.ProtocolUtils.Direction.CLIENTBOUND;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.packet.GameEventPacket;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import org.junit.jupiter.api.Test;

/**
 * Pins the two packets the proxy has to <em>read</em> from a backend to remove the terrain screen
 * on a teleport.
 *
 * <p>Both are easy to get silently wrong. A packet registered encode-only is still written
 * correctly and still has a handler, but that handler is never called, so the feature does nothing
 * and nothing complains -- which is exactly how the first attempt at this failed. And an id that
 * is right for one version is not right for the next, with no error either way: the wrong packet
 * is simply decoded as this one.</p>
 */
class SeamlessTeleportPacketsTest {

  private static MinecraftPacket clientboundPlay(ProtocolVersion version, int id) {
    return StateRegistry.PLAY.getProtocolRegistry(CLIENTBOUND, version).createPacket(id);
  }

  @Test
  void decodesTheRequestToWaitForChunks() {
    assertInstanceOf(GameEventPacket.class, clientboundPlay(MINECRAFT_1_20_3, 0x20));
    assertInstanceOf(GameEventPacket.class, clientboundPlay(MINECRAFT_1_21_9, 0x26));
    assertInstanceOf(GameEventPacket.class, clientboundPlay(MINECRAFT_26_1, 0x26));
    assertInstanceOf(GameEventPacket.class, clientboundPlay(MINECRAFT_26_2, 0x26));
  }

  @Test
  void decodesRespawnsFromTheBackend() {
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_1_20_3, 0x45));
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_1_21_9, 0x50));
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_26_1, 0x52));
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_26_2, 0x52));
  }

  @Test
  void leavesTheGameEventAloneAbove26Point2() {
    // 26.3 moved ids somewhere between 0x20 and 0x2c and this one's new value is not known, so it
    // is deliberately unregistered there rather than guessed at. If this starts failing because
    // the id was added, that is the moment to extend the mapping.
    assertNull(clientboundPlay(ProtocolVersion.MINECRAFT_26_3, 0x26));
  }
}
