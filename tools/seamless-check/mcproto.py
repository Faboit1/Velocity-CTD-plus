"""Just enough Minecraft protocol to drive a proxy's login phase. No compression, no encryption."""
import socket, struct, uuid as uuidmod


def varint(value):
    out = bytearray()
    value &= 0xFFFFFFFF
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def string(text):
    raw = text.encode()
    return varint(len(raw)) + raw


class Reader:
    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def _need(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("connection closed")
            self.buf += chunk

    def read_varint(self):
        value = shift = 0
        while True:
            self._need(1)
            byte, self.buf = self.buf[0], self.buf[1:]
            value |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return value
            shift += 7

    def read_packet(self):
        """Returns (packet_id, payload)."""
        length = self.read_varint()
        self._need(length)
        body, self.buf = self.buf[:length], self.buf[length:]
        inner = Reader.__new__(Reader)
        inner.sock, inner.buf = self.sock, body
        return inner.read_varint(), inner.buf


def send(sock, packet_id, payload=b""):
    body = varint(packet_id) + payload
    sock.sendall(varint(len(body)) + body)


def read_string(data, offset=0):
    length = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        length |= (byte & 0x7F) << shift
        if not byte & 0x80:
            break
        shift += 7
    return data[offset:offset + length].decode(errors="replace"), offset + length


def read_varint_from(data, offset=0):
    value = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
