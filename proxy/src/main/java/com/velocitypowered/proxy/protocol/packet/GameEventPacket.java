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

package com.velocitypowered.proxy.protocol.packet;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import io.netty.buffer.ByteBuf;

/**
 * A grab-bag of small state changes a server tells a client about, identified by a single event
 * byte with one float argument. Rain and thunder levels, the demo message, the end credits.
 *
 * <p>The proxy decodes it for one of them: {@link #START_WAITING_FOR_LEVEL_CHUNKS}, which is what
 * puts the "Loading terrain" screen on a client's screen.</p>
 */
public class GameEventPacket implements MinecraftPacket {

  /**
   * Tells the client to wait for the chunks around it, showing the terrain loading screen until
   * they arrive. From 1.21.2 the server also holds the player still until the client answers with
   * a {@link ServerboundPlayerLoadedPacket}.
   */
  public static final byte START_WAITING_FOR_LEVEL_CHUNKS = 13;

  private byte event;
  private float value;

  public GameEventPacket() {
  }

  public GameEventPacket(byte event, float value) {
    this.event = event;
    this.value = value;
  }

  public byte getEvent() {
    return event;
  }

  public float getValue() {
    return value;
  }

  @Override
  public void decode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    this.event = buf.readByte();
    this.value = buf.readFloat();
  }

  @Override
  public void encode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    buf.writeByte(this.event);
    buf.writeFloat(this.value);
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    return handler.handle(this);
  }

  @Override
  public String toString() {
    return "GameEventPacket{event=" + event + ", value=" + value + '}';
  }
}
