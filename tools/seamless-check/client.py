"""A 1.21.11 client that gets all the way into play, so the proxy issues a real join game.

Enough of the configuration handshake to be let through: client settings, an empty known-packs
answer, and the acknowledgement of finish configuration. Everything else is read and ignored.
"""
import socket, sys, threading, uuid as uuidmod
from mcproto import Reader, send, string, varint, read_string, read_varint_from

PROTOCOL = 774  # 1.21.11

# Configuration state, as the proxy's StateRegistry maps them for this version.
CB_FINISH_CONFIG, CB_KEEP_ALIVE, CB_KNOWN_PACKS = 0x03, 0x04, 0x0E
SB_CLIENT_SETTINGS, SB_FINISH_CONFIG, SB_KEEP_ALIVE, SB_KNOWN_PACKS = 0x00, 0x03, 0x04, 0x07

name = sys.argv[1] if len(sys.argv) > 1 else "TestPlayer"
offline = uuidmod.uuid3(uuidmod.UUID("00000000-0000-0000-0000-000000000000"), "OfflinePlayer:" + name)
hold = int(sys.argv[2]) if len(sys.argv) > 2 else 60

log = lambda m: print(f"[client] {m}", flush=True)
sock = socket.create_connection(("127.0.0.1", 25577), timeout=20)
send(sock, 0x00, varint(PROTOCOL) + string("localhost") + b"\x63\xd1" + varint(2))
send(sock, 0x00, string(name) + offline.bytes)
log(f"login start as {name}")

reader = Reader(sock)
sock.settimeout(hold)
state = "login"
try:
    while True:
        pid, payload = reader.read_packet()

        if state == "login":
            if pid == 0x00:
                log(f"login disconnect: {read_string(payload, 0)[0]}")
                break
            if pid == 0x02:
                log("login success -> acknowledging, entering configuration")
                send(sock, 0x03)
                state = "config"
                send(sock, SB_CLIENT_SETTINGS,
                     string("en_us") + bytes([8]) + varint(0) + b"\x01" + bytes([0x7F])
                     + varint(1) + b"\x00" + b"\x01" + varint(0))
            continue

        if state == "config":
            if pid == CB_KNOWN_PACKS:
                log("known packs -> answering with none")
                send(sock, SB_KNOWN_PACKS, varint(0))
            elif pid == CB_FINISH_CONFIG:
                log("finish configuration -> acknowledging, entering play")
                send(sock, SB_FINISH_CONFIG)
                state = "play"
            elif pid == CB_KEEP_ALIVE:
                send(sock, SB_KEEP_ALIVE, payload)
            elif pid == 0x02:
                log(f"config disconnect ({len(payload)} bytes)")
                break
            continue

        # Play, for 1.21.11: join game is 0x30, keep alive 0x2B in and 0x20 out.
        if pid == 0x30:
            entity_id = int.from_bytes(payload[:4], "big", signed=True)
            log(f"JOIN GAME: entity id {entity_id}")
        elif pid == 0x2B:
            send(sock, 0x20, payload)
except Exception as done:                                          # noqa: BLE001
    log(f"{type(done).__name__}: {done}")
