/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.velocitypowered.proxy.protection.antivpn;

import com.velocitypowered.proxy.config.VelocityConfiguration.AntiVpnConfig;
import com.velocitypowered.proxy.network.ConnectionManager;
import io.netty.util.concurrent.FastThreadLocalThread;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Blocks connections originating from VPNs, public proxies and datacentre ranges.
 *
 * <p>Two independent checks feed one verdict. The local check walks downloaded address feeds and
 * costs a hash lookup and at most a short trie walk, so it runs for every login. The online check
 * asks reputation APIs, which is far slower but catches addresses no public feed lists; it is
 * optional and cached.</p>
 *
 * <p>Both checks fail open. A feed that will not download, an API that times out and a malformed
 * response all mean "let the player in" -- the proxy refusing logins because a third-party list
 * host is down would be a worse outage than the one it is guarding against.</p>
 */
public final class AntiVpn {

  private static final Logger LOGGER = LogManager.getLogger(AntiVpn.class);

  private final ConnectionManager connectionManager;
  private final IpListLoader loader;

  private volatile AntiVpnConfig config;
  private volatile Set<String> whitelistedUsers;
  private volatile IpBlockList configuredWhitelist = IpBlockList.EMPTY;
  private volatile IpBlockList blockList = IpBlockList.EMPTY;
  private volatile IpBlockList feedWhitelist = IpBlockList.EMPTY;
  private volatile @Nullable OnlineVpnLookup onlineLookup;

  private final ScheduledExecutorService executor;

  /**
   * Creates the service. Nothing is downloaded until {@link #start()} is called.
   *
   * @param connectionManager used for the shared HTTP client backing online lookups
   * @param config the anti-VPN configuration
   * @param cacheDirectory where downloaded feeds are mirrored
   */
  public AntiVpn(final ConnectionManager connectionManager, final AntiVpnConfig config,
      final Path cacheDirectory) {
    this.connectionManager = connectionManager;
    this.loader = new IpListLoader(cacheDirectory);
    this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final Thread thread = new FastThreadLocalThread(runnable, "Velocity Anti-VPN Loader");
      thread.setDaemon(true);
      return thread;
    });
    applyConfig(config);
  }

  private void applyConfig(final AntiVpnConfig config) {
    this.config = config;
    this.whitelistedUsers = config.whitelistedUsers().stream()
        .map(name -> name.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());

    final IpBlockList.Builder whitelist = new IpBlockList.Builder();
    for (final String entry : config.whitelistedIps()) {
      if (!whitelist.add(entry)) {
        LOGGER.warn("Anti-VPN: ignoring unparseable whitelist entry '{}'", entry);
      }
    }
    this.configuredWhitelist = whitelist.build();

    this.onlineLookup = config.enabled() && config.onlineEnabled()
        ? new OnlineVpnLookup(connectionManager, config) : null;
  }

  /**
   * Returns whether the anti-VPN checks are switched on.
   *
   * @return {@code true} if enabled
   */
  public boolean isEnabled() {
    return config.enabled();
  }

  /**
   * Downloads the configured feeds and schedules periodic refreshes.
   */
  public void start() {
    if (!config.enabled()) {
      return;
    }
    executor.execute(this::reloadLists);

    final int minutes = config.refreshMinutes();
    if (minutes > 0) {
      executor.scheduleWithFixedDelay(this::reloadLists, minutes, minutes, TimeUnit.MINUTES);
    }
  }

  /**
   * Reloads the configuration and re-downloads every feed.
   *
   * @param config the new configuration
   */
  public void reload(final AntiVpnConfig config) {
    applyConfig(config);
    if (config.enabled()) {
      executor.execute(this::reloadLists);
    } else {
      blockList = IpBlockList.EMPTY;
      feedWhitelist = IpBlockList.EMPTY;
    }
  }

  private void reloadLists() {
    final AntiVpnConfig current = config;
    if (!current.enabled()) {
      return;
    }
    try {
      // Load into locals and publish each as a whole, so a lookup that happens mid-reload sees
      // either the old list or the new one, never a half-built one.
      final IpBlockList whitelist = loader.load(current.whitelistFeeds(), "whitelist");
      final IpBlockList blocked = loader.load(current.feeds(), "blocklist");
      this.feedWhitelist = whitelist;
      this.blockList = blocked;
    } catch (final Exception e) {
      LOGGER.error("Anti-VPN: failed to reload address lists, keeping the previous ones", e);
    }
  }

  /**
   * Shuts the loader down.
   */
  public void shutdown() {
    executor.shutdownNow();
  }

  /**
   * Decides whether a connecting player should be refused.
   *
   * @param remoteAddress the player's remote address
   * @param username the username from the login packet, used for the name whitelist
   * @return a future completing with {@code true} if the connection should be refused
   */
  public CompletableFuture<Boolean> shouldBlock(final @Nullable SocketAddress remoteAddress,
      final String username) {
    final AntiVpnConfig current = config;
    if (!current.enabled() || !(remoteAddress instanceof InetSocketAddress socketAddress)) {
      return CompletableFuture.completedFuture(false);
    }

    final InetAddress address = socketAddress.getAddress();
    if (address == null || isLocal(address)) {
      return CompletableFuture.completedFuture(false);
    }

    if (whitelistedUsers.contains(username.toLowerCase(Locale.ROOT))
        || configuredWhitelist.contains(address)
        || feedWhitelist.contains(address)) {
      return CompletableFuture.completedFuture(false);
    }

    if (blockList.contains(address)) {
      if (current.logBlocked()) {
        LOGGER.info("Anti-VPN: refused {} ({}), listed in a blocklist feed",
            username, address.getHostAddress());
      }
      return CompletableFuture.completedFuture(true);
    }

    final OnlineVpnLookup lookup = onlineLookup;
    if (lookup == null) {
      return CompletableFuture.completedFuture(false);
    }

    final String ip = address.getHostAddress();
    return lookup.isVpn(ip).thenApply(vpn -> {
      if (vpn && current.logBlocked()) {
        LOGGER.info("Anti-VPN: refused {} ({}), reported as a VPN by an online API", username, ip);
      }
      return vpn;
    }).exceptionally(throwable -> {
      LOGGER.debug("Anti-VPN: online lookup for {} failed", ip, throwable);
      return false;
    });
  }

  /**
   * Returns whether the address is one we should never block: a player connecting over loopback,
   * from the same LAN, or through a link-local address is not coming through a VPN provider.
   */
  private static boolean isLocal(final InetAddress address) {
    return address.isLoopbackAddress() || address.isAnyLocalAddress()
        || address.isSiteLocalAddress() || address.isLinkLocalAddress()
        || address.isMulticastAddress();
  }

  /**
   * Returns the number of entries in the active blocklist, for the status command.
   *
   * @return the blocklist entry count
   */
  public int blockListSize() {
    return blockList.size();
  }

  /**
   * Returns the number of entries in the active feed whitelist, for the status command.
   *
   * @return the whitelist entry count
   */
  public int whitelistSize() {
    return feedWhitelist.size() + configuredWhitelist.size();
  }
}
