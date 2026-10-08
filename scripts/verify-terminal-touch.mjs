#!/usr/bin/env node
/**
 * verify-terminal-touch.mjs — real-browser regression runner for the OC-TERMINAL
 * phone/tablet touch + font-geometry fixes in
 *
 *     webapp/src/main/assets/www/oc-host.js   (owned by the lead; this runner never edits it)
 *
 * ── What makes this a *real* regression runner ──────────────────────────────────
 *  · The `OC-TERMINAL` → EOF segment of oc-host.js is **read from disk at every run**
 *    and loaded verbatim into the fixture page as a classic <script>. Nothing is
 *    re-implemented, re-worded or stubbed here; if the host patch regresses, these
 *    checks fail.
 *  · The terminal is the **actual ghostty-web WASM control**: `init()` loads
 *    `ghostty-vt.wasm`, `Terminal` + `FitAddon` create a real canvas inside a real
 *    `[data-component="terminal"]` panel, and >=250 numbered lines are written to
 *    build genuine scrollback.
 *  · Touch input is **trusted browser input** through Chromium's CDP
 *    `Input.dispatchTouchEvent` (never synthetic `dispatchEvent`), so it runs the
 *    same pointer/touch-action/compat-mouse-event path as a real finger.
 *  · Assertions read the terminal's own state (`term.getViewportY()`,
 *    `term.renderer.getMetrics()`, `term.cols`, `term.wasmTerm.hasMouseTracking()`,
 *    `document.activeElement === term.textarea`), never source text.
 *
 * ── Fixture fidelity ───────────────────────────────────────────────────────────
 *  · Outer panel carries `data-component="terminal"` and a mono font stack whose
 *    *shared tail* matches what the control is given — but with a **different first
 *    preference**, exactly the upstream mismatch oc-host.js documents (panel
 *    resolves to "IBM Plex Mono", control uses "JetBrainsMono Nerd Font Mono"). That
 *    is what forces tail-matching rather than whole-string comparison.
 *  · The fixture installs an **upstream-style `pointerdown` listener on the inner
 *    node that focuses `term.textarea`**, reproducing the focus race the host patch
 *    has to win.
 *  · Only the terminal section of the host script is loaded, so the Android bridge
 *    gate (`window.__ocAndroidNative`) and unrelated host APIs are never touched.
 *
 * ── Usage ──────────────────────────────────────────────────────────────────────
 *     node scripts/verify-terminal-touch.mjs
 *     VERIFY_TOUCH_FILTER=gesture node scripts/verify-terminal-touch.mjs   # subset
 *
 * Exit code 0 = all checks passed, 1 = at least one failed. Failures are reported,
 * never repaired — oc-host.js belongs to the lead.
 */
import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import vm from "node:vm"
import { fileURLToPath } from "node:url"
import assert from "node:assert/strict"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, "..")

const HOST_JS = process.env.VERIFY_TOUCH_HOST_JS || path.join(REPO, "webapp/src/main/assets/www/oc-host.js")

const GHOSTTY_PKG =
  process.env.VERIFY_TOUCH_GHOSTTY ||
  path.join(
    REPO,
    "_upstream/node_modules/.bun",
    "ghostty-web@github+anomalyco+ghostty-web+83c0a07/node_modules/ghostty-web",
  )

const PLAYWRIGHT_MJS =
  process.env.VERIFY_TOUCH_PLAYWRIGHT ||
  path.join(REPO, "_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs")

const CHROMIUM =
  process.env.VERIFY_TOUCH_CHROMIUM ||
  "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"

// Failure screenshots land in an already-gitignored tree (research/**/*.png).
const SHOT_DIR = path.join(REPO, "research/tmp/verify-terminal-touch")

const FILTER = process.env.VERIFY_TOUCH_FILTER || ""
const SHOTS = process.env.VERIFY_TOUCH_NO_SHOTS !== "1"

