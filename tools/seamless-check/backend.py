"""Stands in for a backend running VelocitySeamless: asks the proxy on velocityctd:seamless.

Usage: python3 backend.py [port]   (default 25599)
"""
import socket, sys, threading
from mcproto import Reader, send, string, varint, read_string, read_varint_from

CHANNEL = "velocityctd:seamless"
MESSAGE_ID = 0x5EA11E55
FORMAT_VERSION = 1


def serve(conn, addr):
    log = lambda m: print(f"[backend] {m}", flush=True)
    reader = Reader(conn)
    try:
        pid, payload = reader.read_packet()          # handshake
        protocol, off = read_varint_from(payload, 0)
        host, off = read_string(payload, off)
        log(f"handshake: protocol={protocol} host={host!r}")

        pid, payload = reader.read_packet()          # login start
        name, _ = read_string(payload, 0)
        log(f"login start: name={name!r} (packet id {pid})")

        # What the plugin does at LOGIN_START: ask the proxy for the client's entity ID.
        body = varint(MESSAGE_ID) + string(CHANNEL) + bytes([FORMAT_VERSION])
        send(conn, 0x04, body)
        log(f"sent login plugin request on {CHANNEL} as message {MESSAGE_ID}")

        conn.settimeout(10)
        while True:
            pid, payload = reader.read_packet()
            if pid != 0x02:
                log(f"unexpected packet id 0x{pid:02x} ({len(payload)} bytes)")
                continue
            message_id, off = read_varint_from(payload, 0)
            successful = payload[off] if off < len(payload) else 0
            data = payload[off + 1:]
            log(f"login plugin RESPONSE: message={message_id} successful={bool(successful)} "
                f"data={data.hex() or '(none)'}")
            if message_id == MESSAGE_ID:
                if not successful:
                    log("VERDICT: the proxy DECLINED the channel")
                elif len(data) >= 2:
                    entity_id, _ = read_varint_from(data, 1)
                    log(f"VERDICT: the proxy ANSWERED, format={data[0]} entityId={entity_id}")
                else:
                    log(f"VERDICT: answered but payload too short: {data.hex()}")
                return
    except (EOFError, socket.timeout) as done:
        log(f"VERDICT: no response -- {type(done).__name__}")
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
