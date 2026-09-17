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

package com.velocitypowered.proxy.util.concurrent;

import static com.google.common.base.Preconditions.checkNotNull;

import io.netty.util.concurrent.FastThreadLocalThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Factory to create threads for the Netty event loop groups.
 */
public class VelocityNettyThreadFactory implements ThreadFactory {

  private static final Logger LOGGER = LogManager.getLogger(VelocityNettyThreadFactory.class);

  /**
   * Shared handler so a thread that dies to an unexpected throwable leaves a trace instead of
   * disappearing silently. Static, so it costs nothing per thread.
   */
  private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER =
      (thread, throwable) ->
          LOGGER.error("Uncaught exception in thread {}", thread.getName(), throwable);

  private final AtomicInteger threadNumber = new AtomicInteger();

  /**
   * The part of the name format before the {@code %d}, precomputed so {@link #newThread} can
   * concatenate instead of running {@link String#format}.
   */
  private final String namePrefix;

  /**
   * The part of the name format after the {@code %d}, usually empty.
   */
  private final String nameSuffix;

  /**
   * Creates a factory naming its threads after {@code nameFormat}.
   *
   * @param nameFormat the thread name format, which must contain a single {@code %d}
   */
  public VelocityNettyThreadFactory(String nameFormat) {
    checkNotNull(nameFormat, "nameFormat");
    final int placeholder = nameFormat.indexOf("%d");
    if (placeholder < 0) {
      throw new IllegalArgumentException("nameFormat must contain a %d placeholder");
    }
    this.namePrefix = nameFormat.substring(0, placeholder);
    this.nameSuffix = nameFormat.substring(placeholder + 2);
  }

  @Override
  public Thread newThread(@NotNull Runnable r) {
    final String name = nameSuffix.isEmpty()
        ? namePrefix + threadNumber.getAndIncrement()
        : namePrefix + threadNumber.getAndIncrement() + nameSuffix;
    // Hand the runnable to the constructor rather than wrapping it in an anonymous subclass:
    // one fewer class to load and one fewer object per thread.
    final Thread thread = new FastThreadLocalThread(r, name);
    thread.setUncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER);
    return thread;
  }
}
