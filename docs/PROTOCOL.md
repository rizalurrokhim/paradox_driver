# Protokol HID Paradox / Ghost ZX68

Dokumen ini adalah hasil pembacaan driver Windows
`Ghost ZX68_Driver ver.6.0.0.3.exe` dan menjadi acuan implementasi Android di
`app/src/main/java/id/paradox/zx68/protocol/`.

Semua alamat RVA di bawah mengacu ke `CDDriver/COR-M3-MKDriver.dll`
(image base `0x10000000`) yang ada di dalam installer.

---

## 1. Identitas perangkat

| Item | Nilai | Sumber |
|------|-------|--------|
| Vendor ID | `0x2726` | `CMSEngine/driver/device/modellist.json` |
| Product ID ZX68 (rev. A) | `0x0094` | ModelID `656801940` = `0x27260094` |
| Product ID ZX68 (rev. B) | `0x00A6` | ModelID `656801958` = `0x272600A6` |
| FWID | `0x97220410`, `0x97220411` | `modellist.json` |
| Nama di driver | `ZX68` | `modellist.json` |

Model satu keluarga (bukan ZX68) yang ikut dikenali driver:
`GH68` (`0x0031`), `GHOST OPT64` (`0x005E`, `0x006B`), `GH21` (`0x00A4`).

### VID/PID generik pada unit nyata

Pada sebagian unit, keyboard melaporkan **ID controller**-nya, bukan ID
Paradox, selama berada di mode keyboard biasa. Kasus yang teramati di
lapangan: VID `0x1EA7` (SinoWealth) PID `0x0907`. Perangkat itu tetap
keyboard yang sama — interface konfigurasi vendornya tetap ada.

Karena itu aplikasi Android **tidak** mencocokkan VID/PID saja. Pemilihan
dilakukan lewat bentuk interface (`UsbHidTransport.scoreOf()`):

| Ciri | Bobot | Arti |
|------|-------|------|
| VID `0x2726` + PID `0x0094`/`0x00A6` | +100 | ID resmi driver |
| VID/PID generik yang teramati | +55 | mis. `0x1EA7:0x0907` |
| Interface HID vendor (class 3, subclass 0, protocol 0) dengan endpoint interrupt OUT ≥ 64 byte | +45 | kanal perintah driver |
| Ada endpoint OUT | +15 | bisa dikirimi paket |
| Ada endpoint IN | +10 | bisa dibaca balasannya |
| Hanya interface keyboard boot (subclass 1, protocol 1) | −30 | bukan kanal konfigurasi |

Perangkat dengan skor tertinggi dipakai. Bila hasilnya salah, tab
**Perangkat** menampilkan semua perangkat yang terpasang dan pengguna bisa
memilih manual; pilihan itu langsung dipakai untuk `open()`.

Daftar ID generik ada di `DeviceIds.OBSERVED_DEVICE_IDS`; menambah entri di
sana hanya menaikkan skor, bukan menggantikan pencocokan interface.

---

## 2. Transport

Driver Windows (`HIDComm.dll`) **tidak** memakai `HidD_SetFeature`.
Ia membuka perangkat dengan `CreateFileW` lalu menulis lewat `WriteFile`
(lihat import `KERNEL32!WriteFile` dan fungsi `?Write@CHIDComm@@...` di
RVA `0x1370`). Artinya data dikirim sebagai **output report** ke endpoint
interrupt OUT, bukan feature report.

Padanan Android (`UsbHidTransport`):

| Windows | Android |
|---------|---------|
| `CreateFileW(path)` | `UsbManager.openDevice()` + `claimInterface(iface, force=true)` |
| `WriteFile(handle, buf, 64)` | `bulkTransfer(outEndpoint, buf, 64, timeout)` |
| `ReadFile(handle, buf, 64)` | `bulkTransfer(inEndpoint, buf, 64, timeout)` |
| fallback pipe kontrol | `controlTransfer(SET_REPORT / GET_REPORT)` |

`force = true` pada `claimInterface` penting: ia melepas driver keyboard
kernel Android sehingga perangkat tidak lagi mengetik ke sistem selama
dikonfigurasi (setara eksklusif `CreateFileW` di Windows).

Ukuran satu paket **selalu 64 byte** — berasal dari `push 0x40` pada RVA
`0x3bad`, `0x2355`, `0x3313`, `0x3b35`.

Driver mengirim ulang hingga **3 kali** bila balasan tidak sesuai
(loop `cmp ebx, 3` di RVA `0x3bd4`), dengan jeda `WaitForSingleObject(...,
1000 ms)`.

---

## 3. Layout frame (64 byte)

```
offset  ukuran  isi
------  ------  ---------------------------------------------------------
  0       1     command
  1       1     sub / length   (arti tergantung command)
  2       3     address 24-bit little-endian
  5       1     length byte    (jumlah data valid / argumen)
  6       2     CRC-16/CCITT-FALSE, little-endian
  8      56     payload
```

Dibangun oleh fungsi di RVA `0x3af0` (command generik `0x27`) dan
`0x2310` (command `0x04`).

### Aturan byte *length* (offset 5)

