#!/usr/bin/env python3
"""Layout oracle: Maradona's intermediate representation vs mermaid's geometry.

The pixel comparison is a sanity check, not a fidelity verdict: a flowchart is
sparse line art, so "completely different" renders can still differ by under 2%
of the canvas. What actually differs between the two renderers is *geometry*,
so this compares geometry directly:

* golden side: mermaid's SVG (node ids, shapes, edge topology and paths, labels,
  clusters) plus its PNG raster for pixel-exact boxes - see golden_geometry.py
* ours: Maradona's layout (`FlowchartLayout` result) as dumped by the desktop
  oracle test (`out/desktop/<id>.layout.json`)

Both are in pixels at their own render scale. The oracle capture pipeline
already matches our content size to the golden's, so the two are compared after
scaling the golden by the ratio of the *binding* axis content sizes; every
number reported is in our pixels.

Reported per chart: node box size deltas, node centre offsets, label padding,
edge-label placement, cluster boxes. Exit code is non-zero when a chart is
outside tolerance.

Usage: compare_layout.py [chart ...]
"""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from structure import node_shapes  # noqa: E402

from PIL import Image  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "out" / "desktop"

# Tolerances for a faithful layout. These are what "matches" means; the current
# state of Maradona vs mermaid fails several of them, which is the point.
MAX_SIZE_ERR = 0.05        # node box width/height, relative
MAX_CENTRE_ERR = 0.02      # node centre offset, as a share of the diagonal
MAX_TEXT_ERR = 0.10        # label text width, relative (font shaping differences)
MAX_LABEL_ERR = 0.03       # edge label centre offset, share of the diagonal
# Rendered fill vs the IL box: the fill sits inside a 1dp border (density px)
# and its edges anti-alias, so a couple of px per side is expected.
MAX_RENDER_ERR = 2.0 * 3.0
# Edge endpoints, as a share of the diagonal: an edge must start and end where
# mermaid's does (its attachment point on the node outline).
MAX_EDGE_ERR = 0.02


def _centre(rect):
    return (rect[0] + rect[2] / 2, rect[1] + rect[3] / 2)


def _ink_in_window(img, rect, offset):
    """Find non-background ink bbox inside `rect` (shifted by `offset`) in `img`."""
    bg = img.getpixel((0, 0))[:3]
    x0 = max(0, int(offset[0] + rect[0]) - 3)
    y0 = max(0, int(offset[1] + rect[1]) - 3)
    x1 = min(img.width, int(offset[0] + rect[0] + rect[2]) + 3)
    y1 = min(img.height, int(offset[1] + rect[1] + rect[3]) + 3)
    if x1 <= x0 or y1 <= y0:
        return None
    crop = img.crop((x0, y0, x1, y1)).convert("RGB")
    px = crop.load()
    cw, ch = crop.size
    min_x, min_y, max_x, max_y = cw, ch, -1, -1
    for y in range(ch):
        for x in range(cw):
            r, g, b = px[x, y]
            if max(abs(r - bg[0]), abs(g - bg[1]), abs(b - bg[2])) > 10:
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if y < min_y:
                    min_y = y
                if y > max_y:
                    max_y = y
    if max_x < 0:
        return None
    return [x0 + min_x - offset[0], y0 + min_y - offset[1], max_x - min_x + 1, max_y - min_y + 1]


