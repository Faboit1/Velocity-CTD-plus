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

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import org.junit.jupiter.api.Test;

/**
 * Pins that the proxy can <em>read</em> a respawn packet coming back from a backend.
 *
 * <p>It has to, for {@code keep-client-world-on-switch} to be correct. A backend moves a player
 * between dimensions without any server switch -- a nether portal, a {@code /world} command -- and
 * the proxy only learns of it from this packet. Miss it and the dimension the proxy holds is
 * whatever the last join game said, which means a player who walked into the nether and then
 * switched servers would be told to keep a world they are no longer in.</p>
 *
 * <p>This is easy to get silently wrong in two ways, and it has been wrong both ways before. A
 * packet registered encode-only is still written correctly and still has a handler, but that
 * handler is never called, so the feature does nothing and nothing complains. And an id that is
 * right for one version is not right for the next, with no error either way: the wrong packet is
 * simply decoded as this one.</p>
 */
class RespawnPacketDecodingTest {

  private static MinecraftPacket clientboundPlay(ProtocolVersion version, int id) {
    return StateRegistry.PLAY.getProtocolRegistry(CLIENTBOUND, version).createPacket(id);
  }

  @Test
  void decodesRespawnsFromTheBackend() {
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_1_20_3, 0x45));
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_1_21_9, 0x50));
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_26_1, 0x52));
    assertInstanceOf(RespawnPacket.class, clientboundPlay(MINECRAFT_26_2, 0x52));
  }
}