Byte ini **tidak selalu dipakai**. Driver hanya menulisnya untuk command yang
membawa data berukuran variabel:

| Command | Byte `length` diisi? | Bukti |
|---------|---------------------|-------|
| `0x04`, `0x16`, `0x27`, `0x26` | ya (panjang data) | ada tulis ke `[edi+0x509]` |
| `0x01` | tidak (pakai `sub`) | — |
| `0x03`, `0x0B`, `0x15` sub 1/2/3, `0x17`, `0x21`, `0x22` | tidak (tetap 0) | buffer di-`memset(0x40)`, `buf[5]` tak pernah ditulis |

Karena itu aplikasi mengirim `length = 0` untuk setelan LED (`0x15`) dan
commit (`0x17`), walaupun payload-nya berisi 1–4 byte. Menyamakan dengan
driver menghindari firmware menolak paket karena field yang tidak konsisten.

Contoh frame `cmd 0x01, sub 9` (baca 9 byte kapabilitas):

```
01 09 00 00 00 09 <crc-lo> <crc-hi> 00 00 ... (sampai 64 byte)
```

### CRC

| Parameter | Nilai |
|-----------|-------|
| Algoritma | CRC-16/CCITT-FALSE |
| Polynomial | `0x1021` |
| Initial | `0xFFFF` |
| Refleksi | tidak |
| XOR out | `0x0000` |
| Cakupan | seluruh 64 byte, dengan byte 6 dan 7 **dinolkan** |
| Check value | `0x29B1` untuk `"123456789"` |

Implementasi driver: RVA `0x1780` (menulis CRC ke `buf[6..7]`) memakai tabel
256 kata di RVA `0x1a040`. Implementasi Android: `Crc16Ccitt.kt`, diuji lewat
`ProtocolTest`.

---

## 4. Daftar command

| Command | Nama | RVA pembangun | Parameter | Keyakinan |
|---------|------|---------------|-----------|-----------|
| `0x01` | Baca memori | `0x1ef0`, `0x2090`, `0x2cd0` | `sub` = jumlah byte, `address` | Tinggi |
| `0x03` | Ganti mode/profil | `0x21e0` | `sub = 2` | Sedang |
| `0x04` | Tulis memori | `0x2310`, `0x2484` | `sub` = panjang, `address` | Tinggi |
| `0x0B` | Setelan global (kecerahan) | `0x31b0` | `sub` = nilai (payload tidak dipakai) | Sedang |
| `0x15` | Setelan LED perangkat | `0x2600` (sub 1), `0x2750` (sub 2), `0x28b0` (sub 3) | payload 1–4 byte, `length` = 0 | Sedang |
| `0x16` | Tabel keymap / LED | `0x29e0` | `sub 1` keymap, `sub 3` LED; `address` = offset byte | Tinggi |
| `0x17` | Commit / apply | `0x2b50` | `sub 1`, payload `F0 01 12 03` | Tinggi |
| `0x21` | Pilih efek LED | `0x32e0` | 2 byte parameter | Sedang |
| `0x22` | Tulis data efek LED | `0x3410` | `address` + panjang, per 56 byte | Sedang |
| `0x27` | Tulis blok generik | `0x3af0` | `sub` = panjang, `address`, payload | Tinggi |

Command lain yang ditemukan tetapi **belum** dipetakan ke fungsi UI:
`0x0C`, `0x1A` (sub 1/2), `0x24`, `0x25`, `0x26`, `0x31`.

### Contoh payload yang pasti

- Commit: `F0 01 12 03` (RVA `0x2bac`–`0x2bc1`).
- Handshake awal driver: `cmd 0x01 sub 9` → kirim generik (`0x1b60`)
  → `cmd 0x01 sub 8` (RVA `0x1088`).

---

## 5. Remap tombol (`DriverValue`)

Profil bawaan (`profile_online_1.json`) menunjukkan pola:

| Index | DriverValue | Arti |
|-------|-------------|------|
| 2 | `0x02000001` | LeftCtrl |
| 3 | `0x02000002` | LeftShift |
| 4 | `0x02000004` | LeftAlt |
| 5 | `0x02000008` | LeftGui |
| 10 | `0x02000400` | tombol **A** (HID usage `0x04`) |
| 11 | `0x02000500` | tombol **B** |
| 1 | `0x02006500` | Application/Menu (usage `0x65`) |

Encoding 32-bit little-endian:

```
byte 0 : modifier mask  (0x01 LCtrl, 0x02 LShift, 0x04 LAlt, 0x08 LGui,
                         0x10 RCtrl, 0x20 RShift, 0x40 RAlt, 0x80 RGui)
byte 1 : HID keyboard usage (0x04 = A, 0x28 = Enter, ...)
byte 2 : 0x00
byte 3 : tipe entri, 0x02 = tombol keyboard biasa
```

Rumus: `DriverValue = (0x02 << 24) | (usage << 8) | modifier`.

Driver menulis tabel ini sebagai array `dword` per index
(`WriteDevice`, RVA `0x4b19`–`0x4b74`).

### Alamat tabel keymap