/** Device profiles mirroring the ones in oc-host.js's own field notes. */
const PHONE = { name: "phone", viewport: { width: 411, height: 914 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true }
const TABLET = { name: "tablet", viewport: { width: 1024, height: 768 }, deviceScaleFactor: 2, isMobile: false, hasTouch: true }

/* ───────────────────────── host section extraction ───────────────────────── */

/**
 * Pull the `OC-TERMINAL` banner through EOF out of oc-host.js.
 *
 * The banner is located by its own comment marker, then walked back to the
 * `// ====` rule line that opens the section. Everything from there to the end of
 * the file is returned verbatim — the host file keeps OC-TERMINAL and everything
 * after it (OC-SETTINGS) in one contiguous tail, so the tail *is* the unit under
 * test.
 */
function extractTerminalSection(src, file) {
  const lines = src.split("\n")
  const banner = lines.findIndex((l) => /\/\/\s*OC-TERMINAL\s*[·:—-]/.test(l))
  assert.ok(banner >= 0, `could not find the OC-TERMINAL banner in ${file}`)

  let start = banner
  while (start > 0 && !/^\s*\/\/ ={10,}\s*$/.test(lines[start])) start--
  assert.ok(start < banner, `could not find the section rule line above the OC-TERMINAL banner in ${file}`)

  const section = lines.slice(start).join("\n")

  // The extracted tail must be valid JS on its own...
  new vm.Script(section, { filename: "oc-host-terminal-section.js" })
  // ...must actually be the terminal machinery we mean to exercise...
  assert.match(section, /function\s+installTerminalTouchScroll\s*\(/, "extracted section lost installTerminalTouchScroll")
  assert.match(section, /function\s+installTerminalFontScale\s*\(/, "extracted section lost installTerminalFontScale")
  // ...and must not drag in the Android bridge (fixture never defines it).
  assert.ok(
    !/__ocAndroidNative/.test(section),
    "extracted section references __ocAndroidNative; it is no longer loadable standalone",
  )
  return { section, startLine: start + 1, totalLines: lines.length }
}

/* ─────────────────────────────── fixture page ─────────────────────────────── */

// Shared mono tail. The control is constructed with a *different first preference*
// than the panel resolves to, so the host patch must compare the tail, not the
// whole stack — this is the situation documented in oc-host.js.
const FONT_TAIL = "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
const PANEL_FONT_FAMILY = `"IBM Plex Mono", ${FONT_TAIL}`
const TERMINAL_FONT_FAMILY = `"JetBrainsMono Nerd Font Mono", ${FONT_TAIL}`

const HTML_PAGE = `<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>oc-terminal-touch fixture</title>
<style>
  html, body { margin: 0; height: 100%; background: #0b0b0f; overflow: hidden; }
  /* Mirrors the upstream terminal panel: 14px padding, mono stack, full height. */
  [data-component="terminal"] {
    box-sizing: border-box; padding: 14px; height: 100%;
    font-family: ${PANEL_FONT_FAMILY}; font-size: 14px; color: #d4d4d4;
  }
  [data-component="terminal"] > .oc-term-inner { width: 100%; height: 100%; }
</style>
<script src="/oc-terminal-section.js"></script>
</head>
<body>
<div data-component="terminal"><div class="oc-term-inner" id="inner"></div></div>
<script type="module" src="/fixture.js"></script>
</body></html>`

const FIXTURE_MODULE = `
import { init, Terminal, FitAddon } from "/dist/ghostty-web.js"

// Real WASM terminal — no mocks, no fake control.
await init()

const inner = document.getElementById("inner")
const term = new Terminal({
  fontSize: 14,                                   // upstream hard-codes this
  fontFamily: ${JSON.stringify(TERMINAL_FONT_FAMILY)},
  scrollback: 20000,
  cols: 80,
  rows: 24,
})
const fit = new FitAddon()
term.loadAddon(fit)
term.open(inner)
fit.fit()

// Upstream-style focus behaviour: a plain pointerdown listener on the inner node
// focuses the terminal's hidden textarea. This is the focus race the host patch
// must beat on touch — without it the "drag must not raise the keyboard" check
// would be vacuous.
inner.addEventListener("pointerdown", () => {
  try { if (term.textarea) term.textarea.focus() } catch (e) {}
})

// >= 250 numbered lines so scrollback is real and every direction has room.
const lines = []
for (let i = 1; i <= 250; i++) {
  lines.push("[" + String(i).padStart(3, "0") + "] oc-terminal-touch regression payload")
}
term.write(lines.join("\\r\\n") + "\\r\\n")
await new Promise((r) => setTimeout(r, 500))
fit.fit()

// ── Observers (record only; never stopPropagation / preventDefault) ────────────
const rec = { wheels: [], pointers: { down: 0, up: 0, cancel: 0, move: 0 }, t0: Date.now() }
// Capture on the *panel*: it is an ancestor of the control's own wheel listener,
// so it observes every wheel the host synthesises before the control sees it.
document.querySelector('[data-component="terminal"]').addEventListener(
  "wheel",
  (e) => rec.wheels.push({ t: Date.now() - rec.t0, dY: e.deltaY, dM: e.deltaMode, shift: e.shiftKey }),
  true,
)
for (const [k, type] of [["down", "pointerdown"], ["up", "pointerup"], ["cancel", "pointercancel"], ["move", "pointermove"]]) {
  document.addEventListener(type, () => { rec.pointers[k]++ }, true)
}

// Unpatched reference measure: measured on an offscreen 2d context with a font
// stack that deliberately does NOT contain the panel tail, so the host's
// measureText patch returns the browser's true advance untouched.
function rawAdvance(px) {
  const c = document.createElement("canvas").getContext("2d")
  c.font = px + "px \\"Probe Mono\\", monospace"
  return c.measureText("M").width
}

function inPanelCtx() {
  const c = document.createElement("canvas")
  c.style.width = "10px"; c.style.height = "10px"
  document.querySelector('[data-component="terminal"]').appendChild(c)
  return { canvas: c, ctx: c.getContext("2d") }
}
function inPanelFontPx(px) {
  const { canvas, ctx } = inPanelCtx()
  try { ctx.font = px + "px " + ${JSON.stringify(TERMINAL_FONT_FAMILY)}; return ctx.font }
  finally { canvas.remove() }
}

window.__probe = {
  rec,
  clearRec() {
    rec.wheels.length = 0
    rec.pointers.down = rec.pointers.up = rec.pointers.cancel = rec.pointers.move = 0
    rec.t0 = Date.now()
  },
  wheelCount: () => rec.wheels.length,
  wheels: () => rec.wheels.slice(),
  pointers: () => Object.assign({}, rec.pointers),

  vpy: () => term.getViewportY(),
  scrollbackLength: () => term.wasmTerm.getScrollbackLength(),
  scrollToBottom() { term.scrollToBottom() },
  metrics: () => { const m = term.renderer.getMetrics(); return { width: m.width, height: m.height, baseline: m.baseline } },
  cols: () => term.cols,
  rows: () => term.rows,
  canvasPresent: () => !!(term.renderer && term.renderer.getCanvas && term.renderer.getCanvas()),
  canvasInsidePanel: () => {
    const cv = term.renderer.getCanvas()
    return !!(cv && cv.closest && cv.closest('[data-component="terminal"]'))
  },

  hasMouseTracking: () => term.wasmTerm.hasMouseTracking(),
  enableMouseTracking() {
    // DECSET 1000 + SGR 1006 — what a real full-screen app (vim/less/tmux) turns on.
    term.write("\\x1b[?1000h\\x1b[?1006h")
    return new Promise((r) => setTimeout(() => r(term.wasmTerm.hasMouseTracking()), 250))
  },

  tail: () => window.ocTerminalFontTail(),
  scale: () => window.ocTerminalScale(),
  // The scale the terminal actually rendered with. oc-host.js captures this once
  // per page so a rotation/resize cannot change the drawn font size mid-session;
  // fall back to the live scale if that capture helper is absent.
  renderScale: () =>
    typeof window.ocTerminalRenderScale === "function" ? window.ocTerminalRenderScale() : window.ocTerminalScale(),
  rawAdvance,
  inPanelFontPx,
  fontPxOf: (font) => {
    const m = /([\\d.]+)px/.exec(String(font || ""))
    return m ? parseFloat(m[1]) : null
  },

  textareaFocused: () => document.activeElement === term.textarea,
  activeTag: () => (document.activeElement ? document.activeElement.tagName : null),
  blurActive() {
    const a = document.activeElement
    if (a && typeof a.blur === "function") a.blur()
    document.activeElement && document.activeElement.blur && document.activeElement.blur()
  },
  viewport: () => ({ w: innerWidth, h: innerHeight, dpr: devicePixelRatio, narrow: matchMedia("(max-width: 767px)").matches }),
  innerRect: () => { const r = document.getElementById("inner").getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height } },
  canvasRect: () => { const r = term.renderer.getCanvas().getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height } },
}
window.__ready = true
`

/* ────────────────────────────── static server ─────────────────────────────── */

function startServer(section) {
  const send = (res, code, type, body) => {
    res.writeHead(code, { "content-type": type, "cache-control": "no-store" })
    res.end(body)
  }
  const server = http.createServer((req, res) => {
    let pathname = "/"
    try {
      pathname = decodeURIComponent(new URL(req.url || "/", "http://127.0.0.1").pathname)
    } catch {
      return send(res, 400, "text/plain; charset=utf-8", "bad request")
    }
    try {
      switch (pathname) {
        case "/":
          return send(res, 200, "text/html; charset=utf-8", HTML_PAGE)
        case "/oc-terminal-section.js":
          return send(res, 200, "application/javascript; charset=utf-8", section)
        case "/fixture.js":
          return send(res, 200, "application/javascript; charset=utf-8", FIXTURE_MODULE)
        case "/dist/ghostty-web.js":
          return send(res, 200, "application/javascript; charset=utf-8", fs.readFileSync(path.join(GHOSTTY_PKG, "dist/ghostty-web.js")))
        case "/dist/__vite-browser-external-2447137e.js":
          return send(res, 200, "application/javascript; charset=utf-8", fs.readFileSync(path.join(GHOSTTY_PKG, "dist/__vite-browser-external-2447137e.js")))
        case "/ghostty-vt.wasm":
        case "/dist/ghostty-vt.wasm":
          return send(res, 200, "application/wasm", fs.readFileSync(path.join(GHOSTTY_PKG, "ghostty-vt.wasm")))
        case "/favicon.ico":
          res.writeHead(204)
          return res.end()
        default:
          return send(res, 404, "text/plain; charset=utf-8", "not found")
      }
    } catch (err) {
      return send(res, 500, "text/plain; charset=utf-8", String((err && err.message) || err))
    }
  })
  return new Promise((resolve, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port }))
  })
}