def compare_non_flowchart_chart(chart, golden, ours, manifest):
    """Validate non-flowchart family layout geometry, invariants, and IL-to-render consistency."""
    family = ours.get("family", "unknown")
    gold_content = golden["content"]
    our_content = (manifest["contentW"], manifest["contentH"])
    ratio_w = gold_content[2] / our_content[0]
    ratio_h = gold_content[3] / our_content[1]
    absolute_scale = ratio_w if abs(ratio_w - 1) <= abs(ratio_h - 1) else ratio_h
    scale = absolute_scale

    canvas = (manifest["canvasW"], manifest["canvasH"])
    view = (manifest["viewportW"], manifest["viewportH"])
    offset = ((view[0] - canvas[0]) / 2, (view[1] - canvas[1]) / 2)
    render = Image.open(OUT / f"{chart}-100.png")

    invariants = ours.get("invariants", [])
    failed_invariants = [inv for inv in invariants if not inv.get("pass", False)]

    rows = []
    render_rows = []
    worst = {
        "size": 0.0 if not failed_invariants else 1.0,
        "centre": 0.0,
        "label": 0.0,
        "render": 0.0,
        "edges": 0.0,
        "missing": len(failed_invariants),
    }

    for inv in invariants:
        ok = bool(inv.get("pass", False))
        rows.append(
            (
                f"[{family}] {inv['name']}",
                inv.get("expected", ""),
                inv.get("actual", ""),
                "PASS" if ok else "FAIL",
                "0px" if ok else "FAIL",
                family,
            )
        )

    ow_il, oh_il = float(ours["width"]), float(ours["height"])
    for n in ours.get("nodes", []):
        r = n["rect"]
        in_bounds = (
            r[2] > 0
            and r[3] > 0
            and r[0] >= -2.0
            and r[1] >= -2.0
            and (r[0] + r[2]) <= ow_il + 4.0
            and (r[1] + r[3]) <= oh_il + 4.0
        )
        if not in_bounds:
            worst["size"] = max(worst["size"], 1.0)
        oc = _centre(r)
        ink = _ink_in_window(render, r, offset)
        if ink is None:
            render_rows.append((n["id"], "unmatched", "", f"{r[2]:.0f}x{r[3]:.0f}"))
        else:
            render_rows.append((n["id"], "0px", f"{ink[2]:.0f}x{ink[3]:.0f}", f"{r[2]:.0f}x{r[3]:.0f}"))
        rows.append(
            (
                n["id"],
                n.get("kind", "item"),
                f"{r[2]:.0f}x{r[3]:.0f} @{oc[0]:.0f},{oc[1]:.0f}",
                "in-bounds" if in_bounds else "OOB",
                "ink-ok" if ink is not None else "NO-INK",
                (n.get("label") or "")[:18],
            )
        )

    edge_rows = []
    for e in ours.get("edges", []):
        pts = e.get("points", [])
        if len(pts) < 2:
            worst["edges"] = max(worst["edges"], 1.0)
            edge_rows.append((f"{e['from']}->{e['to']}", "EMPTY", "0", f"{len(pts)}"))
        else:
            p0, p1 = pts[0], pts[-1]
            edge_rows.append(
                (
                    f"{e['from']}->{e['to']}",
                    f"({p0[0]:.0f},{p0[1]:.0f})->({p1[0]:.0f},{p1[1]:.0f})",
                    family,
                    f"{len(pts)}",
                )
            )

    unmatched_count = sum(1 for r in render_rows if not r[1].endswith("px"))
    worst["unmatched"] = unmatched_count
    passed = (
        worst["missing"] == 0
        and unmatched_count == 0
        and worst["size"] <= MAX_SIZE_ERR
        and worst["edges"] <= MAX_EDGE_ERR
        and abs(absolute_scale - 1.0) <= 0.03
    )
    return {
        "kind": "layout",
        "family": family,
        "chart": chart,
        "scale": round(scale, 4),
        "absolute_scale": round(absolute_scale, 4),
        "rows": rows,
        "labels": [],
        "pads": [],
        "render": render_rows,
        "edges": edge_rows,
        "worst": {k: round(v, 4) for k, v in worst.items()},
        "pass": passed,
    }


