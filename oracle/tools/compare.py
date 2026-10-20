#!/usr/bin/env python3
"""Compares Maradona oracle captures against the mermaid goldens.

Every capture is framed the same way on every platform: the diagram canvas is
rendered at a scale chosen so its content matches the golden's, centred in the
platform's viewport (the desktop render scene or the device screen), and panned
by a fraction of the scrollable range. `oracle_geom` describes that framing, so
a capture and its golden are compared 1:1, at the same size, with no letterbox
normalisation and no rescaling of our render.

Levels mean different things, and are reported as such:

* ``100`` - the full-size render: capture sanity plus the size relationship
  (our content must match the golden's in the axis the density was matched
  against). The pixel difference is reported for the viewer but is not a gate -
  fidelity is measured structurally by compare_layout.py.
* ``min`` - 0.1x: the drawing must sit where the golden's does.
* ``max`` - scale correctness at 5x. Mermaid cannot produce a meaningful 5x
  reference (its raster would be a blurry upscale, ours crisp vector), so the
  reference is our own 100% render upscaled 5x, which must match ours closely.

Captures that are not real diagram renders are rejected before any comparison:
the background must be bright (a dimmed screenshot means a system dialog or an
ANR stole the foreground) and ink coverage must be sane (not blank, not a wall
of non-diagram pixels such as system bars).

Fidelity levels also enforce the size relationship the oracle exists for: the
renderer chooses its scale from the golden, so our content must match the
golden's in the binding axis (the other axis legitimately drifts - Maradona's
layout is not mermaid's - and is reported as drift).

Tolerances live right below; `MAX_MISMATCH` fractions are measured against
real captures.

Usage: compare.py <desktop|android|ios>  - exit code 0 iff every entry passes.
"""
import collections
import json
import pathlib
import sys

from PIL import Image, ImageChops

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from oracle_geom import LEVELS, LEVEL_SCALE, tile_path, window_origin  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent

# per-channel delta that still counts as equal
PIXEL_TOLERANCE = 48
# distance from the background colour treated as background
BG_TOLERANCE = 28
# There is deliberately NO mismatch budget for the golden levels. A flowchart is
# sparse line art: a 4px misalignment scores the same as a perfect render
# (measured: 0.084 vs 0.085 of the canvas), so a fraction-of-canvas threshold
# either passes everything or fails everything. Fidelity is measured
# structurally instead - see compare_layout.py, which compares Maradona's
# layout to mermaid's SVG geometry. The pixel diff here is a visual aid.
# 5x self-consistency: same geometry at a different scale, so this is tight.
SCALE_MAX_MISMATCH = 0.10
# ... and we must have drawn a comparable amount of ink to the reference.
# Not 1.0: the 5x reference is a 1x render upscaled, whose 1px strokes become 5px
# and whose glyphs carry a heavier weight than text laid out at 5x. On a sparse
# wide chart (a single row of shapes) that gap is large - measured 0.35 there,
# ~0.8-0.95 elsewhere - so the floor only catches a render that is essentially
# empty.
INK_RATIO_MIN, INK_RATIO_MAX = 0.25, 1.6
# a capture must be a plausible diagram render. The 0.1x level is legitimately
# almost empty on a phone screen (a small chart is a few dozen pixels), so it is
# judged against the reference's ink instead of the viewport's.
MIN_INK, MAX_INK = 0.0015, 0.50
# 0.1x: the drawing must sit where the reference puts it (offset as a share of
# the viewport, size as a ratio) even though its strokes are sub-pixel.
MIN_BOX_OFFSET = 0.02
MIN_BOX_SCALE = 0.5
MIN_BG_BRIGHTNESS = 180
# the renderer picks its scale from the golden, so one content axis must line up
AXIS_MATCH_TOLERANCE = 0.03
# viewer composites are downscaled to this long edge
DISPLAY_MAX = 1600
WHITE = (255, 255, 255)


# ---------- shared image helpers ----------