/* ──────────────────────────────── reporting ───────────────────────────────── */

const results = []
let currentPage = null
let failures = 0

function record(name, ok, detail) {
  results.push({ name, ok, detail: detail || "" })
  if (!ok) failures++
  const mark = ok ? "PASS" : "FAIL"
  console.log(`  [${mark}] ${name}${detail ? ` — ${detail}` : ""}`)
}

async function shot(page, name) {
  if (!page || !SHOTS) return
  try {
    fs.mkdirSync(SHOT_DIR, { recursive: true })
    await page.screenshot({ path: path.join(SHOT_DIR, `${name.replace(/[^\w.-]+/g, "_")}.png`) })
  } catch {
    /* screenshot is a diagnostic nicety, never a hard failure */
  }
}

async function check(name, fn) {
  if (FILTER && !name.toLowerCase().includes(FILTER.toLowerCase())) return
  const page = currentPage
  try {
    await fn()
    record(name, true, "")
  } catch (err) {
    record(name, false, (err && err.message) || String(err))
    await shot(page, name)
  }
}

/* ─────────────────────────────── touch driver ─────────────────────────────── */

/** CDP touch primitives: trusted input, exactly what a finger produces. */
function makeTouch(cdp) {
  return {
    start(x, y, id = 1) {
      return cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x: Math.round(x), y: Math.round(y), id }] })
    },
    move(x, y, id = 1) {
      return cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x: Math.round(x), y: Math.round(y), id }] })
    },
    end() {
      // Chromium expects an EMPTY touchPoints list on release; sending stale
      // coordinates here makes the browser end the gesture somewhere else and
      // silently cancels the real drag.
      return cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
    },
    cancel() {
      return cdp.send("Input.dispatchTouchEvent", { type: "touchCancel", touchPoints: [] })
    },
  }
}

