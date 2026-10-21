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
<link rel="icon" href="data:,">
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
           justify-content: safe center; padding: 16px; gap: 16px; min-height: 0; }
  .panel { text-align: center; flex: 0 0 auto; }
  /* In manual zoom, side-by-side panels each get their own scrollable viewport
     and sync scroll position so scrolling either half scrolls the other in lockstep. */
  #stage.split { flex-wrap: nowrap; overflow: hidden; align-items: stretch; }
  #stage.split .panel { flex: 1 1 0; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
  #stage.split .panel .scroll { flex: 1 1 0; overflow: auto; display: flex;
                                background: #fff; border: 1px solid #ccc6b2; }
  #stage.split .panel img { border: none; flex: 0 0 auto; margin: auto; display: block; }
  /* A very wide chart reads better stacked than side by side, where each half is
     only as wide as half the window. */
  #stage.stack { flex-direction: column; align-items: safe center; justify-content: flex-start; }
  #stage.split.stack { align-items: stretch; }
  #zoom { display: flex; align-items: center; gap: 6px; margin-left: 12px; }
  #zoom button { border: 1px solid #ccc6b2; background: #fbfaf4; border-radius: 6px; padding: 3px 9px; cursor: pointer; font: inherit; font-size: 12px; }
  #zoom button.sel { background: #3b3826; color: #fff; border-color: #3b3826; }
  #zoom input { width: 130px; }
  #tiles { display: flex; flex-wrap: wrap; gap: 4px; justify-content: center; padding: 6px 14px;
           background: var(--panel); border-bottom: 1px solid #ddd8c8; }
  #tiles[hidden] { display: none; }
  #tiles button { border: 1px solid #ccc6b2; background: #fbfaf4; border-radius: 4px;
                  padding: 2px 8px; cursor: pointer; font: inherit; font-size: 12px; }
  #tiles button.sel { background: #3b3826; color: #fff; border-color: #3b3826; }
  #zoom .pct { font-size: 12px; color: #666; min-width: 38px; text-align: right; }
  .panel .cap { font-size: 12px; color: #666; padding: 6px; flex: 0 0 auto; }
  #stage img { background: #fff; border: 1px solid #ccc6b2; max-width: none; image-rendering: auto; }
  #onionWrap { position: relative; display: inline-block; }
  #onionWrap img { display: block; }
  #onionWrap .top { position: absolute; inset: 0; }
  #sliders { display: flex; gap: 24px; justify-content: center; padding: 8px; background: var(--panel); border-top: 1px solid #ddd8c8; }
  #sliders[hidden] { display: none; }
  #sliders label { display: flex; gap: 8px; align-items: center; font-size: 12px; color: #444; }
  #empty { margin: auto; color: #888; }
  #stage.flow { display: block; overflow: auto; }
  .row { display: flex; gap: 16px; justify-content: safe center; align-items: flex-start; overflow-x: auto; padding-bottom: 8px; }
  .row.stack { flex-direction: column; align-items: safe center; }
  .row.split { overflow: hidden; }
  .row.split.stack { align-items: stretch; }
  .row.split .panel { flex: 1 1 0; min-width: 0; max-width: 100%; display: flex; flex-direction: column; }
  .row.split .panel .scroll { overflow: auto; max-height: 55vh; display: flex;
                              background: #fff; border: 1px solid #ccc6b2; }
  .row.split .panel img { border: none; flex: 0 0 auto; margin: auto; display: block; }
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
        <button data-zoom="100">100%</button>
        <input id="zoomRange" type="range" min="10" max="200" step="10" value="100">
        <span class="pct" id="zoomPct"></span>
      </span>
    </div>
    <div id="tiles" hidden></div>
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
const tilesBar = document.getElementById('tiles');
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

let needsInitialScroll = false;

function img(src, cls) {
  const i = document.createElement('img');
  i.src = src; if (cls) i.className = cls;
  i.onload = () => applyZoom();
  return i;
}

function syncScrolls(containers) {
  let syncing = false;
  for (const c of containers) {
    c.onscroll = () => {
      if (syncing) return;
      syncing = true;
      const rx = c.scrollWidth > c.clientWidth ? c.scrollLeft / (c.scrollWidth - c.clientWidth) : 0;
      const ry = c.scrollHeight > c.clientHeight ? c.scrollTop / (c.scrollHeight - c.clientHeight) : 0;
      for (const other of containers) {
        if (other === c) continue;
        if (Math.abs(other.scrollWidth - c.scrollWidth) <= 4 && Math.abs(other.scrollHeight - c.scrollHeight) <= 4) {
          other.scrollLeft = c.scrollLeft;
          other.scrollTop = c.scrollTop;
        } else {
          other.scrollLeft = rx * Math.max(0, other.scrollWidth - other.clientWidth);
          other.scrollTop = ry * Math.max(0, other.scrollHeight - other.clientHeight);
        }
      }
      syncing = false;
    };
  }
}

/** Fit-to-window or real pixels; wide images stack so a fit still reads. */
function applyZoom() {
  const images = Array.from(stage.querySelectorAll('img'));
  const widest = images.reduce((a, i) => Math.max(a, (i.naturalWidth || 0) / (i.naturalHeight || 1)), 0);
  const fitting = zoomMode === 'fit';
  const isSide = sel && sel.data && sel.data.kind !== 'layout' && view === 'side';
  const row = stage.querySelector('.row');
  // Wide charts stay stacked at any zoom: stacked panels share the horizontal
  // scroll, so the same region of both is on screen and a single node can be
  // compared directly.
  stage.classList.toggle('stack', widest > 2 && !row);
  stage.classList.toggle('split', !fitting && isSide);
  if (row) {
    row.classList.toggle('stack', widest > 2);
    row.classList.toggle('split', !fitting);
  }
  const stacked = stage.classList.contains('stack') || (row && row.classList.contains('stack'));
  for (const i of images) {
    if (fitting) {
      i.style.maxWidth = stacked ? '100%' : '46vw';
      i.style.maxHeight = row ? '55vh' : '78vh';
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
  document.querySelectorAll('#zoom button').forEach(b => {
    const z = b.dataset.zoom;
    b.classList.toggle('sel', (z === 'fit' && fitting) || (z === '100' && !fitting && zoomPercent === 100));
  });
  const slider = document.getElementById('zoomRange');
  slider.value = zoomPercent;
  if (!fitting && needsInitialScroll && images.every(i => i.complete && i.naturalWidth > 0)) {
    needsInitialScroll = false;
    const scrolls = Array.from(stage.querySelectorAll('.scroll'));
    for (const s of scrolls) {
      if (widest > 2) {
        s.scrollLeft = 0;
        s.scrollTop = Math.max(0, Math.round((s.scrollHeight - s.clientHeight) / 2));
      } else {
        s.scrollLeft = Math.max(0, Math.round((s.scrollWidth - s.clientWidth) / 2));
        s.scrollTop = 0;
      }
    }
  }
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
    + `edge label ${(w.label * 100).toFixed(1)}%  edges ${(w.edges * 100).toFixed(1)}%  `
    + `render-vs-IL ${w.render.toFixed(0)}px  missing ${w.missing}`;
  wrap.appendChild(sum);
  const t = document.createElement('table');
  t.innerHTML = '<tr><th>node</th><th>mermaid</th><th>maradona</th><th>delta w/h</th><th>centre</th><th>text w</th></tr>';
  for (const r of e.rows) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${r[0]}</td><td>${r[1]}</td><td>${r[2]}</td><td>${r[3]}</td><td>${r[4]}</td>`
      + `<td class="pad">${r[5] || ''}</td>`;
    t.appendChild(tr);
  }
  wrap.appendChild(t);
  if (e.edges && e.edges.length) {
    const et = document.createElement('table');
    et.style.marginTop = '10px';
    et.innerHTML = '<tr><th>edge</th><th>start/end offset</th><th>mermaid pts</th><th>maradona pts</th></tr>';
    for (const er of e.edges) {
      const tr = document.createElement('tr');
      tr.innerHTML = `<td>${er[0]}</td><td>${er[1]}</td><td>${er[2]}</td><td>${er[3]}</td>`;
      et.appendChild(tr);
    }
    wrap.appendChild(et);
  }
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

function makePanel(src, cap) {
  const p = document.createElement('div'); p.className = 'panel';
  const s = document.createElement('div'); s.className = 'scroll';
  s.appendChild(img(src));
  p.appendChild(s);
  const c = document.createElement('div'); c.className = 'cap'; c.textContent = cap;
  p.appendChild(c);
  return { panel: p, scroll: s };
}

function render() {
  stage.textContent = '';
  tilesBar.textContent = '';
  tilesBar.hidden = true;
  needsInitialScroll = true;
  if (!sel) { stage.innerHTML = '<div id="empty">Select a comparison on the left.</div>'; return; }
  const e = sel.data;
  if (e.kind === 'layout') {
    sliders.hidden = true;
    stage.classList.add('flow');
    stage.classList.remove('split', 'stack');
    document.querySelectorAll('#tabs button[data-view]').forEach(b => b.disabled = true);
    const w = e.worst;
    meta.textContent = `layout · ${sel.key} · scale ×${e.scale.toFixed(3)} · size ${(w.size * 100).toFixed(1)}% · centre ${(w.centre * 100).toFixed(1)}% · edges ${(w.edges * 100).toFixed(1)}%`;
    if (e.images) {
      const row = document.createElement('div'); row.className = 'row';
      const scrolls = [];
      for (const [src, cap] of [[pick(e.images, 'gold'), 'mermaid golden'], [pick(e.images, 'ours'), 'maradona']]) {
        const { panel, scroll } = makePanel(src, cap);
        row.appendChild(panel);
        scrolls.push(scroll);
      }
      syncScrolls(scrolls);
      stage.appendChild(row);
    }
    stage.appendChild(table(e));
    applyZoom();
    return;
  }
  document.querySelectorAll('#tabs button[data-view]').forEach(b => b.disabled = false);
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
    tilesBar.hidden = false;
    // index 0 is the stitched canvas, 1..n the individual windows
    for (let i = 0; i < tileCount; i++) {
      const b = document.createElement('button');
      b.textContent = i === 0 ? 'stitched' : String(i);
      b.className = i === Math.min(tileIndex, tileCount - 1) ? 'sel' : '';
      b.onclick = () => { tileIndex = i; render(); };
      tilesBar.appendChild(b);
    }
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
    const scrolls = [];
    for (const [src, cap] of [[pick(e.images, 'gold'), goldCap], [pick(e.images, 'ours'), 'maradona capture']]) {
      const { panel, scroll } = makePanel(src, cap);
      stage.appendChild(panel);
      scrolls.push(scroll);
    }
    syncScrolls(scrolls);
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
let userExplicitZoom = false;

function select(el, platform, key, data) {
  document.querySelectorAll('.item.sel').forEach(x => x.classList.remove('sel'));
  el.classList.add('sel');
  const prevWasMax = sel && sel.key && sel.key.endsWith('-max');
  const nextIsMax = key.endsWith('-max');
  sel = { platform, key, data };
  tileIndex = 0;
  // Max-zoom captures exist to be inspected at 1:1 of the zoomed-in size, with
  // synchronized scrolling across both sides.
  if (!userExplicitZoom) {
    if (nextIsMax) {
      zoomMode = 'manual';
      zoomPercent = 100;
    } else if (prevWasMax) {
      zoomMode = 'fit';
    }
  }
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
document.querySelectorAll('#tabs button[data-view]').forEach(b => b.onclick = () => {
  view = b.dataset.view;
  document.querySelectorAll('#tabs button[data-view]').forEach(x => x.classList.toggle('sel', x === b));
  render();
});
document.querySelectorAll('#zoom button').forEach(b => b.onclick = () => {
  userExplicitZoom = true;
  if (b.dataset.zoom === 'fit') {
    zoomMode = 'fit';
  } else {
    zoomMode = 'manual';
    zoomPercent = Number(b.dataset.zoom) || 100;
  }
  applyZoom();
});
document.getElementById('zoomRange').oninput = (e) => {
  userExplicitZoom = true;
  zoomMode = 'manual';
  zoomPercent = Number(e.target.value);
  applyZoom();
};
opacity.oninput = applyOnion;
wipe.oninput = applyOnion;
function selectFromHash() {
  if (location.hash.length > 1) {
    const el = document.querySelector(`[data-id="${location.hash.slice(1)}"]`);
    if (el) el.click();
  }
}
window.addEventListener('hashchange', selectFromHash);
selectFromHash();
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
        # A report whose renders have been wiped (a failed capture deletes them
        # before taking new ones) describes images that no longer exist; showing
        # it means showing stale results that look current.
        if not any((ROOT / "out" / platform).glob("*.png")):
            print(f"skipping report-{platform}.json: no renders in out/{platform}")
            continue
        data[platform] = json.loads(report.read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    html = HTML.replace("__DATA__", json.dumps(data))
    if "__DATA__" in html:
        raise RuntimeError("unreplaced __DATA__ placeholder in generated HTML")
    for section, entries in data.items():
        if not entries:
            raise RuntimeError(f"empty section in web report: {section}")
        for key, entry in entries.items():
            if not entry.get("images") or not entry["images"].get("gold") or not entry["images"].get("ours"):
                raise RuntimeError(f"missing comparison images for {section}/{key}")
    (OUT / "index.html").write_text(html)
    print(f"wrote {OUT / 'index.html'} ({sum(len(v) for v in data.values())} entries across {list(data.keys())})")
    _verify_browser_dom()


def _verify_browser_dom() -> None:
    """Headless browser smoke check for zoom, sync-scroll, tabs, and tile controls."""
    import shutil
    import subprocess
    puppeteer_dir = ROOT / "tools" / "node_modules" / "puppeteer"
    chrome = pathlib.Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    if not shutil.which("node") or not puppeteer_dir.exists() or not chrome.exists():
        return
    script = f"""
const puppeteer = require({json.dumps(str(puppeteer_dir))});
(async () => {{
  const browser = await puppeteer.launch({{
    headless: "new",
    executablePath: {json.dumps(str(chrome))}
  }});
  try {{
    const page = await browser.newPage();
    const errors = [];
    page.on("pageerror", e => errors.push(e.message));
    page.on("console", m => {{ if (m.type() === "error") errors.push(m.text()); }});
    await page.setViewport({{ width: 1400, height: 900 }});
    const fileUrl = {json.dumps((OUT / "index.html").as_uri())};
    await page.goto(fileUrl + "#layout/shapes-lr", {{ waitUntil: "load" }});
    await page.click('#zoom button[data-zoom="100"]');
    const layoutCheck = await page.evaluate(() => {{
      const btn100 = document.querySelector('#zoom button[data-zoom="100"]');
      const scrolls = Array.from(document.querySelectorAll('.row .scroll'));
      if (scrolls.length === 2) {{
        scrolls[0].scrollLeft = 400;
        scrolls[0].dispatchEvent(new Event('scroll'));
      }}
      const text = document.body.innerText;
      return {{
        disabled: btn100.disabled,
        selected: btn100.classList.contains('sel'),
        scrollSync: scrolls.length === 2 && scrolls[0].scrollLeft === 400 && scrolls[1].scrollLeft === 400,
        hasUndefined: text.includes('undefined') || text.includes('NaN'),
      }};
    }});
    if (layoutCheck.disabled || !layoutCheck.selected || !layoutCheck.scrollSync || layoutCheck.hasUndefined) {{
      throw new Error("layout viewer check failed: " + JSON.stringify(layoutCheck));
    }}
    await page.goto(fileUrl + "#desktop/basic-td-100", {{ waitUntil: "load" }});
    await page.click('#tabs button[data-view="onion"]');
    const onionHidden = await page.evaluate(() => document.getElementById('sliders').hidden);
    if (onionHidden) throw new Error("#sliders stayed hidden in onion view");
    await page.click('#tabs button[data-view="side"]');
    const sideHidden = await page.evaluate(() => document.getElementById('sliders').hidden);
    if (!sideHidden) throw new Error("#sliders stayed visible in side view");
    if (errors.length) throw new Error("browser console/page errors: " + errors.join("; "));
  }} finally {{
    await browser.close();
  }}
}})();
"""
    subprocess.run(["node", "-e", script], check=True)
    print("verified web report interactive DOM in headless Chrome")


if __name__ == "__main__":
    main()
