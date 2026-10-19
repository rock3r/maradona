// Exact geometry for every mermaid node shape, straight from Chrome.
//
// Measuring a shape from the raster goes wrong for path-shaped nodes: a fill's
// bounding box stops short of an anti-aliased cap (a cylinder loses its arcs)
// and a notch can poke outside the box (a flag). Chrome's own getBBox() has
// none of those problems, so the golden geometry uses it as the source of
// truth for shape boxes.
//
// Usage: node svg_boxes.mjs <chart.svg> [more.svg ...]
// Prints one JSON line per file: {"<svg>": {"<nodeId>": {tag, class, box}}}.
import puppeteer from 'puppeteer';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const files = process.argv.slice(2);
const config = JSON.parse(readFileSync(new URL('./puppeteer-config.json', import.meta.url), 'utf8'));
const executablePath = config.executablePath;

const browser = await puppeteer.launch({
  executablePath,
  headless: true,
  args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars'],
});
const page = await browser.newPage();

for (const file of files) {
  const svg = readFileSync(file, 'utf8');
  await page.setContent(
    `<!DOCTYPE html><html><body style="margin:0">${svg}</body></html>`,
    { waitUntil: 'load' },
  );
  const boxes = await page.evaluate(() => {
    const svg = document.querySelector('svg');
    const root = svg.getBoundingClientRect();
    const parts = (svg.getAttribute('viewBox') || '0 0 1 1').trim().split(/\s+/).map(Number);
    const vb = { x: parts[0], y: parts[1] };
    const scale = root.width / (parts[2] || root.width);
    const out = {};
    for (const g of document.querySelectorAll('g.node')) {
      const id = (g.id || '').replace(/^.*flowchart-/, '').replace(/-\d+$/, '');
      // The shape is the first direct drawing child (the label group comes
      // after it, and it has its own empty <rect/>).
      let shape = null;
      for (const child of g.children) {
        // Skip the label group only: shape wrappers are often classed
        // "basic label-container outer-path" too.
        if (child.tagName === 'g' && child.classList.contains('label')) continue;
        if (['rect', 'polygon', 'circle', 'ellipse', 'path'].includes(child.tagName)) {
          shape = child;
          break;
        }
        const nested = child.querySelector('rect, polygon, circle, ellipse, path');
        if (nested) {
          shape = nested;
          break;
        }
      }
      if (!shape) continue;
      // Screen-space rect, then back to viewBox user units: shapes carry their
      // own transforms (mermaid translates polygons), which getBBox() ignores.
      const r = shape.getBoundingClientRect();
      out[id] = {
        tag: shape.tagName,
        cls: shape.getAttribute('class') || '',
        box: [
          (r.left - root.left) / scale + vb.x,
          (r.top - root.top) / scale + vb.y,
          r.width / scale,
          r.height / scale,
        ].map((v) => Math.round(v * 100) / 100),
      };
    }
    return out;
  });
  console.log(JSON.stringify({ [resolve(file)]: boxes }));
}

await browser.close();
