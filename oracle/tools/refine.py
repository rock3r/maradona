#!/usr/bin/env python3
"""Refines a capture density from the real 100% tiles.

The rough calibration (calibrate.py) extrapolates from a 0.1x render. At that
size minimum stroke widths and layout rounding inflate the ink bounding box by
a few percent, so the corrected density lands 4-10% off. This stitches the
actual 100% tiles - the framing is exact, oracle_geom knows where every tile
sits - measures the real ink, and returns the density that matches the golden.

    refine.py --dir out/ios --chart basic-td --canvas 607x1061 --viewport 750x1334 \
              --tiles "0,0;0.5,0;..." --density 1.2799 --gold goldens/basic-td.png

Prints "<refined density> <inkW>x<inkH>".
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from compare import bg_color, content_bbox  # noqa: E402
from oracle_geom import tile_path, window_origin  # noqa: E402

from PIL import Image  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", required=True)
    ap.add_argument("--chart", required=True)
    ap.add_argument("--canvas", required=True)
    ap.add_argument("--viewport", required=True)
    ap.add_argument("--tiles", required=True, help="pan fractions, 'fx,fy;fx,fy;...'")
    ap.add_argument("--density", type=float, required=True)
    ap.add_argument("--gold", required=True)
    args = ap.parse_args()

    canvas = tuple(int(v) for v in args.canvas.lower().split("x"))
    view = tuple(int(v) for v in args.viewport.lower().split("x"))
    fractions = [tuple(float(v) for v in pair.split(",")) for pair in args.tiles.split(";")]
    cols = len({round(fx, 4) for fx, _ in fractions})

    stitched = Image.new("RGB", canvas, (255, 255, 255))
    for i, (fx, fy) in enumerate(fractions):
        index = None if len(fractions) == 1 else (i // cols, i % cols)
        path = pathlib.Path(args.dir) / tile_path(args.chart, "100", index)
        if not path.exists():
            print(f"refine: missing {path}", file=sys.stderr)
            print(f"{args.density:.6f} unknown")
            return 1
        stitched.paste(
            Image.open(path).convert("RGB"),
            (round(window_origin(canvas[0], view[0], fx)), round(window_origin(canvas[1], view[1], fy))),
        )

    ink = content_bbox(stitched, bg_color(stitched))
    if ink is None:
        print("refine: no ink in the 100% tiles", file=sys.stderr)
        print(f"{args.density:.6f} unknown")
        return 1
    ink_w, ink_h = ink[2] - ink[0], ink[3] - ink[1]

    gold = Image.open(args.gold).convert("RGB")
    gold_box = content_bbox(gold, bg_color(gold))
    gold_w, gold_h = gold_box[2] - gold_box[0], gold_box[3] - gold_box[1]

    # Maradona's layout is not mermaid's, so only the axis the density was
    # calibrated against has to line up; the other can differ wildly (an LR
    # chart of ours is one row where mermaid's wraps).
    if max(ink_w / gold_w, ink_h / gold_h) < 0.5:
        print(f"refine: measured ink {ink_w}x{ink_h} is far smaller than the golden "
              f"{gold_w}x{gold_h} - the 100% tiles are not a complete render", file=sys.stderr)
        return 1

    correction = min(gold_w / ink_w, gold_h / ink_h)
    print(f"{args.density * correction:.6f} {ink_w}x{ink_h}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
