# Paradox ZX68 Configurator (Android)

Aplikasi Android untuk mengatur keyboard **Paradox / Ghost ZX68** — pencahayaan,
remap tombol, dan profil — langsung dari HP atau tablet lewat kabel OTG,
**tanpa PC dan tanpa root**.

Aplikasi ini adalah porting dari driver Windows resmi
`Ghost ZX68_Driver ver.6.0.0.3.exe`. Driver diekstrak, protokolnya dibaca dari
DLL native-nya (`COR-M3-MKDriver.dll`), lalu diimplementasikan ulang di Kotlin
di atas Android USB Host API — jadi bukan wrapper, bukan emulator.

```
Driver Windows                              Aplikasi Android
──────────────                              ────────────────
CMS.exe / CMSEngine.exe (UI CEF)            MainActivity + layar fitur
   │                                            │
COR-M3-MKDriver.dll                         Zx68Protocol + M3Frame
   │ frame 64 byte, CRC-16/CCITT                │ frame 64 byte, CRC-16/CCITT
HIDComm.dll ──► WriteFile (HID OUT)         UsbHidTransport ──► bulkTransfer
```

---

## ⬇️ Unduh APK

**Langsung pasang, tanpa build:**

- [`dist/ParadoxZx68-1.0.3-debug.apk`](dist/ParadoxZx68-1.0.3-debug.apk)
  (±5,8 MB, minSdk 23 / Android 6.0+, targetSdk 34)

Tautan unduh langsung (tanpa halaman GitHub):
<https://raw.githubusercontent.com/rizalurrokhim/paradox_driver/main/dist/ParadoxZx68-1.0.3-debug.apk>

Buka tautan itu di browser HP, lalu pasang file APK yang terunduh
(SHA-256 diawali `b4cf0b84`).

> APK ditandatangani sertifikat **debug** Android. Untuk memasangnya, aktifkan
> "Instal dari sumber tidak dikenal" untuk aplikasi file/browser yang Anda pakai.
> Ini normal untuk APK yang tidak lewat Play Store.

---

## Fitur

| Fitur | Status | Catatan |
|-------|--------|---------|
| Deteksi & koneksi USB (VID `0x2726`, PID `0x0094`/`0x00A6`) | siap | izin USB Android + pemilihan antarmuka otomatis |
| Handshake + baca info perangkat | siap | `cmd 0x01 sub 9/8` |
| Pengaturan efek LED (model, kecerahan, kecepatan, warna) | siap* | pemetaan efek lihat batasan |
| Commit/simpan ke keyboard | siap | `cmd 0x17` payload `F0 01 12 03` |
| Remap tombol per tombol | siap* | tulis 4 byte di `logic*4`, tanpa menimpa tombol lain |
| Konsol protokol (kirim frame hex apa pun) | siap | untuk verifikasi di perangkat asli |
| Uji mandiri protokol | siap | CRC, frame, encoding, aset — tanpa keyboard |

\* Lihat **Batasan** di bawah.

## Persyaratan

- HP/tablet Android 6.0 (API 23) ke atas dengan **USB host / OTG**.
- Kabel OTG yang sesuai (USB-C atau micro-USB ke USB-A).
- Keyboard ZX68 dalam mode kabel (bukan dongle wireless).

## Instalasi

1. Unduh APK dari tautan di atas.
2. Izinkan pemasangan dari sumber tidak dikenal, lalu pasang.
3. Colokkan keyboard lewat OTG. Aplikasi bisa terbuka otomatis (filter
   `USB_DEVICE_ATTACHED`), atau buka manual.
4. Tekan **Hubungkan keyboard** → pilih **Izinkan** pada dialog Android.

Selama terhubung, Android melepas keyboard dari sistem (antarmuka HID di-claim
dengan `force = true`), jadi keyboard **tidak mengetik** ke HP. Tekan
**Putuskan** untuk mengembalikannya; bila keyboard tetap tidak mengetik,
cabut dan colok ulang kabel OTG.

## Cara pakai

- **Pencahayaan** — pilih efek, atur kecerahan (0–18), kecepatan (0–3), dan
  warna, lalu **Kirim ke keyboard**. **Kirim & simpan** menambahkan commit
  (`cmd 0x17`) supaya setelan bertahan setelah kabel dicabut.
- **Remap tombol** — ketuk tombol pada gambar layout asli driver, pilih fungsi
  baru (termasuk modifier), lalu **Terapkan ke keyboard**.
- **Konsol protokol** — kirim frame 64 byte manual, lihat balasan, hitung ulang
  CRC. Alat ini yang dipakai kalau ada perilaku firmware yang berbeda.
