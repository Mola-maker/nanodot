"""Run rebrand on real Android consumers and evaluate their composited theme roles."""

import re
import shutil
from pathlib import Path

import pytest

from scripts.brand_profile import load_brand
from scripts.rebrand import apply_brand

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path("android/src/android/app/src/main/java/com/openminis/app")
THINKING = "ui/chat/ChatAssistantMessageUI.kt"
PICKER = "ui/chat/ChatModelPickerSheet.kt"
DETAIL = "ui/chat/ChatToolDetailUI.kt"
GLYPHS = (
    "ui/components/ModelEntryPicker.kt",
    "ui/components/UnifiedModelPickerSheet.kt",
    "ui/settings/MountDetailScreen.kt",
    "ui/settings/SharedFolderDetailScreen.kt",
    "ui/settings/MountedFoldersScreen.kt",
    "ui/settings/AgentLoopAddSheets.kt",
    "ui/settings/MinisSkillsBrowserScreen.kt",
)


def fixture(tmp_path, legacy=False):
    root = tmp_path / "tree"
    sources = (
        THINKING,
        PICKER,
        DETAIL,
        *GLYPHS,
        "ui/settings/backup/RestoreBrowseScreen.kt",
        "MainActivity.kt",
        "ui/theme/Theme.kt",
        "ui/settings/AppearanceScreen.kt",
        "ui/settings/SkillsManagementScreen.kt",
    )
    for name in sources:
        target = root / JAVA / name
        target.parent.mkdir(parents=True, exist_ok=True)
        historic = ROOT / "tests/fixtures/android_accent_consumers" / Path(name).name
        shutil.copyfile(historic if legacy and historic.exists() else ROOT / JAVA / name, target)
        if legacy and name in GLYPHS:
            # Seed the earlier branded glyph input so removing the migration is observable.
            target.write_text(
                target.read_text(encoding="utf-8").replace(
                    "androidx.compose.material3.MaterialTheme.colorScheme.primary",
                    "Color(0xFF136F63)",
                ),
                encoding="utf-8",
            )
    user = root / "profile.json"
    user.write_text('{"theme_mode":2,"avatar":"custom-ice","color":"#123456"}', encoding="utf-8")
    brand = load_brand(ROOT / "config/brand.nanodot.json")
    apply_brand(root, brand)
    return root, brand


def expression_after(text, anchor, field="color", last=False):
    position = text.rindex(anchor) if last else text.index(anchor)
    return re.search(rf"\b{field}\s*=\s*([^\n]+)", text[position:])[1].split("//")[0].rstrip(", ")


def rgba(expression, palette, variables=None):
    """Evaluate only supported production foreground expressions; fail on an unreviewed role."""
    expression = expression.split("//")[0].strip().rstrip(",")
    alpha = 1.0
    match = re.search(r"\.copy\(alpha = ([\d.]+)f\)", expression)
    if match:
        alpha = float(match[1])
        expression = expression[: match.start()]
    if expression in (variables or {}):
        color = rgba(variables[expression], palette)
        return (*color[:3], alpha * color[3])
    if expression.endswith("MaterialTheme.colorScheme.primary"):
        value = palette["primary"][1:]
    elif expression == "Color.White":
        value = "FFFFFF"
    else:
        match = re.fullmatch(r"Color\(0xFF([\dA-Fa-f]{6})\)", expression)
        assert match, f"Unsupported consumer color: {expression}"
        value = match[1]
    return (*(int(value[i : i + 2], 16) / 255 for i in (0, 2, 4)), alpha)


def over(source, background):
    return tuple(source[i] * source[3] + background[i] * (1 - source[3]) for i in range(3))


def contrast(foreground, background):
    def luminance(color):
        linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in color]
        return sum(c * w for c, w in zip(linear, (0.2126, 0.7152, 0.0722), strict=True))

    values = sorted((luminance(foreground), luminance(background)))
    return (values[1] + 0.05) / (values[0] + 0.05)


def actual_mode(root, saved_mode, system_dark):
    # Execute the literal branch table from MainActivity's actual saved theme route.
    # Android preference storage/composition delivery remains a native acceptance item.
    main = (root / JAVA / "MainActivity.kt").read_text(encoding="utf-8")
    body = re.search(r"val darkTheme = when \(themeMode\) \{([^}]+)\}", main)[1]
    branches = dict(re.findall(r"(\d+) -> (true|false)", body))
    value = branches.get(str(saved_mode))
    if value is None:
        assert "else -> isSystemInDarkTheme()" in body
        dark = system_dark
    else:
        dark = value == "true"
    assert "MinisTheme(darkTheme = darkTheme" in main
    theme = (root / JAVA / "ui/theme/Theme.kt").read_text(encoding="utf-8")
    assert "val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme" in theme
    return "dark" if dark else "light"


