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

    python tools/doc-images.py             # crop the render that already exists
    python tools/doc-images.py --gradle    # re-run the render tests first
    python tools/doc-images.py --check     # exit 1 if the committed images are stale

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

CARD_MARGIN_X = 20   # canvas visible around a card, in render pixels
CARD_MARGIN_Y = 16
FULL_WIDTH = 411     # README width for the whole-screen image
MIN_CARD_HEIGHT = 100
MIN_CARD_INK = 0.03  # a card must be more than 3% covered by its own rows to count


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
        r, g, b = pixels[x, y]
        return abs(r - background[0]) <= 1 and abs(g - background[1]) <= 1 and abs(b - background[2]) <= 1

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
    args = parser.parse_args()

    if args.gradle:
        run_gradle()

    try:
        outputs = build(args.render)
    except SystemExit as error:
        print(error, file=sys.stderr)
        return 1

    if args.check:
        stale = []
        for path, data in outputs.items():
            if not path.exists() or path.read_bytes() != data:
                stale.append(path.relative_to(ROOT))
        if stale:
            print("stale images, run `python tools/doc-images.py`:")
            for path in stale:
                print("  " + str(path))
            return 1
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
