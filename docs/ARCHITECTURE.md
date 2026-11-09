# Arsitektur aplikasi

Aplikasi Android ini adalah porting langsung driver Windows ZX68. Tidak ada
kode native di APK: seluruh protokol (frame, CRC, perintah) diimplementasikan
ulang di Kotlin di atas Android USB Host API.

## Peta modul

```
id.paradox.zx68
├── MainActivity.kt              status koneksi, izin USB, log TX/RX, navigasi
├── model/
│   └── LightingSettings.kt      parameter LESet (model, kecerahan, kecepatan, ...)
├── protocol/
│   ├── DeviceIds.kt             VID/PID ZX68 + model sekeluarga
│   ├── ProtocolCommands.kt      nomor command + RVA asalnya
│   ├── Crc16Ccitt.kt            CRC-16/CCITT-FALSE (identik driver)
│   ├── M3Frame.kt               susun/parse paket 64 byte + validasi CRC
│   ├── KeyCodes.kt              encoding DriverValue + tabel HID usage
│   └── Zx68Protocol.kt          perintah tingkat tinggi (baca/tulis/LED/commit)
├── usb/
│   ├── UsbHidTransport.kt       open/claim/send/receive + izin runtime
│   └── Zx68Session.kt           sesi tunggal + executor IO
├── data/
│   ├── KeyLayoutEntry.kt        satu tombol dari keymap.js
│   ├── AssetRepository.kt       baca assets/zx68_layout.json + efek
│   └── ProfileStore.kt          simpan setelan lokal (JSON di filesDir)
├── ui/
│   ├── LightingActivity.kt      pengaturan efek LED
│   ├── KeymapActivity.kt        remap tombol
│   ├── KeymapView.kt            gambar layout + deteksi ketukan
│   ├── ConsoleActivity.kt       kirim frame hex manual (verifikasi lapangan)
│   └── AboutActivity.kt         info + uji mandiri
└── util/
    ├── Hex.kt                   byte <-> teks hex
    └── LogBus.kt                log ringan yang bisa diamati UI
```

## Alur data

```
Pengguna
   │  (ketuk / geser / pilih)
   ▼
Activity ──────────────► Zx68Protocol            (main thread menyusun perintah)
   │                        │
   │                        ▼
   │                     M3Frame.build()  ──► Crc16Ccitt
   │                        │
   ▼                        ▼
Zx68Session.io  ──────► UsbHidTransport          (thread IO tunggal)
                            │  bulkTransfer / controlTransfer
                            ▼
                        Keyboard ZX68 (VID 0x2726)
                            │  balasan 64 byte
                            ▼
                        LogBus ──► TextView log
```

Semua operasi USB dijalankan pada satu `Executors.newSingleThreadExecutor()`
(`Zx68Session.io`) supaya tidak ada dua transaksi yang saling menimpa.

## Alur koneksi (izin USB)

1. `UsbHidTransport.findDevice()` mencocokkan VID/PID di `deviceList`.
2. `hasPermission()`; bila belum, `requestPermission()` memasang
   `BroadcastReceiver` untuk `id.paradox.zx68.USB_PERMISSION`.
3. `open()` → `openDevice()` → `claimInterface(iface, force = true)`
   (melepas driver keyboard kernel) → cari endpoint interrupt OUT/IN.
4. `Zx68Protocol.handshake()` mengirim `cmd 0x01 sub 9` lalu `sub 8`,
   meniru urutan driver Windows.

## Sumber data statis

| Aset | Isi | Asal |
|------|-----|------|
| `assets/zx68_layout.json` | 93 tombol + koordinat | `keymap.js` model `656801940` |
| `assets/zx68_effects.json` | 34 nama efek LED | `lelist_en.json` |

Keduanya dihasilkan oleh `tools/extract_assets.py` dan dibaca oleh
`AssetRepository`.

## Build

Prasyarat: JDK 17, Android SDK (platform 34, build-tools 34.0.0).

```bash
# sdk.dir di local.properties
./gradlew :app:assembleDebug     # APK: app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest # unit test protokol (tanpa perangkat)
```

Wrapper memakai Gradle 8.7; AGP 8.5.2 dan Kotlin 1.9.24 diambil otomatis
dari Google Maven/Maven Central pada build pertama.

## Pengujian

| Jenis | Cakupan | Butuh perangkat? |
|-------|---------|------------------|
| `ProtocolTest` (JVM) | CRC, layout frame, encoding keymap, ID perangkat | tidak |
| Uji mandiri di aplikasi | sama + pemuatan aset, dijalankan di layar Info | tidak |
| Konsol protokol | kirim/terima frame nyata | ya |

## Batasan yang diketahui

- Tidak ada emulator/perangkat pada lingkungan pengembangan, sehingga
  pengiriman ke perangkat keras belum diverifikasi langsung.
- Pemetaan nama efek → `LEModel` baru sebagian (lihat `docs/PROTOCOL.md` §8).
- Perangkat harus mendukung USB host (OTG). Beberapa ponsel mematikan port
  USB saat meng-claim antarmuka HID; gunakan kabel OTG yang benar.
