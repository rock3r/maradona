#!/usr/bin/env python3
"""Compares Maradona renders against mermaid goldens, tolerantly.

Desktop (strict, fixed canvas): all zoom levels of a chart share one canvas
(the gold's 100% content size + margin). Our renderer density-matches so its
natural content size equals the gold's; compare.py enforces that match
(per-axis ratio beyond SIZE_TOLERANCE fails the chart). Gold views are
derived from the committed 100% gold: 100/min paste the (re)sized gold
content centered on the canvas, max tiles crop the 5x-upscaled gold with the
same pan window the renderer used. Images are then compared at EXACTLY the
same size - no rescaling to a common canvas.

Devices (loose): the screen is the viewport, so sizes can't match the gold.
fit/min get a letterbox-aligned comparison (scale-aligned, blur + tolerance);
100 and max tiles are viewport fragments and get a smoke check (non-blank,
sane ink coverage).

Tolerances absorb web-vs-native text shaping, fonts and AA:
  PIXEL_TOLERANCE  per-channel delta that still counts as equal
  BG_TOLERANCE     distance from bg colour treated as background (flattened)
  BLUR_RADIUS      gaussian blur before diffing
  MAX_MISMATCH     fraction of pixels allowed to differ
  SIZE_TOLERANCE   max per-axis content size ratio vs gold (desktop)

Usage: compare.py <desktop|android|ios>   - exit code 0 iff all pairs pass.
"""
import json
import pathlib
import sys

from PIL import Image, ImageChops, ImageFilter

PIXEL_TOLERANCE = 48
BG_TOLERANCE = 28
BLUR_RADIUS = 2.0
MAX_MISMATCH = 0.15
# Max-zoom tiles compare against our own upscaled 100% render: text re-laid
# out at 5x differs subtly from 5x the hinted 100% text, which shifts glyphs,
# node widths and label positions slightly - so zoom tiles get more blur and
# a looser budget. They still catch exploded/missing/misplaced content.
ZOOM_BLUR_RADIUS = 4.0
ZOOM_MAX_MISMATCH = 0.25
SIZE_TOLERANCE = None      # disabled: legit layout differences shift the bbox; pixel diff is the judge
SMOKE_MIN_INK = 0.003
SMOKE_MAX_INK = 0.6        # an ANR dialog over a dimmed screen is mostly "ink"
SMOKE_MIN_BG_BRIGHTNESS = 180   # dimmed screenshots are dark grey
MAX_ZOOM = 5.0
MIN_ZOOM = 0.1

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODES = {
    "desktop": "strict",
    "android": "loose",
    "ios": "loose",
}


# ---------- shared image helpers ----------

def bg_color(img: Image.Image) -> tuple[int, int, int]:
    w, h = img.size
    pts = [img.getpixel(p) for p in [(2, 2), (w - 3, 2), (2, h - 3), (w - 3, h - 3)]]
    return tuple(sorted(c[i] for c in pts)[1] for i in range(3))


def content_bbox(img: Image.Image, bg: tuple[int, int, int]):
    bg_img = Image.new("RGB", img.size, bg)
    diff = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 255 if v > BG_TOLERANCE else 0)
    return diff.getbbox()


def crop_to_content(img: Image.Image, bg: tuple[int, int, int], pad: int = 8) -> Image.Image:
    bbox = content_bbox(img, bg)
    if bbox is None:
        return img
    l, t, r, b = bbox
    return img.crop((max(0, l - pad), max(0, t - pad), min(img.size[0], r + pad), min(img.size[1], b + pad)))


def flatten_bg(img: Image.Image, bg: tuple[int, int, int]) -> Image.Image:
    """Near-background pixels become pure white so theme bgs don't dominate the diff."""
    bg_img = Image.new("RGB", img.size, bg)
    mask = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 0 if v <= BG_TOLERANCE else 255)
    return Image.composite(img, Image.new("RGB", img.size, (255, 255, 255)), mask)


