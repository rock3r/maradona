#!/usr/bin/env python3
"""Compares Maradona renders against mermaid goldens, tolerantly.

Pipeline per pair:
  1. crop to content bbox (background = dominant corner colour)
  2. remap near-background pixels to pure white (theme bg differs: cream vs white)
  3. letterbox both onto a shared canvas sized to the *smaller* content
     (never upscale the low-res side; absorbs font/AA differences)
  4. gaussian blur to absorb anti-aliasing and font-shape differences
  5. per-pixel max-channel delta; mismatch if > PIXEL_TOLERANCE
  6. PASS if mismatching fraction <= MAX_MISMATCH

Zoom modes per platform: fit/100/min/max get a full comparison on desktop;
on devices 100 and max show viewport-clipped fragments (the screen is the
viewport), so they get a smoke check (non-blank, sane ink coverage) instead.

Usage: compare.py <platform>   (desktop | android | ios)
Exit code 0 iff every pair passes.
"""
import json
import pathlib
import sys

from PIL import Image, ImageChops, ImageFilter

PIXEL_TOLERANCE = 48      # per-channel delta that still counts as equal
BG_TOLERANCE = 28         # distance from bg colour treated as background
BLUR_RADIUS = 2.0
MAX_MISMATCH = 0.15       # fraction of pixels allowed to differ
SMOKE_MIN_INK = 0.003     # fraction of non-bg pixels for a "rendered something" check
CANVAS = 800

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODES = {
    "desktop": {"fit": "compare", "100": "compare", "min": "compare", "max": "compare"},
    "android": {"fit": "compare", "min": "compare", "100": "smoke", "max": "smoke"},
    "ios": {"fit": "compare", "min": "compare", "100": "smoke", "max": "smoke"},
}


def bg_color(img: Image.Image) -> tuple[int, int, int]:
    w, h = img.size
    pts = [img.getpixel(p) for p in [(2, 2), (w - 3, 2), (2, h - 3), (w - 3, h - 3)]]
    return tuple(sorted(c[i] for c in pts)[1] for i in range(3))  # median-ish per channel


def crop_to_content(img: Image.Image, bg: tuple[int, int, int], pad: int = 8) -> Image.Image:
    bg_img = Image.new("RGB", img.size, bg)
    diff = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 255 if v > BG_TOLERANCE else 0)
    bbox = diff.getbbox()
    if bbox is None:
        return img
    l, t, r, b = bbox
    l = max(0, l - pad); t = max(0, t - pad)
    r = min(img.size[0], r + pad); b = min(img.size[1], b + pad)
    return img.crop((l, t, r, b))


def flatten_bg(img: Image.Image, bg: tuple[int, int, int]) -> Image.Image:
    """Near-background pixels become pure white so theme bgs don't dominate the diff."""
    bg_img = Image.new("RGB", img.size, bg)
    mask = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 0 if v <= BG_TOLERANCE else 255)
    white = Image.new("RGB", img.size, (255, 255, 255))
    return Image.composite(img, white, mask)


def letterbox(img: Image.Image, canvas: int) -> Image.Image:
    w, h = img.size
    scale = min(canvas / w, canvas / h, 1.0)  # never upscale
    nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
    resized = img.resize((nw, nh), Image.LANCZOS) if scale < 1.0 else img
    out = Image.new("RGB", (canvas, canvas), (255, 255, 255))
    out.paste(resized, ((canvas - nw) // 2, (canvas - nh) // 2))
    return out


def normalise(path: pathlib.Path) -> Image.Image:
    img = Image.open(path).convert("RGB")
    bg = bg_color(img)
    return letterbox(flatten_bg(crop_to_content(img, bg), bg), CANVAS).filter(ImageFilter.GaussianBlur(BLUR_RADIUS))


def compare(gold: pathlib.Path, ours: pathlib.Path) -> dict:
    g = normalise(gold)
    o = normalise(ours)
    diff = ImageChops.difference(g, o)
    hist = diff.convert("L").histogram()
    total = CANVAS * CANVAS
    mismatched = sum(hist[PIXEL_TOLERANCE + 1:])
    # also check each channel (a pure green/red swap must not hide in L)
    channel_mismatch = 0
    for ch in diff.split():
        h = ch.histogram()
        channel_mismatch = max(channel_mismatch, sum(h[PIXEL_TOLERANCE + 1:]))
    frac = max(mismatched, channel_mismatch) / total
    return {"mode": "compare", "mismatch": round(frac, 4), "pass": frac <= MAX_MISMATCH}


def smoke(ours: pathlib.Path) -> dict:
    img = Image.open(ours).convert("RGB")
    bg = bg_color(img)
    cropped = crop_to_content(img, bg)
    w, h = img.size
    ink = 1.0 - (w * h - (cropped.size[0] * cropped.size[1])) / (w * h)
    ok = cropped.size != img.size or ink > SMOKE_MIN_INK
    return {"mode": "smoke", "ink": round(ink, 4), "pass": ok and ink > SMOKE_MIN_INK}


def main() -> int:
    platform = sys.argv[1] if len(sys.argv) > 1 else "desktop"
    gold_dir = ROOT / "goldens"
    ours_dir = ROOT / "out" / platform
    modes = MODES[platform]
    report = {}
    failures = 0
    for ours in sorted(ours_dir.glob("*.png")):
        chart, _, zoom = ours.stem.rpartition("-")
        gold = gold_dir / f"{chart}.png"
        if not gold.exists():
            report[ours.stem] = {"pass": False, "error": f"missing golden {gold}"}
            failures += 1
            continue
        result = compare(gold, ours) if modes.get(zoom, "compare") == "compare" else smoke(ours)
        report[ours.stem] = result
        status = "PASS" if result["pass"] else "FAIL"
        if not result["pass"]:
            failures += 1
        detail = f"mismatch={result.get('mismatch')}" if result["mode"] == "compare" else f"ink={result.get('ink')}"
        print(f"{status}  {platform}/{ours.stem:28s} {result['mode']:7s} {detail}")
    out = ROOT / "out" / f"report-{platform}.json"
    out.write_text(json.dumps(report, indent=2))
    print(f"\n{platform}: {len(report) - failures}/{len(report)} passed -> {out}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
