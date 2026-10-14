# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot es un agente personal de código abierto derivado de [nanoMuse](https://github.com/nano-muse/nanoMuse), cuyas aplicaciones móviles se basan en [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis). Su asistente predeterminado es **Dot**. El repositorio incluye Android, iOS, escritorio, consola Web y runtime Python.

## Versión preliminar para Android · 1.1.0 Crystal

**[Descargar el APK de Android](https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk)** · [Versión y sumas de verificación](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0) · [Notas de la versión](../releases/v1.1.0.md)

La primera versión preliminar de Nanodot para Android requiere **Android 8.0+ / ARM64**: versión **1.1.0**, versionCode **45**. `Nanodot-1.1.0-arm64.apk` usa una clave de firma de distribución dedicada a Nanodot. Las notas explican la instalación y la verificación del archivo.

Incluye la esfera de cristal Dot, chat flotante en un panel de cristal azul, preguntas sobre una región seleccionada de la pantalla y guía Care. Los modelos configurados explícitamente para imágenes reciben la imagen enmascarada; los demás usan el texto accesible de la selección. Care conserva las instrucciones y ofrece confirmación de pasos y Stop.

El tema sigue al sistema; se conservan los nombres, SOUL.md, avatares, animaciones, modelos y preferencias personalizados. La voz continua y las actualizaciones automáticas están desactivadas por defecto.

**Límites de la versión preliminar:** las pruebas anteriores usaron un emulador API 35 y escenarios controlados, y no sustituyen la validación de este APK en hardware real. Quedan pendientes tareas Care completas con un proveedor real, aplicaciones arbitrarias, dispositivos ARM64, voz en chino y la validación actual de las otras plataformas. Consulta el [registro de verificación](../android-verification.md).

## Compilación e instalación

Compila Android localmente desde este checkout. Los paquetes debug y release son distintos; release requiere una clave de firma propia y nunca usa una clave debug como alternativa. El script no publica, sube archivos ni crea tags.

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

Consulta la [guía de compilación](../build.md), el [contrato de marca](../brand.md) y la [documentación](../index.md). La versión preliminar firmada está en el [lanzamiento 1.1.0 de Nanodot](https://github.com/Mola-maker/nanodot/releases/tag/v1.1.0). Las descargas y TestFlight de nanoMuse pertenecen a otro producto.

Android usa `io.github.nanodot.app` y `nanodot://`. Se conservan los paquetes internos `com.openminis.app` e `io.github.nanomuse.*`, las rutas `minis://` y los directorios del sandbox. El nuevo identificador tiene almacenamiento privado separado; los datos nanoMuse necesitan exportación/importación explícita.

## Modelos y servicios

Usa tu propio proveedor de modelos o configura el relay existente. **nanoMuse Cloud** conserva el nombre y los endpoints del servicio original. Nanodot no afirma operarlo ni promete cuotas gratuitas; las cuentas y políticas de datos dependen del proveedor.

## Contribuciones y licencia

Informa de problemas de este fork en las [issues de Nanodot](https://github.com/Mola-maker/nanodot/issues). Lee [CONTRIBUTING.md](../../CONTRIBUTING.md) y [AGENTS.md](../../AGENTS.md); los commits requieren sign-off DCO. Licencia: [GPL-3.0-or-later](../../LICENSE), con créditos en [NOTICE](../../NOTICE) y [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). Nanodot es independiente de Meta y su marca Muse.

## Historia y créditos del proyecto original

Las noticias, cita, tarjetas de mantenedores y colaboradores y agradecimientos siguientes son archivos de nanoMuse. Sus fechas, nombres y enlaces describen el proyecto original, no versiones ni mantenedores de Nanodot.

<details>
<summary>nanoMuse</summary>

## 🗞️ Novedades

- `2026-10-09` 🚀 Ya está la versión [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0), para el teléfono, el ordenador y el navegador; se instala sobre la anterior conservando tus datos, y sus notas de la versión recogen cada cambio.
- `2026-10-08` 🤗 Nuestro artículo está en [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699), tercero en la lista del 8 de octubre; las preguntas y los comentarios son bienvenidos en la página del artículo.
- `2026-10-07` 📄 Nuestro artículo está disponible en [arXiv](https://arxiv.org/abs/2610.08699): *nanoMuse: An Open-Source Personal Agent for Every Device You Own*, de Guangyi Liu, Yong Liu y Jiangning Zhang (Universidad de Zhejiang); la cita está al final de esta página.
- `2026-09-25` 🎉 nanoMuse se publica en GitHub bajo GPL-3.0-or-later: una aplicación Android construida sobre OpenMinis 1.13; el relay, la aplicación de escritorio y la aplicación para iPhone llegaron en los días siguientes.

Todas las versiones: [releases](https://github.com/nano-muse/nanoMuse/releases).

## Mantenedores

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## Colaboradores de la comunidad

Todas las personas con un cambio integrado en `main`, según la lista de colaboradores de GitHub.

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ Cita

Si nanoMuse te resulta útil, te agradecemos que cites el artículo.

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

## Agradecimientos

nanoMuse se apoya en el trabajo de otros; [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) recoge las licencias.

- [OpenMinis](https://github.com/OpenMinis/OpenMinis): el agente en el dispositivo sobre el que está construida la app del teléfono, con [proot](https://github.com/nano-muse/proot) y [Alpine Linux](https://alpinelinux.org/) para el sandbox.
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): el harness de agentes del que la app de escritorio es un plugin.
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) (ByteDance): el operador de las manos del escritorio es una adaptación del suyo, y los marcadores del escenario siguen su ScreenMarker.
- [MobileGym](https://github.com/Purewhiter/mobilegym), [MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench), [PhoneHarness](https://github.com/PhoneHarness/PhoneHarness), [CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse), [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM), [ClawGUI](https://github.com/ZJU-REAL/ClawGUI): el operador del teléfono, las trazas y las ideas de producto.

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
