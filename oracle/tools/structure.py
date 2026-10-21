#!/usr/bin/env python3
"""Structural comparison of two flowchart renders.

Pixel diffs cannot express "these look completely different": a flowchart is
sparse line art, so a fraction-of-canvas mismatch stays small even when nothing
lines up. What the eye compares is geometry, so this module extracts it and
compares it numerically:

* **nodes** - the filled shapes. Both renderers use the same theme, so the fill
  colour is a reliable mask; each connected component is one node. From it we
  get the box size, the text bounding box inside it (the padding the renderer
  gives a label) and the position.
* **labels** - dark text clusters outside every node box ("Yes"/"No" and other
  edge labels), which is where the two renderers differ most visibly.
* **ink coverage** - the share of each drawing that has the other's ink within
  a small radius, a symmetric "does it line up at all" measure that is honest
  about the denominator (drawing vs canvas).

Everything is in pixels at the render's own scale. Oracle captures are density
matched, so the golden and our render are directly comparable.
"""
import numpy as np
from PIL import Image
from scipy import ndimage

# Both renderers are configured with Maradona's palette (see mermaid-config.json),
# plus the custom node fills used in styling.mmd and subgraphs.mmd.
NODE_FILL = (242, 240, 229)
STYLED_NODE_FILLS = (
    (255, 204, 204),  # #ffcccc
    (255, 255, 204),  # #ffffcc
    (211, 229, 245),  # #d3e5f5
)
FILL_TOLERANCE = 12
STYLED_FILL_TOLERANCE = 3
TEXT_MAX = 140          # luminance below this is text/stroke ink
MIN_NODE_AREA = 200     # px; smaller components are anti-aliasing debris
MIN_NODE_DIM = 16       # px; 1px anti-aliased border strips are not nodes
MAX_NODE_AREA_SHARE = 0.6


def _mask_near(img, colour, tolerance):
    a = np.asarray(img.convert("RGB"), dtype=np.int16)
    return np.abs(a - np.array(colour, dtype=np.int16)).max(axis=2) <= tolerance


def node_shapes(img):
    """Filled node shapes: bbox, area, text bbox and label padding."""
    w, h = img.size
    fill = _mask_near(img, NODE_FILL, FILL_TOLERANCE)
    for colour in STYLED_NODE_FILLS:
        fill |= _mask_near(img, colour, STYLED_FILL_TOLERANCE)
    grey = np.asarray(img.convert("L"), dtype=np.int16)
    labels, count = ndimage.label(fill)
    raw = []
    for index in range(1, count + 1):
        ys, xs = np.nonzero(labels == index)
        area = len(ys)
        if area < MIN_NODE_AREA or area > w * h * MAX_NODE_AREA_SHARE:
            continue
        x0, x1 = int(xs.min()), int(xs.max())
        y0, y1 = int(ys.min()), int(ys.max())
        bw, bh = x1 - x0 + 1, y1 - y0 + 1
        if bw < 4 or bh < 4:
            continue
        raw.append([x0, y0, x1, y1, area, [index]])

    # A cylinder's cap arc or a subroutine's side bars split one node's fill into
    # compartments that share the exact same width (cylinder) or height (subroutine)
    # separated only by the 1dp stroke. Merge compartments that form one box.
    merged = True
    while merged:
        merged = False
        for i in range(len(raw)):
            for j in range(i + 1, len(raw)):
                ax0, ay0, ax1, ay1, aa, ai = raw[i]
                bx0, by0, bx1, by1, ba, bi = raw[j]
                same_x = abs(ax0 - bx0) <= 4 and abs(ax1 - bx1) <= 4
                v_gap = max(0, max(ay0, by0) - min(ay1, by1))
                same_y = abs(ay0 - by0) <= 4 and abs(ay1 - by1) <= 4
                h_gap = max(0, max(ax0, bx0) - min(ax1, bx1))
                if (same_x and v_gap <= 6) or (same_y and h_gap <= 6):
                    raw[i] = [min(ax0, bx0), min(ay0, by0), max(ax1, bx1), max(ay1, by1), aa + ba, ai + bi]
                    raw.pop(j)
                    merged = True
                    break
            if merged:
                break

    out = []
    for x0, y0, x1, y1, area, indices in raw:
        if (x1 - x0 + 1) < MIN_NODE_DIM or (y1 - y0 + 1) < MIN_NODE_DIM:
            continue
        # Text is measured inside the shape's own interior: fill the label
        # holes first, then erode, so the shape's stroke (which sits on the
        # bbox edge) cannot be mistaken for the label.
        comp = np.isin(labels, indices)
        shape_mask = ndimage.binary_fill_holes(comp)
        interior = ndimage.binary_erosion(shape_mask, np.ones((5, 5), bool))
        text = (grey < TEXT_MAX) & interior
        if text.any():
            tys, txs = np.nonzero(text)
            text_box = (int(txs.min()) - x0, int(tys.min()) - y0,
                        int(txs.max() - txs.min()) + 1, int(tys.max() - tys.min()) + 1)
        else:
            text_box = (0, 0, 0, 0)
        out.append({
            "bbox": (x0, y0, x1 - x0 + 1, y1 - y0 + 1),
            "area": area,
            "text": text_box,
            "pad_x": ((x1 - x0 + 1) - text_box[2]) / 2,
            "pad_y": ((y1 - y0 + 1) - text_box[3]) / 2,
            "centre": ((x0 + x1) / 2, (y0 + y1) / 2),
        })
    return sorted(out, key=lambda n: (n["centre"][1], n["centre"][0]))


