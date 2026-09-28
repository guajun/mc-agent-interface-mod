#!/usr/bin/env python3
"""Minimal Minecraft server-list ping, stdlib only.

This is the "normal player-shaped" traffic for the issue #7 regression: the
first byte of a real handshake is a VarInt frame length, so a vanilla status
probe must pass through the same-port sniffer untouched and still get the
normal status JSON back. It also does the request/response ping round-trip.

    python spike/mc_ping.py --host 127.0.0.1 --port 25565
    python spike/mc_ping.py --host 127.0.0.1 --port 25565 --json
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys


def write_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        part = value & 0x7F
        value >>= 7
        if value:
            out.append(part | 0x80)
        else:
            out.append(part)
            return bytes(out)


def read_varint(reader) -> int:
    value = 0
    shift = 0
    while True:
        chunk = reader(1)
        if not chunk:
            raise EOFError("connection closed while reading a VarInt")
        byte = chunk[0]
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value
        shift += 7
        if shift > 35:
            raise ValueError("VarInt too long")


def read_exactly(reader, count: int) -> bytes:
    data = bytearray()
    while len(data) < count:
        chunk = reader(count - len(data))
        if not chunk:
            raise EOFError("connection closed while reading a packet")
        data += chunk
    return bytes(data)


def read_packet(sock: socket.socket) -> bytes:
    reader = sock.recv
    length = read_varint(reader)
    return read_exactly(reader, length)


def status(host: str = "127.0.0.1", port: int = 25565, protocol: int = 0,
           timeout: float = 5.0, handshake_host: str | None = None) -> dict:
    """Return the server-list status JSON, like the client's multiplayer list.

    ``handshake_host`` overrides the name inside the handshake packet, which
    lets the test use a one-character virtual host: that makes the whole
    handshake plus status request only 10 bytes, shorter than the same-port
    marker, and is the regression for the sniffer's framing.
    """
    with socket.create_connection((host, port), timeout=timeout) as sock:
        reader = sock.recv
        host_bytes = (handshake_host or host).encode("utf-8")
        body = (
            write_varint(0)  # handshake packet id
            + write_varint(protocol)
            + write_varint(len(host_bytes))
            + host_bytes
            + struct.pack(">H", port)
            + write_varint(1)  # next state: status
        )
        sock.sendall(write_varint(len(body)) + body)
        sock.sendall(write_varint(1) + write_varint(0))  # status request
        payload = read_packet(sock)
        # packet id 0x00, then a VarInt-prefixed JSON string
        if not payload or payload[0] != 0x00:
            raise ValueError(f"unexpected status packet id: {payload[:8]!r}")
        offset = 1
        length = 0
        shift = 0
        while True:
            byte = payload[offset]
            offset += 1
            length |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7
        text = payload[offset:offset + length].decode("utf-8")
        return json.loads(text)


def ping(host: str = "127.0.0.1", port: int = 25565, timeout: float = 5.0) -> int:
    """Return the pong payload, exercising the status ping request/response."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        reader = sock.recv
        host_bytes = host.encode("utf-8")
        body = (
            write_varint(0)
            + write_varint(0)
            + write_varint(len(host_bytes))
            + host_bytes
            + struct.pack(">H", port)
            + write_varint(1)
        )
        sock.sendall(write_varint(len(body)) + body)
        sock.sendall(write_varint(1) + write_varint(0))
        read_packet(sock)
        payload = struct.pack(">q", 0x0102030405060708)
        sock.sendall(write_varint(len(payload) + 1) + write_varint(1) + payload)
        pong = read_packet(sock)
        if not pong or pong[0] != 0x01:
            raise ValueError(f"unexpected pong packet id: {pong[:8]!r}")
        return struct.unpack(">q", pong[1:9])[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--json", action="store_true", help="print the whole status JSON")
    parser.add_argument("--handshake-host", default=None,
                        help="virtual host name inside the handshake (defaults to --host)")
    args = parser.parse_args()

    try:
        payload = status(args.host, args.port, timeout=args.timeout,
                         handshake_host=args.handshake_host)
        pong = ping(args.host, args.port, timeout=args.timeout)
    except OSError as error:
        print(f"status ping failed: {error}", file=sys.stderr)
        return 1

    version = payload.get("version", {})
    players = payload.get("players", {})
    print(f"status: {version.get('name')} protocol={version.get('protocol')} "
          f"players={players.get('online')}/{players.get('max')}")
    print(f"pong: 0x{pong:016x}")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
