# Cara driver ZX68 diekstrak dan dianalisis

Dokumen ini mencatat langkah yang benar-benar dilakukan untuk mendapatkan
protokol di `docs/PROTOCOL.md`, supaya hasilnya bisa diverifikasi ulang.

---

## 1. Sumber

| Item | Nilai |
|------|-------|
| Nama file | `Ghost ZX68_Driver ver.6.0.0.3.exe` |
| Ukuran | 33.927.144 byte |
| MD5 | `c4eda1a3f62f431896e2daab3a0d8042` |
| Format | PE32 (GUI) installer **Inno Setup**, aplikasi Delphi |

Installer diunduh dari Google Drive (berkas publik, 32 MB) kemudian disimpan
di `_download/zx68_driver.exe` (folder ini di-`.gitignore`).

## 2. Ekstraksi installer

Installer Inno Setup bisa dibuka dengan `innoextract`. Pada mesin tanpa akses
root, paket Debian/Ubuntu diambil lalu diekstrak ke direktori lokal:

```bash
# 1. unduh paket
curl -O http://archive.ubuntu.com/ubuntu/pool/universe/i/innoextract/innoextract_1.9-0.1build1_amd64.deb
curl -O http://archive.ubuntu.com/ubuntu/pool/main/b/boost1.83/libboost-filesystem1.83.0_1.83.0-5ubuntu1_amd64.deb
curl -O http://archive.ubuntu.com/ubuntu/pool/main/b/boost1.83/libboost-iostreams1.83.0_1.83.0-5ubuntu1_amd64.deb
curl -O http://archive.ubuntu.com/ubuntu/pool/main/b/boost1.83/libboost-program-options1.83.0_1.83.0-5ubuntu1_amd64.deb

# 2. ekstrak isi paket ke folder lokal (tanpa root)
for d in *.deb; do dpkg-deb -x "$d" root; done
export LD_LIBRARY_PATH=$PWD/root/usr/lib/x86_64-linux-gnu

# 3. buka installer
root/usr/bin/innoextract -e -s -d out "_download/zx68_driver.exe"
```

Hasil: 259 file di `out/app/`.

## 3. Arsitektur yang ditemukan

```
CMS.exe                    (MFC, 597 KB)  launcher/UI lama
CMSEngine/CMSEngine.exe    (Qt + CEF, 1.8 MB)  host UI web + HTTP 127.0.0.1:8010
CMSEngine/driver/          (Vue.js bundle)  UI konfigurasi, 20 chunk
CMSEngine/libcef.dll       Chromium Embedded Framework

CDManager.dll              (127 KB)  manajer driver perangkat, impor HIDComm.dll
CDDriver/COR-M3-MKDriver.dll (118 KB)  ← PROTOKOL SEBENARNYA (C++, MSVC 2008)
CDDriver/CDBootDriver.dll  (23 KB)   mode bootloader/firmware
HIDComm.dll                (21 KB)   wrapper HID.DLL (CHIDComm / CHIDCommSTD)

CMSEngine/driver/device/modellist.json      daftar VID/PID + FWID
CMSEngine/driver/device/656801940/          data ZX68 (keymap.js, profil)
CMSEngine/driver/res/data/le/               pustaka efek LED (.le)
```

UI web memanggil native lewat `window.callFunc` / `window.onFunc`
(injeksi V8 dari `CMSEngine.exe`), dengan payload
`{ "funcname": "ReadDevice", ... }`. Implementasi `funcname` ada di
`COR-M3-MKDriver.dll`.

## 4. Analisis statis `COR-M3-MKDriver.dll`

Alat: Python + `capstone` + `pefile` (lihat `tools/pe_tools.py`).

Langkah yang dipakai:

1. **Export** — hanya `CDDriverRegister` (RVA `0x1230`). Fungsi ini
   memvalidasi versi (`xor` dengan `[0x1001a254]` = `0x04020000`) lalu
   mengembalikan objek driver statis di `0x1001a258`.
2. **CRC** — ditemukan saat membaca `CDDriverRegister`: RVA `0x1250` adalah
   loop CRC dengan tabel di `0x1a040`. Tabelnya identik dengan
   CRC-16/CCITT-FALSE (poly `0x1021`), terbukti dari
   `table[1] = 0x1021`, `table[8] = 0x8108`.
3. **Pengirim paket** — RVA `0x3af0` membangun buffer 64 byte:
   `memset 0x40`, isi cmd/sub/alamat/length, `memcpy` payload, panggil CRC
   (`0x1780`), lalu `MakeHIDReport` (slot vtable `+0x1c`) dan `Write`
   (slot `+0x0c`).
4. **Daftar command** — semua fungsi yang memanggil CRC (`0x1780`) dicari
   lewat xref `call rel32`; hasilnya 23 fungsi, masing-masing dengan
   `mov byte ptr [esi], imm8` sebagai command.
5. **Vtable driver** — `0x10016328` berisi 12 method; yang relevan:
   `0x3ca0`, `0x3ce0`, `0x3d30`, `0x3d70` (setelan LED), `0x4520`
   (WriteDevice), `0x5050` (jalur LED/profil).
6. **JSON** — nama field (`KeySet`, `Index`, `DriverValue`, `KeyLE`,
   `DeviceLE`, `LESet`, `ModeIndex`, `LEData`) ditemukan di `.rdata` lalu
   xref-nya ditelusuri untuk memetakan arti tiap parameter.

### RVA penting

| RVA | Isi |
|-----|-----|
| `0x1230` | `CDDriverRegister` |
| `0x1250` | fungsi CRC (parameter: buffer, panjang, nilai awal) |
| `0x1780` | tulis CRC ke `buf[6..7]` |
| `0x1a040` | tabel CRC 256 × uint16 |
| `0x1b60` | command generik (cmd dari parameter) |
| `0x1ef0` | `cmd 0x01 sub 8` (versi firmware) |
| `0x2090` | `cmd 0x01 sub 9` (kapabilitas/matriks) |
| `0x21e0` | `cmd 0x03 sub 2` (ganti mode) |
| `0x2310` | `cmd 0x04` tulis memori |
| `0x2600`/`0x2750`/`0x28b0` | `cmd 0x15` sub 1/2/3 |
| `0x29e0` | `cmd 0x16` tabel keymap/LED |
| `0x2b50` | `cmd 0x17` commit |
| `0x32e0` | `cmd 0x21` pilih efek |
| `0x3410` | `cmd 0x22` data efek |
| `0x3af0` | pembangun paket generik `cmd 0x27` |
| `0x4b19` | penulisan `DriverValue` ke tabel |
| `0x4bc3` | loop tulis keymap (alamat mulai 0, langkah 0x38) |
| `0x16328` | vtable driver |

## 5. Data yang diekstrak menjadi aset aplikasi

`tools/extract_assets.py` mengubah:

| Sumber di driver | Aset aplikasi |
|------------------|---------------|
| `device/656801940/data/keymap.js` | `app/src/main/assets/zx68_layout.json` (93 entri) |
| `res/data/le/lelist_en.json` | `app/src/main/assets/zx68_effects.json` (34 efek) |

Jalankan ulang:

```bash
python3 tools/extract_assets.py --driver-dir _download/extracted/app
```

## 6. Yang tidak dilakukan

- Tidak ada emulasi/eksekusi driver (butuh Windows + perangkat asli).
- File `.le` (parameter efek) bertanda `1FMC` dan terkompresi; belum dibuka.
- `CMSEngine.exe` (Qt/CEF) tidak dibedah lebih jauh karena logika HID sudah
  ditemukan di `COR-M3-MKDriver.dll`.
