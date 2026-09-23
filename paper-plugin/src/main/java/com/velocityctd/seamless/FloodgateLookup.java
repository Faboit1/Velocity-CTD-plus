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

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Asks Floodgate, or failing that Geyser, whether a player is on Bedrock Edition.
 *
 * <p>Both publish an API for exactly this question, and either is a far better authority than
 * anything this plugin could work out for itself. The usual shortcut -- calling a UUID Bedrock
 * because its top half is zero -- is a guess about an internal detail of Floodgate's UUID
 * allocation, and it is wrong in both directions: it misses a Bedrock player whose account has been
 * linked to a Java one, and it can catch a Java player whose UUID was assigned by something else.
 * So no guessing happens here. If neither API is installed, the answer is simply "not known", and
 * the player keeps whatever the proxy reported.</p>
 *
 * <p>The APIs are reached by reflection rather than compiled against. That keeps this jar buildable
 * and loadable with no Geyser artefacts anywhere near it, which matters because the overwhelming
 * majority of servers running this plugin have no Bedrock support at all.</p>
 */
final class FloodgateLookup {

  /**
   * Ways to ask, in the order they are tried. Each is a class with a no-argument static accessor
   * returning the API instance, and an instance method taking a UUID and returning a boolean.
   *
   * <p>Floodgate comes first because it is the one that answers for a Bedrock player connecting
   * through a separate Geyser instance; Geyser's own API only knows about sessions it is hosting
   * itself.</p>
   */
  private static final String[][] CANDIDATES = {
      {"org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer"},
      {"org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer"},
  };

  private final Logger logger;

  /**
   * The resolved API instance, or null if none was found. Resolved once, on the first question, so
   * that a Geyser or Floodgate plugin enabling after this one is still picked up.
   */
  private Object api;

  /**
   * The resolved lookup method, bound to {@link #api}.
   */
  private Method query;

  private boolean resolved;

  private String source = "nothing";

  FloodgateLookup(final Logger logger) {
    this.logger = logger;
  }

  private synchronized void resolve() {
    if (resolved) {
      return;
    }
    resolved = true;
    for (final String[] candidate : CANDIDATES) {
      try {
        final Class<?> apiClass = Class.forName(candidate[0]);
        final Object instance = apiClass.getMethod(candidate[1]).invoke(null);
        if (instance == null) {
          continue;
        }
        final Method method = apiClass.getMethod(candidate[2], UUID.class);
        this.api = instance;
        this.query = method;
        this.source = candidate[0];
        return;
      } catch (final ClassNotFoundException absent) {
        // Not installed. By far the common case, and not worth a line in the log.
      } catch (final ReflectiveOperationException | RuntimeException | LinkageError broken) {
        // Installed but not shaped as expected -- a version that moved or renamed its API. Say so
        // once: the symptom otherwise is Bedrock players silently reported as offline accounts.
        logger.warning("Found " + candidate[0] + " but could not use it to identify Bedrock "
            + "players (" + broken + "). Bedrock players will be reported as whatever the proxy "
            + "says they are.");
      }
    }
  }

  /**
   * Says whether some Bedrock-aware API was found.
   *
   * @return {@code true} if a Bedrock question can be answered at all
   */
  boolean isAvailable() {
    resolve();
    return query != null;
  }

  /**
   * Names the API being used, for logging.
   *
   * @return the class name of the API in use, or {@code "nothing"}
   */
  String source() {
    resolve();
    return source;
  }

  /**
   * Asks whether a player is on Bedrock Edition.
   *
   * @param uuid the player's UUID as this server knows it
   * @return {@code true} only if a Bedrock-aware API is installed and says yes
   */
  boolean isBedrockPlayer(final UUID uuid) {
    resolve();
    if (query == null || uuid == null) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(query.invoke(api, uuid));
    } catch (final ReflectiveOperationException | RuntimeException | LinkageError failed) {
      logger.warning("Asking " + source + " about " + uuid + " failed (" + failed + ").");
      return false;
    }
  }
}