- **Info & uji mandiri** — memverifikasi CRC, susunan frame, encoding keymap,
  dan pemuatan aset tanpa perangkat.

## Verifikasi cepat setelah pasang

1. Buka aplikasi → **Info & uji mandiri** → **Jalankan uji mandiri**.
   Semua baris harus `PASS` (CRC, frame, encoding keymap, aset).
2. Colokkan keyboard lewat OTG → **Hubungkan keyboard** → **Izinkan**.
   Log harus menampilkan `claimInterface(...) = true` dan endpoint OUT.
3. **Konsol protokol** → kirim bawaan `cmd 0x01 sub 9` (baca kapabilitas).
   Bila ada balasan `RX` dengan `CRC valid: true`, jalur baca sudah benar.
4. **Pencahayaan** → ubah kecerahan → **Kirim ke keyboard**. LED keyboard
   harus berubah. Bila sudah berubah, tekan **Kirim & simpan** agar bertahan.

Kalau langkah 3 tidak menghasilkan balasan, kirim isi log TX/RX saat melapor —
log itu menunjukkan persis di mana jalurnya berhenti.

## Troubleshooting

| Masalah | Penyebab & solusi |
|---------|-------------------|
| "Keyboard ZX68 tidak ditemukan" | Kabel bukan OTG, atau HP tidak mendukung USB host. Coba kabel lain; cek log aplikasi untuk VID/PID. |
| Dialog izin tidak muncul | Aplikasi menunggu ±15 detik; tekan **Hubungkan** lagi. Pastikan tidak ada aplikasi lain yang memegang keyboard. |
| "Gagal membuka perangkat USB" | Antarmuka gagal di-claim. Lihat log: `claimInterface(...) = false` berarti driver sistem menolak — cabut-colok ulang lalu coba lagi. |
| Perintah terkirim tapi LED diam | Cek log endpoint: harus ada `OUT=addr=0x.. type=.. maxPacket=64`. Bila `maxPacketSize < 64`, paket bisa terpotong. |
| Setelan hilang setelah dicabut | Gunakan **Kirim & simpan** (commit), bukan hanya **Kirim**. |
| Keyboard berhenti mengetik | Normal selama aplikasi memegang antarmuka. Tekan **Putuskan**, atau cabut-colok ulang. |
| Remap tidak berefek | Pastikan menekan **Terapkan ke keyboard**, lalu commit ikut dikirim otomatis. |

