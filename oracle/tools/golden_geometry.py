#!/usr/bin/env python3
"""Extracts the golden's geometry, from mermaid's SVG plus its PNG raster.

The SVG is the reference *structure*: node ids and shapes, edge topology, edge
paths and labels, clusters. The PNG (a faithful raster of that SVG at scale 2)
supplies pixel-exact boxes, which is what the layout comparison and the
rendering test both need. Combining them avoids writing a path-bbox parser and
keeps one source of truth per fact.

Output: oracle/goldens/<id>.geometry.json

    {
      "scale": 2, "origin": [4, 4],          # SVG -> PNG mapping: (p - origin) * scale
      "size": [720, 1004],                   # PNG size
      "nodes": [{"id": "A", "shape": "rect", "label": "Mermaid text",
                 "box": [x, y, w, h], "label_box": [x, y, w, h], "centre": [cx, cy]}],
      "edges": [{"id": "L-A-B-0", "from": "A", "to": "B", "path": [[x, y], ...],
                 "label": "Yes"|null, "label_box": [...]|null}],
      "clusters": [{"id": "client", "label": "Client", "box": [...]}]
    }

Run: oracle/tools/.venv/bin/python oracle/tools/golden_geometry.py [id ...]
"""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from compare import bg_color, content_bbox  # noqa: E402
from structure import node_shapes  # noqa: E402

from PIL import Image  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG_NS = "{http://www.w3.org/2000/svg}"
XHTML_NS = "{http://www.w3.org/1999/xhtml}"


def _translate(element):
    transform = element.get("transform") or ""
    m = re.search(r"translate\(\s*([-\d.]+)[ ,]+([-\d.]+)", transform)
    return (float(m.group(1)), float(m.group(2))) if m else (0.0, 0.0)


def _points(text):
    nums = [float(v) for v in re.findall(r"-?\d+\.?\d*(?:e-?\d+)?", text or "")]
    return list(zip(nums[0::2], nums[1::2]))


def _shape_box(element, offset):
    """Bounding box of a shape element, in SVG units."""
    tag = element.tag.replace(SVG_NS, "")
    tx, ty = _translate(element)
    if tag == "rect":
        x, y = float(element.get("x", 0)), float(element.get("y", 0))
        w, h = float(element.get("width", 0)), float(element.get("height", 0))
        return (x + tx + offset[0], y + ty + offset[1], w, h)
    if tag == "polygon":
        pts = _points(element.get("points"))
        xs = [p[0] + tx + offset[0] for p in pts]
        ys = [p[1] + ty + offset[1] for p in pts]
        return (min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys))
    if tag in ("circle", "ellipse"):
        cx, cy = float(element.get("cx", 0)) + tx + offset[0], float(element.get("cy", 0)) + ty + offset[1]
        rx = float(element.get("r", element.get("rx", 0)))
        ry = float(element.get("r", element.get("ry", 0)))
        return (cx - rx, cy - ry, 2 * rx, 2 * ry)
    return None


def _path_points(d):
    """Polyline through a path's anchor points (enough to compare routing)."""
    tokens = re.findall(r"([MLCQHVZmlcqhvz])([^MLCQHVZmlcqhvz]*)", d or "")
    points, cursor = [], (0.0, 0.0)
    for command, args in tokens:
        nums = [float(v) for v in re.findall(r"-?\d+\.?\d*(?:e-?\d+)?", args)]
        if command in "ML":
            for i in range(0, len(nums) - 1, 2):
                cursor = (nums[i], nums[i + 1])
                points.append(cursor)
        elif command in "HV":
            for v in nums:
                cursor = (v, cursor[1]) if command == "H" else (cursor[0], v)
                points.append(cursor)
        elif command in "CQ":
            step = 6 if command == "C" else 4
            for i in range(0, len(nums) - step + 1, step):
                cursor = (nums[i + step - 2], nums[i + step - 1])
                points.append(cursor)
    return points


def _shapes(element):
    """Shape elements under a node, skipping the label subtree (it has its own rect)."""
    for child in element:
        if "label" in (child.get("class") or "").split():
            continue
        tag = child.tag.replace(SVG_NS, "")
        if tag in ("rect", "polygon", "circle", "ellipse", "path"):
            if tag == "rect" and child.get("width") is None:
                continue
            yield child
        yield from _shapes(child)


def _element_text(element):
    """Label text: SVG <text>/<tspan> when htmlLabels is off, XHTML span otherwise."""
    for tspan in element.iter(SVG_NS + "tspan"):
        text = "".join(tspan.itertext()).strip()
        if text:
            return text
    for span in element.iter(XHTML_NS + "span"):
        text = "".join(span.itertext()).strip()
        if text:
            return text
    return ""


def _shape_kind(shape_el, node_group):
    """Shape kind: element tag, refined by the class mermaid puts on it."""
    if shape_el is None:
        return "unknown"
    classes = " ".join((el.get("class") or "") for el in node_group.iter())
    tag = shape_el.tag.replace(SVG_NS, "")
    for kind in ("cylinder", "stadium", "subroutine", "hexagon", "parallelogram",
                 "trapezoid", "circle", "rhombus", "diamond"):
        if kind in classes:
            return kind
    return tag