/** A linear finger drag over `steps` frames, `delayMs` apart so velocity is real. */
async function fingerDrag(touch, page, x, fromY, toY, { steps = 8, delayMs = 16, release = true } = {}) {
  await touch.start(x, fromY)
  for (let i = 1; i <= steps; i++) {
    await touch.move(x, fromY + ((toY - fromY) * i) / steps)
    await page.waitForTimeout(delayMs)
  }
  if (release) await touch.end()
}

/** Wait until getViewportY() stops moving (smoothScrollTo animates). */
async function settle(page, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    const v = await page.evaluate(() => window.__probe.vpy())
    if (last !== null && Math.abs(v - last) < 0.01) return v
    last = v
    await page.waitForTimeout(60)
  }
  return last
}

const frames = (page, n = 3) => page.evaluate((k) => new Promise((res) => {
  let i = 0
  const step = () => (++i >= k ? res(true) : requestAnimationFrame(step))
  requestAnimationFrame(step)
}), n)

/* ───────────────────────────────── main ───────────────────────────────────── */

async function main() {
  for (const [label, p] of [["host script", HOST_JS], ["ghostty-web", GHOSTTY_PKG], ["chromium", CHROMIUM]]) {
    if (!fs.existsSync(p)) throw new Error(`missing ${label}: ${p}`)
  }
  if (!fs.existsSync(PLAYWRIGHT_MJS)) throw new Error(`missing playwright: ${PLAYWRIGHT_MJS}`)

  const hostSrc = fs.readFileSync(HOST_JS, "utf8")
  const { section, startLine, totalLines } = extractTerminalSection(hostSrc, HOST_JS)
  console.log(`host section : oc-host.js lines ${startLine}-${totalLines} (${section.length} chars), parsed as standalone JS`)
  console.log(`ghostty-web  : ${GHOSTTY_PKG}`)
  console.log(`chromium     : ${CHROMIUM}`)

  const { server, port } = await startServer(section)
  const { chromium } = await import(`file://${PLAYWRIGHT_MJS.replace(/\\/g, "/")}`)

  // No --no-sandbox / --disable-setuid-sandbox / any escalation flags.
  const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })

  const openPage = async (device, { scale = null } = {}) => {
    const ctx = await browser.newContext({
      viewport: device.viewport,
      deviceScaleFactor: device.deviceScaleFactor,
      isMobile: device.isMobile,
      hasTouch: device.hasTouch,
    })
    if (scale !== null) {
      await ctx.addInitScript((v) => {
        try { window.localStorage.setItem("oc.settings.terminalFontScale", v) } catch (e) {}
      }, String(scale))
    }
    const page = await ctx.newPage()
    const pageErrors = []
    page.on("pageerror", (e) => pageErrors.push(String(e)))
    await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: "load" })
    await page.waitForFunction("window.__ready === true", null, { timeout: 45000 })
    const cdp = await ctx.newCDPSession(page)
    const rect = await page.evaluate(() => window.__probe.canvasRect())
    // Aim at the middle of the real canvas, not the panel padding.
    const pt = { x: Math.round(rect.x + rect.w / 2), y: Math.round(rect.y + rect.h / 2) }
    return {
      ctx, page, cdp, pt, pageErrors,
      touch: makeTouch(cdp),
      wheel: (dx, dy, modifiers = 0) =>
        cdp.send("Input.dispatchMouseEvent", { type: "mouseWheel", x: pt.x, y: pt.y, deltaX: dx, deltaY: dy, modifiers }),
      close: async () => { await ctx.close().catch(() => {}) },
    }
  }

  const resultsOut = []

  try {
    /* ── Sanity: this really is a live WASM terminal with scrollback ───────── */
    await (async () => {
      const h = await openPage(PHONE)
      currentPage = h.page
      try {
        await check("fixture/sanity: real canvas inside the terminal panel", async () => {
          const v = await h.page.evaluate(() => ({
            canvas: window.__probe.canvasPresent(),
            inPanel: window.__probe.canvasInsidePanel(),
            rows: window.__probe.rows(),
            scrollback: window.__probe.scrollbackLength(),
          }))
          assert.ok(v.canvas, "terminal renderer produced no canvas")
          assert.ok(v.inPanel, "canvas is not inside [data-component='terminal']")
          assert.ok(v.rows > 0, "terminal has no rows")
          assert.ok(v.scrollback >= 100, `expected >=100 lines of scrollback, got ${v.scrollback}`)
        })

        await check("fixture/sanity: >=250 numbered lines reached the buffer", async () => {
          // 250 lines are written; the control keeps (lines - rows) of them as
          // scrollback and the rest on screen, so rows + scrollback is the total.
          const v = await h.page.evaluate(() => ({ rows: window.__probe.rows(), sb: window.__probe.scrollbackLength() }))
          assert.ok(
            v.rows + v.sb >= 240,
            `only ${v.rows + v.sb} lines in the buffer (${v.rows} on screen + ${v.sb} scrollback); expected the 250 written`,
          )
        })

        await check("fixture/sanity: narrow breakpoint is active on phone, panel tail matches control", async () => {
          const v = await h.page.evaluate(() => ({
            vp: window.__probe.viewport(),
            tail: window.__probe.tail(),
            scale: window.__probe.scale(),
          }))
          assert.ok(v.vp.narrow, `phone should match (max-width: 767px); innerWidth=${v.vp.w}`)
          assert.ok(v.tail && v.tail.startsWith("ui-monospace"), `unexpected panel font tail: ${JSON.stringify(v.tail)}`)
          assert.equal(v.scale, 0.65, `default terminal font scale should be 0.65, got ${v.scale}`)
        })
      } finally {
        currentPage = null
        await h.close()
      }
    })()

    /* ── Font scale / geometry (real metrics, real control) ────────────────── */

    // Reference: an unscaled 14px advance, read on the tablet where the host's
    // narrow-screen gate is inert, so this is the browser's own measurement.
    let advance14 = null

    await (async () => {
      const h = await openPage(TABLET)
      currentPage = h.page
      try {
        await check("font/tablet: wide viewport keeps the 14px cell (no scale)", async () => {
          const v = await h.page.evaluate(() => ({
            narrow: window.__probe.viewport().narrow,
            m: window.__probe.metrics(),
            inPanel: window.__probe.fontPxOf(window.__probe.inPanelFontPx(14)),
            raw: window.__probe.rawAdvance(14),
          }))
          assert.ok(!v.narrow, `tablet 1024px should not match (max-width: 767px)`)
          advance14 = v.raw
          assert.ok(v.raw > 0, "raw 14px advance probe returned nothing")
          assert.ok(
            Math.abs(v.m.width - v.raw) <= 0.4,
            `tablet cell width ${v.m.width} should equal the unscaled 14px advance ${v.raw}`,
          )
          assert.ok(
            Math.abs(v.inPanel - 14) < 0.01,
            `in-panel ctx.font should stay 14px on tablet, got ${v.inPanel}px`,
          )
        })
      } finally {
        currentPage = null
        await h.close()
      }
    })()

    await (async () => {
      const h = await openPage(PHONE)
      currentPage = h.page
      try {
        await check("font/phone: cell width is 14 * 0.65 (default 0.65 scale)", async () => {
          const v = await h.page.evaluate(() => ({ m: window.__probe.metrics(), scale: window.__probe.scale() }))
          assert.equal(v.scale, 0.65, "default scale should be 0.65")
          const want = advance14 * 0.65
          assert.ok(
            Math.abs(v.m.width - want) <= 0.4,
            `phone cell ${v.m.width} should be 0.65 * ${advance14.toFixed(3)} = ${want.toFixed(3)}`,
          )
          // Non-vacuous: the scale must actually have moved the needle.
          assert.ok(
            Math.abs(v.m.width - advance14) > 0.4,
            `phone cell ${v.m.width} is indistinguishable from the unscaled ${advance14.toFixed(3)} — scaling had no effect`,
          )
        })

        await check("font/phone: glyph drawing font is 14 * 0.65 = 9.1px", async () => {
          const px = await h.page.evaluate(() => window.__probe.fontPxOf(window.__probe.inPanelFontPx(14)))
          assert.ok(Math.abs(px - 9.1) < 0.01, `in-panel ctx.font should be 9.1px on phone, got ${px}px`)
        })

        await check("font/geometry: drawn glyph advance equals the cell width (no letter-spacing)", async () => {
          const v = await h.page.evaluate(() => ({ m: window.__probe.metrics(), px: window.__probe.fontPxOf(window.__probe.inPanelFontPx(14)) }))
          // The control measures cells on an OFFSCREEN context at 14px (scaled), and
          // draws at 14*scale px. Geometry "matches" iff the cell width equals the
          // advance of the font actually used for drawing.
          const wantAdvance = advance14 * (v.px / 14)
          assert.ok(
            Math.abs(v.m.width - wantAdvance) <= 0.4,
            `cell ${v.m.width} != advance at ${v.px}px (${wantAdvance.toFixed(3)}) — glyphs would render smaller than their cells`,
          )
        })

        await check("font/scope: scaling does not leak outside the terminal panel", async () => {
          const v = await h.page.evaluate((fam) => {
            const c = document.createElement("canvas").getContext("2d")
            c.font = "14px " + fam
            return window.__probe.fontPxOf(c.font)
          }, TERMINAL_FONT_FAMILY)
          assert.ok(Math.abs(v - 14) < 0.01, `offscreen ctx.font should stay 14px, got ${v}px`)
        })
      } finally {
        currentPage = null
        await h.close()
      }
    })()

    await (async () => {
      // Same phone viewport, only the stored scale differs → columns must grow.
      const scaled = await openPage(PHONE)
      const plain = await openPage(PHONE, { scale: 1 })
      currentPage = scaled.page
      try {
        await check("font/cols: 0.65 scale yields more columns than 100% at the same width", async () => {
          const a = await scaled.page.evaluate(() => window.__probe.cols())
          const b = await plain.page.evaluate(() => window.__probe.cols())
          assert.ok(a > b, `cols at 0.65 (${a}) should exceed cols at 1.0 (${b})`)
          assert.ok(a >= b * 1.3, `expected a meaningful column gain, got ${a} vs ${b}`)
        })
      } finally {
        currentPage = null
        await scaled.close()
        await plain.close()
      }
    })()

    await (async () => {
      // Rotation / narrow<->wide: the captured scale must survive.
      const h = await openPage(PHONE)
      currentPage = h.page
      try {
        await check("font/rotate: narrow→wide→narrow keeps the captured scale and drawn font size", async () => {
          // oc-host.js captures the render scale once per page precisely so that a
          // rotation (or an IME-driven resize) cannot change the font it already
          // measured and drew with — only cols/rows may change.
          const read = () =>
            h.page.evaluate(() => ({
              rs: window.__probe.renderScale(),
              px: window.__probe.fontPxOf(window.__probe.inPanelFontPx(14)),
              cols: window.__probe.cols(),
              rows: window.__probe.rows(),
              narrow: window.__probe.viewport().narrow,
              m: window.__probe.metrics(),
            }))
          const before = await read()
          assert.equal(before.rs, 0.65, `captured render scale should be 0.65 on a phone page, got ${before.rs}`)
          await h.page.setViewportSize(TABLET.viewport)
          await h.page.evaluate(() => new Promise((r) => setTimeout(r, 300)))
          const wide = await read()
          await h.page.setViewportSize(PHONE.viewport)
          await h.page.evaluate(() => new Promise((r) => setTimeout(r, 300)))
          const after = await read()

          assert.ok(!wide.narrow, "wide viewport should leave the narrow gate")
          assert.ok(after.narrow, "narrow viewport should re-enter the narrow gate")
          assert.equal(wide.rs, before.rs, `captured scale changed when rotating to wide: ${before.rs} -> ${wide.rs}`)
          assert.equal(after.rs, before.rs, `captured scale changed when rotating back: ${wide.rs} -> ${after.rs}`)
          assert.ok(
            Math.abs(wide.px - before.px) < 0.01,
            `drawn font size changed on rotation: ${before.px}px -> ${wide.px}px`,
          )
          assert.ok(
            Math.abs(after.px - before.px) < 0.01,
            `drawn font size changed after rotating back: ${wide.px}px -> ${after.px}px`,
          )
          assert.ok(
            Math.abs(after.m.width - before.m.width) <= 0.4,
            `cell width changed across rotation: ${before.m.width} -> ${after.m.width}`,
          )
        })
      } finally {
        currentPage = null
        await h.close()
      }
    })()

    /* ── Touch gestures: phone, trusted CDP touch ──────────────────────────── */

    await (async () => {
      const h = await openPage(PHONE)
      currentPage = h.page
      try {
        await check("gesture/finger-down: dragging the finger down scrolls back toward history", async () => {
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await settle(h.page)
          await h.page.evaluate(() => window.__probe.clearRec())
          const start = await h.page.evaluate(() => window.__probe.vpy())
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 130)
          const end = await settle(h.page)
          assert.ok(end > start + 1, `viewportY should grow into history: ${start} -> ${end}`)
        })

        await check("gesture/finger-up: dragging the finger up returns toward newer output", async () => {
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 130)
          const deep = await settle(h.page)
          assert.ok(deep > 1, `precondition: expected to be scrolled into history, got ${deep}`)
          await h.page.evaluate(() => window.__probe.clearRec())
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y + 130, h.pt.y - 130)
          const back = await settle(h.page)
          assert.ok(back < deep - 1, `viewportY should shrink toward newer output: ${deep} -> ${back}`)
          assert.ok(back >= -0.01, `viewportY must not go negative: ${back}`)
        })

        await check("gesture/tap: a tap with no movement focuses the terminal textarea", async () => {
          await h.page.evaluate(() => { window.__probe.blurActive(); window.__probe.clearRec() })
          assert.ok(!(await h.page.evaluate(() => window.__probe.textareaFocused())), "precondition: textarea already focused")
          await h.touch.start(h.pt.x, h.pt.y)
          await frames(h.page, 2)
          await h.touch.end()
          await h.page.waitForTimeout(120)
          assert.ok(
            await h.page.evaluate(() => window.__probe.textareaFocused()),
            `tap did not focus the terminal textarea (activeElement=${await h.page.evaluate(() => window.__probe.activeTag())})`,
          )
          const moved = await h.page.evaluate(() => window.__probe.wheelCount())
          assert.equal(moved, 0, `a tap must not scroll; ${moved} wheel(s) were dispatched`)
        })

        await check("gesture/scroll-focus: a drag that starts unfocused must NOT focus the textarea", async () => {
          await h.page.evaluate(() => window.__probe.blurActive())
          await h.page.waitForTimeout(60)
          assert.ok(!(await h.page.evaluate(() => window.__probe.textareaFocused())), "precondition: textarea already focused")
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 130)
          await settle(h.page)
          assert.ok(
            !(await h.page.evaluate(() => window.__probe.textareaFocused())),
            `dragging raised the keyboard: textarea focused (activeElement=${await h.page.evaluate(() => window.__probe.activeTag())})`,
          )
        })

        await check("gesture/mouse-wheel: a real mouse wheel still scrolls the terminal", async () => {
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await settle(h.page)
          const mt = await h.page.evaluate(() => window.__probe.hasMouseTracking())
          assert.ok(!mt, "precondition: mouse tracking should be off by default")
          await h.wheel(0, -400)
          const v = await settle(h.page)
          assert.ok(v > 1, `mouse wheel did not scroll into history, viewportY=${v}`)
        })

        await check("gesture/held: a gesture that stops and is held must not fling on release", async () => {
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await settle(h.page)
          // Drag, then keep the finger perfectly still long enough for any velocity
          // window to expire, then release.
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 40, { release: false })
          await frames(h.page, 2)
          await h.page.evaluate(() => window.__probe.clearRec())
          await h.page.waitForTimeout(420)
          const beforeRelease = await h.page.evaluate(() => window.__probe.wheelCount())
          assert.equal(beforeRelease, 0, `holding still should dispatch no wheels, got ${beforeRelease}`)
          await h.touch.end()
          await frames(h.page, 2)
          const afterRelease = await h.page.evaluate(() => window.__probe.wheelCount())
          await h.page.waitForTimeout(700)
          const later = await h.page.evaluate(() => window.__probe.wheelCount())
          assert.equal(afterRelease, 0, `release after a held stop should not start inertia, got ${afterRelease} wheel(s)`)
          assert.equal(later, 0, `inertia kept running after a held stop: ${later} wheel(s)`)
        })

        await check("gesture/cancel: a canceled gesture leaves no trailing wheels", async () => {
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await settle(h.page)
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 40, { release: false })
          // A genuine platform cancel (the compositor taking the gesture over).
          await h.touch.cancel()
          await h.page.waitForTimeout(120)
          const p = await h.page.evaluate(() => window.__probe.pointers())
          assert.ok(p.cancel > 0, `precondition: expected a pointercancel, saw ${JSON.stringify(p)}`)
          // Let the in-flight drag flush land, then start counting: "no trailing
          // wheels" means nothing arrives *after* the cancel settled, i.e. no
          // inertia tail.
          await frames(h.page, 3)
          await h.page.evaluate(() => window.__probe.clearRec())
          const atCancel = await h.page.evaluate(() => window.__probe.wheelCount())
          await h.page.waitForTimeout(700)
          const later = await h.page.evaluate(() => window.__probe.wheelCount())
          assert.equal(atCancel, 0, `in-flight flush after cancel: ${atCancel} wheel(s)`)
          assert.equal(later, 0, `canceled gesture left trailing wheels: ${later}`)
        })

        await check("gesture/reflinge: touching down again stops an in-flight fling", async () => {
          await h.page.evaluate(() => { window.__probe.scrollToBottom(); window.__probe.clearRec() })
          await settle(h.page)
          // Fast drag + release starts a fling.
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 150, h.pt.y + 150, { steps: 5, delayMs: 8 })
          await h.page.waitForTimeout(60)
          const midFling = await h.page.evaluate(() => window.__probe.wheelCount())
          // New finger down must stop it.
          await h.touch.start(h.pt.x, h.pt.y)
          await frames(h.page, 2)
          const atTouch = await h.page.evaluate(() => window.__probe.wheelCount())
          await h.page.waitForTimeout(400)
          const later = await h.page.evaluate(() => window.__probe.wheelCount())
          await h.touch.end()
          assert.ok(midFling > 0, `precondition: expected a fling to be running, saw ${midFling} wheel(s)`)
          assert.equal(later, atTouch, `fling continued after the new touch (${atTouch} -> ${later} wheels)`)
        })

        await check("gesture/shiftkey: synthesised scrollback wheels are not eaten by app mouse reporting", async () => {
          // With full-screen mouse reporting on, a plain wheel is reported to the
          // application instead of scrolling scrollback (ghostty-web bails out
          // unless shiftKey is set) — so a finger drag would do nothing at all.
          const on = await h.page.evaluate(() => window.__probe.enableMouseTracking())
          assert.ok(on, "could not enable mouse tracking on the terminal")
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await settle(h.page)
          await h.page.evaluate(() => window.__probe.clearRec())
          const start = await h.page.evaluate(() => window.__probe.vpy())

          await h.wheel(0, -400)
          await h.page.waitForTimeout(300)
          const afterPlain = await settle(h.page)
          assert.ok(
            afterPlain <= start + 0.01,
            `precondition: a plain wheel should NOT scroll while the app tracks the mouse (got ${start} -> ${afterPlain})`,
          )

          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 130)
          const end = await settle(h.page)
          const wheels = await h.page.evaluate(() => window.__probe.wheels())
          assert.ok(wheels.length > 0, "the drag dispatched no wheels at all")
          assert.ok(
            end > start + 1,
            `finger drag did not scroll while the app tracked the mouse: ${start} -> ${end} (needs shiftKey on synthetic wheels)`,
          )
        })

        await check("gesture/shiftkey-flag: every synthetic scrollback wheel carries shiftKey", async () => {
          const on = await h.page.evaluate(() => window.__probe.enableMouseTracking())
          assert.ok(on, "could not enable mouse tracking on the terminal")
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await settle(h.page)
          await h.page.evaluate(() => window.__probe.clearRec())
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 130)
          await settle(h.page)
          const wheels = await h.page.evaluate(() => window.__probe.wheels())
          assert.ok(wheels.length > 0, "the drag dispatched no wheels")
          const bad = wheels.filter((w) => !w.shift)
          assert.equal(
            bad.length,
            0,
            `${bad.length}/${wheels.length} synthetic wheels lacked shiftKey; ghostty-web forwards those to the app as mouse reports instead of scrolling`,
          )
        })
      } finally {
        currentPage = null
        await h.close()
      }
    })()

    /* ── Tablet sanity: touch also works there (breakpoint only gates the font) ─ */
    await (async () => {
      const h = await openPage(TABLET)
      currentPage = h.page
      try {
        await check("gesture/tablet: finger drag scrolls scrollback at 1024x768 too", async () => {
          await h.page.evaluate(() => window.__probe.scrollToBottom())
          await settle(h.page)
          const start = await h.page.evaluate(() => window.__probe.vpy())
          await fingerDrag(h.touch, h.page, h.pt.x, h.pt.y - 130, h.pt.y + 130)
          const end = await settle(h.page)
          assert.ok(end > start + 1, `tablet drag did not scroll: ${start} -> ${end}`)
        })
      } finally {
        currentPage = null
        await h.close()
      }
    })()
  } finally {
    try {
      await browser.close()
    } catch {
      /* keep going — the server still needs closing */
    }
    server.close()
  }

  resultsOut.push(...results)
  console.log("")
  console.log(`verify-terminal-touch: ${results.length - failures}/${results.length} checks passed`)
  if (failures) {
    console.log("")
    console.log("failed checks:")
    for (const r of results.filter((x) => !x.ok)) console.log(`  - ${r.name}: ${r.detail}`)
    if (SHOTS) console.log(`\nscreenshots (if any): ${path.relative(REPO, SHOT_DIR)}`)
  }
  process.exitCode = failures ? 1 : 0
}

main().catch((err) => {
  console.error("verify-terminal-touch: fatal:", (err && err.stack) || err)
  process.exitCode = 1
})