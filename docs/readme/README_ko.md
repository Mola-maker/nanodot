# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot은 [nanoMuse](https://github.com/nano-muse/nanoMuse)를 기반으로 한 오픈소스 개인 에이전트입니다. 상위 프로젝트의 모바일 앱은 [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis)을 기반으로 합니다. 기본 어시스턴트는 **Dot**입니다. Android, iOS, 데스크톱, Web 콘솔과 Python 런타임이 들어 있습니다.

## Android 사전 출시 · 1.1.0 Crystal

**[Android APK 다운로드](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)** · [릴리스와 체크섬](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0) · [릴리스 노트](../releases/v1.1.0.md)

Nanodot의 첫 Android 사전 출시 버전은 **Android 8.0+ / ARM64**용입니다. 버전은 **1.1.0**, versionCode는 **45**입니다. `Nanodot-1.1.0-arm64.apk`는 Nanodot 전용 배포 키로 서명됩니다. 설치와 파일 검증 방법은 릴리스 노트를 확인하세요.

이 미리 보기에는 수정 구슬 Dot, 파란 유리 패널의 플로팅 채팅, 선택한 화면 영역에 대한 질문, Care 안내가 포함됩니다. 이미지 지원이 명시적으로 설정된 모델에는 마스킹한 이미지를 보내고, 다른 모델에는 선택 영역에서 가져올 수 있는 텍스트를 사용합니다. Care는 지시를 대화에 보관하고 단계 완료 확인과 Stop을 제공합니다.

테마는 시스템을 따르며 사용자 이름, SOUL.md, 아바타, 애니메이션, 모델과 설정은 보존됩니다. 연속 음성과 자동 업데이트는 기본적으로 꺼져 있습니다.

**사전 출시 검증 범위:** 기존 검증은 API 35 에뮬레이터와 통제된 테스트 환경에서 이루어졌으며, 이번 배포 APK의 실제 기기 검증을 대신하지 않습니다. 실제 모델 제공자를 통한 Care 작업의 완전한 성공, 임의의 앱, ARM64 실제 기기, 중국어 음성 입력·출력, 다른 플랫폼의 현재 동작 검증은 아직 완료되지 않았습니다. [검증 기록](../android-verification.md)을 확인하세요.

## 빌드와 설치

이 checkout에서 Android를 로컬로 빌드하세요. debug와 release 패키지는 별개입니다. release에는 전용 서명 키가 필요하며 debug 키로 대체하지 않습니다. 스크립트는 게시, 업로드 또는 태그 생성을 하지 않습니다.

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

[빌드 안내](../build.md), [브랜드 계약](../brand.md), [문서](../index.md)를 참고하세요. 서명된 Android 미리 보기는 [Nanodot 1.1.0 릴리스](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0)에서 받을 수 있습니다. nanoMuse 다운로드와 TestFlight 빌드는 다른 제품입니다.

Android ID는 `io.github.nanodot.app`, 외부 scheme은 `nanodot://`입니다. 내부 `com.openminis.app`, `io.github.nanomuse.*` 패키지, `minis://` 경로와 샌드박스 경로는 유지합니다. 새 ID는 별도 개인 저장소를 사용하므로 nanoMuse 데이터는 명시적으로 내보내고 가져와야 합니다.

## 모델과 서비스

자신의 모델 제공자를 사용하거나 기존 릴레이 연동을 설정하세요. **nanoMuse Cloud**는 상위 서비스의 이름과 엔드포인트를 유지합니다. Nanodot은 서비스를 운영한다고 주장하거나 무료 사용량을 약속하지 않습니다. 계정과 데이터 정책은 서비스 제공자가 정합니다.

## 기여와 라이선스

이 fork의 문제는 [Nanodot issues](https://github.com/Mola-maker/nanodot/issues)에 보고하세요. [CONTRIBUTING.md](../../CONTRIBUTING.md)와 [AGENTS.md](../../AGENTS.md)를 읽고 커밋에 DCO sign-off를 넣으세요. [GPL-3.0-or-later](../../LICENSE)를 적용하며 상위 크레딧은 [NOTICE](../../NOTICE)와 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)에 유지합니다. Nanodot은 Meta나 Muse 상표와 무관합니다.

## 상위 프로젝트의 기록과 크레딧

아래 뉴스, 논문 인용, 메인테이너와 기여자 카드, 감사의 말은 nanoMuse 기록입니다. 원래 날짜, 이름과 링크를 보존하며 Nanodot 릴리스나 메인테이너를 뜻하지 않습니다.

<details>
<summary>nanoMuse</summary>

## 🗞️ 소식

- `2026-10-09` 🚀 버전 [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0)이 나왔습니다. 휴대폰과 데스크톱과 브라우저 모두를 위한 버전입니다; 이전 버전 위에 설치되어 데이터가 그대로 남고, 릴리스 노트에 모든 변경 사항이 적혀 있습니다.
- `2026-10-08` 🤗 저희 논문이 [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699)에 올라 10월 8일 목록에서 3위를 차지했습니다; 질문과 의견은 논문 페이지에 남겨 주세요.
- `2026-10-07` 📄 저희 논문이 [arXiv](https://arxiv.org/abs/2610.08699)에 공개되었습니다: 『nanoMuse: An Open-Source Personal Agent for Every Device You Own』, 저자 Guangyi Liu, Yong Liu, Jiangning Zhang(저장대학교); 인용 정보는 이 페이지 끝에 있습니다.
- `2026-09-25` 🎉 nanoMuse를 GPL-3.0-or-later로 GitHub에 공개했습니다: OpenMinis 1.13을 바탕으로 한 Android 앱입니다; 릴레이, 데스크톱 앱, iPhone 앱이 그 뒤 며칠에 걸쳐 차례로 더해졌습니다.

모든 버전: [releases](https://github.com/nano-muse/nanoMuse/releases).

## 메인테이너

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## 커뮤니티 기여자

`main`에 변경이 반영된 모든 분, GitHub 기여자 목록 기준입니다.

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ 인용

nanoMuse가 도움이 되었다면 논문을 인용해 주세요.

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

## 감사의 말

nanoMuse는 다른 사람들이 만든 것 위에 세워졌습니다. 조건은 [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md)에.

- [OpenMinis](https://github.com/OpenMinis/OpenMinis): 휴대폰 앱의 바탕이 된 온디바이스 에이전트. 샌드박스는 [proot](https://github.com/nano-muse/proot)와 [Alpine Linux](https://alpinelinux.org/).
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): 데스크톱 앱이 플러그인으로 올라가 있는 에이전트 하네스.
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) (ByteDance): 데스크톱 손의 오퍼레이터는 이것을 포팅한 것이고, 스테이지의 마커는 ScreenMarker를 따릅니다.
- [MobileGym](https://github.com/Purewhiter/mobilegym), [MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench), [PhoneHarness](https://github.com/PhoneHarness/PhoneHarness), [CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse), [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM), [ClawGUI](https://github.com/ZJU-REAL/ClawGUI): 휴대폰 오퍼레이터, 트레이스, 제품 아이디어.

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
