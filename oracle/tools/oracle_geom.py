#!/usr/bin/env python3
"""Oracle capture geometry, shared by the capture scripts and compare.py.

MaradonaDiagram centres the diagram in its viewport and pans within the
scrollable range with ``pan = R/2 - f*R``, where ``R = max(0, canvas - view)``
and ``f`` in [0, 1] is the pan fraction the oracle passes as ``initialPan``.
So for a diagram canvas of ``scaled`` px inside a viewport of ``view`` px:

* ``scaled <= view``: there is no scroll range, the canvas is centred
* ``scaled >  view``: the visible window starts at ``f * (scaled - view)``

The same maths describes every capture: the desktop renderer's viewport, the
phone's screen, and the windows the comparator crops out of the golden.

Tiles are identified by their pan fractions, so a manifest written by any
producer is enough for the comparator to reproduce the framing exactly.
"""
import argparse
import json
import math
import pathlib
import sys

MIN_ZOOM, MAX_ZOOM = 0.1, 5.0
LEVELS = ("100", "min", "max")
LEVEL_SCALE = {"100": 1.0, "min": MIN_ZOOM, "max": MAX_ZOOM}
# "100" is compared against the golden, so its grid must COVER the canvas
# without gaps. "min"/"max" are scale checks: a 3-per-axis sample is enough
# and keeps the device capture runs (each tile is a launch) bounded.
MAX_TILES_PER_AXIS = 8
SAMPLE_PER_AXIS = 3


def level_mode(level):
    # Every level is captured as a *covering* grid: the tiles are windows into the
    # zoomed canvas and get stitched back together, so they must leave no gaps.
    # At 5x the canvas is several times the viewport, so a 3-per-axis sample would
    # leave blank bands in the stitched image.
    return "cover"


def fractions(n):
    if n <= 1:
        return [0.5]
    return [i / (n - 1) for i in range(n)]


def axis_count(scaled, view, mode):
    if scaled <= view:
        return 1
    want = math.ceil(scaled / view) if mode == "cover" else SAMPLE_PER_AXIS
    return max(2, min(MAX_TILES_PER_AXIS, want))


def tiles(canvas, view, level, mode=None):
    """Pan fractions (fx, fy) needed to capture `level` of a `canvas` chart."""
    z = LEVEL_SCALE[level]
    mode = mode or level_mode(level)
    nx = axis_count(canvas[0] * z, view[0], mode)
    ny = axis_count(canvas[1] * z, view[1], mode)
    return [(fx, fy) for fy in fractions(ny) for fx in fractions(nx)]


def window_origin(scaled, view, fraction):
    """Canvas-space origin of the viewport window at `fraction`."""
    if scaled <= view:
        return (scaled - view) / 2
    return fraction * (scaled - view)


def tile_path(chart, level, index=None):
    """Render file name: `<id>-<level>.png`, or `<id>-<level>-<i>-<j>.png`."""
    if index is None:
        return f"{chart}-{level}.png"
    i, j = index
    return f"{chart}-{level}-{i}-{j}.png"


def manifest_from_captures(captures, out_path):
    """Turns `chart canvasW canvasH viewportW viewportH level fx fy` lines into manifest.json."""
    charts = {}
    for line in captures.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        chart, cw, ch, vw, vh, level, fx, fy = line.split()
        meta = charts.setdefault(chart, {
            "canvasW": int(cw), "canvasH": int(ch),
            "viewportW": int(vw), "viewportH": int(vh), "tiles": {},
        })
        meta["tiles"].setdefault(level, []).append([round(float(fx), 4), round(float(fy), 4)])
    out_path.write_text(json.dumps({"charts": charts}, indent=2, sort_keys=True))
    return len(charts)


def _parse_size(text):
    w, h = text.lower().split("x")
    return (int(w), int(h))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("action", choices=["tiles", "manifest"])
    ap.add_argument("--canvas", help="diagram canvas, WxH px")
    ap.add_argument("--viewport", help="capture viewport, WxH px")
    ap.add_argument("--level", choices=LEVELS)
    ap.add_argument("--captures", help="capture log for the manifest action")
    ap.add_argument("--out", help="manifest.json to write")
    args = ap.parse_args()

    if args.action == "tiles":
        canvas = _parse_size(args.canvas)
        view = _parse_size(args.viewport)
        level_tiles = tiles(canvas, view, args.level)
        cols = len({round(fx, 4) for fx, _ in level_tiles})
        for i, (fx, fy) in enumerate(level_tiles):
            print(f"{i // cols} {i % cols} {fx:.4f} {fy:.4f}")
        return 0

    n = manifest_from_captures(pathlib.Path(args.captures), pathlib.Path(args.out))
    print(f"wrote {args.out} ({n} charts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