@pytest.mark.parametrize("legacy", [False, True])
@pytest.mark.parametrize("saved_mode,system_dark", [(1, True), (2, False), (0, False), (0, True)])
def test_actual_thinking_and_picker_composites_have_readable_text(
    tmp_path, saved_mode, system_dark, legacy
):
    root, brand = fixture(tmp_path, legacy)
    mode = actual_mode(root, saved_mode, system_dark)
    palette = brand.themeColors[mode]
    thinking = (root / JAVA / THINKING).read_text(encoding="utf-8")
    picker = (root / JAVA / PICKER).read_text(encoding="utf-8")
    variables = {"thinkingBlue": re.search(r"val thinkingBlue = ([^\n]+)", thinking)[1]}
    tint = re.search(r"\.background\((thinkingBlue.copy\(alpha = [\d.]+f\))", thinking)[1]
    badge_scope = picker[picker.index("stringResource(R.string.model_picker_default_badge)") :]
    badge_tint = re.search(r"\.background\(\s*([^\n]+)", badge_scope)[1].rstrip(",")
    for surface in ("surface", "background"):
        host = rgba(f"Color(0xFF{palette[surface][1:]})", palette)
        thinking_background = over(rgba(tint, palette, variables), host)
        badge_background = over(rgba(badge_tint, palette), host)
        pairs = [
            (
                "thinking header",
                expression_after(thinking, 'text = "Deep Thinking"'),
                thinking_background,
            ),
            ("thinking count", expression_after(thinking, "text = charLabel"), thinking_background),
            (
                "full-content entry",
                expression_after(thinking, "text = stringResource(R.string.thinking_view_full)"),
                thinking_background,
            ),
            (
                "thinking dialog",
                expression_after(thinking, 'text = "Deep Thinking"', last=True),
                host[:3],
            ),
            (
                "default badge",
                expression_after(picker, "stringResource(R.string.model_picker_default_badge)"),
                badge_background,
            ),
            (
                "show models",
                expression_after(picker, "R.plurals.model_picker_show_models"),
                host[:3],
            ),
        ]
        for name, expression, background in pairs:
            foreground = over(rgba(expression, palette, variables), background)
            ratio = contrast(foreground, background)
            assert ratio >= 4.5, f"{mode} {surface}: {name} {ratio:.3f}:1"


def test_saved_dark_override_changes_consumer_role_even_when_system_is_light(tmp_path):
    root, brand = fixture(tmp_path)
    mode = actual_mode(root, saved_mode=2, system_dark=False)
    expression = expression_after(
        (root / JAVA / PICKER).read_text(encoding="utf-8"), "R.plurals.model_picker_show_models"
    )
    actual = rgba(expression, brand.themeColors[mode])
    assert actual == rgba(
        f"Color(0xFF{brand.themeColors['dark']['primary'][1:]})", brand.themeColors[mode]
    )


def test_foreground_glyph_migration_keeps_filled_badges_and_user_choices(tmp_path):
    root, brand = fixture(tmp_path, legacy=True)
    for name in (PICKER, *GLYPHS):
        text = (root / JAVA / name).read_text(encoding="utf-8")
        for expression in re.findall(r"\btint = (?:if \([^\n]+?\) )?([^\n]+)", text):
            if (
                "MaterialTheme.colorScheme.primary" in expression
                or "Color(0xFF2D6396)" in expression
            ):
                for mode in ("light", "dark"):
                    palette = brand.themeColors[mode]
                    foreground = rgba(expression.split(",")[0].strip(), palette)
                    background = rgba(f"Color(0xFF{palette['surface'][1:]})", palette)
                    assert contrast(over(foreground, background), background[:3]) >= 4.5
    # These are deliberately white-on-blue filled badges, not foreground accents.
    detail = (root / JAVA / DETAIL).read_text(encoding="utf-8")
    action = detail[detail.index("text = action,") :]
    foreground = expression_after(action, "text = action,")
    fill = re.search(r"\.background\((Color\(0xFF[\dA-Fa-f]{6}\)), CircleShape", action)[1]
    for mode in ("light", "dark"):
        palette = brand.themeColors[mode]
        bg = rgba(fill, palette)
        assert contrast(over(rgba(foreground, palette), bg), bg[:3]) >= 4.5
    for name in (
        "MainActivity.kt",
        "ui/theme/Theme.kt",
        "ui/settings/AppearanceScreen.kt",
        "ui/settings/SkillsManagementScreen.kt",
    ):
        assert (root / JAVA / name).read_bytes() == (ROOT / JAVA / name).read_bytes()
    assert (root / "profile.json").read_text(
        encoding="utf-8"
    ) == '{"theme_mode":2,"avatar":"custom-ice","color":"#123456"}'
    assert apply_brand(root, brand) == []


