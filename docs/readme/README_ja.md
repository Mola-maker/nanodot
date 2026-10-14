# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot は [nanoMuse](https://github.com/nano-muse/nanoMuse) を基にしたオープンソースの個人エージェントです。上流のモバイルアプリは [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis) を基にしています。標準アシスタントの名前は **Dot** です。Android、iOS、デスクトップ、Web コンソール、Python ランタイムを含みます。

## Android プレビュー版 · 1.1.0 Crystal

**[Android APK をダウンロード](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)** · [リリースとチェックサム](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0) · [リリースノート](../releases/v1.1.0.md)

Nanodot 初の Android プレビュー版は **Android 8.0+ / ARM64** 向けです。バージョンは **1.1.0**、versionCode は **45** です。`Nanodot-1.1.0-arm64.apk` は Nanodot 専用の配布用鍵で署名されます。インストールとファイル検証の手順はリリースノートをご覧ください。

クリスタルの Dot、青いガラスパネルのフローティングチャット、画面の選択範囲についての質問、Care ガイドを含みます。画像対応が明示的に設定されたモデルにはマスク済み画像を送り、それ以外は取得可能な選択範囲のテキストを使います。Care は指示を会話に残し、手順の完了確認と Stop を提供します。

テーマはシステムに従い、独自の名前、SOUL.md、アバター、アニメーション、モデル、設定を保持します。連続音声と自動更新は初期状態では無効です。

**プレビュー版の検証範囲：**従来の検証は API 35 エミュレーターと制御されたテスト環境で行われており、この配布 APK の実機検証を代替しません。実際のモデル提供者を使った Care タスクの完遂、任意のアプリ、ARM64 実機、中国語の音声入力・出力、他プラットフォームの現在の動作検証は未完了です。[検証記録](../android-verification.md)をご覧ください。

## ビルドとインストール

この checkout から Android をローカルでビルドします。debug と release は別パッケージです。release は専用の署名鍵が必須で、debug 鍵にフォールバックしません。スクリプトは公開、アップロード、タグ作成を行いません。

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

[ビルド手順](../build.md)、[ブランド契約](../brand.md)、[ドキュメント](../index.md)を参照してください。署名付き Android プレビュー版は [Nanodot 1.1.0 リリース](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0)から入手できます。nanoMuse のダウンロードと TestFlight は別の製品です。

Android の ID は `io.github.nanodot.app`、外部 scheme は `nanodot://` です。内部の `com.openminis.app`、`io.github.nanomuse.*`、`minis://` ルート、サンドボックスのパスは保持します。新しい ID は独立したプライベートストレージを持つため、nanoMuse データには明示的なエクスポート/インポートが必要です。

## モデルとサービス

自分のモデル提供者を使うか、既存のリレー連携を設定できます。**nanoMuse Cloud** は上流サービスの名前とエンドポイントを維持します。Nanodot は運営主体を名乗らず、無料枠を約束しません。アカウントとデータ方針はサービス提供者が定めます。

## 貢献とライセンス

この fork の問題は [Nanodot issues](https://github.com/Mola-maker/nanodot/issues)へ報告してください。[CONTRIBUTING.md](../../CONTRIBUTING.md) と [AGENTS.md](../../AGENTS.md)を読み、コミットに DCO sign-off を付けてください。[GPL-3.0-or-later](../../LICENSE) に従い、上流の署名は [NOTICE](../../NOTICE) と [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)に残します。Nanodot は Meta および Muse 商標と無関係です。

## 上流の履歴とクレジット

以下のニュース、論文引用、メンテナーと貢献者のカード、謝辞は nanoMuse の記録です。元の日付、名前、リンクを保持しており、Nanodot のリリースやメンテナーを示すものではありません。

<details>
<summary>nanoMuse</summary>

## 🗞️ ニュース

- `2026-10-09` 🚀 バージョン [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0) を公開しました。スマートフォン、デスクトップ、ブラウザのすべてに対応し、前の版の上にそのままインストールしてデータを引き継ぎます。変更点はリリースノートにすべて記載しています。
- `2026-10-08` 🤗 私たちの論文が [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699) に掲載され、10 月 8 日のリストで 3 位になりました。質問やコメントは論文ページへどうぞ。
- `2026-10-07` 📄 私たちの論文が [arXiv](https://arxiv.org/abs/2610.08699) に公開されました：『nanoMuse: An Open-Source Personal Agent for Every Device You Own』、著者は Guangyi Liu、Yong Liu、Jiangning Zhang（浙江大学）。引用情報はこのページの末尾にあります。
- `2026-09-25` 🎉 nanoMuse を GPL-3.0-or-later のもと GitHub で公開しました：OpenMinis 1.13 をもとにした Android アプリです。リレー、デスクトップアプリ、iPhone アプリはその後の数日で順に加わりました。

すべてのバージョン：[releases](https://github.com/nano-muse/nanoMuse/releases)。

## メンテナー

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## コミュニティの貢献者

`main` に変更が取り込まれた全員。GitHub のコントリビューター一覧から。

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ 引用

nanoMuse が役に立ったら、論文を引用してください。

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

## 謝辞

nanoMuse は多くの人の成果の上に成り立っています。条件は [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) に。

- [OpenMinis](https://github.com/OpenMinis/OpenMinis)：スマホアプリの土台となったオンデバイスのエージェント。サンドボックスは [proot](https://github.com/nano-muse/proot) と [Alpine Linux](https://alpinelinux.org/)。
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：デスクトップアプリがプラグインとして載っているエージェントハーネス。
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop)(ByteDance)：デスクトップの手のオペレーターはその移植で、ステージのマーカーは ScreenMarker に倣っています。
- [MobileGym](https://github.com/Purewhiter/mobilegym)、[MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench)、[PhoneHarness](https://github.com/PhoneHarness/PhoneHarness)、[CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse)、[Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)、[ClawGUI](https://github.com/ZJU-REAL/ClawGUI)：スマホのオペレーター、トレース、製品の着想。

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
