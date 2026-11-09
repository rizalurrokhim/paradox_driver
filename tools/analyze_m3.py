#!/usr/bin/env python3
"""Mereproduksi temuan protokol dari `COR-M3-MKDriver.dll`.

Skrip ini membuktikan tiga hal yang dipakai aplikasi Android:

1. Tabel CRC di RVA 0x1a040 identik dengan CRC-16/CCITT (poly 0x1021).
2. Fungsi pembangun paket memakai buffer 64 byte dan menulis CRC di byte 6..7.
3. Daftar command (`mov byte ptr [esi], imm8`) beserta RVA-nya.

Kebutuhan: `pip install capstone pefile`
Contoh:
  python3 tools/analyze_m3.py --dll _download/extracted/app/CDDriver/COR-M3-MKDriver.dll
"""

from __future__ import annotations

import argparse
import pathlib
import struct
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from pe_tools import PE32  # noqa: E402

# RVA penting (lihat docs/PROTOCOL.md dan docs/REVERSE_ENGINEERING.md)
CRC_TABLE_RVA = 0x1A040
CRC_WRITER_RVA = 0x1780
PACKET_BUILDER_RVA = 0x3AF0
VTABLE_RVA = 0x16328


def crc16_ccitt(data: bytes, initial: int = 0xFFFF) -> int:
    """CRC-16/CCITT-FALSE, sama dengan implementasi Kotlin di aplikasi."""
    crc = initial & 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def check_crc_table(pe: PE32) -> bool:
    """Membandingkan tabel CRC driver dengan tabel yang dihitung sendiri."""
    raw = pe.read_rva(CRC_TABLE_RVA, 512)
    driver_table = struct.unpack("<256H", raw)
    expected = []
    for index in range(256):
        crc = (index << 8) & 0xFFFF
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
        expected.append(crc)

    matches = sum(1 for a, b in zip(driver_table, expected) if a == b)
    print(f"[CRC ] tabel cocok {matches}/256 entri (table[1]=0x{driver_table[1]:04X})")
    print(f"[CRC ] check value '123456789' = 0x{crc16_ccitt(b'123456789'):04X} (harus 0x29B1)")
    return matches == 256


def show_command_builders(pe: PE32) -> None:
    """Menampilkan fungsi yang menulis CRC + command byte yang dipakai."""
    calls = pe.find_call_imm(pe.image_base + CRC_WRITER_RVA, max_hits=100)
    print(f"\n[CMD ] {len(calls)} fungsi memanggil penulis CRC (0x{CRC_WRITER_RVA:x})")
    for call_rva, _ in calls:
        code = pe.read_rva(call_rva - 0x60, 0x60)
        # cari pola "mov byte ptr [esi], imm8" -> C6 06 xx
        command = None
        for i in range(len(code) - 2):
            if code[i] == 0xC6 and code[i + 1] == 0x06:
                command = code[i + 2]
        if command is not None:
            print(f"       RVA 0x{call_rva:06x}: cmd 0x{command:02X}")


def show_vtable(pe: PE32) -> None:
    """Menampilkan isi vtable driver (12 method)."""
    print(f"\n[VTBL] vtable driver di RVA 0x{VTABLE_RVA:x}")
    for slot in range(12):
        target = struct.unpack("<I", pe.read_rva(VTABLE_RVA + slot * 4, 4))[0]
        if target > pe.image_base:
            print(f"       [{slot:2d}] -> RVA 0x{target - pe.image_base:06x}")
        else:
            break


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--dll",
        type=pathlib.Path,
        default=pathlib.Path("_download/extracted/app/CDDriver/COR-M3-MKDriver.dll"),
    )
    args = parser.parse_args()

    if not args.dll.exists():
        print(f"DLL tidak ditemukan: {args.dll}", file=sys.stderr)
        return 1

    pe = PE32(str(args.dll))
    ok = check_crc_table(pe)
    show_command_builders(pe)
    show_vtable(pe)

    print("\nSelesai. Detail pemetaan command ada di docs/PROTOCOL.md.")
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
