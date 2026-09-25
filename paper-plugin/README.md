# VelocitySeamless

Backend companion plugin for Velocity-CTD+'s seamless switching. It removes the terrain loading
screen in the two places it is not telling the truth: a server switch that keeps the player's world,
and a teleport within one. It also answers a question a backend cannot answer for itself: how each
arriving player got in — with a real Minecraft account, without one, or from Bedrock Edition.

## What it does

**No loading screen when switching servers.** The proxy can withhold the destination's join game
and respawn packets so the client keeps the world it already has — but only if the destination
gives the player the entity ID their client already holds. The proxy knows that ID; the backend
cannot work it out for itself. This plugin asks for it during login and applies it before the
server writes the join packet.

**No loading screen when teleporting within a world.** Two packets put that screen up and both are
withheld. The *respawn packet* draws it — Folia moves a player across a region boundary by
respawning them, so on Folia a long teleport **is** a respawn, and nothing dropped afterwards takes
the screen away because it is already up. The *"start waiting for level chunks" game event* then
keeps it up, and the server holds the player still until the client reports it has loaded. Withhold
both, answer the server's wait on the client's behalf, and the client keeps the level it has: no
screen at all.

A respawn is only withheld when it would not have changed anything the client holds — the same
world, and the server asking for all player data to be kept. A different world, or a respawn that
resets the player such as after death, passes through untouched and keeps its screen: there the
client genuinely has to rebuild, and the screen ends soonest by letting the chunk handshake run.
Suppressing only the game event in that case is the worst of both, since the screen stays and the
handshake that would have ended it is gone.

The cost of the fast path is that the server believes the player finished loading as soon as they
are moved, so chunks stream in without holding them still. That is the point, and on a slow
connection it means briefly walking over terrain that has not arrived yet.

## Account types

A backend behind a proxy runs in offline mode by definition — it trusts whatever identity the
proxy forwards — so on its own it cannot tell a paid account from a cracked one. The proxy can, and
this plugin asks it during login, on `velocityctd:accounttype`. Bedrock players are recognised
separately, by asking Floodgate or Geyser through their own API.

Other plugins read the result through `AccountTypes`. Add `VelocitySeamless` to your plugin's
`softdepend` and call it from anywhere:

```java
import com.velocityctd.seamless.AccountType;
import com.velocityctd.seamless.AccountTypes;

switch (AccountTypes.of(player)) {
  case PREMIUM -> // owns the game; their UUID and name are genuinely theirs
  case OFFLINE -> // cracked account; trust nothing tied to identity
  case BEDROCK -> // Bedrock Edition through Geyser, confirmed by Floodgate
  case UNKNOWN -> // nothing authoritative answered; do not assume
}
```

There is also `AccountTypes.isPremium(player)` and `AccountTypes.isBedrock(player)`.

Each player's type is settled during `PlayerLoginEvent` at `LOWEST` priority, so it is already
available to every later login listener, to `PlayerJoinEvent`, and to everything after.
`/accounttype [player]` reports it for someone online, along with where Bedrock detection is coming
from.

Two things are worth being careful about:

- **`UNKNOWN` means the question went unanswered, not that the answer was no.** Gate on `PREMIUM`
  rather than testing for `OFFLINE`, so that a proxy which never replied fails closed.
- **Bedrock players are neither premium nor cracked.** They authenticate with Xbox, not Mojang, so
  the proxy quite correctly reports them as offline and Floodgate's answer overrides it. Without
  Floodgate or Geyser on the backend they stay `OFFLINE`.

No guessing is done from the shape of a UUID. The usual shortcut — calling a UUID Bedrock because
its top half is zero — is a guess about a Floodgate internal, and it is wrong in both directions:
it misses a Bedrock player whose account has been linked to a Java one, and it can catch a Java
player whose UUID was assigned by something else.

## Requirements