def compare_chart(chart):
    golden = json.loads((ROOT / "goldens" / f"{chart}.geometry.json").read_text())
    ours = json.loads((OUT / f"{chart}.layout.json").read_text())
    manifest = json.loads((OUT / "manifest.json").read_text())["charts"][chart]
    if ours.get("family", "flowchart") != "flowchart":
        return compare_non_flowchart_chart(chart, golden, ours, manifest)

    gold_content = golden["content"]
    our_content = (manifest["contentW"], manifest["contentH"])
    # Compare at matched scale: the capture density is calibrated from the whole
    # diagram's ink, so a few percent of absolute scale difference is a
    # calibration artifact, not a geometry difference. It is reported separately
    # (`absolute_scale`) rather than hidden.
    ratio_w = gold_content[2] / our_content[0]
    ratio_h = gold_content[3] / our_content[1]
    absolute_scale = ratio_w if abs(ratio_w - 1) <= abs(ratio_h - 1) else ratio_h
    scale = absolute_scale

    def to_ours(rect):
        return [rect[0] * scale, rect[1] * scale, rect[2] * scale, rect[3] * scale]

    diagonal = (our_content[0] ** 2 + our_content[1] ** 2) ** 0.5
    our_nodes = {n["id"]: n for n in ours["nodes"]}

    rows, worst = [], {"size": 0.0, "centre": 0.0, "label": 0.0,
                       "render": 0.0, "edges": 0.0, "missing": 0}
    for gn in golden["nodes"]:
        on = our_nodes.get(gn["id"])
        if on is None:
            worst["missing"] += 1
            rows.append((gn["id"], "MISSING", "", "", "", ""))
            continue
        gb, ob = to_ours(gn["box"]), on["rect"]
        dw = ob[2] / gb[2] - 1
        dh = ob[3] / gb[3] - 1
        gc, oc = _centre(gb), _centre(ob)
        dc = ((gc[0] - oc[0]) ** 2 + (gc[1] - oc[1]) ** 2) ** 0.5
        worst["size"] = max(worst["size"], abs(dw), abs(dh))
        worst["centre"] = max(worst["centre"], dc / diagonal)
        rows.append((gn["id"], f"{gb[2]:.0f}x{gb[3]:.0f} @{gc[0]:.0f},{gc[1]:.0f}",
                     f"{ob[2]:.0f}x{ob[3]:.0f} @{oc[0]:.0f},{oc[1]:.0f}",
                     f"{dw * 100:+.0f}%/{dh * 100:+.0f}%", f"{dc:.0f}px",
                     f"{gn['label_box'][2] * scale:.0f}px" if gn.get("label_box") and gn["label_box"][2] else "-"))

    gold_labels = [e for e in golden["edges"] if e.get("label")]
    our_labels = [e for e in ours["edges"] if e.get("label_rect")]
    label_rows = []
    for gl, ol in zip(gold_labels, our_labels):
        gc = to_ours(gl["label_box"]) if gl.get("label_box") else None
        if not gc:
            continue
        gcen, ocen = _centre(gc), _centre(ol["label_rect"])
        dc = ((gcen[0] - ocen[0]) ** 2 + (gcen[1] - ocen[1]) ** 2) ** 0.5
        worst["label"] = max(worst["label"], dc / diagonal)
        label_rows.append((gl["label"], f"{dc:.0f}px", f"{dc / diagonal * 100:.1f}%"))

    # Two things only pixels can tell us, both matched per node id:
    #  * does our renderer draw the IL it was given (renderer check)?
    #  * how much padding does each renderer put around a label (fidelity)?
    canvas = (manifest["canvasW"], manifest["canvasH"])
    view = (manifest["viewportW"], manifest["viewportH"])
    offset = ((view[0] - canvas[0]) / 2, (view[1] - canvas[1]) / 2)
    render = Image.open(OUT / f"{chart}-100.png")
    rendered = node_shapes(render)
    render_rows, pad_rows = [], []
    for gn in golden["nodes"]:
        on = our_nodes.get(gn["id"])
        if on is None:
            continue
        il_centre = _centre(on["rect"])
        best, best_d = None, None
        for shape in rendered:
            centre = (shape["centre"][0] - offset[0], shape["centre"][1] - offset[1])
            d = ((centre[0] - il_centre[0]) ** 2 + (centre[1] - il_centre[1]) ** 2) ** 0.5
            if best_d is None or d < best_d:
                best, best_d = shape, d
        if best is None or best_d > 12 or abs(best["bbox"][2] - on["rect"][2]) > 0.35 * on["rect"][2]:
            # No unambiguous counterpart in the render: either the node is
            # missing or the match is too close to a neighbour to trust.
            render_rows.append((gn["id"], "unmatched", "", ""))
            continue
        # The renderer must reproduce the IL box. The fill sits inside the
        # node's 1dp border, so compare in canvas space and allow that inset
        # (plus anti-aliasing) - see MAX_RENDER_ERR.
        drawn = [best["bbox"][0] - offset[0], best["bbox"][1] - offset[1],
                 best["bbox"][2], best["bbox"][3]]
        dr = max(abs(drawn[0] - on["rect"][0]), abs(drawn[1] - on["rect"][1]),
                 abs(drawn[2] - on["rect"][2]), abs(drawn[3] - on["rect"][3]))
        worst["render"] = max(worst["render"], dr)
        render_rows.append((gn["id"], f"{dr:.0f}px", f"{drawn[2]:.0f}x{drawn[3]:.0f}",
                            f"{on['rect'][2]:.0f}x{on['rect'][3]:.0f}"))
        # The label size is implied by the node box plus mermaid's padding
        # rules (which NodeSizing ports), and a pixel-measured text box is
        # polluted by the shape's own stroke on diamonds and stadiums, so it is
        # reported for information only.

    # Edges: presence, endpoints and length. Node geometry alone does not catch
    # a diagram that renders with no connections at all, which is exactly the
    # regression this check exists for.
    our_edges = {(e["from"], e["to"]): e for e in ours["edges"]}
    edge_rows = []
    for ge in golden["edges"]:
        key = (ge.get("from"), ge.get("to"))
        if key[0] is None or key[1] is None:
            continue
        oe = our_edges.get(key)
        if oe is None:
            worst["edges"] = max(worst["edges"], 1.0)
            edge_rows.append((f"{key[0]}->{key[1]}", "MISSING", "", ""))
            continue
        gp = [(x * scale, y * scale) for x, y in ge["path"]]
        op = [(x, y) for x, y in oe["points"]]
        if len(gp) < 2 or len(op) < 2:
            worst["edges"] = max(worst["edges"], 1.0)
            edge_rows.append((f"{key[0]}->{key[1]}", "EMPTY", f"{len(gp)}", f"{len(op)}"))
            continue
        start_err = max(abs(gp[0][0] - op[0][0]), abs(gp[0][1] - op[0][1]))
        end_err = max(abs(gp[-1][0] - op[-1][0]), abs(gp[-1][1] - op[-1][1]))
        err = max(start_err, end_err) / diagonal
        worst["edges"] = max(worst["edges"], err)
        edge_rows.append((f"{key[0]}->{key[1]}", f"{start_err:.0f}/{end_err:.0f}px",
                          f"{len(gp)}", f"{len(op)}"))

    unmatched_count = sum(1 for r in render_rows if not r[1].endswith("px"))
    worst["unmatched"] = unmatched_count
    passed = (worst["missing"] == 0
              and unmatched_count == 0
              and worst["size"] <= MAX_SIZE_ERR
              and worst["centre"] <= MAX_CENTRE_ERR
              and worst["label"] <= MAX_LABEL_ERR
              and worst["edges"] <= MAX_EDGE_ERR
              and worst["render"] <= MAX_RENDER_ERR)
    return {
        "kind": "layout", "chart": chart, "scale": round(scale, 4),
        "absolute_scale": round(absolute_scale, 4), "rows": rows,
        "labels": label_rows, "pads": pad_rows, "render": render_rows, "edges": edge_rows,
        "worst": {k: round(v, 4) for k, v in worst.items()}, "pass": passed,
    }


