"""Stands in for a backend running VelocitySeamless: asks the proxy what the plugin asks it.

Two login-phase questions, both on their real channels with their real message IDs:

  velocityctd:seamless     -- which entity ID does this client already hold?
  velocityctd:accounttype  -- did this player authenticate with Mojang?

Usage: python3 backend.py [port]   (default 25599)
"""
import socket, sys, threading
from mcproto import Reader, send, string, varint, read_string, read_varint_from

SEAMLESS_CHANNEL = "velocityctd:seamless"
SEAMLESS_MESSAGE_ID = 0x5EA11E55

ACCOUNT_TYPE_CHANNEL = "velocityctd:accounttype"
ACCOUNT_TYPE_MESSAGE_ID = 0x0ACC7B4E

FORMAT_VERSION = 1

ACCOUNT_TYPES = {0: "OFFLINE", 1: "PREMIUM", 2: "BEDROCK"}


def read_seamless(data, log):
    if len(data) >= 2:
        entity_id, _ = read_varint_from(data, 1)
        log(f"VERDICT [{SEAMLESS_CHANNEL}]: ANSWERED, format={data[0]} entityId={entity_id}")
    else:
        log(f"VERDICT [{SEAMLESS_CHANNEL}]: answered but payload too short: {data.hex()}")


def read_account_type(data, log):
    if len(data) >= 2:
        name = ACCOUNT_TYPES.get(data[1], f"unrecognised({data[1]})")
        log(f"VERDICT [{ACCOUNT_TYPE_CHANNEL}]: ANSWERED, format={data[0]} accountType={name}")
    else:
        log(f"VERDICT [{ACCOUNT_TYPE_CHANNEL}]: answered but payload too short: {data.hex()}")


QUESTIONS = {
    SEAMLESS_MESSAGE_ID: (SEAMLESS_CHANNEL, read_seamless),
    ACCOUNT_TYPE_MESSAGE_ID: (ACCOUNT_TYPE_CHANNEL, read_account_type),
}


def serve(conn, addr):
    log = lambda m: print(f"[backend] {m}", flush=True)
    reader = Reader(conn)
    outstanding = set(QUESTIONS)
    try:
        pid, payload = reader.read_packet()          # handshake
        protocol, off = read_varint_from(payload, 0)
        host, off = read_string(payload, off)
        log(f"handshake: protocol={protocol} host={host!r}")

        pid, payload = reader.read_packet()          # login start
        name, _ = read_string(payload, 0)
        log(f"login start: name={name!r} (packet id {pid})")

        # What the plugin does at LOGIN_START: ask everything it needs before the player joins.
        for message_id, (channel, _) in QUESTIONS.items():
            body = varint(message_id) + string(channel) + bytes([FORMAT_VERSION])
            send(conn, 0x04, body)
            log(f"sent login plugin request on {channel} as message {message_id}")

        conn.settimeout(10)
        while outstanding:
            pid, payload = reader.read_packet()
            if pid != 0x02:
                log(f"unexpected packet id 0x{pid:02x} ({len(payload)} bytes)")
                continue
            message_id, off = read_varint_from(payload, 0)
            successful = payload[off] if off < len(payload) else 0
            data = payload[off + 1:]
            log(f"login plugin RESPONSE: message={message_id} successful={bool(successful)} "
                f"data={data.hex() or '(none)'}")
            if message_id not in outstanding:
                continue
            outstanding.discard(message_id)
            channel, interpret = QUESTIONS[message_id]
            if not successful:
                log(f"VERDICT [{channel}]: the proxy DECLINED the channel")
            else:
                interpret(data, log)
    except (EOFError, socket.timeout) as done:
        for message_id in outstanding:
            log(f"VERDICT [{QUESTIONS[message_id][0]}]: no response")
        log(f"finished -- {type(done).__name__}")
    except Exception as failed:                                    # noqa: BLE001
        log(f"error: {failed!r}")
    finally:
        conn.close()


server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25599
server.bind(("127.0.0.1", PORT))
server.listen(5)
print(f"[backend] listening on 127.0.0.1:{PORT}", flush=True)
while True:
    conn, addr = server.accept()
    threading.Thread(target=serve, args=(conn, addr), daemon=True).start()