| | |
|---|---|
| Server | Paper 1.20.5+, Folia, or Canvas |
| Also install | [packetevents](https://modrinth.com/plugin/packetevents) |
| Proxy | Velocity-CTD+ with `keep-client-world-on-switch = true` |
| Optional | [Floodgate](https://geysermc.org) or Geyser, to recognise Bedrock players |

Paper 1.20.5 is the floor because the entity ID is set through the server's own internals, which
only became reachable by name once Paper moved to Mojang mappings — which is also why this needs no
version-specific build and works unchanged on Folia and Canvas.

The teleport half needs 1.21.2+ to be useful, since that is when the client/server "player loaded"
handshake was introduced.

## Setup

1. Install `packetevents` and this plugin on every backend.
2. In the proxy's `velocity.toml`:
   ```toml
   remove-reconfig = true
   keep-client-world-on-switch = true
   ```
3. Restart. The plugin logs what it enabled:
   ```
   [VelocitySeamless] Reporting account types; asking the proxy on velocityctd:accounttype as each player logs in. Bedrock detection: unavailable, no Floodgate or Geyser on this server.
   [VelocitySeamless] Entity ID reuse enabled; asking the proxy on velocityctd:seamless as each player logs in.
   [VelocitySeamless] Removing the terrain loading screen on server switches, and on teleports that keep the player's world.
   ```

Every feature can be switched off independently in `config.yml`.

## When a loading screen still appears

Debug logging is on by default while this feature is still being proven in the field. The plugin
logs, for every arriving player and every loading request, what it decided and why -- whether the proxy had an entity ID to reuse, and
whether the request was suppressed or deliberately allowed through. That turns "it still flashes"
into a line naming the cause. Set `debug: false` once your setup is confirmed working.

A line naming the cause beats guessing, but these are the usual ones:

1. **Nothing answered `velocityctd:seamless`.** The proxy is not a Velocity-CTD+, or
   `keep-client-world-on-switch` is not actually on in its `velocity.toml`. Adding the proxy jar
   does not add the key to an existing config -- an absent key reads as off.
2. **The proxy had no entity ID to reuse.** Expected on a first join, since there is no previous
   world to keep. If you see it on a `/server` switch, check the player really moved between two
   backends rather than reconnecting.
3. **The screen was allowed through because the client really is rebuilding.** The respawn named a
   different world, or did not keep all player data — a death, say. Those keep their screen by
   design; suppressing only the game event there makes it last longer, not shorter.
4. `packetevents` is not installed, so this plugin never loaded.

## Caveats

- **All backends must run the same Minecraft version.** With `remove-reconfig` on, the client keeps
  the registry data it got from the server it first joined; a backend on another protocol version
  will desync it.
- **Scoreboard objectives, teams and the player list header/footer are not cleared** on a switch,
  because the configuration state that normally wipes them is skipped. Have your backends set or
  clear those on join.

## How it degrades

Nothing here can break a login or a teleport. If the proxy does not answer, answers in a format the
plugin does not know, is not a Velocity-CTD+, or has the feature off, no entity ID is recorded and
the player joins normally. If the server internals cannot be resolved, the plugin says so at
startup and offers only the teleport half. In every one of those cases the cost is a loading
screen, not a broken session — and the proxy independently re-checks the entity ID and dimension
before it withholds anything.

## Protocol

Two login plugin requests, each carrying one byte — its format version — and each answered by the
proxy in the same format:

| Channel | Answer |
|---|---|
| `velocityctd:seamless` | the format byte, then the client's entity ID as a VarInt, where `0` means "join this player normally" |
| `velocityctd:accounttype` | the format byte, then the account type: `0` offline, `1` premium, `2` reserved for a proxy that learns to recognise Bedrock itself |

`SeamlessPayloadTest` and `AccountTypePayloadTest` pin those encodings, since a mismatch between
separately-shipped halves would not throw — it would quietly hand back a wrong entity ID, or a
confident wrong answer about whether someone owns the game.

`tools/seamless-check/backend.py` in the proxy repository stands in for this plugin and prints what
a proxy answers on both channels, without needing a Minecraft client.
