// Verifies the incremental DOM behaviour of the Chromium overlay page (assets/war_project/web/overlay.html).
//
// The page used to rewrite its bar texts, rebuild the teammate roster and force a width measurement for
// every snapshot it received. Both are layout-thrashing operations, and the roster lives in a hidden
// element while the panel is collapsed. This harness loads the page's real <script> into a minimal DOM
// stub and counts document writes, so the "no change, no work" contract can be checked without a browser.
//
// Run: node scripts/verify-overlay-page.mjs
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const html = fs.readFileSync(path.join(root, 'src/main/resources/assets/war_project/web/overlay.html'), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((match) => match[1]);
if (scripts.length !== 1) {
  throw new Error(`expected exactly one script block, found ${scripts.length}`);
}

const stats = { textWrites: 0, classWrites: 0, htmlWrites: 0, layoutReads: 0, appends: 0, creations: 0 };
const ids = ['slab', 'roster', 'amount', 'confirm', 'result', 'title', 'held', 'limit',
  'ammo', 'ammoRate', 'fuel', 'fuelRate', 'cancel',
  'vpwar', 'vpMineScore', 'vpFoeScore', 'vpMineTrack', 'vpFoeTrack', 'vpMineFill', 'vpFoeFill', 'vpStars'];
const elements = new Map();

class Element {
  constructor(tag, id) {
    this.tagName = tag;
    this.id = id ?? '';
    this.children = [];
    this.classes = new Set();
    // Enough of the DOM for the page: plain style writes plus setProperty (used for CSS variables).
    this.style = { setProperty: (name, value) => { this.style[name] = value; } };
    this.dataset = {};
    this.listeners = {};
    this.value = '';
    this._text = '';
  }

  get textContent() { return this._text; }
  set textContent(value) { stats.textWrites++; this._text = String(value); }

  get className() { return [...this.classes].join(' '); }
  set className(value) {
    stats.classWrites++;
    this.classes = new Set(String(value).split(/\s+/).filter(Boolean));
  }

  get classList() {
    const self = this;
    return {
      add: (...names) => names.forEach((name) => self.classes.add(name)),
      remove: (...names) => names.forEach((name) => self.classes.delete(name)),
      contains: (name) => self.classes.has(name),
      toggle: (name, force) => {
        const want = force === undefined ? !self.classes.has(name) : Boolean(force);
        if (want) { self.classes.add(name); } else { self.classes.delete(name); }
        return want;
      },
    };
  }

  get innerHTML() { return ''; }
  set innerHTML(value) { stats.htmlWrites++; this.children = []; }

  // Both of these are layout-forcing reads in a real browser; that is exactly what is being counted.
  get offsetWidth() { stats.layoutReads++; return 140; }
  getBoundingClientRect() { stats.layoutReads++; return { width: 140, height: 18 }; }

  appendChild(child) { stats.appends++; this.children.push(child); return child; }

  removeChild(child) {
    const index = this.children.indexOf(child);
    if (index >= 0) { this.children.splice(index, 1); }
    return child;
  }

  get lastChild() { return this.children.length ? this.children[this.children.length - 1] : null; }

  /** The stub does not materialise innerHTML, so a generated child is legitimately absent. */
  querySelector() { return null; }
  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
  focus() {}
  select() {}
}

for (const id of ids) {
  elements.set(id, new Element('div', id));
}

const queries = [];
const document = {
  getElementById: (id) => elements.get(id) ?? null,
  createElement: (tag) => { stats.creations++; return new Element(tag); },
};
const timers = [];
const sandbox = {
  document,
  cefQuery: (payload) => queries.push(payload),
  console,
  window: {
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    clearTimeout: () => {},
    setInterval: () => 1,
    clearInterval: () => {},
  },
  Date, Math, JSON, parseInt, parseFloat, String, Number, isNaN,
};
sandbox.window.document = document;

vm.runInNewContext(scripts[0], vm.createContext(sandbox), { filename: 'overlay-inline.js' });
const wp = sandbox.window.wp;
if (!wp) {
  throw new Error('the page did not define window.wp');
}

const checks = [];
const check = (label, ok) => checks.push({ label, ok });

const snapshot = (over) => Object.assign({
  running: true, hasTeam: true, ammo: 100, fuel: 20, ammoRate: 5, fuelRate: 1,
  maxAmmo: 50, maxFuel: 25,
  teammates: [
    { name: 'Alpha', online: true, ammo: 10, fuel: 2 },
    { name: 'Bravo', online: false, ammo: 0, fuel: 0 },
  ],
}, over);

const reset = () => { for (const key of Object.keys(stats)) stats[key] = 0; };

// 1. First snapshot with the panel collapsed: the bar is filled, the hidden roster is not built.
reset();
wp.apply(snapshot());
check('first apply writes the bar', stats.textWrites >= 4);
check('first apply does not build the hidden roster', stats.htmlWrites === 0 && stats.appends === 0);
check('first apply measures the bar once', stats.layoutReads >= 1 && stats.layoutReads <= 3);

// 2. The same snapshot again: nothing may touch the document.
reset();
wp.apply(snapshot());
check('repeated snapshot writes nothing', stats.textWrites === 0);
check('repeated snapshot does not measure', stats.layoutReads === 0);
check('repeated snapshot does not rebuild the roster', stats.htmlWrites === 0 && stats.appends === 0);

// 3. One changed number: exactly that text and one measurement.
reset();
wp.apply(snapshot({ ammo: 101 }));
check('changed amount writes one text', stats.textWrites === 1);
check('changed amount measures once', stats.layoutReads >= 1 && stats.layoutReads <= 3);
check('changed amount leaves the roster alone', stats.appends === 0);

// 4. Opening the panel fills the roster once.
reset();
wp.openPanel({ kind: 'ammo', cooldown: 120 });
// Two teammates: two row.appendChild(name) + two row.appendChild(values) + two roster.appendChild(row).
check('openPanel builds the roster', stats.htmlWrites === 1 && stats.appends === 6);
check('openPanel writes the controls', stats.textWrites >= 3);

// 5. A snapshot that changes nothing while open: no writes at all.
reset();
wp.apply(snapshot({ ammo: 101 }));
check('unchanged snapshot on an open panel writes nothing', stats.textWrites === 0 && stats.appends === 0);

// 6. A teammate change while open rebuilds the roster exactly once.
reset();
wp.apply(snapshot({ ammo: 101, teammates: [{ name: 'Alpha', online: true, ammo: 11, fuel: 2 }] }));
check('teammate change rebuilds the roster once', stats.htmlWrites === 1 && stats.appends === 3);
check('teammate change updates the headline', stats.textWrites >= 1);

// 7. Closed again: the roster stops being rebuilt.
wp.closePanel();
reset();
wp.apply(snapshot({ ammo: 102, teammates: [{ name: 'Alpha', online: true, ammo: 12, fuel: 2 }] }));
check('collapsed panel ignores roster changes', stats.htmlWrites === 0 && stats.appends === 0);
check('collapsed panel still updates the bar', stats.textWrites === 1);

// 8. The bar still reports its measured width to Java (the click target depends on it).
const sizeQueries = queries.filter((entry) => JSON.parse(entry.request).op === 'size');
check('bar width is reported to Java', sizeQueries.length >= 1);

const failures = checks.filter((entry) => !entry.ok);
for (const entry of checks) {
  console.log(`${entry.ok ? 'ok  ' : 'FAIL'}  ${entry.label}`);
}
console.log(failures.length === 0
  ? `ALL ${checks.length} CHECKS PASSED`
  : `FAILED ${failures.length} of ${checks.length}`);
process.exit(failures.length === 0 ? 0 : 1);
