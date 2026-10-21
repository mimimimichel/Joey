#!/usr/bin/env python3
"""Protocole Bluetooth de la nacelle Feiyu SPG (décodé depuis une capture, voir PROTOCOLE.md).

    python3 spg.py verifier captures/session1_2026-10-21.txt   # contrôle toutes les trames + résumé
    python3 spg.py joystick 0 200                              # fabrique une trame joystick
"""
import struct
import sys


def crc16_xmodem(data: bytes) -> int:
    """CRC-16 polynôme 0x1021, départ 0x0000 (XMODEM)."""
    c = 0
    for b in data:
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x1021) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    return c


def crc16_false(data: bytes) -> int:
    """CRC-16 polynôme 0x1021, départ 0xFFFF (CCITT-FALSE), pour les trames 55 AA."""
    c = 0xFFFF
    for b in data:
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x1021) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    return c


def frame(dest: int, cmd: int, payload: bytes) -> bytes:
    """A5 5A | dest | cmd | longueur | données | CRC (petit-boutiste, calculé après A5 5A)."""
    body = bytes([dest, cmd, len(payload)]) + payload
    return b"\xA5\x5A" + body + struct.pack("<H", crc16_xmodem(body))


def joystick(pan: int, tilt: int) -> bytes:
    """Joystick virtuel : pan (+ = droite), tilt (+ = haut). Plage vue : environ -320..+320."""
    return frame(0x00, 0x10, b"\xFF" + struct.pack("<hh", pan, tilt))


RECENTRER = frame(0x00, 0x40, b"\x05\x02")


def check(f: bytes) -> bool:
    if f[:2] == b"\xA5\x5A":
        return crc16_xmodem(f[2:-2]) == struct.unpack("<H", f[-2:])[0]
    if f[:2] == b"\x55\xAA":
        return crc16_false(f[2:-2]) == struct.unpack("<H", f[-2:])[0]
    return False


def decode(f: bytes):
    if f[:2] != b"\xA5\x5A":
        return None
    dest, cmd, n = f[2], f[3], f[4]
    p = f[5:5 + n]
    if dest == 0x03 and cmd == 0x10 and n == 7:
        tilt, roll, pan = (v / 100 for v in struct.unpack("<hhh", p[1:7]))
        return f"angles tilt={tilt:.2f} roulis={roll:.2f} pan={pan:.2f}"
    if cmd == 0x10 and n == 5:
        x, y = struct.unpack("<hh", p[1:5])
        return f"joystick pan={x} tilt={y}"
    return f"dest={dest:02x} cmd={cmd:02x} données={p.hex()}"


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "verifier":
        ok = bad = 0
        kinds = {}
        for line in open(sys.argv[2], encoding="utf8"):
            if line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) != 3 or parts[1] not in ("TX", "RX"):
                continue
            f = bytes.fromhex(parts[2])
            if check(f):
                ok += 1
            else:
                bad += 1
                print("CRC faux :", line.strip())
            d = decode(f) or f.hex()
            key = parts[1] + " " + d.split("=")[0] if d.startswith(("angles", "joystick")) else parts[1] + " " + d
            kinds[key] = kinds.get(key, 0) + 1
        print(f"{ok} trames correctes, {bad} fausses")
        for k, n in sorted(kinds.items()):
            print(f"{n:6d}  {k}")
    elif len(sys.argv) == 4 and sys.argv[1] == "joystick":
        print(joystick(int(sys.argv[2]), int(sys.argv[3])).hex())
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
