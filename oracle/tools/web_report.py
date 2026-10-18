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
  #stage { flex: 1; overflow: auto; display: flex; align-items: flex-start; justify-content: center; padding: 16px; gap: 16px; }
  .panel { text-align: center; }
  .panel .cap { font-size: 12px; color: #666; padding: 6px; }
  #stage img { background: #fff; border: 1px solid #ccc6b2; max-width: none; image-rendering: auto; }
  #onionWrap { position: relative; display: inline-block; }
  #onionWrap img { display: block; }
  #onionWrap .top { position: absolute; inset: 0; }
  #sliders { display: flex; gap: 24px; justify-content: center; padding: 8px; background: var(--panel); border-top: 1px solid #ddd8c8; }
  #sliders label { display: flex; gap: 8px; align-items: center; font-size: 12px; color: #444; }
  #empty { margin: auto; color: #888; }
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

function img(src, cls) {
  const i = document.createElement('img');
  i.src = src; if (cls) i.className = cls;
  return i;
}
function applyOnion() {
  const top = stage.querySelector('.top');
  if (!top) return;
  top.style.opacity = opacity.value / 100;
  top.style.clipPath = `inset(0 ${100 - wipe.value}% 0 0)`;
}
function render() {
  stage.textContent = '';
  if (!sel) { stage.innerHTML = '<div id="empty">Select a comparison on the left.</div>'; return; }
  const e = sel.data;
  meta.textContent = `${sel.platform} · ${sel.key} · ${e.mode}` +
    ('mismatch' in e ? ` · mismatch ${(e.mismatch * 100).toFixed(2)}%` : ` · ink ${(e.ink * 100).toFixed(1)}%`);
  document.querySelectorAll('#tabs button').forEach(b => b.disabled = e.mode === 'smoke');
  if (e.mode === 'smoke') {
    sliders.hidden = true;
    const p = document.createElement('div'); p.className = 'panel';
    p.appendChild(img(e.images.raw));
    const c = document.createElement('div'); c.className = 'cap';
    c.textContent = 'raw capture (smoke-checked only: viewport-clipped zoom level)';
    p.appendChild(c); stage.appendChild(p);
    return;
  }
  if (view === 'side') {
    sliders.hidden = true;
    const goldCap = e.mode === 'zoom' ? 'maradona 100% ×5 (self-consistency)' : 'mermaid (gold)';
    for (const [src, cap] of [[e.images.gold, goldCap], [e.images.ours, 'maradona']]) {
      const p = document.createElement('div'); p.className = 'panel';
      p.appendChild(img(src));
      const c = document.createElement('div'); c.className = 'cap'; c.textContent = cap;
      p.appendChild(c); stage.appendChild(p);
    }
  } else if (view === 'onion') {
    sliders.hidden = false;
    const w = document.createElement('div'); w.id = 'onionWrap';
    w.appendChild(img(e.images.gold));
    w.appendChild(img(e.images.ours, 'top'));
    stage.appendChild(w);
    applyOnion();
  } else {
    sliders.hidden = true;
    const p = document.createElement('div'); p.className = 'panel';
    p.appendChild(img(e.images.diff));
    const c = document.createElement('div'); c.className = 'cap';
    c.textContent = 'red = pixels beyond tolerance';
    p.appendChild(c); stage.appendChild(p);
  }
}
function select(el, platform, key, data) {
  document.querySelectorAll('.item.sel').forEach(x => x.classList.remove('sel'));
  el.classList.add('sel');
  sel = { platform, key, data };
  render();
}
const side = document.getElementById('side');
for (const [platform, entries] of Object.entries(DATA)) {
  const h = document.createElement('h2'); h.textContent = platform; side.appendChild(h);
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
opacity.oninput = applyOnion;
wipe.oninput = applyOnion;
</script>
</body>
</html>
"""


def main() -> None:
    data = {}
    for report in sorted((ROOT / "out").glob("report-*.json")):
        platform = report.stem.removeprefix("report-")
        data[platform] = json.loads(report.read_text())
    OUT.mkdir(parents=True, exist_ok=True)
    html = HTML.replace("__DATA__", json.dumps(data))
    (OUT / "index.html").write_text(html)
    print(f"wrote {OUT / 'index.html'}")


if __name__ == "__main__":
    main()
