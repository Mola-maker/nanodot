"""Run the real generator on isolated upstream and already-branded trees."""

import tempfile
import unittest
import xml.etree.ElementTree as ET
from dataclasses import replace
from pathlib import Path

from scripts import rebrand
from scripts.brand_profile import load_brand

ROOT = Path(__file__).resolve().parents[1]
APP = Path("android/src/android/app")
MAIN = APP / "src/main"
JAVA = MAIN / "java/com/openminis/app"


class NanodotRebrandTest(unittest.TestCase):
    def test_assistant_identity_copy_is_scoped_to_three_keys_in_all_locales(self):
        # Literal upstream copy exercises Latin, translated and inflected identity names.
        cases = {
            "values": ("Portraits of your Muse.", "Portraits of Echo."),
            "values-de": ("Porträts deiner Muse.", "Porträts von Echo."),
            "values-es": ("Retratos de tu Musa.", "Retratos de Echo."),
            "values-fil": ("Mga larawan ng iyong Muse.", "Mga larawan ni Echo."),
            "values-fr": ("Des portraits de votre Muse.", "Des portraits de Echo."),
            "values-in": ("Potret Muse-mu.", "Potret Echo."),
            "values-ja": ("ミューズの肖像。", "Echoの肖像。"),
            "values-ko": ("뮤즈의 초상.", "Echo의 초상."),
            "values-ms": ("Potret Muse anda.", "Potret Echo."),
            "values-pl": ("Portrety twojej Muzy.", "Portrety Echo."),
            "values-pt-rBR": ("Retratos da sua Musa.", "Retratos de Echo."),
            "values-ro": ("Portrete ale Muzei tale.", "Portrete ale lui Echo."),
            "values-ru": ("Портреты вашей Музы.", "Портреты Echo."),
            "values-th": ("ภาพบุคคลของมิวส์ของคุณ", "ภาพบุคคลของEcho"),
            "values-tr": ("Muse’unun portreleri.", "Echo’un portreleri."),
            "values-zh": ("形象的肖像。", "形象的肖像。"),
            "values-zh-rTW": ("形象的肖像。", "形象的肖像。"),
        }
        video_variants = {
            "values-ro": ("Clipuri scurte cu Muza ta.", "Clipuri scurte cu Echo."),
            "values-ru": ("Короткие клипы с вашей Музой.", "Короткие клипы с Echo."),
        }
        locale_names = {p.parent.name for p in (ROOT / MAIN / "res").glob("values*/nm_strings.xml")}
        self.assertEqual(locale_names, set(cases))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for locale, (before, _) in cases.items():
                path = root / MAIN / "res" / locale / "nm_strings.xml"
                path.parent.mkdir(parents=True)
                video_before = video_variants.get(locale, (before, ""))[0]
                path.write_text(
                    '<resources><string name="nm_appearance_header_model_footer">'
                    "Muse shows only the name.</string>"
                    f'<string name="nm_models_image_sub">{before}</string>'
                    f'<string name="nm_models_video_sub">{video_before}</string>'
                    '<string name="nm_avatar_style_muse">3D toy (Muse)</string>'
                    '<string name="nm_about_trademark_detail">Muse is a trademark of Meta.</string>'
                    '<string name="nm_custom_identity">My Muse is custom</string>'
                    '<string name="nm_cloud">nanoMuse Cloud</string>'
                    '<string name="nm_upstream">Based on nanoMuse and OpenMinis</string>'
                    '<string name="nm_menu_parallel_addition">Parallel menu entry</string>'
                    "</resources>",
                    encoding="utf-8",
                )
            brand = replace(load_brand(ROOT / "config/brand.nanodot.json"), agentName="Echo")
            rebrand.apply_brand(root, brand)
            for locale, (_, expected) in cases.items():
                values = {
                    n.attrib["name"]: n.text
                    for n in ET.parse(root / MAIN / "res" / locale / "nm_strings.xml")
                    .getroot()
                    .findall("string")
                }
                self.assertEqual(
                    "Echo shows only the name.", values["nm_appearance_header_model_footer"]
                )
                self.assertEqual(expected, values["nm_models_image_sub"])
                self.assertEqual(
                    video_variants.get(locale, ("", expected))[1], values["nm_models_video_sub"]
                )
                self.assertNotIn("Muse", values["nm_avatar_style_muse"])
                self.assertIn("3D", values["nm_avatar_style_muse"])
                self.assertEqual(
                    "Muse is a trademark of Meta.", values["nm_about_trademark_detail"]
                )
                self.assertEqual("My Muse is custom", values["nm_custom_identity"])
                self.assertEqual("nanoMuse Cloud", values["nm_cloud"])
                self.assertEqual("Based on nanoMuse and OpenMinis", values["nm_upstream"])
                self.assertEqual("Parallel menu entry", values["nm_menu_parallel_addition"])
            self.assertEqual([], rebrand.apply_brand(root, brand))

    def test_avatar_studio_uses_crystal_example_without_renaming_style_ids(self):
        expected = {
            "values": (
                "A transparent pale-blue crystal orb with soft highlights and a gentle breathing glow",
                "3D toy",
            ),
            "values-zh": ("淡蓝色透明水晶球，柔和高光，带有轻柔的呼吸感", "3D 玩偶"),
            "values-zh-rTW": ("淡藍色透明水晶球，柔和高光，帶有輕柔的呼吸感", "3D 玩偶"),
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for locale in expected:
                path = root / MAIN / "res" / locale / "nm_strings.xml"
                path.parent.mkdir(parents=True)
                path.write_text(
                    '<resources><string name="nm_avatar_default_description">'
                    "A pale-yellow dragon with orange horns</string>"
                    '<string name="nm_avatar_style_muse">3D toy (Muse)</string>'
                    '<string name="nm_about_trademark_detail">Muse is a trademark of Meta.</string>'
                    '<string name="nm_custom_avatar">My dragon and orange horns</string>'
                    '<string name="nm_menu_parallel_addition">New menu text</string></resources>',
                    encoding="utf-8",
                )
            style = root / MAIN / "java/io/github/nanomuse/avatar/AvatarStyle.kt"
            style.parent.mkdir(parents=True)
            style.write_text('val styleId = "muse"', encoding="utf-8")
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            for locale, (description, title) in expected.items():
                values = {
                    n.attrib["name"]: n.text
                    for n in ET.parse(root / MAIN / "res" / locale / "nm_strings.xml")
                    .getroot()
                    .findall("string")
                }
                self.assertEqual(description, values["nm_avatar_default_description"])
                self.assertEqual(title, values["nm_avatar_style_muse"])
                self.assertEqual(
                    "Muse is a trademark of Meta.", values["nm_about_trademark_detail"]
                )
                self.assertEqual("My dragon and orange horns", values["nm_custom_avatar"])
                self.assertEqual("New menu text", values["nm_menu_parallel_addition"])
            self.assertEqual('val styleId = "muse"', style.read_text(encoding="utf-8"))
            self.assertEqual([], rebrand.apply_brand(root, brand))

    def test_known_android_display_misses_keep_service_and_sandbox_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixtures = {
                "values-de/nm_strings.xml": (
                    "nm_reach_unreachable_body",
                    "Von %1$s kam keine Antwort; nanoMuses eigene Server sind nicht beteiligt.",
                    "Von %1$s kam keine Antwort; Nanodots eigene Server sind nicht beteiligt.",
                ),
                "values-es/strings.xml": (
                    "mcp_section_footer",
                    "nanoMuse Agent: minis-mcp-cli /var/minis/mcp-servers/servers.json",
                    "Nanodot Agent: minis-mcp-cli /var/minis/mcp-servers/servers.json",
                ),
                "values-ru/strings.xml": (
                    "mcp_section_footer",
                    "nanoMuse Agent: minis-mcp-cli /var/minis/mcp-servers/servers.json",
                    "Nanodot Agent: minis-mcp-cli /var/minis/mcp-servers/servers.json",
                ),
            }
            for relative, (key, before, _) in fixtures.items():
                path = root / MAIN / "res" / relative
                path.parent.mkdir(parents=True)
                path.write_text(
                    f'<resources><string name="{key}">{before}</string>'
                    '<string name="nm_cloud">nanoMuse Cloud</string>'
                    '<string name="nm_upstream">Based on nanoMuse and OpenMinis</string>'
                    '<string name="nm_protocol">nanoMuse.Session</string></resources>',
                    encoding="utf-8",
                )
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            for relative, (_, _, expected) in fixtures.items():
                after = (root / MAIN / "res" / relative).read_text(encoding="utf-8")
                self.assertIn(expected, after)
                self.assertIn("nanoMuse Cloud", after)
                self.assertIn("Based on nanoMuse and OpenMinis", after)
                self.assertIn("nanoMuse.Session", after)
            self.assertEqual([], rebrand.apply_brand(root, brand))

    def test_service_notices_use_provider_terms_in_all_existing_locales(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            locales = sorted((ROOT / MAIN / "res").glob("values*/nm_strings.xml"))
            for source in locales:
                target = root / source.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(
                    '<resources><string name="nm_welcome_notice">'
                    "Nanodot is non-profit, free forever.</string>"
                    '<string name="nm_cloud_notice">A developer-funded model.</string>'
                    '<string name="nm_cloud_spent_total">%1$s %2$s paid by developer</string>'
                    '<string name="nm_cloud_notice_title">Non-profit</string></resources>',
                    encoding="utf-8",
                )
            rebrand.apply_brand(root, load_brand(ROOT / "config/brand.nanodot.json"))
            for source in locales:
                result = (root / source.relative_to(ROOT)).read_text(encoding="utf-8")
                self.assertNotIn("non-profit", result)
                self.assertNotIn("developer-funded", result)
                self.assertIn("nanoMuse Cloud", result)
                self.assertIn("%1$s", result)
                self.assertIn("%2$s", result)
            self.assertEqual(
                [], rebrand.apply_brand(root, load_brand(ROOT / "config/brand.nanodot.json"))
            )

    def test_primary_buttons_follow_the_theme_foreground(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / MAIN / "java/io/github/nanomuse/ui/onboarding/FirstRunSetup.kt"
            path.parent.mkdir(parents=True)
            path.write_text(
                "ButtonDefaults.buttonColors(containerColor = MuseTones.action, "
                "contentColor = Color.White)\n"
                "ButtonDefaults.buttonColors(containerColor = Color.Red, "
                "contentColor = Color.White)",
                encoding="utf-8",
            )
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            result = path.read_text(encoding="utf-8")
            self.assertIn("MaterialTheme.colorScheme.onPrimary", result)
            self.assertIn("containerColor = Color.Red, contentColor = Color.White", result)
            self.assertEqual([], rebrand.apply_brand(root, brand))

    def test_current_and_upstream_converge_without_touching_internal_contracts(self):
        for name, app_id, scheme, accent in (
            ("Minis", "com.openminis.app", "minis", "528AD2"),
            ("nanoMuse", "io.github.nanomuse.app", "nanomuse", "015CFB"),
        ):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                files = {
                    APP
                    / "build.gradle.kts": f'namespace = "com.openminis.app"\napplicationId = "{app_id}"\nversionCode = 44\nversionName = "1.0.1"',
                    MAIN
                    / "AndroidManifest.xml": f'<manifest><data android:scheme="{scheme}" /></manifest>',
                    MAIN
                    / "res/xml/shortcuts.xml": f'<shortcuts android:targetPackage="{app_id}" />',
                    MAIN
                    / "res/values/strings.xml": f'<resources><string name="app_name">{name}</string></resources>',
                    MAIN
                    / "res/values/nm_strings.xml": '<resources><string name="nm_cloud">nanoMuse Cloud</string><string name="nm_title">nanoMuse</string><string name="nm_attribution">Based on nanoMuse and OpenMinis</string></resources>',
                    JAVA / "ui/theme/Theme.kt": f"private val TealPrimary = Color(0xFF{accent})",
                    JAVA
                    / "agent/SoulStore.kt": f'name = "{name}"\nname: "{name}"\nval path = "/var/minis"\nval uri = "minis://settings/soul"',
                    JAVA
                    / "accessibility/MinisAccessibilityService.kt": 'package com.openminis.app.accessibility\nimport android.content.Context\nconst val SERVICE_ID = "com.openminis.app/.accessibility.MinisAccessibilityService"',
                    MAIN
                    / "java/io/github/nanomuse/deeplink/OwnScheme.kt": f'const val SYSTEM = "{scheme}"\nconst val INTERNAL = "minis"',
                    Path("NOTICE"): "nanoMuse / OpenMinis GPL attribution",
                }
                for path, value in files.items():
                    (root / path).parent.mkdir(parents=True, exist_ok=True)
                    (root / path).write_text(value, encoding="utf-8")
                brand = load_brand(ROOT / "config/brand.nanodot.json")
                self.assertTrue(rebrand.apply_brand(root, brand))
                self.assertEqual([], rebrand.apply_brand(root, brand))
                gradle = (root / APP / "build.gradle.kts").read_text(encoding="utf-8")
                self.assertIn('applicationId = "io.github.nanodot.app"', gradle)
                self.assertIn('namespace = "com.openminis.app"', gradle)
                self.assertIn(f'versionName = "{rebrand.VERSION_NAME}"', gradle)
                self.assertIn(f"versionCode = {rebrand.VERSION_CODE}", gradle)
                self.assertIn(
                    'android:scheme="nanodot"',
                    (root / MAIN / "AndroidManifest.xml").read_text(encoding="utf-8"),
                )
                self.assertIn(
                    'android:targetPackage="io.github.nanodot.app"',
                    (root / MAIN / "res/xml/shortcuts.xml").read_text(encoding="utf-8"),
                )
                soul = (root / JAVA / "agent/SoulStore.kt").read_text(encoding="utf-8")
                self.assertIn('name = "Dot"', soul)
                self.assertIn('"/var/minis"', soul)
                self.assertIn('"minis://settings/soul"', soul)
                nm = (root / MAIN / "res/values/nm_strings.xml").read_text(encoding="utf-8")
                self.assertIn("nanoMuse Cloud", nm)
                self.assertIn("Based on nanoMuse and OpenMinis", nm)
                self.assertIn(">Nanodot<", nm)
                self.assertEqual(
                    files[Path("NOTICE")], (root / "NOTICE").read_text(encoding="utf-8")
                )
                self.assertIn(
                    "BuildConfig.APPLICATION_ID",
                    (root / JAVA / "accessibility/MinisAccessibilityService.kt").read_text(
                        encoding="utf-8"
                    ),
                )
                self.assertIn(
                    f"0xFF{brand.themeColors['light']['primary'][1:]}",
                    (root / JAVA / "ui/theme/Theme.kt").read_text(encoding="utf-8"),
                )

    def test_every_existing_android_locale_gets_a_manual_update_explanation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            locales = sorted((ROOT / MAIN / "res").glob("values*/nm_strings.xml"))
            for source in locales:
                path = root / source.relative_to(ROOT)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("<resources></resources>", encoding="utf-8")
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            for source in locales:
                self.assertIn(
                    'name="nm_update_manual"',
                    (root / source.relative_to(ROOT)).read_text(encoding="utf-8"),
                )
            self.assertEqual([], rebrand.apply_brand(root, brand))

    def test_chat_home_and_agent_greeting_use_the_same_brand_tokens(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = {
                JAVA
                / "ui/theme/ChatColors.kt": "val LightChatPalette = ChatPalette(\n    background = Color.White,\n    primaryText = Color(0xFF000000),\n)\nval DarkChatPalette = ChatPalette(\n    background = Color(0xFF000000),\n    primaryText = Color.White,\n)",
                MAIN
                / "java/io/github/nanomuse/ui/home/MuseTones.kt": "val canvas: Color\n        @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF000000) else Color.White\n    val action: Color = Color(0xFF0A66E4)",
                MAIN
                / "res/values/nm_strings.xml": '<resources><string name="nm_intro_hello">Hi, I am nanoMuse.</string></resources>',
            }
            for path, value in paths.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_text(value, encoding="utf-8")
            rebrand.apply_brand(root, load_brand(ROOT / "config/brand.nanodot.json"))
            chat = (root / JAVA / "ui/theme/ChatColors.kt").read_text(encoding="utf-8")
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            for mode in ("light", "dark"):
                for field, token in (("background", "background"), ("primaryText", "text")):
                    self.assertIn(
                        f"{field} = Color(0xFF{brand.themeColors[mode][token][1:]})", chat
                    )
            self.assertIn(
                "DotColors.DarkPrimary",
                (root / MAIN / "java/io/github/nanomuse/ui/home/MuseTones.kt").read_text(
                    encoding="utf-8"
                ),
            )
            self.assertIn(
                "Hi, I am Dot.",
                (root / MAIN / "res/values/nm_strings.xml").read_text(encoding="utf-8"),
            )
            self.assertEqual(
                [], rebrand.apply_brand(root, load_brand(ROOT / "config/brand.nanodot.json"))
            )

    def test_reviewed_identity_service_plural_and_internal_event_contracts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            files = {
                MAIN
                / "res/values-ko/nm_strings.xml": '<resources><string name="nm_intro_hello">Nanodot예요</string><string name="nm_data_improve">Nanodot의 AI 모델 개선 돕기</string><plurals name="nm_star_tasks"><item quantity="other">nanoMuse helps</item></plurals></resources>',
                JAVA / "agent/SoulStore.kt": '.ifEmpty { "nanoMuse" }',
                Path(
                    "android/src/ios/Agent/Session/SoulStore.swift"
                ): 'return n.isEmpty ? "nanoMuse" : n\nname.isEmpty\n ? "Nanodot" : name',
                Path(
                    "android/src/ios/NanoMuse/NanoMuseUpdateCheck.swift"
                ): "private static let updatesEnabled = true",
                Path(
                    "android/src/ios/NanoMuse/NanoMuseFirstRun.swift"
                ): 'AppLocalized("Hi, I\'m Nanodot, the assistant that lives on your phone. Let me take a few things off your plate.")',
                Path(
                    "android/src/ios/NanoMuse/Events.swift"
                ): 'Notification.Name("nanoMuse.openRoom")\nNotification.Name("Nanodot.chatAction")',
                Path(
                    "android/src/ios/Localizable.xcstrings"
                ): '{"strings":{"Help improve Nanodot\'s AI models":{"localizations":{"zh-Hans":{"stringUnit":{"value":"帮助改进 Nanodot 的 AI 模型"}}}},"Hi, I\'m Nanodot, the assistant that lives on your phone. Let me take a few things off your plate.":{"localizations":{"ko":{"stringUnit":{"value":"Nanodot예요"}}}}}}',
            }
            for path, value in files.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_text(value, encoding="utf-8")
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            strings = (root / MAIN / "res/values-ko/nm_strings.xml").read_text(encoding="utf-8")
            self.assertIn(">Dot예요<", strings)
            self.assertIn(">nanoMuse의 AI 모델 개선 돕기<", strings)
            self.assertIn(">Nanodot helps<", strings)
            self.assertIn(
                '.ifEmpty { "Dot" }',
                (root / JAVA / "agent/SoulStore.kt").read_text(encoding="utf-8"),
            )
            events = (root / "android/src/ios/NanoMuse/Events.swift").read_text(encoding="utf-8")
            self.assertIn('"nanoMuse.openRoom"', events)
            self.assertIn('"nanoMuse.chatAction"', events)
            self.assertIn(
                'return n.isEmpty ? "Dot" : n',
                (root / "android/src/ios/Agent/Session/SoulStore.swift").read_text(
                    encoding="utf-8"
                ),
            )
            self.assertIn(
                "private static let updatesEnabled = false",
                (root / "android/src/ios/NanoMuse/NanoMuseUpdateCheck.swift").read_text(
                    encoding="utf-8"
                ),
            )

            catalog = (root / "android/src/ios/Localizable.xcstrings").read_text(encoding="utf-8")
            self.assertIn("Help improve nanoMuse's AI models", catalog)
            self.assertIn("帮助改进 nanoMuse 的 AI 模型", catalog)
            self.assertIn("Hi, I'm Dot", catalog)
            self.assertIn("Dot예요", catalog)
            self.assertIn(
                "Hi, I'm Dot",
                (root / "android/src/ios/NanoMuse/NanoMuseFirstRun.swift").read_text(
                    encoding="utf-8"
                ),
            )
            self.assertEqual([], rebrand.apply_brand(root, brand))
