# AGENTS.md — Panduan kerja agen (termasuk perbaikan bug)

Repo ini adalah **porting driver Windows Paradox/Ghost ZX68 ke Android**: satu
aplikasi Kotlin yang berbicara langsung ke keyboard lewat USB HID (OTG, tanpa
root), plus dokumentasi hasil reverse engineering driver aslinya.

Baca file ini sampai habis sebelum mengubah kode. Untuk aturan yang lebih
ringkas, lihat juga `agent.md` (alias file ini).

---

## 0. Ringkasan cepat

| Hal | Nilai |
|-----|-------|
| Bahasa | Kotlin, Android Views + Material (tanpa Compose) |
| minSdk / targetSdk | 23 / 34 |
| Build | `./gradlew :app:assembleDebug` |
| Test | `./gradlew :app:testDebugUnitTest` (9 tes, tanpa perangkat) |
| Lint | `./gradlew :app:lintDebug` — harus **0 error** |
| APK siap unduh | `dist/ParadoxZx68-1.0.0-debug.apk` |
| Hardware | ID resmi VID `0x2726` PID `0x0094`/`0x00A6`; sebagian unit melaporkan ID generik (mis. `0x1EA7:0x0907`) — lihat §1a |

---

## 1. Aturan keras (jangan dilanggar tanpa bukti baru dari DLL)

Protokol sudah diverifikasi dari disassembly `COR-M3-MKDriver.dll`. Mengubahnya
tanpa bukti akan membuat aplikasi **terlihat sukses tetapi keyboard tidak
berubah** — bug paling sulit dilacak.

1. Paket selalu **64 byte**, dikirim penuh.
2. Layout: `[0]=cmd`, `[1]=sub`, `[2..4]=alamat 24-bit LE`, `[5]=length`,
   `[6..7]=CRC LE`, `[8..63]=payload 56 byte`.
3. CRC-16/CCITT-FALSE: poly `0x1021`, init `0xFFFF`, tanpa refleksi, dihitung
   atas 64 byte dengan byte 6–7 dinolkan. Check value `"123456789"` = `0x29B1`.
4. **Byte `length` hanya diisi untuk command data** (`0x04`, `0x16`, `0x27`).
   Untuk `0x03`, `0x0B`, `0x15` (semua sub), `0x17`, `0x21`, `0x22` driver
   membiarkannya **0** karena buffer di-`memset(0x40)` dan `buf[5]` tak pernah
   ditulis.
5. Encoding keymap: `(0x02 << 24) | (usage << 8) | modifier`; alamat =
   `logic * 4`.
6. Payload commit: `F0 01 12 03` pada `cmd 0x17 sub 1`, `length = 0`.
7. LED: `cmd 0x15 sub 1` = 4 byte `[model, speed, brightness, direction]`,
   `sub 2` = `enable`, `sub 3` = `color`, semuanya `length = 0`.

Tes di `app/src/test/.../ProtocolTest.kt` mengunci poin 1–6. Refactor yang
membuat tes ini gagal berarti refactor itu salah.

### 1a. Deteksi perangkat **jangan** hanya VID/PID

Unit ZX68 di lapangan bisa melaporkan ID controller generik, bukan ID Paradox
(kasus teramati: VID `0x1EA7` PID `0x0907`, controller SinoWealth). Karena itu
`UsbHidTransport.scoreOf()` memberi skor berdasarkan **bentuk interface**:
interface HID class 3 subclass 0 protocol 0 dengan endpoint interrupt OUT
≥ 64 byte adalah kanal perintah driver. VID/PID resmi hanya `+100`, ID generik
`+55`, kanal vendor `+45`.

Jangan “memperbaiki” deteksi dengan menambah VID/PID ke daftar saja tanpa
memeriksa `candidates()` di log; dan jangan pernah membuka perangkat paksa
tanpa meminta izin atau tanpa mengecek ada endpoint OUT.

---

## 2. Peta file

