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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Downloads public proxy/VPN address feeds and turns them into an {@link IpBlockList}.
 *
 * <p>Feeds come in many shapes -- bare addresses, {@code address:port} proxy lists, CIDR ranges,
 * JSON -- so entries are pulled out by pattern rather than by parsing a specific format. Each feed
 * is mirrored to disk as it is read, and a feed that fails to download falls back to that mirror,
 * so a restart while a feed host is down does not silently leave the proxy unprotected.</p>
 */
final class IpListLoader {

  private static final Logger LOGGER = LogManager.getLogger(IpListLoader.class);

  /**
   * Matches a dotted quad with an optional prefix length. The trailing {@code (?![\d.])} stops a
   * longer dotted string (a version number, or the first four groups of a five-group token) from
   * matching its leading quad.
   */
  private static final Pattern IPV4 = Pattern.compile(
      "(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
          + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(/\\d{1,2})?(?![\\d.])");

  /**
   * Matches a reasonably conservative IPv6 form: at least two groups and a {@code ::} or four
   * groups, optionally followed by a prefix length.
   */
  private static final Pattern IPV6 = Pattern.compile(
      "(?<![\\w:])(?:[0-9A-Fa-f]{1,4}:){2,7}(?::|[0-9A-Fa-f]{1,4})(/\\d{1,3})?(?![\\w:])");

  private final HttpClient httpClient;
  private final Path cacheDirectory;

  IpListLoader(final Path cacheDirectory) {
    this.cacheDirectory = cacheDirectory;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * Loads every URL into a single list.
   *
   * @param urls the feeds to load
   * @param label a short name for this set of feeds, used in log messages
   * @return the built list, which is {@link IpBlockList#EMPTY} if nothing could be loaded
   */
  IpBlockList load(final List<String> urls, final String label) {
    if (urls.isEmpty()) {
      return IpBlockList.EMPTY;
    }

    try {
      Files.createDirectories(cacheDirectory);
    } catch (final IOException e) {
      LOGGER.warn("Anti-VPN: could not create cache directory {}", cacheDirectory, e);
    }

    final long started = System.nanoTime();
    final IpBlockList.Builder builder = new IpBlockList.Builder();
    int failed = 0;

    for (final String url : urls) {
      if (Thread.currentThread().isInterrupted()) {
        LOGGER.debug("Anti-VPN: {} load interrupted", label);
        break;
      }
      try {
        download(url, builder);
      } catch (final IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          break;
        }
        final int fromCache = readCache(url, builder);
        if (fromCache < 0) {
          failed++;
          LOGGER.warn("Anti-VPN: {} feed {} failed ({}) and has no cached copy",
              label, url, e.getMessage());
        } else {
          LOGGER.info("Anti-VPN: {} feed {} failed ({}), used cached copy",
              label, url, e.getMessage());
        }
      }
    }

    final IpBlockList list = builder.build();
    final long millis = (System.nanoTime() - started) / 1_000_000L;
    LOGGER.info("Anti-VPN: loaded {} {} entries from {} feeds in {} ms{}",
        list.size(), label, urls.size(), millis,
        failed > 0 ? " (" + failed + " unavailable)" : "");
    return list;
  }

  private void download(final String url, final IpBlockList.Builder builder)
      throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(2))
        .header("User-Agent", "Velocity-CTD-AntiVPN")
        .GET()
        .build();

    final HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() / 100 != 2) {
      response.body().close();
      throw new IOException("HTTP " + response.statusCode());
    }

    // Write the normalised entries to a temporary file and move it into place only once the whole
    // feed has been read, so an interrupted download cannot truncate a good cached copy.
    final Path cacheFile = cacheFileFor(url);
    final Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");

    try (InputStream body = response.body();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(body, StandardCharsets.UTF_8), 1 << 16);
        BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("interrupted while reading " + url);
        }
        extract(line, builder, writer);
      }
    } catch (final IOException | InterruptedException e) {
      Files.deleteIfExists(temporary);
      throw e;
    }

    Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Reads the cached copy of a feed.
   *
   * @return the number of entries added, or {@code -1} if there is no cached copy
   */
  private int readCache(final String url, final IpBlockList.Builder builder) {
    final Path cacheFile = cacheFileFor(url);
    if (!Files.isReadable(cacheFile)) {
      return -1;
    }
    final int before = builder.size();
    try (BufferedReader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        builder.add(line);
      }
    } catch (final IOException e) {
      LOGGER.warn("Anti-VPN: could not read cached feed {}", cacheFile, e);
      return -1;
    }
    return builder.size() - before;
  }

  private static void extract(final String line, final IpBlockList.Builder builder,
      final BufferedWriter writer) throws IOException {
    if (line.isEmpty() || line.charAt(0) == '#') {
      return;
    }

    final Matcher v4 = IPV4.matcher(line);
    while (v4.find()) {
      write(v4.group(), builder, writer);
    }

    // Only bother with the IPv6 pattern when the line could plausibly contain one.
    if (line.indexOf(':') >= 0) {
      final Matcher v6 = IPV6.matcher(line);
      while (v6.find()) {
        write(v6.group(), builder, writer);
      }
    }
  }

  private static void write(final String entry, final IpBlockList.Builder builder,
      final BufferedWriter writer) throws IOException {
    if (builder.add(entry)) {
      writer.write(entry);
      writer.newLine();
    }
  }

  private Path cacheFileFor(final String url) {
    final StringBuilder name = new StringBuilder(url.length() + 4);
    for (int i = 0; i < url.length(); i++) {
      final char c = url.charAt(i);
      name.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' ? c : '_');
    }
    // Keep the name bounded and unique even when two long URLs share a prefix.
    if (name.length() > 100) {
      name.setLength(100);
    }
    name.append('-').append(Integer.toHexString(url.hashCode())).append(".txt");
    return cacheDirectory.resolve(name.toString());
  }
}