def extract(chart_id):
    svg_path = ROOT / "goldens" / f"{chart_id}.svg"
    png_path = ROOT / "goldens" / f"{chart_id}.png"
    root = ET.fromstring(svg_path.read_text())
    view = root.get("viewBox", "0 0 0 0").split()
    origin = (float(view[0]), float(view[1]))
    scale = 2  # mmdc -s 2; also PNG px / SVG unit
    png = Image.open(png_path).convert("RGB")

    def to_png(box):
        x, y, w, h = box
        return [round((x - origin[0]) * scale, 1), round((y - origin[1]) * scale, 1),
                round(w * scale, 1), round(h * scale, 1)]

    # --- nodes from the SVG, identified by centre ---
    svg_nodes = []
    for g in root.iter(SVG_NS + "g"):
        classes = (g.get("class") or "").split()
        if "node" not in classes:
            continue
        raw_id = g.get("id") or ""
        m = re.search(r"flowchart-(.+?)-\d+$", raw_id)
        node_id = m.group(1) if m else raw_id
        tx, ty = _translate(g)
        shape_el = next(iter(_shapes(g)), None)
        box = _shape_box(shape_el, (tx, ty)) if shape_el is not None else None
        svg_nodes.append({
            "id": node_id,
            "shape": _shape_kind(shape_el, g),
            "label": _element_text(g),
            "svg_box": box,
            "centre_svg": (tx, ty),
        })

    # --- exact boxes from the PNG fill components ---
    png_shapes = node_shapes(png)
    nodes = []
    for node in svg_nodes:
        cx = (node["centre_svg"][0] - origin[0]) * scale
        cy = (node["centre_svg"][1] - origin[1]) * scale
        best, best_d = None, None
        for shape in png_shapes:
            d = ((shape["centre"][0] - cx) ** 2 + (shape["centre"][1] - cy) ** 2) ** 0.5
            if best_d is None or d < best_d:
                best, best_d = shape, d
        # The PNG box is authoritative (exact pixels); the SVG box is a fallback.
        if best is not None and best_d < 40:
            box = list(best["bbox"])
            tx_, ty_, tw_, th_ = best["text"]
            label_box = [box[0] + tx_, box[1] + ty_, tw_, th_]
        elif node["svg_box"]:
            box = to_png(node["svg_box"])
            label_box = None
        else:
            continue
        nodes.append({
            "id": node["id"], "shape": node["shape"], "label": node["label"],
            "box": box, "label_box": label_box,
            "centre": [box[0] + box[2] / 2, box[1] + box[3] / 2],
            "svg_box": to_png(node["svg_box"]) if node["svg_box"] else None,
        })

    # --- edges: paths and their labels ---
    edges = []
    for path in root.iter(SVG_NS + "path"):
        classes = (path.get("class") or "")
        if "flowchart-link" not in classes:
            continue
        raw_id = path.get("id") or ""
        m = re.search(r"L[_-](.+?)[_-](.+?)[_-]\d+$", raw_id)
        pts = [((x - origin[0]) * scale, (y - origin[1]) * scale) for x, y in _path_points(path.get("d"))]
        edges.append({
            "id": raw_id.replace("my-svg-", ""),
            "from": m.group(1) if m else None,
            "to": m.group(2) if m else None,
            "path": [[round(x, 1), round(y, 1)] for x, y in pts],
            "label": None, "label_box": None,
        })

    # Edge labels: each carries data-id = the edge id it belongs to.
    by_id = {e["id"]: e for e in edges}
    for group in root.iter(SVG_NS + "g"):
        if "edgeLabel" not in (group.get("class") or ""):
            continue
        inner = group.find(SVG_NS + "g")
        edge_id = (inner.get("data-id") if inner is not None else None) or ""
        edge = by_id.get(edge_id)
        if edge is None:
            continue
        tx, ty = _translate(group)
        tx2, ty2 = _translate(inner) if inner is not None else (0.0, 0.0)
        background = None
        for el in group.iter(SVG_NS + "rect"):
            if "background" in (el.get("class") or ""):
                background = el
        if background is not None:
            w, h = float(background.get("width", 0)), float(background.get("height", 0))
            cx = tx + tx2 + float(background.get("x", 0)) + w / 2
            cy = ty + ty2 + float(background.get("y", 0)) + h / 2
        else:
            w = h = 0.0
            cx, cy = tx, ty
        edge["label"] = _element_text(group)
        edge["label_box"] = to_png((cx - w / 2, cy - h / 2, w, h)) if w else None
        edge["label_centre"] = [round((cx - origin[0]) * scale, 1), round((cy - origin[1]) * scale, 1)]

    clusters = []
    for g in root.iter(SVG_NS + "g"):
        if (g.get("class") or "").split() != ["cluster"]:
            continue
        rect = g.find(SVG_NS + "rect")
        clusters.append({
            "id": (g.get("id") or "").replace("my-svg-", ""),
            "label": _element_text(g),
            "box": to_png(_shape_box(rect, (0.0, 0.0))) if rect is not None else None,
        })

    content = content_bbox(png, bg_color(png))
    return {
        "scale": scale, "origin": list(origin), "size": list(png.size),
        "content": [content[0], content[1], content[2] - content[0], content[3] - content[1]],
        "nodes": nodes, "edges": edges, "clusters": clusters,
    }


def main():
    ids = sys.argv[1:] or sorted(p.stem for p in (ROOT / "charts").glob("*.mmd"))
    for chart_id in ids:
        geometry = extract(chart_id)
        out = ROOT / "goldens" / f"{chart_id}.geometry.json"
        out.write_text(json.dumps(geometry, indent=1))
        print(f"{chart_id}: {len(geometry['nodes'])} nodes, {len(geometry['edges'])} edges, "
              f"{len(geometry['clusters'])} clusters -> {out.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