def edge_labels(img, nodes):
    """Dark text clusters that are not inside a node box - edge labels."""
    grey = np.asarray(img.convert("L"), dtype=np.int16)
    ink = grey < TEXT_MAX
    for n in nodes:
        x, y, w, h = n["bbox"]
        ink[y:y + h, x:x + w] = False
    # Strokes are thin and long; labels are compact blobs of text.
    labels, count = ndimage.label(ink)
    out = []
    for index in range(1, count + 1):
        ys, xs = np.nonzero(labels == index)
        if len(ys) < 40:
            continue
        w, h = int(xs.max() - xs.min()) + 1, int(ys.max() - ys.min()) + 1
        if w > 140 or h > 40 or w < 8:
            continue
        out.append({"bbox": (int(xs.min()), int(ys.min()), w, h),
                    "centre": ((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2)})
    return sorted(out, key=lambda l: (l["centre"][1], l["centre"][0]))


def ink_coverage(gold, ours, radius=2):
    """Share of each drawing's ink that the other has ink within `radius` px."""
    g = np.asarray(gold.convert("L"), dtype=np.int16) < TEXT_MAX
    o = np.asarray(ours.convert("L"), dtype=np.int16) < TEXT_MAX
    structure = np.ones((2 * radius + 1, 2 * radius + 1), bool)
    g_d = ndimage.binary_dilation(g, structure)
    o_d = ndimage.binary_dilation(o, structure)
    gold_covered = (g & o_d).sum() / max(1, g.sum())
    ours_covered = (o & g_d).sum() / max(1, o.sum())
    return float(gold_covered), float(ours_covered)


def _match(gold_nodes, our_nodes):
    """Greedy nearest-centre matching. Returns pairs, plus the unmatched counts."""
    pairs, used = [], set()
    for gn in gold_nodes:
        best, best_d = None, None
        for i, on in enumerate(our_nodes):
            if i in used:
                continue
            d = ((gn["centre"][0] - on["centre"][0]) ** 2 + (gn["centre"][1] - on["centre"][1]) ** 2) ** 0.5
            if best_d is None or d < best_d:
                best, best_d = i, d
        if best is not None:
            used.add(best)
            pairs.append((gn, our_nodes[best], best_d))
    return pairs, len(gold_nodes) - len(pairs), len(our_nodes) - len(used)


def compare(gold_img, ours_img):
    """Geometry deltas between a golden render and ours, in pixels."""
    g_nodes, o_nodes = node_shapes(gold_img), node_shapes(ours_img)
    g_labels, o_labels = edge_labels(gold_img, g_nodes), edge_labels(ours_img, o_nodes)
    diagonal = (gold_img.size[0] ** 2 + gold_img.size[1] ** 2) ** 0.5
    pairs, missing, extra = _match(g_nodes, o_nodes)

    size_err = pad_err = pos_err = 0.0
    detail = []
    for gn, on, dist in pairs:
        dw = on["bbox"][2] / max(1, gn["bbox"][2]) - 1
        dh = on["bbox"][3] / max(1, gn["bbox"][3]) - 1
        dp = max(abs(on["pad_x"] - gn["pad_x"]), abs(on["pad_y"] - gn["pad_y"]))
        size_err = max(size_err, abs(dw), abs(dh))
        pad_err = max(pad_err, dp)
        pos_err = max(pos_err, dist / diagonal)
        detail.append({
            "gold_bbox": gn["bbox"], "ours_bbox": on["bbox"],
            "dw": round(dw, 3), "dh": round(dh, 3),
            "gold_pad": (round(gn["pad_x"], 1), round(gn["pad_y"], 1)),
            "ours_pad": (round(on["pad_x"], 1), round(on["pad_y"], 1)),
            "offset": round(dist, 1),
        })

    gold_covered, ours_covered = ink_coverage(gold_img, ours_img)
    return {
        "nodes": len(g_nodes), "nodes_ours": len(o_nodes),
        "nodes_missing": missing, "nodes_extra": extra,
        "labels": len(g_labels), "labels_ours": len(o_labels),
        "size_err": round(size_err, 3),
        "pad_err": round(pad_err, 1),
        "pos_err": round(pos_err, 4),
        "ink_coverage": round(gold_covered, 3),
        "ink_coverage_rev": round(ours_covered, 3),
        "detail": detail,
    }
