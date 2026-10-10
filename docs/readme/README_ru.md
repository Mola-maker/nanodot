# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot: персональный агент с открытым исходным кодом на основе [nanoMuse](https://github.com/nano-muse/nanoMuse), чьи мобильные приложения основаны на [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis). Ассистент по умолчанию: **Dot**. Репозиторий содержит Android, iOS, настольную оболочку, Web-консоль и среду Python.

## Объём P0

P0 задаёт имя Nanodot, общую конфигурацию бренда, локальные изображения Dot и безопасные значения по умолчанию. Новый плавающий шар и режим заботы относятся к следующим этапам; текущие настройки не включают эти функции и не выдают разрешения доступности.

Тема следует системе. Dot имеет пять встроенных статичных выражений. Пользовательские имена, SOUL.md, аватары, анимации, модели и настройки сохраняются. Непрерывный голос и автоматические обновления по умолчанию отключены; проверяйте выпуски этого репозитория вручную.

Сохраняется версия **1.0.1**, Android versionCode **44**. Конфигурации платформ синхронизированы. Установка и обновление на реальном устройстве ещё требуют проверки; Linux не подтверждает сборку Xcode и нативных пакетов Windows/macOS.

## Сборка и установка

Собирайте Android локально из этого checkout. Пакеты debug и release различаются; release требует отдельного ключа подписи без подмены ключом debug. Скрипт не публикует, не загружает файлы и не создаёт теги.

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

См. [руководство по сборке](../build.md), [контракт бренда](../brand.md) и [документацию](../index.md). Опубликованные пакеты Nanodot должны находиться в [выпусках Nanodot](https://github.com/Mola-maker/nanodot/releases). Загрузки и TestFlight nanoMuse относятся к другому продукту.

Android использует `io.github.nanodot.app` и `nanodot://`. Внутренние пакеты `com.openminis.app` и `io.github.nanomuse.*`, маршруты `minis://` и пути песочницы сохранены. Новый ID имеет отдельное приватное хранилище; данные nanoMuse требуют явного экспорта/импорта.

## Модели и сервисы

Используйте своего поставщика моделей или настройте существующее подключение к relay. **nanoMuse Cloud** сохраняет имя и адреса исходного сервиса. Nanodot не заявляет, что управляет им, и не обещает бесплатный лимит; аккаунты и политика данных определяются поставщиком.

## Участие и лицензия

Сообщайте о проблемах этого fork через [Nanodot issues](https://github.com/Mola-maker/nanodot/issues). Прочтите [CONTRIBUTING.md](../../CONTRIBUTING.md) и [AGENTS.md](../../AGENTS.md); коммиты требуют DCO sign-off. Лицензия: [GPL-3.0-or-later](../../LICENSE), авторство сохранено в [NOTICE](../../NOTICE) и [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). Nanodot не связан с Meta и её маркой Muse.

## История и авторство исходного проекта

Новости, цитата, карточки сопровождающих и участников и благодарности ниже относятся к nanoMuse. Их исходные даты, имена и ссылки относятся к исходному проекту, а не к выпускам или сопровождающим Nanodot.

<details>
<summary>nanoMuse</summary>

## 🗞️ Новости

- `2026-10-09` 🚀 Вышла версия [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0), для телефона, компьютера и браузера; она ставится поверх предыдущей с сохранением ваших данных, а в заметках к выпуску перечислено каждое изменение.
- `2026-10-08` 🤗 Наша статья попала в [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699) и заняла третье место в списке за 8 октября; вопросы и комментарии можно оставить на странице статьи.
- `2026-10-07` 📄 Наша статья доступна на [arXiv](https://arxiv.org/abs/2610.08699): *nanoMuse: An Open-Source Personal Agent for Every Device You Own*, авторы Guangyi Liu, Yong Liu и Jiangning Zhang (Чжэцзянский университет); ссылка для цитирования в конце этой страницы.
- `2026-09-25` 🎉 nanoMuse выпущен на GitHub под GPL-3.0-or-later: Android-приложение на основе OpenMinis 1.13; реле, настольное приложение и приложение для iPhone появились в следующие дни.

Все версии: [releases](https://github.com/nano-muse/nanoMuse/releases).

## Сопровождающие

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## Участники сообщества

Все, чьё изменение попало в `main`, по списку участников GitHub.

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ Цитирование

Если nanoMuse вам пригодился, пожалуйста, сошлитесь на статью.

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

## Благодарности

nanoMuse опирается на работу других людей; условия — в [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

- [OpenMinis](https://github.com/OpenMinis/OpenMinis): агент на устройстве, на котором построено приложение для телефона, с [proot](https://github.com/nano-muse/proot) и [Alpine Linux](https://alpinelinux.org/) для песочницы.
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): агентный harness, плагином к которому является приложение для компьютера.
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) (ByteDance): оператор рук на компьютере портирован с их кода, а маркеры сцены следуют их ScreenMarker.
- [MobileGym](https://github.com/Purewhiter/mobilegym), [MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench), [PhoneHarness](https://github.com/PhoneHarness/PhoneHarness), [CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse), [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM), [ClawGUI](https://github.com/ZJU-REAL/ClawGUI): оператор телефона, трассы и продуктовые идеи.

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
