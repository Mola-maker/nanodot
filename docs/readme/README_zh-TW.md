# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot 是基於 [nanoMuse](https://github.com/nano-muse/nanoMuse) 的開源個人智能體；上游手機端基於 [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis)。預設助理叫 **Dot**。倉庫包含 Android、iOS、桌面外殼、Web 控制台與 Python 執行環境。

## Android 預發佈 · 1.1.0 Crystal

**[下載 Android APK](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)** · [發佈頁與校驗值](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0) · [發佈說明](../releases/v1.1.0.md)

Nanodot 首個 Android 預發佈適用於 **Android 8.0+ / ARM64**，版本 **1.1.0**（versionCode **45**）。`Nanodot-1.1.0-arm64.apk` 使用 Nanodot 專用發行金鑰簽章；安裝與校驗步驟見發佈說明。

此預覽版包含水晶 Dot 懸浮球、藍色玻璃前台對話、螢幕圈選提問與 Care 關懷引導。明確支援圖像的模型會收到遮罩後的選區圖片，其他模型使用可取得的選區文字。Care 保留文字指令，提供步驟確認與 Stop。

主題跟隨系統；自訂名字、SOUL.md、頭像、動畫、模型與偏好保留。連續語音與自動更新預設關閉。

**預發佈邊界：**既有驗收來自 API 35 模擬器與受控測試，不能代替本次發行包的真機驗收。完整真實模型 Care 任務、任意應用程式、ARM64 實機、中文麥克風／語音輸出，以及目前其他平台的執行驗收仍未完成。請閱讀[驗收記錄](../zh/android-verification.md)。

## 建置與安裝

從目前 checkout 本地建置 Android。debug 與 release 套件分開；release 必須使用獨立簽章，不會退回 debug 金鑰。建置腳本不會發佈、上傳或建立 tag。

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

參見[建置指南](../zh/build.md)、[品牌契約](../zh/brand.md)與[專案文件](../zh/index.md)。簽章 Android 預覽版見本倉庫的 [1.1.0 發佈頁](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0)。上游 nanoMuse 下載與 TestFlight 建置屬於另一個產品。

Android applicationId 為 `io.github.nanodot.app`，外部 scheme 為 `nanodot://`。內部 `com.openminis.app`、`io.github.nanomuse.*` 套件、`minis://` 路由與沙箱路徑保留。新 applicationId 使用獨立私有儲存；nanoMuse 資料需要明確匯出/匯入，不能自動讀取舊應用程式檔案。

## 模型與服務

可使用自己的模型服務商，或設定既有中繼整合。**nanoMuse Cloud** 保留上游服務的名稱與端點；Nanodot 不宣稱營運該服務，也不承諾免費額度。帳號、可用性與資料政策由該服務提供方決定。

## 貢獻與授權

此 fork 的問題請提交至 [Nanodot issues](https://github.com/Mola-maker/nanodot/issues)。閱讀 [CONTRIBUTING.md](../../CONTRIBUTING.md) 與 [AGENTS.md](../../AGENTS.md)，提交需附 DCO sign-off。授權為 [GPL-3.0-or-later](../../LICENSE)，上游署名保留於 [NOTICE](../../NOTICE) 與 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。Nanodot 與 Meta 及其 Muse 商標無關。

## 上游歷史與署名

以下動態、論文引用、維護者與貢獻者卡片、致謝來自 nanoMuse。原有日期、名字與連結保持，指向上游專案，不代表 Nanodot 的發佈或維護者。

<details>
<summary>nanoMuse</summary>

## 🗞️ 最新消息

- `2026-10-09` 🚀 版本 [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0) 發布，手機、電腦和瀏覽器都有；安裝在上一版之上，資料保留，每一處改動都寫在發布說明裡。
- `2026-10-08` 🤗 我們的論文登上 [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699)，在 10 月 8 日的榜單上排第三；歡迎到論文頁提問和留言。
- `2026-10-07` 📄 我們的論文已發布在 [arXiv](https://arxiv.org/abs/2610.08699)：《nanoMuse: An Open-Source Personal Agent for Every Device You Own》，作者 Guangyi Liu、Yong Liu、Jiangning Zhang（浙江大學）；引用格式見本頁末尾。
- `2026-09-25` 🎉 nanoMuse 以 GPL-3.0-or-later 發布在 GitHub：一個基於 OpenMinis 1.13 的 Android 應用程式；中繼、電腦版和 iPhone 版在隨後幾天陸續到來。

全部版本：[releases](https://github.com/nano-muse/nanoMuse/releases)。

## 維護者

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## 社群貢獻者

每一位有改動合併進 `main` 的人，取自 GitHub 的貢獻者列表。

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ 引用

如果 nanoMuse 對你有幫助，歡迎引用我們的論文。

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

## 致謝

nanoMuse 站在別人的工作之上；條款見 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)。

- [OpenMinis](https://github.com/OpenMinis/OpenMinis)：手機 App 所基於的裝置端智慧體，沙箱來自 [proot](https://github.com/nano-muse/proot) 和 [Alpine Linux](https://alpinelinux.org/)。
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：桌面 App 作為外掛程式執行在它上面。
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop)（字節跳動）：桌面端「手」的操作器移植自它，舞台上的標記參考它的 ScreenMarker。
- [MobileGym](https://github.com/Purewhiter/mobilegym)、[MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench)、[PhoneHarness](https://github.com/PhoneHarness/PhoneHarness)、[CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse)、[Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)、[ClawGUI](https://github.com/ZJU-REAL/ClawGUI)：手機操作器、軌跡和產品思路。

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