Detail protokol, nomor command, dan tingkat keyakinan tiap temuan ada di
[`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Build dari source

Prasyarat: **JDK 17**, **Android SDK** platform 34 + build-tools 34.0.0.

```bash
echo "sdk.dir=/path/ke/Android/Sdk" > local.properties

./gradlew :app:assembleDebug        # APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 9 tes protokol, tanpa perangkat
./gradlew :app:lintDebug            # harus 0 error
```

Wrapper memakai Gradle 8.7; AGP 8.5.2 dan Kotlin 1.9.24 diambil otomatis dari
Google Maven/Maven Central saat build pertama.

APK hasil build disalin ke `dist/` agar bisa diunduh langsung:

```bash
cp app/build/outputs/apk/debug/app-debug.apk dist/ParadoxZx68-1.0.3-debug.apk
```

## Struktur proyek

```
app/src/main/java/id/paradox/zx68/
├── MainActivity.kt          status, izin USB, pemantauan colok/lepas, log
├── protocol/                M3Frame, Crc16Ccitt, KeyCodes, Zx68Protocol
├── usb/                     UsbHidTransport (pilih antarmuka + endpoint), Zx68Session
├── data/                    aset layout/efek, penyimpanan setelan
├── ui/                      pencahayaan, remap, konsol, info + uji mandiri
└── util/                    hex + log
app/src/main/assets/         data hasil ekstraksi driver (JSON)
docs/                        PROTOCOL, REVERSE_ENGINEERING, ARCHITECTURE
tools/                       skrip ekstraksi aset & analisis DLL
dist/                        APK siap unduh
```

## Dokumentasi

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — spesifikasi 64 byte, CRC, aturan byte
  `length`, daftar command, encoding keymap, tingkat keyakinan.
- [`docs/REVERSE_ENGINEERING.md`](docs/REVERSE_ENGINEERING.md) — cara installer
  diekstrak dan DLL dianalisis (lengkap dengan RVA yang bisa dicek ulang).
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — peta modul & alur data.
- [`AGENTS.md`](AGENTS.md) — aturan kerja + **playbook perbaikan bug** untuk
  agen/model berikutnya (`agent.md` adalah alias ringkasnya).

## Status verifikasi

Yang **sudah** dijalankan di repo ini:

- `./gradlew :app:assembleDebug` → sukses, APK terbentuk dan tersigning.
- `./gradlew :app:testDebugUnitTest` → **9 tes lulus** (CRC `0x29B1`, layout
  frame, alamat 24-bit, batas payload 56 byte, byte `length`, encoding
  `DriverValue`, payload commit, identitas perangkat).
- `./gradlew :app:lintDebug` → **0 error**.

Yang **belum** diverifikasi: perilaku pada keyboard ZX68 fisik (tidak ada
perangkat di lingkungan build). Karena itu aplikasi menyediakan konsol protokol
dan log TX/RX agar Anda bisa memastikan sendiri di perangkat nyata.

## Riwayat perubahan

**1.0.3**

- **Deteksi keyboard diperbaiki untuk unit dengan VID/PID generik.** Banyak
  ZX68 melaporkan ID controller-nya, bukan ID Paradox — kasus nyata:
  VID `0x1EA7` PID `0x0907` (SinoWealth). Sebelumnya aplikasi hanya mencari
  VID `0x2726`, jadi keyboard tidak pernah cocok. Sekarang perangkat dipilih
  lewat bentuk interface (HID class 3 dengan endpoint interrupt OUT 64 byte),
  dan VID/PID hanya menambah skor. Lihat `docs/PROTOCOL.md` §1.
- **Pemilih perangkat manual** di tab Perangkat: semua perangkat USB yang
  terpasang ditampilkan, lengkap dengan skor kelayakan, dan bisa diketuk untuk
  dihubungkan. Ini jalur keluar bila deteksi otomatis masih salah.
- **UI disusun ulang menyerupai software Windows**: header perangkat + pil
  status, tab Pencahayaan/Perangkat, grid 34 efek seperti tile pada aplikasi
  aslinya, baris warna, slider kecerahan & kecepatan, serta bar aksi bawah.
- Izin USB memakai `PendingIntent` dengan `FLAG_MUTABLE` (wajib di Android 12+)
  sehingga dialog izin tidak lagi gagal muncul.
- Log TX/RX kini menampilkan potongan hex paket, interface yang dipilih, dan
  endpoint OUT/IN yang dipakai — cukup untuk mendiagnosis sendiri di HP.

**1.0.2**

- Perbaikan deteksi perangkat: bila perangkat ZX68 tidak langsung muncul di daftar USB, aplikasi sekarang menunggu beberapa detik dan mencoba lagi sebelum melaporkan "tidak ditemukan", karena beberapa Android hanya menyebarkan perangkat setelah event `USB_DEVICE_ATTACHED`.
- Bila perangkat ditemukan, log kini mencantumkan nama perangkat (`device.deviceName`) dan deskripsi lengkap (VID/PID + nama) sehingga lebih mudah memastikan keyboard tepat teridentifikasi.
- Alur izin USB ditunda sedikit: aplikasi kini menunggu izin dengan polling (hingga ±15 detik) setelah menekan **Hubungkan** dan berharap dialog izin muncul; bila tidak ada dialog, periksa log perangkat yang terlihat.

**1.0.1**

- Pemilihan antarmuka USB diperbaiki: aplikasi kini memilih antarmuka yang
  benar-benar punya endpoint OUT 64 byte (sebelumnya bisa memilih antarmuka
  keyboard boot yang hanya punya IN, sehingga perintah tulis tidak berpengaruh).
- Izin USB tidak lagi bergantung pada broadcast: status izin dicek lewat
  `hasPermission()` dengan batas waktu, jadi tidak macet setelah menekan
  "Izinkan" di perangkat tertentu.
- Status "terkirim" tidak lagi dilaporkan palsu ketika `bulkTransfer` gagal
  (dibedakan antara gagal kirim dan tidak ada balasan).
- Byte `length` untuk `0x15` dan `0x17` disamakan dengan driver (= 0).
- Perintah tulis tidak lagi menunggu 400 ms balasan, jadi pengiriman setelan
  terasa jauh lebih cepat.
- Pilihan efek yang tersimpan kini dipulihkan dengan benar saat layar dibuka.
- Ditambahkan pemantauan colok/lepas USB dan pesan status yang lebih jelas.

**1.0.0** — rilis awal: protokol, pencahayaan, remap, konsol, uji mandiri.

## Lisensi & atribusi

Kode aplikasi ditulis ulang dari hasil analisis driver. Nama produk "Paradox",
"Ghost", dan "ZX68" milik pemegang merek masing-masing. Proyek ini tidak
berafiliasi resmi dengan vendor.