| Path | Isi |
|------|-----|
| `app/src/main/java/id/paradox/zx68/protocol/` | frame, CRC, command, encoding keymap |
| `app/src/main/java/id/paradox/zx68/usb/` | transport USB + sesi tunggal |
| `app/src/main/java/id/paradox/zx68/ui/` | layar pencahayaan, remap, konsol, info |
| `app/src/main/java/id/paradox/zx68/data/` | aset driver + simpan setelan |
| `app/src/main/assets/` | `zx68_layout.json` (93 tombol), `zx68_effects.json` (34 efek) |
| `app/src/test/` | unit test JVM |
| `docs/PROTOCOL.md` | spesifikasi + tingkat keyakinan tiap temuan |
| `docs/REVERSE_ENGINEERING.md` | cara driver dibedah (RVA, perintah ulang) |
| `docs/ARCHITECTURE.md` | peta modul & alur data |
| `tools/` | `analyze_m3.py`, `extract_assets.py`, `pe_tools.py` |
| `_download/` | installer + DLL sumber (gitignored) |

---

## 3. Menyiapkan lingkungan build

Butuh **JDK 17** dan **Android SDK** (platform 34, build-tools 34.0.0).

```bash
echo "sdk.dir=/path/ke/Android/Sdk" > local.properties
./gradlew :app:assembleDebug        # APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Bila SDK/JDK belum ada dan tidak boleh `apt`, unduh manual lalu set
`JAVA_HOME`/`ANDROID_HOME`:

```bash
curl -L -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
curl -L -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
# lalu: sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

---

## 4. Playbook perbaikan bug (untuk model/agen berikutnya)

Ikuti urutan ini; jangan melompat ke "coba ubah angka".

### Langkah 1 — Reproduksi & klasifikasi
Tentukan lapisan mana yang rusak:

| Gejala | Lapisan tersangka |
|--------|-------------------|
| Keyboard tidak terdeteksi | `UsbHidTransport.scoreOf/candidates` (bukan VID/PID saja) |
| Dialog izin tidak muncul / macet | alur izin di `MainActivity` |
| "Gagal membuka perangkat" | `UsbHidTransport.open` (claim/interface) |
| Semua perintah "terkirim" tapi tidak ada efek | interface salah, atau `length`/CRC salah, atau butuh commit `0x17` |
| Balasan tidak pernah masuk | endpoint IN salah, atau perintah memang tak dibalas |
| Aplikasi crash | `adb logcat | grep -i zx68` |
| UI menampilkan angka aneh | `KeyCodes` / `LightingSettings` |

### Langkah 2 — Baca log di aplikasi
Layar utama menampilkan setiap `TX`/`RX` 64 byte. Ini bukti utama. Cek:
- apakah `TX` muncul saat tombol ditekan?
- apakah `claimInterface(...) = true`?
- endpoint mana yang dipilih (`OUT=addr=0x.. type=.. maxPacket=..`)?
- apakah `RX` masuk dengan CRC valid?

### Langkah 3 — Uji terisolasi dengan Konsol protokol
Kirim frame manual (`cmd 0x01 sub 9`) untuk memastikan jalur baca hidup.
Lalu uji satu perintah tulis dan amati keyboard secara fisik.

### Langkah 4 — Verifikasi ke DLL, bukan menebak
Semua semantik protokol berasal dari `_download/extracted/app/CDDriver/COR-M3-MKDriver.dll`:

```bash
.tools/venv/bin/python tools/analyze_m3.py      # tabel CRC, command, vtable
```

Untuk melihat satu fungsi:

```python
from tools.pe_tools import PE32
p = PE32('_download/extracted/app/CDDriver/COR-M3-MKDriver.dll')
p.print_disasm(0x2600, 55)   # contoh: builder cmd 0x15 sub 1
```

Yang dicari: `mov byte ptr [esi], <cmd>` (nomor command), `[edi+0x505]`
(sub), `[edi+0x509]` (length), `[edi+0x50c+]` (payload), `push 0x40` (ukuran
paket), `ret <n>` (jumlah argumen).

### Langkah 5 — Patch minimal
- Perbaiki **satu** penyebab per perubahan; jangan refactor sekaligus.
- Pertahankan nama command lewat `ProtocolCommands`, jangan angka mentah.
- Jangan "menambal" dengan menelan error (`catch {}` kosong) atau menurunkan
  timeout sampai test hijau.

### Langkah 6 — Kunci dengan tes
Tambahkan satu tes di `ProtocolTest` untuk setiap perilaku yang diperbaiki
(layout frame, byte length, encoding, alamat). Tes harus bisa jalan tanpa
perangkat.

