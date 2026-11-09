# agent.md

Panduan kerja agen untuk proyek ini ada di **[AGENTS.md](AGENTS.md)** — file
itu yang dipakai sebagai acuan utama (nama `AGENTS.md` adalah konvensi yang
dibaca otomatis oleh banyak agen).

Isi singkat yang paling penting:

1. Protokol 64 byte ZX68 (CRC-16/CCITT-FALSE, alamat 24-bit, payload 56 byte)
   **tidak boleh diubah tanpa bukti baru** dari `COR-M3-MKDriver.dll`.
2. Byte `length` hanya diisi untuk command data (`0x04`, `0x16`, `0x27`);
   untuk `0x15` dan `0x17` nilainya **0**.
3. Build: `./gradlew :app:assembleDebug`; tes: `./gradlew :app:testDebugUnitTest`;
   lint harus 0 error.
4. APK hasil build disalin ke `dist/ParadoxZx68-1.0.0-debug.apk`.
5. Playbook perbaikan bug langkah demi langkah ada di `AGENTS.md` §4, dan
   matriks gejala → penyebab → perbaikan ada di §5.
6. Jangan mengklaim perilaku sudah teruji pada keyboard fisik bila tidak ada
   perangkat yang dicoba.
