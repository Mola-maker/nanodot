"""Run the real generator on isolated upstream and already-branded trees."""

import tempfile
import unittest
from pathlib import Path

from scripts import rebrand
from scripts.brand_profile import load_brand

ROOT = Path(__file__).resolve().parents[1]
APP = Path("android/src/android/app")
MAIN = APP / "src/main"
JAVA = MAIN / "java/com/openminis/app"


class NanodotRebrandTest(unittest.TestCase):
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
                    '<string name="nm_cloud_notice_title">Non-profit</string></resources>'
                )
            rebrand.apply_brand(root, load_brand(ROOT / "config/brand.nanodot.json"))
            for source in locales:
                result = (root / source.relative_to(ROOT)).read_text()
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
                "contentColor = Color.White)"
            )
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            result = path.read_text()
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
                    (root / path).write_text(value)
                brand = load_brand(ROOT / "config/brand.nanodot.json")
                self.assertTrue(rebrand.apply_brand(root, brand))
                self.assertEqual([], rebrand.apply_brand(root, brand))
                gradle = (root / APP / "build.gradle.kts").read_text()
                self.assertIn('applicationId = "io.github.nanodot.app"', gradle)
                self.assertIn('namespace = "com.openminis.app"', gradle)
                self.assertIn('versionName = "1.0.1"', gradle)
                self.assertIn(
                    'android:scheme="nanodot"', (root / MAIN / "AndroidManifest.xml").read_text()
                )
                self.assertIn(
                    'android:targetPackage="io.github.nanodot.app"',
                    (root / MAIN / "res/xml/shortcuts.xml").read_text(),
                )
                soul = (root / JAVA / "agent/SoulStore.kt").read_text()
                self.assertIn('name = "Dot"', soul)
                self.assertIn('"/var/minis"', soul)
                self.assertIn('"minis://settings/soul"', soul)
                nm = (root / MAIN / "res/values/nm_strings.xml").read_text()
                self.assertIn("nanoMuse Cloud", nm)
                self.assertIn("Based on nanoMuse and OpenMinis", nm)
                self.assertIn(">Nanodot<", nm)
                self.assertEqual(files[Path("NOTICE")], (root / "NOTICE").read_text())
                self.assertIn(
                    "BuildConfig.APPLICATION_ID",
                    (root / JAVA / "accessibility/MinisAccessibilityService.kt").read_text(),
                )
                self.assertIn("0xFF136F63", (root / JAVA / "ui/theme/Theme.kt").read_text())

    def test_every_existing_android_locale_gets_a_manual_update_explanation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            locales = sorted((ROOT / MAIN / "res").glob("values*/nm_strings.xml"))
            for source in locales:
                path = root / source.relative_to(ROOT)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("<resources></resources>")
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            for source in locales:
                self.assertIn(
                    'name="nm_update_manual"', (root / source.relative_to(ROOT)).read_text()
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
                (root / path).write_text(value)
            rebrand.apply_brand(root, load_brand(ROOT / "config/brand.nanodot.json"))
            chat = (root / JAVA / "ui/theme/ChatColors.kt").read_text()
            self.assertIn("background = Color(0xFFF4F7F8)", chat)
            self.assertIn("background = Color(0xFF10191D)", chat)
            self.assertIn("primaryText = Color(0xFF16252C)", chat)
            self.assertIn("primaryText = Color(0xFFE5EDF0)", chat)
            self.assertIn(
                "DotColors.DarkPrimary",
                (root / MAIN / "java/io/github/nanomuse/ui/home/MuseTones.kt").read_text(),
            )
            self.assertIn("Hi, I am Dot.", (root / MAIN / "res/values/nm_strings.xml").read_text())
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
                (root / path).write_text(value)
            brand = load_brand(ROOT / "config/brand.nanodot.json")
            rebrand.apply_brand(root, brand)
            strings = (root / MAIN / "res/values-ko/nm_strings.xml").read_text()
            self.assertIn(">Dot예요<", strings)
            self.assertIn(">nanoMuse의 AI 모델 개선 돕기<", strings)
            self.assertIn(">Nanodot helps<", strings)
            self.assertIn('.ifEmpty { "Dot" }', (root / JAVA / "agent/SoulStore.kt").read_text())
            events = (root / "android/src/ios/NanoMuse/Events.swift").read_text()
            self.assertIn('"nanoMuse.openRoom"', events)
            self.assertIn('"nanoMuse.chatAction"', events)
            self.assertIn(
                'return n.isEmpty ? "Dot" : n',
                (root / "android/src/ios/Agent/Session/SoulStore.swift").read_text(),
            )
            self.assertIn(
                "private static let updatesEnabled = false",
                (root / "android/src/ios/NanoMuse/NanoMuseUpdateCheck.swift").read_text(),
            )

            catalog = (root / "android/src/ios/Localizable.xcstrings").read_text()
            self.assertIn("Help improve nanoMuse's AI models", catalog)
            self.assertIn("帮助改进 nanoMuse 的 AI 模型", catalog)
            self.assertIn("Hi, I'm Dot", catalog)
            self.assertIn("Dot예요", catalog)
            self.assertIn(
                "Hi, I'm Dot",
                (root / "android/src/ios/NanoMuse/NanoMuseFirstRun.swift").read_text(),
            )
            self.assertEqual([], rebrand.apply_brand(root, brand))