`WriteDevice` mengirim tabel dengan `cmd 0x16 sub 1`, alamat mulai `0`
(RVA `0x4bc3` → `xor ecx, ecx`) dan naik `0x38` (56) per paket
(RVA `0x4bfe`). Karena isi payload adalah byte tabel apa adanya, alamat
adalah **offset byte**; satu tombol menempati 4 byte, jadi tombol `logic`
berada di `logic * 4`.

Aplikasi menulis hanya tombol yang diubah (4 byte per paket) supaya tombol
lain tidak tertimpa.

`KeySet[].Index` pada profil driver **sama dengan** `LogicCode` pada
`keymap.js` untuk 44 dari 73 entri yang bisa dipasangkan; sisanya adalah
tombol Fn/khusus. Layout fisik ada di `assets/zx68_layout.json`.

---

## 6. Pencahayaan

Struktur `LESet` pada profil driver (`profile_online_1.json`, efek "RGB渐变"):

```json
{
  "LEModel": 241, "LESubModel": 1, "LELight": 18,
  "LESpeed": 3, "LEDir": 0, "LEColor": 0, "LEEnable": 15
}
```

- `LEModel` — kode efek (241 = RGB gradasi).
- `LELight` — kecerahan, 0..18 pada profil bawaan.
- `LESpeed` — kecepatan, 0..3.
- `LEDir` — arah animasi.
- `LEColor` — indeks warna.
- `LEEnable` — mask zona LED aktif (15 = semua zona).

Pemetaan ke command yang dipakai aplikasi:

| Command | RVA | Argumen |
|---------|-----|---------|
| `0x15` sub 1 | `0x2600` | **4 byte** payload: `[LEModel, LESpeed, LELight, LEDir]` |
| `0x15` sub 2 | `0x2750` | 1 byte: `LEEnable` |
| `0x15` sub 3 | `0x28b0` | 1 byte: `LEColor` |
| `0x0B` | `0x31b0` | 1 byte di **`sub`** (tanpa payload) — belum dipakai UI |
| `0x17` | `0x2b50` | commit, payload `F0 01 12 03` |

Bukti bentuk paket: `0x2600` memakai `ret 0x10` (empat argumen) dan menulis
al ke `buf[8]`, `cl` ke `buf[9]`, `dl` ke `buf[10]`, argumen keempat ke
`buf[11]`. `0x2750`/`0x28b0` hanya mengisi `buf[8]`. `0x31b0` hanya mengisi
`buf[1]` dan **tidak** menyalin payload, sehingga `cmd 0x0B` membawa nilainya
di `sub`, bukan di payload.

Daftar 34 nama efek ada di `assets/zx68_effects.json` (dari
`res/data/le/lelist_en.json`). Data parameter tiap efek disimpan driver dalam
file biner `.le` (terkompresi, header `1FMC`) sehingga **pemetaan nama efek →
`LEModel` belum lengkap**; lihat bagian 8.

---

## 7. Format balasan

Balasan memakai layout yang sama dengan permintaan:

- byte 0 = command yang di-echo
- byte 1 = sub yang di-echo
- byte 6..7 = CRC-16/CCITT-FALSE dari seluruh 64 byte (byte 6..7 dinolkan)

Driver membandingkan `cmd`/`sub` balasan dengan yang dikirim
(RVA `0x1fe2`–`0x2031`) dan memverifikasi CRC. `M3Frame.isValidResponse()`
melakukan hal yang sama.

Banyak perintah tulis tidak dibalas; aplikasi menganggapnya sukses bila
pengiriman berhasil (perilaku ini juga dipakai driver: `expectReply = false`).

---

## 8. Batas dan hal yang belum pasti

Ditandai jujur agar tidak menyesatkan saat debug di perangkat asli:

1. **Pemetaan efek → `LEModel`**: daftar nama efek sudah lengkap, tetapi kode
   numerik tiap efek berada di file `.le` biner. Aplikasi memakai `241` untuk
   RGB dan `index + 1` untuk efek lain. Konsol protokol disediakan untuk
   menemukan nilai yang benar per efek.
2. **Urutan field pada `0x15 sub 1`**: bentuknya pasti 4 byte, tetapi urutan
   `[model, speed, brightness, dir]` disimpulkan dari urutan `LESet` pada
   profil driver, bukan dari nama argumen (optimasi compiler menghapusnya).
3. **`0x0B`**: nilainya dikirim di `sub` (terbukti dari RVA `0x31b0`), namun
   arti nilai tersebut (kecerahan? mode?) belum dipastikan — belum dipakai UI.
4. **Tabel LED per tombol** (`0x16 sub 3`): panjang = `rows × columns`
   (dibaca dari `cmd 0x01 sub 9`), jumlah byte per LED belum dipastikan
   (dugaan 3 byte RGB).
5. **Command `0x03`, `0x0C`, `0x1A`, `0x24`–`0x26`, `0x31`** belum dipetakan
   ke fitur UI.

Cara memastikan: kirim lewat **Konsol protokol** di aplikasi, amati reaksi
keyboard, lalu sesuaikan konstanta di `ProtocolCommands`/`Zx68Protocol`.
