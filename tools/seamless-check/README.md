# seamless-check

Checks that a proxy answers the `velocityctd:seamless` login handshake, and that the entity ID it
reports on a switch is the one the client actually holds — without a Minecraft client.

That gap is why the feature shipped broken twice. Both halves of the handshake degrade silently by
design: a proxy that does not answer and a proxy that answers "nothing to preserve" produce the
same absence of an entity ID, and the same ordinary join. Nothing throws, so nothing surfaces until
someone reports a loading screen that should not be there.

`backend.py` stands in for a backend running VelocitySeamless: it takes a proxy's login connection,
asks the same question on the same channel, and prints what came back. `client.py` is just enough
of a 1.21.11 client to make the proxy connect to a backend at all — login, configuration, and the
keep-alives that stop it being timed out.

## Checking that the proxy answers

```
python3 backend.py 25599          # stand-in backend, "lobby" in velocity.toml
java -jar velocity.jar            # keep-client-world-on-switch = true, compression-threshold = -1
python3 client.py Faboit
```

```
[backend] sent login plugin request on velocityctd:seamless as message 1587617365
[backend] login plugin RESPONSE: message=1587617365 successful=True data=0100
[backend] VERDICT: the proxy ANSWERED, format=1 entityId=0
```

Entity ID 0 is right here: a first join has no previous world to keep.

## Checking a switch

For a non-zero ID the proxy has to have seen the player join somewhere first, so this needs a real
backend and somewhere to move them to. Point `lobby` at a real server, add a second entry for
`backend.py`, and put both in `try` so an unexpected disconnect fails over:

```toml
lobby  = "127.0.0.1:25599"    # a real Paper/Folia server
lobby2 = "127.0.0.1:25598"    # backend.py 25598
try = ["lobby", "lobby2"]
```

Connect with `client.py`, note the entity ID the real server assigns, then stop it. The proxy moves
the player to `lobby2`, which reports what the proxy offered the destination:

```
Faboit[...] logged in with entity id 1        # the real backend
[backend] VERDICT: the proxy ANSWERED, format=1 entityId=1
```

Those two numbers matching is the whole mechanism: the destination can now give the player the
entity ID their client already holds, so the proxy can withhold the join game packet and the client
keeps the world it has.

## Limits

This proves the proxy's half. It cannot tell you whether a screen appeared — that needs a real
client — and it does not exercise the backend plugin, which has its own debug logging for that.
Set `compression-threshold = -1` while using it; the scripts speak no compression.