def bg_color(img):
    """The diagram background: the most common colour, not a corner sample.

    A zoomed tile can land with a node border in its corner, which would make a
    corner-based estimate report the border as the background (and then flag a
    perfectly good render as "too dark").
    """
    small = img.resize((160, 160)) if max(img.size) > 160 else img
    counts = collections.Counter(small.getdata())
    return counts.most_common(1)[0][0]


def content_bbox(img, bg):
    bg_img = Image.new("RGB", img.size, bg)
    diff = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 255 if v > BG_TOLERANCE else 0)
    return diff.getbbox()


def crop_to_content(img, bg, pad=0):
    bbox = content_bbox(img, bg)
    if bbox is None:
        return None
    l, t, r, b = bbox
    return img.crop((max(0, l - pad), max(0, t - pad), min(img.size[0], r + pad), min(img.size[1], b + pad)))


def ink_fraction(img, bg):
    """Share of pixels that are not background (a real measure, not a bbox area)."""
    bg_img = Image.new("RGB", img.size, bg)
    mask = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 255 if v > 60 else 0)
    return sum(mask.histogram()[1:]) / (img.size[0] * img.size[1])


def flatten_bg(img, bg):
    bg_img = Image.new("RGB", img.size, bg)
    mask = ImageChops.difference(img, bg_img).convert("L").point(lambda v: 0 if v <= BG_TOLERANCE else 255)
    return Image.composite(img, Image.new("RGB", img.size, WHITE), mask)


