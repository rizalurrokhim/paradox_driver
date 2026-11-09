#!/usr/bin/env python3
"""Mengekstrak data ZX68 dari driver Windows menjadi aset aplikasi Android.

Sumber data ada di dalam installer Inno Setup `Ghost ZX68_Driver ver.6.0.0.3.exe`
yang sudah dibuka dengan `innoextract` (lihat docs/REVERSE_ENGINEERING.md).

Yang dihasilkan:
  app/src/main/assets/zx68_layout.json   <- keymap.js (93 tombol + posisi)
  app/src/main/assets/zx68_effects.json  <- lelist_en.json (34 efek LED)

Contoh:
  python3 tools/extract_assets.py --driver-dir _download/extracted/app
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

# Model ZX68 yang dipakai sebagai sumber layout.
# 656801940 = VID 0x2726 PID 0x0094 (FWID 0x97220410)
ZX68_MODEL_DIR = "656801940"


def convert_layout(driver_dir: pathlib.Path) -> list[dict]:
    """Membaca keymap.js dan mengubahnya menjadi struktur ringkas."""
    source = driver_dir / "CMSEngine" / "driver" / "device" / ZX68_MODEL_DIR / "data" / "keymap.js"
    if not source.exists():
        raise FileNotFoundError(f"keymap tidak ditemukan: {source}")

    raw = json.loads(source.read_text(encoding="utf-8-sig"))
    result = []
    for entry in raw:
        position = entry["Position"]
        result.append(
            {
                "name": entry["KeyName"],
                "show": entry["Show"],
                "logic": entry["LogicCode"],
                "location": entry["LocationCode"],
                "rect": [
                    position["Left"],
                    position["Top"],
                    position["Width"],
                    position["Height"],
                ],
            }
        )
    return result


def convert_effects(driver_dir: pathlib.Path) -> list[dict]:
    """Membaca daftar efek LED (nama + GUID) milik driver."""
    source = driver_dir / "CMSEngine" / "driver" / "res" / "data" / "le" / "lelist_en.json"
    if not source.exists():
        raise FileNotFoundError(f"daftar efek tidak ditemukan: {source}")

    raw = json.loads(source.read_text(encoding="utf-8-sig"))
    return [{"guid": item["GUID"], "name": item["Name"]} for item in raw]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--driver-dir",
        type=pathlib.Path,
        default=pathlib.Path("_download/extracted/app"),
        help="Folder hasil innoextract (berisi CMSEngine/)",
    )
    parser.add_argument(
        "--out-dir",
        type=pathlib.Path,
        default=pathlib.Path("app/src/main/assets"),
        help="Folder aset aplikasi Android",
    )
    args = parser.parse_args()

    if not args.driver_dir.exists():
        print(f"Folder driver tidak ada: {args.driver_dir}", file=sys.stderr)
        print("Ekstrak installer terlebih dahulu (docs/REVERSE_ENGINEERING.md).", file=sys.stderr)
        return 1

    layout = convert_layout(args.driver_dir)
    effects = convert_effects(args.driver_dir)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "zx68_layout.json").write_text(json.dumps(layout, indent=1), encoding="utf-8")
    (args.out_dir / "zx68_effects.json").write_text(json.dumps(effects, indent=1), encoding="utf-8")

    print(f"layout  : {len(layout)} tombol -> {args.out_dir / 'zx68_layout.json'}")
    print(f"efek    : {len(effects)} efek   -> {args.out_dir / 'zx68_effects.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
