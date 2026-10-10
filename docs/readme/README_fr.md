# Nanodot

<p align="center"><img src="../../assets/brand/nanodot-mark.svg" alt="Nanodot · Dot" width="112" height="112"></p>

[English](https://github.com/Mola-maker/nanodot/blob/main/README.md) | [简体中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh.md) | [繁體中文](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_zh-TW.md) | [Español](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_es.md) | [Français](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_fr.md) | [Bahasa Indonesia](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_id.md) | [日本語](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ja.md) | [한국어](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ko.md) | [Русский](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_ru.md) | [Tiếng Việt](https://github.com/Mola-maker/nanodot/blob/main/docs/readme/README_vi.md)

Nanodot est un agent personnel open source dérivé de [nanoMuse](https://github.com/nano-muse/nanoMuse), dont les applications mobiles reposent sur [OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis). Son assistant par défaut est **Dot**. Ce dépôt contient les applications Android et iOS, le client de bureau, la console Web et le runtime Python.

## Périmètre P0

P0 définit l’identité Nanodot, la configuration de marque commune, les images Dot hors ligne et les paramètres par défaut. La nouvelle bulle flottante et le mode attention sont prévus pour des étapes ultérieures ; leurs préférences n’activent aucune fonction ni autorisation d’accessibilité.

Le thème suit le système. Dot possède cinq expressions statiques intégrées. Les noms, SOUL.md, avatars, animations, modèles et préférences personnalisés sont conservés. La voix continue et les mises à jour automatiques sont désactivées par défaut ; consultez les versions de ce dépôt manuellement.

La version héritée reste **1.0.1**, avec Android versionCode **44**. Les configurations des plateformes sont synchronisées. L’installation et la mise à niveau sur appareil réel restent à valider ; Linux ne valide pas Xcode ni les paquets natifs Windows/macOS.

## Compilation et installation

Compilez Android localement depuis ce checkout. Les paquets debug et release sont distincts ; release exige une clé de signature dédiée sans recours à une clé debug. Le script ne publie, ne téléverse et ne crée aucun tag.

```sh
bash scripts/build-nanodot-apk.sh debug
# Dedicated local signing configuration is required for release.
bash scripts/build-nanodot-apk.sh release
```

Consultez le [guide de compilation](../build.md), le [contrat de marque](../brand.md) et la [documentation](../index.md). Les paquets Nanodot publiés seront sur la [page des versions Nanodot](https://github.com/Mola-maker/nanodot/releases). Les téléchargements et TestFlight de nanoMuse sont ceux d’un autre produit.

Android utilise `io.github.nanodot.app` et `nanodot://`. Les packages internes `com.openminis.app` et `io.github.nanomuse.*`, les routes `minis://` et les chemins du bac à sable sont conservés. Le nouvel identifiant possède son propre stockage privé ; les données nanoMuse nécessitent un export/import explicite.

## Modèles et services

Utilisez votre propre fournisseur de modèles ou configurez le relais existant. **nanoMuse Cloud** conserve le nom et les endpoints du service amont. Nanodot ne prétend pas exploiter ce service et ne promet aucun quota gratuit ; les comptes et politiques de données dépendent du fournisseur.

## Contributions et licence

Signalez les problèmes de ce fork dans les [issues Nanodot](https://github.com/Mola-maker/nanodot/issues). Lisez [CONTRIBUTING.md](../../CONTRIBUTING.md) et [AGENTS.md](../../AGENTS.md) ; les commits exigent le sign-off DCO. Licence : [GPL-3.0-or-later](../../LICENSE). Les crédits restent dans [NOTICE](../../NOTICE) et [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). Nanodot est indépendant de Meta et de sa marque Muse.

## Historique et crédits du projet amont

Les actualités, la citation, les cartes de mainteneurs et contributeurs et les remerciements suivants sont des archives nanoMuse. Leurs dates, noms et liens concernent le projet amont, pas les versions ou mainteneurs Nanodot.

<details>
<summary>nanoMuse</summary>

## 🗞️ Actualités

- `2026-10-09` 🚀 La version [1.0.0 Keel](https://github.com/nano-muse/nanoMuse/releases/tag/v1.0.0) est sortie, pour le téléphone, l'ordinateur et le navigateur ; elle s'installe par-dessus la précédente en gardant vos données, et ses notes de version listent chaque changement.
- `2026-10-08` 🤗 Notre article est sur [Hugging Face Daily Papers](https://huggingface.co/papers/2610.08699), classé troisième de la liste du 8 octobre ; questions et commentaires sont les bienvenus sur la page de l'article.
- `2026-10-07` 📄 Notre article est disponible sur [arXiv](https://arxiv.org/abs/2610.08699) : *nanoMuse: An Open-Source Personal Agent for Every Device You Own*, par Guangyi Liu, Yong Liu et Jiangning Zhang (université du Zhejiang) ; la citation est en bas de cette page.
- `2026-09-25` 🎉 nanoMuse est publié sur GitHub sous GPL-3.0-or-later : une application Android construite sur OpenMinis 1.13 ; le relais, l'application de bureau et l'application iPhone sont arrivés dans les jours suivants.

Toutes les versions : [releases](https://github.com/nano-muse/nanoMuse/releases).

## Mainteneurs

<table>
  <tr>
    <td align="center"><a href="https://github.com/lgy0404"><img src="https://avatars.githubusercontent.com/u/63797388?v=4&s=80" width="80" height="80" alt="Guangyi Liu"><br><strong>Guangyi Liu</strong></a><br><a href="mailto:guangyiliu@zju.edu.cn"><img src="https://img.shields.io/badge/e--mail-guangyiliu%40zju.edu.cn-0a66e4" alt="guangyiliu@zju.edu.cn"></a></td>
  </tr>
</table>

## Contributeurs de la communauté

Toutes les personnes dont un changement a été intégré dans `main`, d'après la liste des contributeurs de GitHub.

<!-- contributors:start -->
<p>
<a href="https://github.com/wsvn53"><img src="https://avatars.githubusercontent.com/u/912204?v=4&s=48" width="48" height="48" alt="wsvn53"></a>
<a href="https://github.com/aliabbas6622"><img src="https://avatars.githubusercontent.com/u/193962983?v=4&s=48" width="48" height="48" alt="aliabbas6622"></a>
<a href="https://github.com/PovedaAqui"><img src="https://avatars.githubusercontent.com/u/9494679?v=4&s=48" width="48" height="48" alt="PovedaAqui"></a>
<a href="https://github.com/jiangkaiqi2005"><img src="https://avatars.githubusercontent.com/u/184961141?v=4&s=48" width="48" height="48" alt="jiangkaiqi2005"></a>
</p>
<!-- contributors:end -->

## ⭐️ Citation

Si nanoMuse vous est utile, merci de citer l'article.

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

## Remerciements

nanoMuse s'appuie sur le travail d'autres personnes ; [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) en donne les termes.

- [OpenMinis](https://github.com/OpenMinis/OpenMinis) : l'agent embarqué sur lequel est bâtie l'app du téléphone, avec [proot](https://github.com/nano-muse/proot) et [Alpine Linux](https://alpinelinux.org/) pour le bac à sable.
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) : le harnais d'agents dont l'app de bureau est un plugin.
- [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) (ByteDance) : l'opérateur des mains du bureau est un portage du leur, et les marqueurs de la scène suivent leur ScreenMarker.
- [MobileGym](https://github.com/Purewhiter/mobilegym), [MemGUI-Bench](https://github.com/lgy0404/MemGUI-Bench), [PhoneHarness](https://github.com/PhoneHarness/PhoneHarness), [CopilotKit/OpenMuse](https://github.com/CopilotKit/OpenMuse), [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM), [ClawGUI](https://github.com/ZJU-REAL/ClawGUI) : l'opérateur du téléphone, les traces et les idées de produit.

</details>

<p align="center"><img src="https://visitor-badge.laobi.icu/badge?page_id=Mola-maker.nanodot&amp;left_text=visitors" alt="Nanodot visitors"></p>
