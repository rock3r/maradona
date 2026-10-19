#!/usr/bin/env python3
"""Calibrates the density a capture has to use so the render's ink matches the golden.

The sample app picks its render density from the diagram's *layout* box, but
the golden's size is its *ink* bounding box, and Maradona's layout box carries
padding (arrowheads, labels) that mermaid's does not. Matching layout-to-ink
would leave the drawing ~10-20% off, so the capture scripts take one throwaway
render at 0.1x, measure the ink, and pass the corrected density to the real
captures via `oracleDensity`.

    calibrate.py --capture shot.png --scale 0.1 --density 0.33 --gold goldens/x.png

Prints the corrected density, or warns on stderr (and prints the input
density) when the measurement looks unusable.
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from compare import bg_color, content_bbox  # noqa: E402

from PIL import Image  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--capture", required=True)
    ap.add_argument("--scale", type=float, required=True, help="scale the capture was taken at")
    ap.add_argument("--density", type=float, required=True, help="density the app reported")
    ap.add_argument("--gold", required=True)
    args = ap.parse_args()

    shot = Image.open(args.capture).convert("RGB")
    ink = content_bbox(shot, bg_color(shot))
    if ink is None:
        print(f"calibrate: no ink in {args.capture}; keeping density {args.density}", file=sys.stderr)
        print(f"{args.density:.6f}")
        return 0
    if ink[0] <= 1 or ink[1] <= 1 or ink[2] >= shot.size[0] - 1 or ink[3] >= shot.size[1] - 1:
        # The diagram is 10% of its size and centred, so it cannot reach the
        # edge: something else is on screen (a dialog, a stale frame).
        print(f"calibrate: ink touches the capture edge ({ink}) - something other than "
              f"the diagram is on screen", file=sys.stderr)
        return 1

    ink_w = (ink[2] - ink[0]) / args.scale
    ink_h = (ink[3] - ink[1]) / args.scale

    gold = Image.open(args.gold).convert("RGB")
    gold_box = content_bbox(gold, bg_color(gold))
    gold_w, gold_h = gold_box[2] - gold_box[0], gold_box[3] - gold_box[1]

    if max(ink_w / gold_w, ink_h / gold_h) < 0.5:
        print(f"calibrate: measured ink {ink_w:.0f}x{ink_h:.0f} is far smaller than the golden "
              f"{gold_w}x{gold_h} - capture is not a diagram render", file=sys.stderr)
        return 1

    correction = min(gold_w / ink_w, gold_h / ink_h)
    print(f"{args.density * correction:.6f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
