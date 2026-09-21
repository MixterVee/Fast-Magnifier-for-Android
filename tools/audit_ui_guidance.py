from pathlib import Path

root = Path("app/src/main")
text_files = [
    p for p in root.rglob("*")
    if p.is_file() and p.suffix.lower() in {".kt", ".xml"}
]

stale_phrases = [
    "Slide ↑/↓ Zoom",
    "Slide up/down to zoom",
    "Slide zoom",
    "Slide up to zoom",
]

problems = []

guidance_path = root / "java/com/mixtervee/fastmagnifier/GuidanceTextView.kt"

# Check user-facing guidance, but do not flag legacy phrases that intentionally
# remain in GuidanceTextView's cleanup/filter list so stale status text is removed.
for path in text_files:
    text = path.read_text(encoding="utf-8")
    scan_text = text
    if path == guidance_path and "private fun currentGuide" in text:
        scan_text = text.split("private fun currentGuide", 1)[1]

    for phrase in stale_phrases:
        if phrase.lower() in scan_text.lower():
            problems.append(f"{path}: stale user-facing guidance '{phrase}'")

guidance = guidance_path.read_text(encoding="utf-8")

# Guidance can be embedded English text in older source or supplied by localized
# Android string resources in release/beta source.
localized_guidance_ids = [
    "R.string.guide_live",
    "R.string.guide_pinch_zoom",
    "R.string.guide_drag_move",
    "R.string.guide_tap_overview",
    "R.string.guide_tap_full_view",
]
legacy_guidance = [
    "Pinch Zoom",
    "Tap Focus",
    "Hold Freeze",
    "Drag Move",
    "Tap Overview",
    "Tap Full View",
]

if not (
    all(resource_id in guidance for resource_id in localized_guidance_ids)
    or all(phrase in guidance for phrase in legacy_guidance)
):
    problems.append(
        f"{guidance_path}: camera gesture guidance is incomplete"
    )

screen_path = root / "java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt"
screen = screen_path.read_text(encoding="utf-8")
screen_lower = screen.lower()

# Screen controls may be direct labels, equal-width labels, or localized resources.
control_options = {
    "Min": [
        'controlButton("Min")',
        'addEqualControl("Min")',
        "R.string.minimize_short",
    ],
    "Back": [
        'controlButton("Back")',
        'addEqualControl("Back")',
        "R.string.back",
    ],
    "Exit": [
        'controlButton("Exit")',
        'addEqualControl("Exit")',
        "R.string.exit",
    ],
}
for label, options in control_options.items():
    if not any(option in screen for option in options):
        problems.append(
            f"{screen_path}: missing current Screen Magnifier control '{label}'"
        )

# The screen gesture hint can likewise be literal or resource based.
literal_screen_guide = all(
    word in screen_lower for word in ("tap", "activate", "pinch", "resize", "long-press", "copy")
)
resource_screen_guide = (
    "R.string.screen_usage_hint" in screen
    and "R.string.screen_lens_description" in screen
)
if not literal_screen_guide and not resource_screen_guide:
    problems.append(
        f"{screen_path}: missing current Screen Magnifier gesture guidance"
    )

if problems:
    print("UI guidance audit failed:")
    for problem in problems:
        print(f" - {problem}")
    raise SystemExit(1)

print("UI guidance audit passed: camera and screen magnifier instructions match current controls")
