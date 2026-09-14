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
for path in text_files:
    text = path.read_text(encoding="utf-8")
    for phrase in stale_phrases:
        if phrase.lower() in text.lower():
            problems.append(f"{path}: stale guidance '{phrase}'")

# Camera guidance must describe the gesture model actually implemented.
guidance_path = root / "java/com/mixtervee/fastmagnifier/GuidanceTextView.kt"
guidance = guidance_path.read_text(encoding="utf-8")
for phrase in [
    "Pinch Zoom",
    "Tap Focus",
    "Hold Freeze",
    "Drag Move",
    "Tap Overview",
    "Tap Full View",
]:
    if phrase not in guidance:
        problems.append(f"{guidance_path}: missing expected guidance '{phrase}'")

# Screen Magnifier menu/actions must match the current overlay controls after CI patches.
screen_path = root / "java/com/mixtervee/fastmagnifier/ScreenMagnifierService.kt"
screen = screen_path.read_text(encoding="utf-8")
for phrase in [
    'controlButton("Min")',
    'controlButton("Back")',
    'controlButton("Exit")',
    "Tap to activate",
    "pinch to resize",
    "long-press text to copy",
]:
    if phrase not in screen:
        problems.append(f"{screen_path}: missing current Screen Magnifier UI text/control '{phrase}'")

if problems:
    print("UI guidance audit failed:")
    for problem in problems:
        print(f" - {problem}")
    raise SystemExit(1)

print("UI guidance audit passed: camera and screen magnifier instructions match current controls")
