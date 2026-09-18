/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.proxy.player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the entity ID a client currently uses for its own player.
 *
 * <p>A seamless, world-preserving server switch requires the destination backend to give the
 * player the entity ID their client already holds. The backend cannot work that ID out on its own,
 * so a coordinating plugin reads it here and passes it along -- for example in the plugin message
 * it already sends to tell the backend where a player is going -- and the backend then applies it
 * before the player is added to the world.</p>
 *
 * <p>When the ID does not match, the proxy falls back to an ordinary switch, so a plugin that gets
 * this wrong costs the player a loading screen rather than a broken session.</p>
 */
public final class ClientWorldSwitches {

  private static final ConcurrentHashMap<UUID, Integer> CLIENT_ENTITY_IDS = new ConcurrentHashMap<>();

  private ClientWorldSwitches() {
  }

  /**
   * Returns the entity ID the client currently uses for {@code playerId}.
   *
   * @param playerId the player whose client entity ID is wanted
   * @return the entity ID the client holds, or {@code 0} if the player has not finished joining
   */
  public static int clientEntityId(UUID playerId) {
    return CLIENT_ENTITY_IDS.getOrDefault(playerId, 0);
  }

  /**
   * Records the entity ID most recently presented to a client.
   *
   * <p>Called by the proxy. Plugins want {@link #clientEntityId(UUID)}.</p>
   *
   * @param playerId the client that received the ID
   * @param entityId the entity ID from the join game packet it was sent
   */
  public static void rememberClientEntityId(UUID playerId, int entityId) {
    if (entityId > 0) {
      CLIENT_ENTITY_IDS.put(playerId, entityId);
    }
  }

  /**
   * Drops the recorded state for a disconnected player.
   *
   * <p>Called by the proxy.</p>
   *
   * @param playerId the disconnected player
   */
  public static void forget(UUID playerId) {
    CLIENT_ENTITY_IDS.remove(playerId);
  }
}