def diff_counts(a, b):
    """Mismatching pixel count plus a red-overlay visualisation."""
    diff = ImageChops.difference(a, b)
    mask = diff.split()[0]
    for ch in diff.split()[1:]:
        mask = ImageChops.lighter(mask, ch)
    mask = mask.point(lambda v: 255 if v > PIXEL_TOLERANCE else 0)
    count = sum(mask.histogram()[1:])
    gray = Image.blend(a, b, 0.5).convert("L").point(lambda v: 200 + v * 55 // 255).convert("RGB")
    red = Image.new("RGB", gray.size, (255, 40, 40))
    return count, Image.composite(red, gray, mask)


def scaled(img, z):
    if z == 1.0:
        return img
    size = (max(1, round(img.size[0] * z)), max(1, round(img.size[1] * z)))
    return img.resize(size, Image.LANCZOS)


def framed(ref, origin, size, fill=WHITE):
    """`ref` (a canvas image) seen through a `size` viewport at canvas `origin`."""
    out = Image.new("RGB", size, fill)
    out.paste(ref, (-round(origin[0]), -round(origin[1])))
    return out


def centre_tile(tiles_imgs, total):
    """The middle tile of a device capture: overlapping windows cannot be joined."""
    return tiles_imgs[len(tiles_imgs) // 2]


def display_asset(img):
    w, h = img.size
    if max(w, h) <= DISPLAY_MAX:
        return img
    s = DISPLAY_MAX / max(w, h)
    return img.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)


def mosaic(tiles_imgs, fractions):
    """Side-by-side montage of the tiles, for charts whose tiles form a cover grid.

    Only the desktop renderer produces such a grid. A device capture's tiles are
    *overlapping* pan windows, so gluing them together repeats the content and
    looks like broken stitching; callers use [centre_tile] for those instead.
    """
    ys = sorted({round(fy, 4) for _, fy in fractions})
    xs = sorted({round(fx, 4) for fx, _ in fractions})
    nx, ny = len(xs), len(ys)
    if nx == 1 and ny == 1:
        return tiles_imgs[0]
    w, h = tiles_imgs[0].size
    out = Image.new("RGB", (w * nx, h * ny), WHITE)
    for (fx, fy), img in zip(fractions, tiles_imgs):
        out.paste(img, (xs.index(round(fx, 4)) * w, ys.index(round(fy, 4)) * h))
    return out


# ---------- capture loading ----------

def chart_meta(manifest, chart):
    meta = manifest["charts"][chart]
    canvas = (meta["canvasW"], meta["canvasH"])
    view = (meta["viewportW"], meta["viewportH"])
    return canvas, view, meta["tiles"]


def load_tiles(ours_dir, chart, level, fractions):
    n = len(fractions)
    tiles = []
    for i, (fx, fy) in enumerate(fractions):
        index = None if n == 1 else (i // _cols(fractions), i % _cols(fractions))
        path = ours_dir / tile_path(chart, level, index)
        if not path.exists():
            return None, path
        tiles.append(Image.open(path).convert("RGB"))
    return tiles, None


def _cols(fractions):
    return len({round(fx, 4) for fx, _ in fractions})


# ---------- comparison ----------

def compare_chart(platform, chart, manifest, gold_path, ours_dir, web_img):
    """Returns {entry_name: report_dict} for one chart."""
    canvas, view, tile_map = chart_meta(manifest, chart)
    meta = manifest["charts"][chart]
    results = {}
    prefix = f"{chart}-"

    gold_full = Image.open(gold_path).convert("RGB")
    gold_content = crop_to_content(gold_full, bg_color(gold_full))

    # ---- 100: covering grid, stitched, becomes the content anchor ----
    f100 = [tuple(f) for f in tile_map["100"]]
    tiles100, missing = load_tiles(ours_dir, chart, "100", f100)
    if missing:
        results[f"{prefix}100"] = {"mode": "fidelity", "pass": False, "error": f"missing {missing.name}"}
        return results
    bad = [msg for i, t in enumerate(tiles100) for msg in _capture_problems(t, view)]
    if bad:
        results[f"{prefix}100"] = {"mode": "fidelity", "pass": False, "error": "; ".join(sorted(set(bad))[:3])}
        return results

    stitched = Image.new("RGB", canvas, bg_color(tiles100[0]))
    for (fx, fy), img in zip(f100, tiles100):
        stitched.paste(img, (round(window_origin(canvas[0], view[0], fx)),
                             round(window_origin(canvas[1], view[1], fy))))
    ours_content = crop_to_content(stitched, bg_color(stitched))
    if ours_content is None:
        results[f"{prefix}100"] = {"mode": "fidelity", "pass": False, "error": "blank capture"}
        return results
    box = content_bbox(stitched, bg_color(stitched))
    size = ours_content.size
    offset = (box[0], box[1])

    # The size relationship uses the renderer's own content measurement when the
    # manifest has one (the desktop test writes it): measuring the stitched tiles
    # here would use a different background threshold and report drift that is
    # not in the layout. Device manifests carry no such measurement, so they fall
    # back to the stitched content.
    measured = (meta["contentW"], meta["contentH"]) if "contentW" in meta else size
    ratio_w = measured[0] / gold_content.size[0]
    ratio_h = measured[1] / gold_content.size[1]
    drift = (round(max(ratio_w, 1 / ratio_w), 4), round(max(ratio_h, 1 / ratio_h), 4))
    # 1.0 == same size as the golden. The renderer picks its scale so one axis
    # lines up; the other legitimately differs (Maradona's layout is not
    # mermaid's) and is reported as drift.
    binding = min(drift) - 1.0

    def anchor_reference():
        """Canvas-sized image: golden content at our measured content size/offset."""
        ref = Image.new("RGB", canvas, WHITE)
        ref.paste(gold_content.resize(size, Image.LANCZOS), offset)
        return ref

    def scale_reference():
        """Canvas-sized image: our own 100% render (for zoom scale checks)."""
        return stitched

    # ---- every level ----
    for level in LEVELS:
        if level not in tile_map:
            continue
        fractions = [tuple(f) for f in tile_map[level]]
        tiles, missing = load_tiles(ours_dir, chart, level, fractions)
        entry = f"{prefix}{level}"
        if missing:
            results[entry] = {"mode": _mode(level), "pass": False, "error": f"missing {missing.name}"}
            continue
        problems = []
        for tile in tiles:
            problems += _capture_problems(tile, view)
        ink = sum(ink_fraction(t, bg_color(t)) for t in tiles) / len(tiles)
        if level != "min" and ink < MIN_INK:
            problems.append("blank capture")
        elif ink > MAX_INK:
            problems.append(f"ink {ink:.3f} too high (not a diagram?)")
        if problems:
            results[entry] = {"mode": _mode(level), "pass": False,
                              "error": "; ".join(sorted(set(problems))[:3])}
            continue

        z = LEVEL_SCALE[level]
        src = scale_reference() if level == "max" else anchor_reference()
        big = scaled(src, z)
        o_imgs, ref_imgs, diff_imgs = [], [], []
        mismatch = union = ours_ink = ref_ink = total = 0
        for (fx, fy), ours in zip(fractions, tiles):
            origin = (window_origin(big.size[0], view[0], fx), window_origin(big.size[1], view[1], fy))
            ref = framed(big, origin, view)
            f_ref = flatten_bg(ref, WHITE)
            f_ours = flatten_bg(ours, bg_color(ours))
            count, diff_img = diff_counts(f_ref, f_ours)
            mismatch += count
            r_ink, o_ink = _ink_pixels(f_ref), _ink_pixels(f_ours)
            ours_ink += o_ink
            ref_ink += r_ink
            union += r_ink + o_ink
            total += view[0] * view[1]
            o_imgs.append(f_ours)
            ref_imgs.append(f_ref)
            diff_imgs.append(diff_img)

        frac = mismatch / total if total else 1.0
        # Share of the *drawing* that differs. Reported, not gated: our layout
        # is not mermaid's (node widths differ), so thin lines land in
        # different places and this number is large even for a faithful render.
        ink_frac = mismatch / union if union else 1.0
        # How much of the reference's drawing we actually drew. Catches a
        # render that silently came out empty, which a fraction-of-viewport
        # diff cannot see on a sparse 5x canvas.
        ink_ratio = ours_ink / ref_ink if ref_ink else 1.0
        # At 0.1x the drawing is sub-pixel, so ink counts say nothing; check
        # instead that it is where the reference says it should be.
        box_error = None
        if level == "min":
            ours_box = _faint_bbox(o_imgs[0])
            ref_box = _faint_bbox(ref_imgs[0])
            if ours_box is None or ref_box is None:
                box_error = "nothing visible at 0.1x"
            else:
                off = max(abs((ours_box[0] + ours_box[2]) - (ref_box[0] + ref_box[2])),
                          abs((ours_box[1] + ours_box[3]) - (ref_box[1] + ref_box[3]))) / 2
                scale = max((ours_box[2] - ours_box[0]) / max(1, ref_box[2] - ref_box[0]),
                            (ours_box[3] - ours_box[1]) / max(1, ref_box[3] - ref_box[1]))
                if off > MIN_BOX_OFFSET * max(view) or not (MIN_BOX_SCALE <= scale <= 1 / MIN_BOX_SCALE):
                    box_error = (f"0.1x drawing is misplaced (offset {off:.0f}px, size x{scale:.2f})")
        budget = SCALE_MAX_MISMATCH if level == "max" else None
        # Golden levels are gated on the size relationship, not on pixels.
        passed = True if budget is None else frac <= budget
        if level == "max" and not (INK_RATIO_MIN <= ink_ratio <= INK_RATIO_MAX):
            passed = False
        if box_error:
            passed = False
        if level == "100" and binding > AXIS_MATCH_TOLERANCE:
            passed = False

        name = f"{platform}-{chart}-{level}"
        web_img.mkdir(parents=True, exist_ok=True)
        images = {"gold": [], "ours": [], "diff": []}
        if len(fractions) == 1:
            # One window: a plain side-by-side pair.
            for key, imgs in (("gold", ref_imgs), ("ours", o_imgs), ("diff", diff_imgs)):
                display_asset(imgs[0]).save(web_img / f"{name}-{key}.png")
                images[key] = [f"img/{name}-{key}.png"]
        else:
            # Several overlapping pan windows: keep them apart, one pair per tile,
            # so each can be inspected at 1:1 with both sides showing the same
            # region. Gluing them into a montage repeats content.
            for index, _ in enumerate(fractions):
                suffix = f"{name}-t{index}"
                display_asset(ref_imgs[index]).save(web_img / f"{suffix}-gold.png")
                display_asset(o_imgs[index]).save(web_img / f"{suffix}-ours.png")
                display_asset(diff_imgs[index]).save(web_img / f"{suffix}-diff.png")
                images["gold"].append(f"img/{suffix}-gold.png")
                images["ours"].append(f"img/{suffix}-ours.png")
                images["diff"].append(f"img/{suffix}-diff.png")
        results[entry] = {
            "mode": _mode(level),
            "mismatch": round(frac, 4),
            "inkMismatch": round(ink_frac, 4),
            "inkRatio": round(ink_ratio, 3),
            "budget": budget,
            "tiles": len(fractions),
            "pass": passed,
            "driftW": drift[0],
            "driftH": drift[1],
            **({} if passed else {"error":
                                  f"binding-axis size drift {binding:.3f} > {AXIS_MATCH_TOLERANCE}"
                                  if level == "100" and binding > AXIS_MATCH_TOLERANCE
                                  else box_error
                                  if box_error
                                  else f"ink ratio {ink_ratio:.2f} outside [{INK_RATIO_MIN}, {INK_RATIO_MAX}]"
                                  if level == "max" and not (INK_RATIO_MIN <= ink_ratio <= INK_RATIO_MAX)
                                  else f"mismatch {frac:.3f} > budget {budget}"}),
            "images": images,
        }
    return results


def _mode(level):
    return "scale" if level == "max" else "capture"


def _ink_pixels(img, threshold=128):
    """Drawn pixels (flattened images use white as background)."""
    return sum(img.convert("L").point(lambda v: 255 if v < threshold else 0).histogram()[1:])


def _faint_bbox(img, threshold=8):
    """Bounding box of anything that is not background, using a low threshold.

    At 0.1x a diagram's strokes are sub-pixel, so they never reach the ink
    threshold; this still finds where the (faint) drawing is.
    """
    return img.convert("L").point(lambda v: 255 if v < 255 - threshold else 0).getbbox()


def _capture_problems(tile, view):
    """Per-tile sanity. Ink is checked per level: a 5x tile can legitimately be empty."""
    problems = []
    if tile.size != view:
        problems.append(f"capture size {tile.size} != viewport {view}")
        return problems
    bg = bg_color(tile)
    if sum(bg) / 3 < MIN_BG_BRIGHTNESS:
        problems.append(f"background too dark {bg} (system UI or a dimmed screen on top?)")
    return problems


def main():
    platform = sys.argv[1] if len(sys.argv) > 1 else "desktop"
    from freshness import require_fresh
    hint = (
        "./gradlew :sample-desktop:desktopTest --tests dev.maradona.sample.OracleGoldenTest --rerun-tasks"
        if platform == "desktop"
        else f"oracle/tools/capture_{'android' if platform == 'android' else 'ios'}.sh <device>"
    )
    if not require_fresh(ROOT / "out" / platform, hint):
        # Remove the previous report: leaving it in place is how a stale result
        # kept being displayed as if it came from the current renders.
        stale_report = ROOT / "out" / f"report-{platform}.json"
        if stale_report.exists():
            stale_report.unlink()
            print(f"removed {stale_report.name}: it describes renders that are gone or stale")
        return 2
    gold_dir = ROOT / "goldens"
    ours_dir = ROOT / "out" / platform
    web_img = ROOT / "out" / "web" / "img"
    manifest = json.loads((ours_dir / "manifest.json").read_text())
    report, failures = {}, 0

    for chart in sorted(manifest["charts"]):
        results = compare_chart(platform, chart, manifest, gold_dir / f"{chart}.png", ours_dir, web_img)
        for entry, result in results.items():
            report[entry] = result
            if not result["pass"]:
                failures += 1

    for stem in sorted(report):
        r = report[stem]
        detail = r.get("error") or (f"mismatch={r['mismatch']} (visual only) "
                                    f"drift={r['driftW']}/{r['driftH']}")
        print(f"{'PASS' if r['pass'] else 'FAIL'}  {platform}/{stem:26s} {r.get('mode', '?'):8s} {detail}")
    out = ROOT / "out" / f"report-{platform}.json"
    out.write_text(json.dumps(report, indent=2))
    print(f"\n{platform}: {len(report) - failures}/{len(report)} passed -> {out}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