### Langkah 7 — Perbarui dokumentasi
- `docs/PROTOCOL.md` bila menyangkut protokol (sebutkan tingkat keyakinan).
- `docs/ARCHITECTURE.md` bila menambah modul/alur.
- `README.md` bila mengubah cara pakai/instalasi.

### Langkah 8 — Verifikasi & serahkan
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/ParadoxZx68-1.0.0-debug.apk
```
Sebutkan dengan jujur apa yang **terverifikasi** (build/test/lint) dan apa yang
**belum** (perilaku pada keyboard fisik, bila tidak ada perangkat).

---

## 5. Matriks masalah umum

| Gejala | Penyebab yang sudah terbukti | Perbaikan |
|--------|------------------------------|-----------|
| `openDevice` null | izin belum diberikan | tunggu `hasPermission()` (polling) sebelum membuka |
| `claimInterface = false` | driver kernel memegang antarmuka | `claimInterface(iface, true)` (force) |
| Perintah terkirim, LED diam | antarmuka salah (keyboard boot, tanpa OUT) | pilih antarmuka ber-`OUT` 64 byte (lihat `candidateInterfaces`) |
| Perintah terkirim, keymap diam | byte `length`/CRC tidak sesuai driver | `length = 0` untuk `0x15`/`0x17`, CRC atas 64 byte |
| Setelan hilang setelah cabut | belum commit | kirim `cmd 0x17` payload `F0 01 12 03` |
| Keyboard berhenti mengetik | antarmuka di-claim aplikasi | tekan **Putuskan** / cabut-colok ulang |
| Remap menimpa tombol lain | menulis seluruh tabel dengan data tak lengkap | pakai `writeKeyTablePatch()` per tombol |
| Izin tidak pernah kembali | broadcast tidak terkirim di sebagian OEM | polling `hasPermission()` (sudah diterapkan) |
| Crash setelah cabut kabel | memakai koneksi yang sudah tertutup | `Transfer.sentOk == false` harus ditangani, jangan dianggap sukses |

---

## 6. Jebakan teknis yang pernah menggigit

- `bulkTransfer` mengembalikan `-1` saat gagal — jangan samakan dengan
  "tidak ada balasan". `UsbHidTransport.Transfer` membedakan keduanya.
- Perintah tulis **tidak dibalas**; jangan menunggu 400 ms per paket
  (`expectReply = false`).
- `PendingIntent` untuk izin USB butuh `FLAG_MUTABLE` di Android 12+.
- `registerReceiver` wajib memakai flag di Android 13+; pakai `ContextCompat`.
- Jangan menghapus `mipmap-anydpi` (ikon untuk API < 26).
- `assets/` hanya boleh berisi JSON hasil ekstraksi; jangan tambah biner besar.

---

## 7. Gaya kode & dokumentasi

- Kotlin, 4 spasi, KDoc **bahasa Indonesia** untuk setiap kelas/fungsi publik:
  apa, mengapa, dan asal perilakunya (nomor command + RVA bila relevan).
- I/O USB hanya di `Zx68Session.io`; UI selalu di main thread.
- Kesalahan ditampilkan ke pengguna (`Toast`/`TextView`), tidak ditelan.
- Bila sesuatu belum pasti, tulis eksplisit dan rujuk `docs/PROTOCOL.md` §8.

## 8. Definisi selesai

1. `assembleDebug` sukses, `testDebugUnitTest` hijau, `lintDebug` 0 error.
2. APK diperbarui di `dist/`.
3. Dokumentasi yang relevan diperbarui.
4. Tidak ada klaim "sudah diuji di keyboard" tanpa perangkat nyata.
5. Perubahan di-commit dengan pesan yang menjelaskan **penyebab**, bukan hanya
   "fix bug".

## 9. Pekerjaan lanjutan yang masuk akal

- Membuka file `.le` (parameter efek) untuk memetakan nama efek → `LEModel`.
- Memetakan command `0x03`, `0x0C`, `0x1A`, `0x24`–`0x26`, `0x31`.
- Tabel LED per tombol (warna RGB) setelah jumlah byte per LED dipastikan.
- Dukungan update firmware bila `CDBootDriver.dll` dibedah.
