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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /accounttype [player]} -- reports what the proxy and Floodgate said about a player.
 *
 * <p>Exists because this feature is otherwise invisible. Everything it produces is consumed by
 * other plugins through {@link AccountTypes}, so without a way to ask out loud, a misconfiguration
 * -- an old proxy, a channel name that does not match, Floodgate absent -- looks exactly like a
 * server where every player happens to be offline.</p>
 */
final class AccountTypeCommand implements CommandExecutor, TabCompleter {

  private final FloodgateLookup floodgate;

  AccountTypeCommand(final FloodgateLookup floodgate) {
    this.floodgate = floodgate;
  }

  @Override
  public boolean onCommand(final CommandSender sender, final Command command, final String label,
      final String[] args) {
    if (args.length > 1) {
      sender.sendMessage("Usage: /" + label + " [player]");
      return true;
    }

    final Player target;
    if (args.length == 1) {
      target = Bukkit.getPlayerExact(args[0]);
      if (target == null) {
        sender.sendMessage("No player named " + args[0] + " is online.");
        return true;
      }
    } else if (sender instanceof Player self) {
      target = self;
    } else {
      sender.sendMessage("Usage: /" + label + " <player>");
      return true;
    }

    final AccountType type = AccountTypes.of(target);
    sender.sendMessage(target.getName() + ": " + describe(type));
    if (type == AccountType.UNKNOWN) {
      sender.sendMessage("Nothing authoritative answered. The proxy is not a Velocity-CTD+ with "
          + "this build, or it is not answering on " + AccountTypeChannel.channel() + ".");
    }
    sender.sendMessage("Bedrock detection: " + (floodgate.isAvailable()
        ? "using " + floodgate.source()
        : "unavailable -- install Floodgate or Geyser on this server to detect Bedrock players"));
    return true;
  }

  private static String describe(final AccountType type) {
    return switch (type) {
      case PREMIUM -> "PREMIUM -- a real Minecraft account, authenticated with Mojang";
      case OFFLINE -> "OFFLINE -- not authenticated; a cracked account";
      case BEDROCK -> "BEDROCK -- Bedrock Edition, through Geyser";
      case UNKNOWN -> "UNKNOWN -- nobody who would know has said";
    };
  }

  @Override
  public List<String> onTabComplete(final CommandSender sender, final Command command,
      final String label, final String[] args) {
    if (args.length != 1) {
      return Collections.emptyList();
    }
    final String prefix = args[0].toLowerCase(Locale.ROOT);
    final List<String> names = new ArrayList<>();
    for (final Player online : Bukkit.getOnlinePlayers()) {
      if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
        names.add(online.getName());
      }
    }
    return names;
  }
}