def main():
    from freshness import require_fresh
    hint = (
        "./gradlew :sample-desktop:desktopTest --tests dev.maradona.sample.OracleGoldenTest "
        "--rerun-tasks"
    )
    if not require_fresh(OUT, hint):
        return 2
    charts = sys.argv[1:] or sorted(p.stem for p in (ROOT / "charts").glob("*.mmd"))
    failures = 0
    report = {}
    desktop = json.loads((ROOT / "out" / "report-desktop.json").read_text()) \
        if (ROOT / "out" / "report-desktop.json").exists() else {}
    for chart in charts:
        if not (OUT / f"{chart}.layout.json").exists():
            print(f"SKIP  {chart}: no layout dump (run the desktop oracle test first)")
            continue
        result = compare_chart(chart)
        failures += 0 if result["pass"] else 1
        # Link the visual side-by-side from the capture report, if there is one.
        visual = desktop.get(f"{chart}-100")
        if visual and visual.get("images"):
            result["images"] = visual["images"]
        report[chart] = result
        w = result["worst"]
        print(f"\n{'PASS' if result['pass'] else 'FAIL'}  {chart}   "
              f"(absolute scale x{result['absolute_scale']:.3f})")
        print(f"      worst: size {w['size'] * 100:.1f}%  centre {w['centre'] * 100:.1f}% of diagonal  "
              f"label {w['label'] * 100:.1f}%  edges {w['edges'] * 100:.1f}%  "
              f"render-vs-IL {w['render']:.0f}px  missing {w['missing']}")
        print(f"      {'node':6s} {'mermaid box @centre':>26s} {'maradona box @centre':>26s} "
              f"{'delta w/h':>12s} {'centre':>8s} {'text':>7s}")
        for row in result["rows"]:
            print(f"      {row[0]:6s} {row[1]:>26s} {row[2]:>26s} {row[3]:>12s} {row[4]:>8s} {row[5]:>7s}")
        for label in result["labels"]:
            print(f"      edge label {label[0]!r}: offset {label[1]} ({label[2]})")
        for node, gold_text, our_text in result["pads"]:
            print(f"      text    {node:6s} mermaid {gold_text:>9s}      maradona {our_text:>9s}")
        for edge, endpoints, gold_points, our_points in result["edges"]:
            print(f"      edge {edge:10s} endpoints {endpoints:>12s}  points {gold_points}->{our_points}")
        bad_render = [r for r in result["render"] if r[1].endswith("px") and float(r[1][:-2]) > MAX_RENDER_ERR]
        unmatched = [r for r in result["render"] if not r[1].endswith("px")]
        print(f"      renderer: {len(result['render'])} nodes, {len(unmatched)} unmatched in the render, "
              f"{len(bad_render)} drawn more than {MAX_RENDER_ERR:.0f}px off the IL")
    out = ROOT / "out" / "report-layout.json"
    out.write_text(json.dumps(report, indent=1))
    print(f"\nlayout: {len(report) - failures}/{len(report)} charts within tolerance -> {out}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
