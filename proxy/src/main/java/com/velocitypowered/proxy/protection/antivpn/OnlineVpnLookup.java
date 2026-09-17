/*
 * Copyright (C) 2026 Velocity-CTD Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it is useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protection.antivpn;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.proxy.config.VelocityConfiguration.AntiVpnConfig;
import com.velocitypowered.proxy.network.ConnectionManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Asks third-party reputation APIs whether an address belongs to a VPN, proxy or hosting provider.
 *
 * <p>This is the slow, accurate half of the anti-VPN checks. Results are cached so a returning
 * player costs nothing, calls are spread across the configured APIs so no single free tier is
 * exhausted, and the number of checks in flight is capped so a connection flood cannot turn into
 * an outbound request flood.</p>
 *
 * <p>Every failure mode -- timeout, rate limit, malformed response, too many checks in flight --
 * resolves to "not a VPN". An unreachable reputation API must never become a reason players cannot
 * join.</p>
 */
final class OnlineVpnLookup {

  private static final Logger LOGGER = LogManager.getLogger(OnlineVpnLookup.class);

  /**
   * Flags that, when truthy anywhere in the response, mean the address is a VPN or proxy.
   */
  private static final String[] POSITIVE_FLAGS = new String[] {
      "proxy", "vpn", "is_vpn", "is_proxy", "is_datacenter", "hosting", "tor", "is_tor",
  };

  private final ConnectionManager connectionManager;
  private final List<String> apis;
  private final int timeoutMillis;
  private final int maxConcurrent;
  private final Cache<String, Boolean> results;
  private final AtomicInteger apiCursor = new AtomicInteger();
  private final AtomicInteger inFlight = new AtomicInteger();

  OnlineVpnLookup(final ConnectionManager connectionManager, final AntiVpnConfig config) {
    this.connectionManager = connectionManager;
    this.apis = List.copyOf(config.onlineApis());
    this.timeoutMillis = config.onlineTimeoutMillis();
    this.maxConcurrent = config.onlineMaxConcurrent();
    this.results = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(config.onlineCacheMinutes()))
        .maximumSize(config.onlineCacheSize())
        .build();
  }

  /**
   * Looks up an address, consulting the cache first.
   *
   * @param ip the textual address to look up
   * @return a future that completes with {@code true} only if an API positively identified the
   *         address as a VPN, proxy or hosting address
   */
  CompletableFuture<Boolean> isVpn(final String ip) {
    if (apis.isEmpty()) {
      return CompletableFuture.completedFuture(false);
    }

    final Boolean cached = results.getIfPresent(ip);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }

    if (inFlight.get() >= maxConcurrent) {
      // Under a flood we would rather let players in than queue up an unbounded pile of outbound
      // requests. The local block list is still doing its job.
      LOGGER.debug("Anti-VPN: skipping online check for {}, {} already in flight", ip, maxConcurrent);
      return CompletableFuture.completedFuture(false);
    }

    final String url = apis.get(Math.floorMod(apiCursor.getAndIncrement(), apis.size()))
        .replace("{ip}", ip);

    final SimpleHttpRequest request = SimpleRequestBuilder.get(url)
        .setHeader("User-Agent", "Velocity-CTD-AntiVPN")
        .setHeader("Accept", "application/json")
        .build();
    request.setConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMillis))
        .setResponseTimeout(Timeout.ofMilliseconds(timeoutMillis))
        .build());

    inFlight.incrementAndGet();
    return connectionManager.sendAsync(request)
        .orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        .handle((response, throwable) -> {
          inFlight.decrementAndGet();
          if (throwable != null) {
            LOGGER.debug("Anti-VPN: online check for {} failed: {}", ip, throwable.toString());
            return false;
          }
          if (response.getCode() / 100 != 2) {
            LOGGER.debug("Anti-VPN: online check for {} returned HTTP {}", ip, response.getCode());
            return false;
          }
          final boolean vpn = parse(response.getBodyText());
          results.put(ip, vpn);
          return vpn;
        });
  }

  /**
   * Decides whether a reputation API response marks the address as a VPN or proxy.
   *
   * <p>The supported APIs disagree about shape: some answer at the top level, some nest the
   * answer under the queried address, and some use strings where others use booleans. Rather than
   * hard-coding each one, this walks the response and looks for any of the known flags.</p>
   *
   * @param body the JSON response body
   * @return {@code true} if any known flag is set
   */
  static boolean parse(final String body) {
    if (body == null || body.isEmpty()) {
      return false;
    }
    try {
      final JsonElement root = JsonParser.parseString(body);
      return root.isJsonObject() && scan(root.getAsJsonObject(), 0);
    } catch (final Exception e) {
      LOGGER.debug("Anti-VPN: could not parse API response", e);
      return false;
    }
  }

  private static boolean scan(final JsonObject object, final int depth) {
    for (final String flag : POSITIVE_FLAGS) {
      if (isTruthy(object.get(flag))) {
        return true;
      }
    }

    // proxycheck.io and ipapi.is describe hosting ranges via a nested "type": "hosting".
    if (isHosting(object.get("company")) || isHosting(object.get("asn"))) {
      return true;
    }

    // Responses that key the answer by the queried address need one more level; cap the descent so
    // a deeply nested or hostile response cannot cost us much.
    if (depth < 2) {
      for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
        if (entry.getValue().isJsonObject() && scan(entry.getValue().getAsJsonObject(), depth + 1)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isHosting(final JsonElement element) {
    if (element == null || !element.isJsonObject()) {
      return false;
    }
    final JsonElement type = element.getAsJsonObject().get("type");
    return type != null && type.isJsonPrimitive()
        && "hosting".equalsIgnoreCase(type.getAsString());
  }

  private static boolean isTruthy(final JsonElement element) {
    if (element == null || !element.isJsonPrimitive()) {
      return false;
    }
    final var primitive = element.getAsJsonPrimitive();
    if (primitive.isBoolean()) {
      return primitive.getAsBoolean();
    }
    if (primitive.isNumber()) {
      return primitive.getAsInt() != 0;
    }
    final String value = primitive.getAsString();
    return "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)
        || "1".equals(value);
  }
}
