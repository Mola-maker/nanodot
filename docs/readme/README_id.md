# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot adalah agen pribadi sumber terbuka turunan [nanoMuse](https://github.com/nano-muse/nanoMuse), yang aplikasi ponselnya berbasis [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis). Asisten bawaannya bernama **Dot**. Repositori ini memuat Android, iOS, aplikasi desktop, konsol Web dan runtime Python.

## Prarilis Android · 1.1.0 Crystal

**[Unduh APK Android](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)** · [Rilis dan checksum](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0) · [Catatan rilis](../releases/v1.1.0.md)

Prarilis Android pertama Nanodot memerlukan **Android 8.0+ / ARM64**: versi **1.1.0**, versionCode **45**. `Nanodot-1.1.0-arm64.apk` memakai kunci penandatanganan rilis khusus Nanodot. Petunjuk instalasi dan pemeriksaan berkas ada dalam catatan rilis.

Pratinjau ini mencakup bola kristal Dot, percakapan mengambang dalam panel kaca biru, pertanyaan tentang area layar yang dipilih, dan panduan Care. Model yang secara eksplisit dikonfigurasi untuk gambar menerima gambar dengan bagian luar pilihan ditutupi; model lain memakai teks pilihan yang tersedia. Care menyimpan instruksi dan menyediakan konfirmasi langkah serta Stop.

Tema mengikuti sistem; nama, SOUL.md, avatar, animasi, model dan preferensi khusus tetap disimpan. Suara berkelanjutan dan pembaruan otomatis mati secara bawaan.

**Batas prarilis:** pengujian sebelumnya memakai emulator API 35 dan skenario terkontrol, bukan validasi APK rilis ini di perangkat nyata. Tugas Care lengkap dengan penyedia nyata, aplikasi sembarang, perangkat ARM64, suara bahasa Mandarin, dan validasi terkini platform lain masih perlu diuji. Baca [catatan verifikasi](../android-verification.md).

## Build dan instalasi

Build Android secara lokal dari checkout ini. Paket debug dan release terpisah; release memerlukan kunci penandatanganan khusus dan tidak beralih ke kunci debug. Skrip tidak menerbitkan, mengunggah atau membuat tag.

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

Baca [panduan build](../build.md), [kontrak merek](../brand.md) dan [dokumentasi](../index.md). Pratinjau bertanda tangan tersedia di [rilis Nanodot 1.1.0](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0). Unduhan dan TestFlight nanoMuse merupakan produk lain.

Android memakai `io.github.nanodot.app` dan `nanodot://`. Paket internal `com.openminis.app` dan `io.github.nanomuse.*`, rute `minis://` dan jalur sandbox tetap sama. ID baru memiliki penyimpanan privat terpisah; data nanoMuse perlu ekspor/impor eksplisit.

## Model dan layanan

Gunakan penyedia model sendiri atau konfigurasi integrasi relay yang ada. **nanoMuse Cloud** tetap memakai nama dan endpoint layanan asal. Nanodot tidak mengklaim mengoperasikannya atau menjanjikan kuota gratis; akun dan kebijakan data menjadi tanggung jawab penyedia layanan.

## Kontribusi dan lisensi

Laporkan masalah fork ini di [issues Nanodot](https://github.com/Mola-maker/nanodot/issues). Baca [CONTRIBUTING.md](../../CONTRIBUTING.md) dan [AGENTS.md](../../AGENTS.md); commit memerlukan sign-off DCO. Lisensi: [GPL-3.0-or-later](../../LICENSE), dengan atribusi di [NOTICE](../../NOTICE) dan [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). Nanodot tidak terkait dengan Meta atau merek Muse.

## Riwayat dan atribusi proyek asal

Berita, sitasi, kartu pemelihara dan kontributor serta ucapan terima kasih berikut adalah arsip nanoMuse. Tanggal, nama dan tautannya merujuk proyek asal, bukan rilis atau pemelihara Nanodot.

<details>
<summary>nanoMuse</summary>

## 🗞️ Kabar

- `2026-10-09` 🚀 Versi [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0) sudah tersedia, untuk ponsel, desktop, dan peramban; dipasang di atas versi sebelumnya dengan data tetap utuh, dan catatan rilisnya memuat setiap perubahan.
- `2026-10-08` 🤗 Makalah kami masuk [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699), peringkat ketiga di daftar 8 Oktober; pertanyaan dan komentar silakan disampaikan di halaman makalah.
- `2026-10-07` 📄 Makalah kami tersedia di [arXiv](https://arxiv.org/abs/2610.08699): *nanoMuse: An Open-Source Personal Agent for Every Device You Own*, oleh Guangyi Liu, Yong Liu, dan Jiangning Zhang (Universitas Zhejiang); sitasinya ada di akhir halaman ini.
- `2026-09-25` 🎉 nanoMuse dirilis di GitHub di bawah GPL-3.0-or-later: aplikasi Android yang dibangun di atas OpenMinis 1.13; relay, aplikasi desktop, dan aplikasi iPhone menyusul dalam beberapa hari berikutnya.

Semua versi: [releases](https://github.com/nano-muse/nanoMuse/releases).

## Pemelihara

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## Kontributor komunitas

Semua orang yang perubahannya sudah masuk ke `main`, dari daftar kontributor GitHub.

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ Sitasi

Jika nanoMuse bermanfaat bagi Anda, silakan kutip makalah ini.

```bibtex
@misc{liu2026nanomuseopensourcepersonalagent,
      title={nanoMuse: An Open-Source Personal Agent for Every Device You Own}, 
      author={Guangyi Liu and Yong Liu and Jiangning Zhang},
      year={2026},
      eprint={2610.08699},
      archivePrefix={arXiv},
      primaryClass={cs.AI},
      url={https://arxiv.org/abs/2610.08699}, 
}
```

## Ucapan terima kasih

nanoMuse berdiri di atas karya orang lain; [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) memuat ketentuannya.

- [OpenMinis](https://github.com/OpenMinis/OpenMinis): agen di perangkat yang menjadi dasar aplikasi ponsel, dengan [proot](https://github.com/nano-muse/proot) dan [Alpine Linux](https://alpinelinux.org/) untuk sandbox-nya.
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): harness agen tempat aplikasi desktop menjadi plugin-nya.
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) (ByteDance): operator tangan di desktop adalah porting dari milik mereka, dan penanda di panggung mengikuti ScreenMarker mereka.
- [MobileGym](https://github.com/Purewhiter/mobilegym), [MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench), [PhoneHarness](https://github.com/PhoneHarness/PhoneHarness), [CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse), [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM), [ClawGUI](https://github.com/ZJU-REAL/ClawGUI): operator ponsel, jejak, dan gagasan produknya.

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
