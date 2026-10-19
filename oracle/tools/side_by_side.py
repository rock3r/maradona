#!/usr/bin/env python3
"""Golden and our render side by side, so differences cannot be missed.

Looking at the two images separately hides differences: a defect that is obvious
in a pair (a reversed polyline, a hollow arrowhead where the reference has a
solid dot, an edge routed through the nodes) reads as "fine" when each image is
seen on its own. This crops the same region of both and puts them next to each
other with a divider.

    python3 oracle/tools/side_by_side.py edges-heads --from 0.30 --to 0.62
    python3 oracle/tools/side_by_side.py shapes-lr --from 0.20 --to 0.34 --x-from 0.20 --x-to 0.34

Left is mermaid, right is Maradona.
"""
import argparse
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = pathlib.Path("/tmp")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("chart")
    ap.add_argument("--from", dest="y0", type=float, default=0.0)
    ap.add_argument("--to", dest="y1", type=float, default=1.0)
    ap.add_argument("--x-from", type=float, default=0.0)
    ap.add_argument("--x-to", type=float, default=1.0)
    ap.add_argument("--platform", default="desktop")
    ap.add_argument("--height", type=int, default=900)
    args = ap.parse_args()

    gold = Image.open(ROOT / "goldens" / f"{args.chart}.png").convert("RGB")
    ours = Image.open(ROOT / "out" / args.platform / f"{args.chart}-100.png").convert("RGB")

    def crop(img: Image.Image) -> Image.Image:
        w, h = img.size
        return img.crop(
            (int(w * args.x_from), int(h * args.y0), int(w * args.x_to), int(h * args.y1))
        )

    def fit(img: Image.Image) -> Image.Image:
        w, h = img.size
        scale = args.height / h
        return img.resize((max(1, int(w * scale)), args.height))

    left, right = fit(crop(gold)), fit(crop(ours))
    canvas = Image.new("RGB", (left.size[0] + right.size[0] + 12, args.height), (255, 255, 255))
    canvas.paste(left, (0, 0))
    canvas.paste(right, (left.size[0] + 12, 0))
    ImageDraw.Draw(canvas).line(
        [(left.size[0] + 5, 0), (left.size[0] + 5, args.height)], fill=(200, 60, 60), width=3
    )
    out = OUT / f"sbs-{args.chart}.png"
    canvas.save(out)
    print(f"{out}  (left = mermaid gold, right = maradona)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
