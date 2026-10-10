# Nanodot brand

Nanodot is based on [nanoMuse](https://github.com/nano-muse/nanoMuse) and its
[OpenMinis 1.13](https://github.com/OpenMinis/OpenMinis) phone subtree. The product is
**Nanodot**; the default assistant is **Dot**. The public repository is
[Mola-maker/nanodot](https://github.com/Mola-maker/nanodot). This P0 contract does not
claim that the future floating ball or care mode has been implemented.

## The mark

Dot is original Nanodot vector artwork: a calm circular face with two eyes and a small
expression. The five offline stills are idle, working, waiting, happy and error. The same
character provides the application mark and default face. Default faces use static artwork
with lightweight state feedback; old bundled dragon videos are not the default motion.
User-created avatars and animation remain supported.

- Shared contract: `config/brand.nanodot.json`, validated by `scripts/brand_profile.py`.
- Vector sources: `assets/brand/nanodot-mark.svg`, `nanodot-icon.svg` and
  `nanodot-avatar/{idle,working,waiting,happy,error}.svg`.
- Generation: `scripts/gen-nanodot-assets.py`, `scripts/gen-android-icons.py` and
  `scripts/gen-ios-icons.py`, all accepting the same `--brand-config` argument.
- Original Dot artwork is GPL-3.0-or-later. SVG comments record its provenance; the
  repository's [LICENSE](../LICENSE), [NOTICE](../NOTICE) and
  [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) retain upstream terms and credits.

Some generated assets keep internal filenames such as `dragon-idle.webp` or
`nm_avatar_idle.webp`. These names preserve resource and client interfaces; their contents
are the Dot stills. Historical demo artwork and upstream logos are not Nanodot defaults.

## Colour

The values below come from `themeColors` in the shared profile. The default theme follows
the system; an existing explicit theme choice takes precedence.

| Token | Light | Dark | Use |
|---|---|---|---|
| `background` | `#F4F7F8` | `#10191D` | page background |
| `surface` | `#FFFFFF` | `#182329` | cards and inputs |
| `text` | `#16252C` | `#E5EDF0` | text |
| `primary` | `#136F63` | `#7DD6C5` | actions and links |
| `onPrimary` | `#FFFFFF` | `#10191D` | text on primary |
| `care` | `#7057A3` | `#C7B4F7` | tertiary accent, reserved for later care interaction |
| `outline` | `#687980` | `#8EABB5` | outlines and secondary text |

## Android mapping

Android's external application ID is `io.github.nanodot.app`; its system link scheme is
`nanodot://`. The Gradle namespace and existing internal Kotlin packages remain
`com.openminis.app` and `io.github.nanomuse.*`. Internal `minis://` links, CLI names and
sandbox paths are unchanged. iOS and desktop build identities are synchronized to Nanodot;
native packaging still requires validation on each platform.

Adaptive icons retain the existing activity aliases and resource names. Light and dark
skins use the configured surface/background and primary colours. The notification small
icon is a 24 dp monochrome Dot silhouette; themed Android icons use an eye-cutout mask.

## Defaults and user choices

Only missing fields are seeded. Existing names, SOUL.md, avatars, animations, models,
themes and preferences are preserved. Restoring defaults is an explicit user action.
`showOnAccessibilityConnected=true` records a future floating-ball choice; it does not
enable the old status capsule, grant permission or turn on Hands. `continuousVoice=false`
records the safe voice default.

Automatic updates are disabled in P0 (`enabled=false`, `repositoryUrl=null`). The
Nanodot releases page is a manual destination, not a claim that a release exists.

## Attribution and service identity

Keep the real names of nanoMuse, OpenMinis and third-party projects in source credits,
licences, citations and historical records. **nanoMuse Cloud** remains the real upstream
service; its endpoints, privacy policies and model-provider identity are not Nanodot's.
Nanodot does not promise a free allowance or claim nonprofit status on the service's behalf.
Do not use the OpenMinis or Meta Muse logo as Nanodot's own mark.

The [build guide](build.md) explains generation, signing and the remaining device checks.