@pytest.mark.parametrize("legacy", [False, True])
def test_generic_tool_links_use_the_theme_while_terminal_pair_is_preserved(tmp_path, legacy):
    root, brand = fixture(tmp_path, legacy)
    detail = (root / JAVA / DETAIL).read_text(encoding="utf-8")
    lazy = detail[detail.index("private fun LazyRevealToolText(") :]
    match = re.search(r"val linkColor = ([^\n]+)", lazy)
    assert match, "Generic tool text must provide a theme-aware link color"
    assert "linkColor = linkColor" in lazy
    assert "remember(shownText, urlClick, linkify, linkColor)" in lazy
    for mode in ("light", "dark"):
        palette = brand.themeColors[mode]
        foreground = rgba(match[1], palette)
        background = rgba(f"Color(0xFF{palette['surface'][1:]})", palette)
        assert contrast(over(foreground, background), background[:3]) >= 4.5
    assert "linkColor = Color(0xFF7CC4FF), // light blue on dark terminal bg" in detail


def test_semantic_consumers_follow_alternate_profiles_without_rewriting_preferences(tmp_path):
    import json

    root, original = fixture(tmp_path, legacy=True)
    data = json.loads((ROOT / "config/brand.nanodot.json").read_text(encoding="utf-8"))
    data["themeColors"]["light"]["primary"] = "#364E72"
    data["themeColors"]["dark"]["primary"] = "#C5DDFA"
    profile = tmp_path / "alternate.json"
    profile.write_text(json.dumps(data), encoding="utf-8")
    alternate = load_brand(profile)
    apply_brand(root, alternate)
    text = (root / JAVA / THINKING).read_text(encoding="utf-8")
    expression = re.search(r"val thinkingBlue = ([^\n]+)", text)[1]
    for mode in ("light", "dark"):
        palette = alternate.themeColors[mode]
        assert rgba(expression, palette) == rgba(f"Color(0xFF{palette['primary'][1:]})", palette)
    assert apply_brand(root, alternate) == []
    apply_brand(root, original)
    assert apply_brand(root, original) == []
    assert (root / "profile.json").read_text(
        encoding="utf-8"
    ) == '{"theme_mode":2,"avatar":"custom-ice","color":"#123456"}'


@pytest.mark.parametrize("input_revision", ["historical", "fix1", "current"])
@pytest.mark.parametrize("saved_mode,system_dark", [(1, True), (2, False), (0, False), (0, True)])
def test_restore_entry_preserves_fixed_fill_and_white_glyph(
    tmp_path, input_revision, saved_mode, system_dark
):
    root, brand = fixture(tmp_path)
    name = "ui/settings/backup/RestoreBrowseScreen.kt"
    filename = (
        "RestoreBrowseScreen.kt" if input_revision == "historical" else "RestoreBrowseScreenFix1.kt"
    )
    source = (
        ROOT / JAVA / name
        if input_revision == "current"
        else ROOT / "tests/fixtures/android_accent_consumers" / filename
    )
    target = root / JAVA / name
    target.write_bytes(source.read_bytes())
    apply_brand(root, brand)
    text = target.read_text(encoding="utf-8")
    entry = text[text.index("private fun EntryRow(") :]
    fill = re.search(r"if \(entry.isDirectory\) (Color\(0xFF[0-9A-Fa-f]+\)) else ([^\n]+)", entry)
    assert fill, "EntryRow must retain its explicit directory/file fill branches"
    assert fill[1] == "Color(0xFF8E8E93)"
    file_fill = fill[2].split("//")[0].rstrip(", ")
    ink = expression_after(entry, "Icons.Outlined.Inventory2", field="tint")
    mode = actual_mode(root, saved_mode, system_dark)
    palette = brand.themeColors[mode]
    background = rgba(file_fill, palette)
    foreground = rgba(ink, palette)
    assert contrast(over(foreground, background), background[:3]) >= 3
    assert file_fill == "Color(0xFF2D6396)"
    assert ink == "Color.White"
    assert apply_brand(root, brand) == []
    assert (root / "profile.json").read_text(
        encoding="utf-8"
    ) == '{"theme_mode":2,"avatar":"custom-ice","color":"#123456"}'