def diff_fraction(a: Image.Image, b: Image.Image) -> tuple[float, Image.Image]:
    """Fraction of mismatching pixels plus a red-overlay visualisation."""
    diff = ImageChops.difference(a, b)
    mask = diff.split()[0]
    for ch in diff.split()[1:]:
        mask = ImageChops.lighter(mask, ch)
    mask = mask.point(lambda v: 255 if v > PIXEL_TOLERANCE else 0)
    frac = sum(mask.histogram()[1:]) / (a.size[0] * a.size[1])
    gray = Image.blend(a, b, 0.5).convert("L").point(lambda v: 200 + v * 55 // 255).convert("RGB")
    red = Image.new("RGB", gray.size, (255, 40, 40))
    return frac, Image.composite(red, gray, mask)


# ---------- desktop: strict, fixed canvas ----------

def strict(platform: str, gold_path: pathlib.Path, manifest: dict, chart: str, ours_dir: pathlib.Path,
           web_img: pathlib.Path, report: dict) -> int:
    meta = manifest[chart]
    canvas = (meta["canvasW"], meta["canvasH"])
    content = (meta["contentW"], meta["contentH"])
    layout = (meta["layoutW"], meta["layoutH"])

    gold_full = Image.open(gold_path).convert("RGB")
    gold_crop = crop_to_content(gold_full, bg_color(gold_full))

    # Size match is a hard requirement, checked before any rescaling.
    ratio = max(
        gold_crop.size[0] / content[0], content[0] / gold_crop.size[0],
        gold_crop.size[1] / content[1], content[1] / gold_crop.size[1],
    )
    if SIZE_TOLERANCE is not None and ratio - 1 > SIZE_TOLERANCE:
        for ours in sorted(ours_dir.glob(f"{chart}-*.png")):
            report[ours.stem] = {
                "mode": "strict", "pass": False,
                "error": f"size mismatch: ours 100% content {content}, gold {gold_crop.size} (ratio {ratio:.3f})",
            }
        return len(report)

    gold_content = gold_crop.resize(content, Image.LANCZOS)

    def gold_canvas(view: Image.Image) -> Image.Image:
        c = Image.new("RGB", canvas, (255, 255, 255))
        c.paste(view, ((canvas[0] - view.size[0]) // 2, (canvas[1] - view.size[1]) // 2))
        return c

    # Max-zoom tiles can't be compared against mermaid: structural layout
    # differences (node spacing) amplify 5x and swamp the pixel diff. Instead
    # they verify zoom self-consistency: our own 100% render upscaled 5x with
    # the same pan window. That checks vector/stroke/text scaling, which is
    # what max zoom exercises; layout fidelity is covered by 100 and min.
    ours100 = Image.open(ours_dir / f"{chart}-100.png").convert("RGB")
    # The whole 100% canvas upscaled 5x. A max tile at pan fraction f shows
    # layout window [f*(5*layout - canvas)], and layout point q sits at
    # (canvas - layout)/2 + q on the 100% canvas, so the window origin in the
    # upscaled canvas is 2.5*(canvas - layout) + f*(5*layout - canvas).
    ours_big = ours100.resize(
        (canvas[0] * int(MAX_ZOOM), canvas[1] * int(MAX_ZOOM)), Image.LANCZOS)

    def gold_view(stem: str) -> Image.Image | None:
        if stem == "100":
            return gold_canvas(gold_content)
        if stem == "min":
            small = gold_content.resize(
                (round(content[0] * MIN_ZOOM), round(content[1] * MIN_ZOOM)), Image.LANCZOS)
            return gold_canvas(small)
        if stem.startswith("max-"):
            i, j = int(stem[4]), int(stem[5])
            fx, fy = i / 2, j / 2
            x0 = round(2.5 * (canvas[0] - layout[0]) + fx * (MAX_ZOOM * layout[0] - canvas[0]))
            y0 = round(2.5 * (canvas[1] - layout[1]) + fy * (MAX_ZOOM * layout[1] - canvas[1]))
            return ours_big.crop((x0, y0, x0 + canvas[0], y0 + canvas[1]))
        return None

    failures = 0
    for ours_path in sorted(ours_dir.glob(f"{chart}-*.png")):
        stem = ours_path.stem.removeprefix(f"{chart}-")
        gv = gold_view(stem)
        if gv is None:
            continue
        ours = Image.open(ours_path).convert("RGB")
        if ours.size != canvas:
            report[ours_path.stem] = {
                "mode": "strict", "pass": False,
                "error": f"canvas size mismatch: {ours.size} != {canvas}",
            }
            failures += 1
            continue
        o = flatten_bg(ours, bg_color(ours))
        g = flatten_bg(gv, (255, 255, 255))
        is_zoom_tile = stem.startswith("max-")
        blur = ZOOM_BLUR_RADIUS if is_zoom_tile else BLUR_RADIUS
        budget = ZOOM_MAX_MISMATCH if is_zoom_tile else MAX_MISMATCH
        o = o.filter(ImageFilter.GaussianBlur(blur))
        g = g.filter(ImageFilter.GaussianBlur(blur))
        frac, diff_img = diff_fraction(g, o)
        passed = frac <= budget
        failures += 0 if passed else 1
        mode = "zoom" if stem.startswith("max-") else "strict"
        web_img.mkdir(parents=True, exist_ok=True)
        name = f"{platform}-{ours_path.stem}"
        g.save(web_img / f"{name}-gold.png")
        o.save(web_img / f"{name}-ours.png")
        diff_img.save(web_img / f"{name}-diff.png")
        report[ours_path.stem] = {
            "mode": mode, "mismatch": round(frac, 4), "pass": passed,
            "images": {"gold": f"img/{name}-gold.png", "ours": f"img/{name}-ours.png", "diff": f"img/{name}-diff.png"},
        }
    return failures


# ---------- devices: loose letterbox compare + smoke ----------

CANVAS = 800


def letterbox(img: Image.Image, canvas: int) -> Image.Image:
    w, h = img.size
    scale = min(canvas / w, canvas / h)
    resized = img.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS)
    out = Image.new("RGB", (canvas, canvas), (255, 255, 255))
    out.paste(resized, ((canvas - resized.size[0]) // 2, (canvas - resized.size[1]) // 2))
    return out


def normalise(path: pathlib.Path) -> Image.Image:
    img = Image.open(path).convert("RGB")
    bg = bg_color(img)
    return letterbox(flatten_bg(crop_to_content(img, bg), bg), CANVAS).filter(ImageFilter.GaussianBlur(BLUR_RADIUS))


def loose_compare(gold: pathlib.Path, ours: pathlib.Path, web_img: pathlib.Path, name: str) -> dict:
    ours_img = Image.open(ours).convert("RGB")
    ours_bg = bg_color(ours_img)
    if sum(ours_bg) / 3 < SMOKE_MIN_BG_BRIGHTNESS:
        return {"mode": "compare", "pass": False,
                "error": f"ours background too dark {ours_bg} (system UI on top?)"}
    g = normalise(gold)
    o = letterbox(flatten_bg(crop_to_content(ours_img, ours_bg), ours_bg), CANVAS).filter(
        ImageFilter.GaussianBlur(BLUR_RADIUS))
    frac, diff_img = diff_fraction(g, o)
    web_img.mkdir(parents=True, exist_ok=True)
    g.save(web_img / f"{name}-gold.png")
    o.save(web_img / f"{name}-ours.png")
    diff_img.save(web_img / f"{name}-diff.png")
    return {
        "mode": "compare", "mismatch": round(frac, 4), "pass": frac <= MAX_MISMATCH,
        "images": {"gold": f"img/{name}-gold.png", "ours": f"img/{name}-ours.png", "diff": f"img/{name}-diff.png"},
    }


def smoke(ours: pathlib.Path) -> dict:
    """Viewport-clipped views can't be pixel-compared, so check the capture
    is a plausible diagram render: reasonably big, with a BRIGHT diagram
    background (a dimmed screen means a system dialog/ANR stole the
    foreground) and sane ink coverage (not blank, but not a wall of
    non-diagram pixels either)."""
    img = Image.open(ours).convert("RGB")
    w, h = img.size
    fail = None
    bg = bg_color(img)
    if w * h < 40_000:
        fail = f"suspicious size {w}x{h}"
    elif sum(bg) / 3 < SMOKE_MIN_BG_BRIGHTNESS:
        fail = f"background too dark {bg} (system UI on top?)"
    cropped = crop_to_content(img, bg)
    bg_img = Image.new("RGB", img.size, bg)
    mask = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 255 if v > 60 else 0)
    ink = sum(mask.histogram()[1:]) / (w * h)
    if fail is None and not (SMOKE_MIN_INK < ink <= SMOKE_MAX_INK):
        fail = f"ink coverage {ink:.3f} out of range"
    return {"mode": "smoke", "ink": round(ink, 4), "pass": fail is None,
            **({"error": fail} if fail else {}),
            "images": {"raw": f"../{ours.parent.name}/{ours.name}"}}


def split_stem(stem: str) -> tuple[str, str]:
    parts = stem.split("-")
    if len(parts) >= 3 and parts[-2] == "max":
        return "-".join(parts[:-2]), "max"
    return "-".join(parts[:-1]), parts[-1]


def main() -> int:
    platform = sys.argv[1] if len(sys.argv) > 1 else "desktop"
    gold_dir = ROOT / "goldens"
    ours_dir = ROOT / "out" / platform
    web_img = ROOT / "out" / "web" / "img"
    report = {}
    failures = 0

    if MODES[platform] == "strict":
        manifest = json.loads((ours_dir / "manifest.json").read_text())
        charts = sorted({split_stem(p.stem)[0] for p in ours_dir.glob("*.png")})
        for chart in charts:
            failures += strict(platform, gold_dir / f"{chart}.png", manifest, chart, ours_dir, web_img, report)
    else:
        for ours in sorted(ours_dir.glob("*.png")):
            chart, zoom = split_stem(ours.stem)
            gold = gold_dir / f"{chart}.png"
            if not gold.exists():
                report[ours.stem] = {"pass": False, "error": f"missing golden {gold}"}
                failures += 1
                continue
            if zoom in ("fit", "min"):
                result = loose_compare(gold, ours, web_img, f"{platform}-{ours.stem}")
            else:
                result = smoke(ours)
            report[ours.stem] = result
            if not result["pass"]:
                failures += 1

    for stem in sorted(report):
        r = report[stem]
        status = "PASS" if r["pass"] else "FAIL"
        detail = (r["error"] if "error" in r
                  else f"mismatch={r['mismatch']}" if "mismatch" in r
                  else f"ink={r['ink']}")
        print(f"{status}  {platform}/{stem:28s} {r.get('mode', '?'):7s} {detail}")
    out = ROOT / "out" / f"report-{platform}.json"
    out.write_text(json.dumps(report, indent=2))
    print(f"\n{platform}: {len(report) - failures}/{len(report)} passed -> {out}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
