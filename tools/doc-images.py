#!/usr/bin/env python3
"""Regenerate docs/images from the app's own renders.

The screenshots in the README are not mock-ups: PhoneScreenRenderTest draws the real
phone layout with Robolectric and writes build/reports/phone-screen.png, and
TrendImageTest writes the real car trend bitmap. This script turns that render into the
files the README shows:

  * seven card crops, found by their canvas colour (no hard-coded pixel offsets),
  * phone-full.png, the whole screen at README width,
  * trend-image.png and app-icon.png, copied from the build output and store assets.

Run it after a UI change:

    python tools/doc-images.py                        # crop the render that already exists
    python tools/doc-images.py --gradle               # re-run the render tests first
    python tools/doc-images.py --check                # exit 1 if the committed images are stale
    python tools/doc-images.py --check --tolerate-renderer   # CI: another platform rendered it

Requires Python 3.9+ and Pillow (pip install pillow).
"""

from __future__ import annotations

import argparse
import io
import os
import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - the message is the useful part
    sys.exit("Pillow is required: pip install pillow")

ROOT = Path(__file__).resolve().parent.parent
RENDER = ROOT / "android" / "app" / "build" / "reports" / "phone-screen.png"
TREND = ROOT / "android" / "app" / "build" / "reports" / "trend-image.png"
ICON = ROOT / "store" / "assets" / "icon-512.png"
OUT = ROOT / "docs" / "images"

# Top-to-bottom order of the cards in phone/MainActivity. The script checks that the
# render contains exactly this many cards; if a card is added or removed, update this
# list (the detected bands are printed so the mapping stays obvious).
CARDS = [
    "phone-live-glucose",
    "phone-driving-mode",
    "phone-setup-check",
    "phone-cgm-source",
    "phone-source-check",
    "phone-health-data",
    "phone-target-range",
]

# Mean difference out of 255 between two renders of the same screen. Measured across the
# Windows render the images come from and the Linux CI runner: 0.0-0.2, so 2.0 leaves about
# ten times headroom. For scale, on a 782x558 card a replaced value reads 2.7, a recoloured
# status pill 5.7, a tinted card 31; a one-word copy edit stays under 0.4 and is therefore
# only caught by the byte check.
RENDERER_TOLERANCE = 2.0
SIZE_TOLERANCE = 2   # pixels a card may differ by before the layout counts as changed

BACKGROUND_TOLERANCE = 4  # how far a pixel may sit from the canvas colour and still be canvas
CARD_MARGIN_X = 20   # canvas visible around a card, in render pixels
CARD_MARGIN_Y = 16
FULL_WIDTH = 411     # README width for the whole-screen image
MIN_CARD_HEIGHT = 100


def canvas_colour(image: Image.Image) -> tuple[int, int, int]:
    """The page background, sampled from the four corners."""
    width, height = image.size
    corners = [
        image.getpixel((2, 2)),
        image.getpixel((width - 3, 2)),
        image.getpixel((2, height - 3)),
        image.getpixel((width - 3, height - 3)),
    ]
    return max(set(corners), key=corners.count)


def find_cards(image: Image.Image) -> list[tuple[int, int, int, int]]:
    """Return (top, bottom, left, right) for every card, in reading order."""
    width, height = image.size
    pixels = image.load()
    background = canvas_colour(image)

    def is_background(x: int, y: int) -> bool:
        # A tolerance, not equality: another platform's renderer rounds the canvas colour
        # slightly differently, and exact matching then finds one card instead of seven.
        r, g, b = pixels[x, y]
        return max(abs(r - background[0]), abs(g - background[1]), abs(b - background[2])) <= BACKGROUND_TOLERANCE

    sample_x = list(range(30, width - 30, 6))

    def row_is_gap(y: int) -> bool:
        background_hits = sum(1 for x in sample_x if is_background(x, y))
        return background_hits > len(sample_x) * 0.97

    rows: list[tuple[int, int]] = []
    start = None
    for y in range(height):
        if row_is_gap(y):
            if start is not None:
                if y - start > MIN_CARD_HEIGHT:
                    rows.append((start, y - 1))
                start = None
        elif start is None:
            start = y
    if start is not None and height - start > MIN_CARD_HEIGHT:
        rows.append((start, height - 1))

    cards = []
    for top, bottom in rows:
        middle = (top + bottom) // 2
        left = next(x for x in range(width) if not is_background(x, middle))
        right = next(x for x in range(width - 1, -1, -1) if not is_background(x, middle))
        cards.append((top, bottom, left, right))
    return cards


def crop_card(image: Image.Image, card: tuple[int, int, int, int]) -> Image.Image:
    top, bottom, left, right = card
    box = (
        max(0, left - CARD_MARGIN_X),
        max(0, top - CARD_MARGIN_Y),
        min(image.width, right + 1 + CARD_MARGIN_X),
        min(image.height, bottom + 1 + CARD_MARGIN_Y),
    )
    return image.crop(box)


