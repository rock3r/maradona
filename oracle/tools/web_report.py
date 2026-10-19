#!/usr/bin/env python3
"""Builds oracle/out/web/index.html from the comparison reports.

Reads oracle/out/report-<platform>.json (written by compare.py) and emits a
self-contained viewer with side-by-side, onion-skin (opacity + wipe) and
pixel-diff views. Serve the oracle/ directory and open out/web/:

    python3 -m http.server 8931 -d oracle
    open http://localhost:8931/out/web/

(Or run oracle/tools/serve.sh, which does both.)
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "out" / "web"

HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Maradona oracle</title>
<style>
  :root { --ok: #1a7f37; --ko: #cf222e; --bg: #f6f5ef; --panel: #fff; }
  * { box-sizing: border-box; }
  body { margin: 0; font: 14px/1.4 -apple-system, system-ui, sans-serif; background: var(--bg); }
  #app { display: flex; height: 100vh; }
  #side { width: 300px; overflow-y: auto; background: var(--panel); border-right: 1px solid #ddd8c8; padding: 8px 0; }
  #side h1 { font-size: 15px; margin: 8px 14px; }
  #side h2 { font-size: 12px; text-transform: uppercase; letter-spacing: .06em; color: #888; margin: 14px 14px 4px; }
  .item { display: flex; justify-content: space-between; gap: 8px; padding: 5px 14px; cursor: pointer; white-space: nowrap; }
  .item:hover { background: #efece0; }
  .item.sel { background: #e5dfc9; font-weight: 600; }
  .badge { border-radius: 8px; padding: 0 7px; font-size: 11px; align-self: center; }
  .pass { background: #dafbe1; color: var(--ok); }
  .fail { background: #ffebe9; color: var(--ko); }
  #main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
  #tabs { display: flex; gap: 4px; padding: 8px 14px; background: var(--panel); border-bottom: 1px solid #ddd8c8; align-items: center; }
  #tabs button { border: 1px solid #ccc6b2; background: #fbfaf4; border-radius: 6px; padding: 4px 12px; cursor: pointer; font: inherit; }
  #tabs button.sel { background: #3b3826; color: #fff; border-color: #3b3826; }
  #tabs .meta { margin-left: auto; color: #666; font-size: 12px; }
  /* Scrolls in both axes: these images are shown at their real pixel size, and a
     wide chart is far wider than any window. */
  /* "safe" centring falls back to flex-start when the content is wider than the
     box: plain centring (or an auto margin) pushes the start of an overflowing
     row into unreachable negative space, so the left of a wide chart cannot be
     scrolled to. */
  #stage { flex: 1; overflow: auto; display: flex; flex-wrap: wrap; align-items: flex-start;
           justify-content: safe center; padding: 16px; gap: 16px; }
  .panel { text-align: center; flex: 0 0 auto; }
  /* A very wide chart reads better stacked than side by side, where each half is
     only as wide as half the window. */
  #stage.stack { flex-direction: column; align-items: safe center; justify-content: flex-start; }
  #zoom { display: flex; align-items: center; gap: 6px; margin-left: 12px; }
  #zoom input { width: 130px; }
  #tiles { flex: 0 0 100%; display: flex; gap: 4px; justify-content: center; }
  #tiles button { border: 1px solid #ccc6b2; background: #fbfaf4; border-radius: 4px;
                  padding: 2px 8px; cursor: pointer; font: inherit; font-size: 12px; }
  #tiles button.sel { background: #3b3826; color: #fff; border-color: #3b3826; }
  #zoom .pct { font-size: 12px; color: #666; min-width: 38px; text-align: right; }
  .panel .cap { font-size: 12px; color: #666; padding: 6px; }
  #stage img { background: #fff; border: 1px solid #ccc6b2; max-width: none; image-rendering: auto; }
  #onionWrap { position: relative; display: inline-block; }
  #onionWrap img { display: block; }
  #onionWrap .top { position: absolute; inset: 0; }
  #sliders { display: flex; gap: 24px; justify-content: center; padding: 8px; background: var(--panel); border-top: 1px solid #ddd8c8; }
  #sliders label { display: flex; gap: 8px; align-items: center; font-size: 12px; color: #444; }
  #empty { margin: auto; color: #888; }
  #stage.flow { display: block; }
  .row { display: flex; gap: 16px; justify-content: center; align-items: flex-start; }
  .report { margin: 0 auto; max-width: 1000px; }
  .report h3 { font-size: 13px; margin: 14px 0 4px; }
  .report table { border-collapse: collapse; font-size: 12px; }
  .report th, .report td { border-bottom: 1px solid #e4dfcc; padding: 3px 10px; text-align: right; }
  .report th:first-child, .report td:first-child { text-align: left; font-weight: 600; }
  .report td.pad { color: #666; }
</style>
</head>
<body>
<div id="app">
  <div id="side"><h1>Maradona oracle</h1></div>
  <div id="main">
    <div id="tabs">
      <button data-view="side" class="sel">Side by side</button>
      <button data-view="onion">Onion skin</button>
      <button data-view="diff">Pixel diff</button>
      <span class="meta" id="meta"></span>
      <span id="zoom">
        <button data-zoom="fit">Fit</button>
        <input id="zoomRange" type="range" min="10" max="200" step="10" value="100">
        <span class="pct" id="zoomPct"></span>
      </span>
    </div>
    <div id="stage"><div id="empty">Select a comparison on the left.</div></div>
    <div id="sliders" hidden>
      <label>opacity <input id="opacity" type="range" min="0" max="100" value="50"></label>
      <label>wipe <input id="wipe" type="range" min="0" max="100" value="100"></label>
    </div>
  </div>
</div>
<script>
const DATA = __DATA__;
let sel = null, view = 'side';
const stage = document.getElementById('stage');
const meta = document.getElementById('meta');
const sliders = document.getElementById('sliders');
const opacity = document.getElementById('opacity');
const wipe = document.getElementById('wipe');

// 'fit' or a zoom percentage. Inspecting a detail (a cylinder's cap, an
// arrowhead) needs real pixels, so the slider can go up to 200%.
let zoomMode = 'fit';
let zoomPercent = 100;

// Some entries have one image per tile (overlapping pan windows); the tile
// selector picks which one is shown, so both sides always show the same region.
let tileIndex = 0;

function pick(images, key) {
  const list = images[key];
  if (!list) return null;
  if (!Array.isArray(list)) return list;
  return list[Math.min(tileIndex, list.length - 1)] || list[0];
}

function img(src, cls) {
  const i = document.createElement('img');
  i.src = src; if (cls) i.className = cls;
  i.onload = () => applyZoom();
  return i;
}

/** Fit-to-window or real pixels; wide images stack so a fit still reads. */
function applyZoom() {
  const images = Array.from(stage.querySelectorAll('img'));
  const widest = images.reduce((a, i) => Math.max(a, (i.naturalWidth || 0) / (i.naturalHeight || 1)), 0);
  const fitting = zoomMode === 'fit';
  // Wide charts stay stacked at any zoom: stacked panels share the horizontal
  // scroll, so the same region of both is on screen and a single node can be
  // compared directly.
  stage.classList.toggle('stack', widest > 2);
  for (const i of images) {
    if (fitting) {
      i.style.maxWidth = stage.classList.contains('stack') ? '100%' : '48vw';
      i.style.maxHeight = '80vh';
      i.style.width = 'auto';
    } else {
      i.style.maxWidth = 'none';
      i.style.maxHeight = 'none';
      const natural = i.naturalWidth || 0;
      i.style.width = natural ? `${Math.round(natural * zoomPercent / 100)}px` : 'auto';
    }
  }
  const shown = fitting ? 'fit' : `${zoomPercent}%`;
  document.getElementById('zoomPct').textContent = shown;
  document.querySelectorAll('#zoom button').forEach(b => b.classList.toggle('sel', fitting && b.dataset.zoom === 'fit'));
  const slider = document.getElementById('zoomRange');
  slider.disabled = fitting;
  slider.value = zoomPercent;
}
function applyOnion() {
  const top = stage.querySelector('.top');
  if (!top) return;
  top.style.opacity = opacity.value / 100;
  top.style.clipPath = `inset(0 ${100 - wipe.value}% 0 0)`;
}
function table(e) {
  const wrap = document.createElement('div'); wrap.className = 'report';
  const h = document.createElement('h3');
  h.textContent = `layout vs mermaid geometry (golden scaled x${e.scale.toFixed(3)})`;
  wrap.appendChild(h);
  const w = e.worst;
  const sum = document.createElement('p'); sum.className = 'cap';
  sum.textContent = `worst: box size ${(w.size * 100).toFixed(1)}%  centre ${(w.centre * 100).toFixed(1)}% of diagonal  `
    + `edge label ${(w.label * 100).toFixed(1)}%  padding ${w.pad.toFixed(0)}px  missing ${w.missing}`;
  wrap.appendChild(sum);
  const t = document.createElement('table');
  t.innerHTML = '<tr><th>node</th><th>mermaid</th><th>maradona</th><th>delta w/h</th><th>centre</th><th>padding</th></tr>';
  const pads = Object.fromEntries(e.pads.map(p => [p[0], p[1]]));
  for (const r of e.rows) {
    const tr = document.createElement('tr');
    const pad = pads[r[0]] || '';
    tr.innerHTML = `<td>${r[0]}</td><td>${r[1]}</td><td>${r[2]}</td><td>${r[3]}</td><td>${r[4]}</td>`
      + `<td class="pad">${pad}</td>`;
    t.appendChild(tr);
  }
  wrap.appendChild(t);
  for (const l of e.labels) {
    const p = document.createElement('p'); p.className = 'cap';
    p.textContent = `edge label ${JSON.stringify(l[0])}: centre off by ${l[1]} (${l[2]})`;
    wrap.appendChild(p);
  }
  const unmatched = e.render.filter(r => !r[1].endsWith('px')).length;
  const bad = e.render.filter(r => r[1].endsWith('px') && parseFloat(r[1]) > 6).length;
  const p2 = document.createElement('p'); p2.className = 'cap';
  p2.textContent = `renderer: ${e.render.length} nodes, ${unmatched} unmatched, ${bad} drawn more than 6px off the IL`;
  wrap.appendChild(p2);
  return wrap;
}

function render() {
  stage.textContent = '';
  if (!sel) { stage.innerHTML = '<div id="empty">Select a comparison on the left.</div>'; return; }
  const e = sel.data;
  if (e.kind === 'layout') {
    sliders.hidden = true;
    stage.classList.add('flow');
    document.querySelectorAll('#tabs button').forEach(b => b.disabled = true);
    if (e.images) {
      const row = document.createElement('div'); row.className = 'row';
      for (const [src, cap] of [[pick(e.images, 'gold'), 'mermaid golden'], [pick(e.images, 'ours'), 'maradona']]) {
        const p = document.createElement('div'); p.className = 'panel';
        p.appendChild(img(src));
        const c = document.createElement('div'); c.className = 'cap'; c.textContent = cap;
        p.appendChild(c); row.appendChild(p);
      }
      stage.appendChild(row);
    }
    stage.appendChild(table(e));
    return;
  }
  meta.textContent = `${sel.platform} · ${sel.key} · ${e.mode}` +
    ('mismatch' in e ? ` · pixel diff ${(e.mismatch * 100).toFixed(2)}% (visual only)` : '') +
    ('driftW' in e ? ` · size vs golden W x${e.driftW.toFixed(2)} H x${e.driftH.toFixed(2)}` : '') +
    (e.tiles > 1 ? ` · ${e.tiles} tiles` : '') +
    (e.error ? ` · ${e.error}` : '');
  sliders.hidden = true;
  stage.classList.remove('flow');
  // One image per tile for levels captured as several pan windows: the selector
  // switches which window is shown, so both sides always show the same region.
  const tileCount = Array.isArray(e.images?.ours) ? e.images.ours.length : 1;
  if (tileCount > 1) {
    const bar = document.createElement('div');
    bar.id = 'tiles';
    for (let i = 0; i < tileCount; i++) {
      const b = document.createElement('button');
      b.textContent = String(i + 1);
      b.className = i === Math.min(tileIndex, tileCount - 1) ? 'sel' : '';
      b.onclick = () => { tileIndex = i; render(); };
      bar.appendChild(b);
    }
    stage.appendChild(bar);
  }
  if (e.error || !e.images) {
    const d = document.createElement('div'); d.id = 'empty';
    d.textContent = e.error || 'no capture';
    stage.appendChild(d);
    return;
  }
  if (view === 'side') {
    const goldCap = e.mode === 'scale'
      ? 'maradona 100%, upscaled ×5 (the scale reference)'
      : (sel.key.endsWith('-min') ? 'mermaid golden ×0.1' : 'mermaid golden');
    for (const [src, cap] of [[pick(e.images, 'gold'), goldCap], [pick(e.images, 'ours'), 'maradona capture']]) {
      const p = document.createElement('div'); p.className = 'panel';
      p.appendChild(img(src));
      const c = document.createElement('div'); c.className = 'cap'; c.textContent = cap;
      p.appendChild(c); stage.appendChild(p);
    }
    applyZoom();
  } else if (view === 'onion') {
    sliders.hidden = false;
    const w = document.createElement('div'); w.id = 'onionWrap';
    w.appendChild(img(pick(e.images, 'gold')));
    w.appendChild(img(pick(e.images, 'ours'), 'top'));
    stage.appendChild(w);
    applyOnion();
    applyZoom();
  } else {
    const p = document.createElement('div'); p.className = 'panel';
    p.appendChild(img(pick(e.images, 'diff')));
    const c = document.createElement('div'); c.className = 'cap';
    c.textContent = 'red = pixels beyond tolerance';
    p.appendChild(c); stage.appendChild(p);
    applyZoom();
  }
}
function select(el, platform, key, data) {
  document.querySelectorAll('.item.sel').forEach(x => x.classList.remove('sel'));
  el.classList.add('sel');
  sel = { platform, key, data };
  render();
}
const side = document.getElementById('side');
const SECTION = {
  layout: 'layout vs mermaid geometry',
  android: 'capture checks: android',
  desktop: 'capture checks: desktop',
  ios: 'capture checks: ios',
};
for (const [platform, entries] of Object.entries(DATA)) {
  const h = document.createElement('h2'); h.textContent = SECTION[platform] || platform; side.appendChild(h);
  for (const [key, e] of Object.entries(entries)) {
    const d = document.createElement('div');
    d.className = 'item';
    d.dataset.id = `${platform}/${key}`;
    d.innerHTML = `<span>${key}</span><span class="badge ${e.pass ? 'pass' : 'fail'}">${e.pass ? 'PASS' : 'FAIL'}</span>`;
    d.onclick = () => { history.replaceState(null, '', '#' + d.dataset.id); select(d, platform, key, e); };
    side.appendChild(d);
  }
}
if (location.hash.length > 1) {
  const el = document.querySelector(`[data-id="${location.hash.slice(1)}"]`);
  if (el) el.click();
}
document.querySelectorAll('#tabs button').forEach(b => b.onclick = () => {
  view = b.dataset.view;
  document.querySelectorAll('#tabs button').forEach(x => x.classList.toggle('sel', x === b));
  render();
});
document.querySelectorAll('#zoom button').forEach(b => b.onclick = () => {
  zoomMode = 'fit';
  applyZoom();
});
document.getElementById('zoomRange').oninput = (e) => {
  zoomMode = 'manual';
  zoomPercent = Number(e.target.value);
  applyZoom();
};
opacity.oninput = applyOnion;
wipe.oninput = applyOnion;
</script>
</body>
</html>
"""


def main() -> None:
    data = {}
    layout = ROOT / "out" / "report-layout.json"
    if layout.exists():
        data["layout"] = json.loads(layout.read_text())
    for report in sorted((ROOT / "out").glob("report-*.json")):
        platform = report.stem.removeprefix("report-")
        if platform == "layout":
            continue
        data[platform] = json.loads(report.read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    html = HTML.replace("__DATA__", json.dumps(data))
    (OUT / "index.html").write_text(html)
    print(f"wrote {OUT / 'index.html'}")


if __name__ == "__main__":
    main()
