# VelocitySeamless

Backend companion plugin for Velocity-CTD+'s seamless switching. Its job is the loading screen on a
server switch; it can also remove the one on some teleports, where the packets allow it.

## What it does

**No loading screen when switching servers.** The proxy can withhold the destination's join game
and respawn packets so the client keeps the world it already has — but only if the destination
gives the player the entity ID their client already holds. The proxy knows that ID; the backend
cannot work it out for itself. This plugin asks for it during login and applies it before the
server writes the join packet.

**No loading screen when teleporting, where that is possible.** The terrain screen has two causes
and only one of them can be removed from here. The removable one is a Game Event packet whose
reason is *start waiting for level chunks*: the plugin drops it and supplies the acknowledgement
the server waits for, so the player is never held still.

The one that cannot be removed is a respawn packet. It tells the client to tear down its level, and
the screen goes up the instant it arrives, before any game event. Dropping the game event after it
does not take the screen away -- it cancels the chunk handshake, so the server stops treating the
player as still loading and the screen the respawn drew stays up *longer* while chunks arrive at
their leisure.

That case is the common one on Folia, which moves a player across a region boundary by respawning
them. So a long Folia teleport is not suppressible from here, the plugin detects that and leaves it
alone, and `hide-teleport-loading-screen` is off by default. A world-preserving server switch is
different: there the proxy withholds the join game packet, so the client is never told to rebuild
and the screen would be covering a world that never went away.

## Requirements

| | |
|---|---|
| Server | Paper 1.20.5+, Folia, or Canvas |
| Also install | [packetevents](https://modrinth.com/plugin/packetevents) |
| Proxy | Velocity-CTD+ with `keep-client-world-on-switch = true` |

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
   [VelocitySeamless] Entity ID reuse enabled; asking the proxy on velocityctd:seamless as each player logs in.
   [VelocitySeamless] Hiding the terrain loading screen on server switches only.
   ```

Both features can be switched off independently in `config.yml`.

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
3. **The screen was allowed through because the client was told to rebuild.** A join game or
   respawn preceded it, so the screen was already drawn and suppressing the request that follows
   would only make it last longer. On Folia that covers every region-crossing teleport.
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

The plugin sends a login plugin request on `velocityctd:seamless` carrying one byte, its format
version. The proxy replies with that format byte followed by the client's entity ID as a VarInt,
where `0` means "join this player normally". `SeamlessPayloadTest` pins that encoding, since a
mismatch between separately-shipped halves would not throw — it would quietly hand back a wrong ID.