def encode(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


def build(render: Path) -> dict[Path, bytes]:
    """Return every README image as path -> PNG bytes, without touching the disk."""
    for path in (render, TREND, ICON):
        if not path.exists():
            raise SystemExit(
                f"missing {path.relative_to(ROOT)}\n"
                "run `python tools/doc-images.py --gradle` to produce the renders first"
            )

    screen = Image.open(render).convert("RGB")
    cards = find_cards(screen)
    if len(cards) != len(CARDS):
        listing = "\n".join(f"  {i}: y {t}-{b} ({b - t + 1}px)" for i, (t, b, _, _) in enumerate(cards))
        raise SystemExit(
            f"expected {len(CARDS)} cards in {render.name}, found {len(cards)}:\n{listing}\n"
            "the layout changed: update CARDS in this script"
        )

    outputs: dict[Path, bytes] = {}
    for name, card in zip(CARDS, cards):
        cropped = crop_card(screen, card)
        if cropped.width < 200 or cropped.height < 150:
            raise SystemExit(f"{name}: crop is only {cropped.size}, the band detection is off")
        outputs[OUT / f"{name}.png"] = encode(cropped)

    full = screen.resize((FULL_WIDTH, round(screen.height * FULL_WIDTH / screen.width)), Image.LANCZOS)
    outputs[OUT / "phone-full.png"] = encode(full)

    # The trend bitmap is produced by TrendImageTest; keep its bytes as they are.
    outputs[OUT / "trend-image.png"] = TREND.read_bytes()
    outputs[OUT / "app-icon.png"] = ICON.read_bytes()
    return outputs


def mean_difference(left: Image.Image, right: Image.Image) -> float:
    """How different two same-sized images are, 0-255, robust to hinting differences."""
    size = (64, 64)
    a = left.convert("L").resize(size, Image.LANCZOS).tobytes()
    b = right.convert("L").resize(size, Image.LANCZOS).tobytes()
    return sum(abs(x - y) for x, y in zip(a, b)) / len(a)


def compare(path: Path, data: bytes, tolerate_renderer: bool) -> tuple[bool, str]:
    """Return (is_current, note) for one generated image."""
    if path.exists() and path.read_bytes() == data:
        return True, ""
    if not path.exists():
        return False, ""
    if not tolerate_renderer:
        return False, ""

    committed = Image.open(path).convert("RGB")
    fresh = Image.open(io.BytesIO(data)).convert("RGB")
    if abs(committed.width - fresh.width) > SIZE_TOLERANCE or abs(committed.height - fresh.height) > SIZE_TOLERANCE:
        return False, f"{committed.size} → {fresh.size}"
    difference = mean_difference(committed, fresh)
    if difference > RENDERER_TOLERANCE:
        return False, f"content changed (difference {difference:.1f}/255)"
    return True, f"{path.name}: same layout, another renderer (difference {difference:.1f}/255)"


def run_gradle() -> None:
    android = ROOT / "android"
    gradle = ROOT / ".toolchain" / "gradle-8.9" / "bin" / "gradle"
    command = [str(gradle) if gradle.exists() else "gradle", "--offline", ":app:testDebugUnitTest"]
    environment = {
        **os.environ,
        "JAVA_HOME": str(ROOT / ".toolchain" / "jdk"),
        "GRADLE_USER_HOME": str(ROOT / ".toolchain" / "gradle-home"),
    }
    print("running", " ".join(command))
    subprocess.run(command, cwd=android, env=environment, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--render", type=Path, default=RENDER, help="phone-screen.png to crop")
    parser.add_argument("--gradle", action="store_true", help="run the render tests before cropping")
    parser.add_argument("--check", action="store_true", help="fail if the committed images are stale")
    parser.add_argument(
        "--tolerate-renderer",
        action="store_true",
        help="another platform rendered the PNG (CI): compare layout, not exact pixels",
    )
    args = parser.parse_args()

    if args.gradle:
        run_gradle()

    try:
        outputs = build(args.render)
    except SystemExit as error:
        print(error, file=sys.stderr)
        return 1

    if args.check:
        stale, notes = [], []
        for path, data in outputs.items():
            current, note = compare(path, data, args.tolerate_renderer)
            if not current:
                stale.append(f"{path.relative_to(ROOT)}{' — ' + note if note else ''}")
            elif note:
                notes.append(note)
        if stale:
            print("stale images, run `python tools/doc-images.py`:")
            for entry in stale:
                print("  " + entry)
            return 1
        for note in notes:
            print("note: " + note)
        print(f"all {len(outputs)} README images are up to date")
        return 0

    OUT.mkdir(parents=True, exist_ok=True)
    for path, data in outputs.items():
        path.write_bytes(data)
        image = Image.open(io.BytesIO(data))
        print(f"wrote {path.relative_to(ROOT)}  {image.width}×{image.height}  {len(data) // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
