# VelocitySeamless

Backend companion plugin for Velocity-CTD+'s seamless switching. It removes the two loading
screens a player would otherwise sit through, and each half works on its own.

## What it does

**No loading screen when switching servers.** The proxy can withhold the destination's join game
and respawn packets so the client keeps the world it already has — but only if the destination
gives the player the entity ID their client already holds. The proxy knows that ID; the backend
cannot work it out for itself. This plugin asks for it during login and applies it before the
server writes the join packet.

**No loading screen when teleporting a long way.** The terrain screen is not a side effect of the
teleport: the server asks for it, with a Game Event packet whose reason is *start waiting for level
chunks*. It sends that whenever the destination chunks are not already on the client — on Folia,
every teleport that crosses into another region. The plugin drops that request, and supplies the
acknowledgement the server waits for on the client's behalf, so the player is never held still.

Moves into a *different* world are deliberately left alone. There the client really does have to
rebuild, and hiding the screen would only show the player an empty void while chunks stream in.

Telling the two apart is the whole job, and the packet type cannot do it: Folia moves a player
between regions by respawning them, so a cross-region teleport arrives as a respawn packet
indistinguishable in kind from a nether portal. What separates them is the world each one names, so
that is what the plugin compares -- the world the client was last placed in against the world the
new packet puts it in.

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
   [VelocitySeamless] Hiding the terrain loading screen on same-world teleports.
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
3. **The screen was allowed through as a world change.** The destination is a different world from
   the one the player left, which neither half of this tries to hide.
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
