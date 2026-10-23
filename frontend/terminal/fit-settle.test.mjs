import test from "node:test"
import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"

// Tripwire for the "right sidebar leaves the terminal misaligned" defect.
//
// A panel transition resizes the terminal container on every animation frame.
// The old `scheduleFit` was first-wins (`if (this.fitFrame) return`), so the
// grid was fitted against a mid-animation width and every later width — the
// final one included — was dropped: the grid kept a stale column count while
// the container had already moved, which painted the text sideways (left edge
// cut, wide empty gutter) and broke Vim's vertical motion.
//
// These assertions are deliberately about the *source*, not about a DOM: the
// regression is a dropped notification, and only the source says whether the
// pending frame is replaced or ignored.
const source = await readFile(join(dirname(fileURLToPath(import.meta.url)), "terminal.js"), "utf8")

test("scheduleFit is last-wins: a pending frame is cancelled, never ignored", () => {
  const body = source.slice(source.indexOf("  scheduleFit() {"), source.indexOf("  scheduleFitSettle() {"))
  assert.match(body, /if \(this\.fitFrame\) cancelAnimationFrame\(this\.fitFrame\)/)
  assert.doesNotMatch(body, /if \(this\.fitFrame\) return/)
  // The old first-wins guard must not survive anywhere in the fit path.
  assert.doesNotMatch(source, /!this\.mobileLayoutReady \|\| this\.fitFrame/)
})

test("a panel transition settles on its final width", () => {
  assert.match(source, /scheduleFitSettle\(\)/)
  assert.match(source, /this\.fitSettleFrame = requestAnimationFrame/)
  assert.match(source, /cancelAnimationFrame\(this\.fitSettleFrame\)/)
  // The settle pass must be guarded by a width comparison, otherwise the
  // settle -> fit -> settle chain would never end.
  assert.match(source, /Math\.abs\(width - this\.fittedWidth\) < 0\.5/)
  // The width the grid was fitted to has to be recorded, or the comparison
  // above can never see a move.
  assert.match(source, /terminal\.fittedWidth = rect\.width/)
})

test("a fit resets the grid's horizontal scroll", () => {
  assert.match(source, /resetHorizontalScroll\(\)/)
  assert.match(source, /querySelector\("\.xterm-scrollable-element"\)/)
  assert.match(source, /scrollable\.scrollLeft = 0/)
  // The reset has to run as part of the fit, not only on some other path.
  const fit = source.slice(source.indexOf("  fit() {"), source.indexOf("  observeResize() {"))
  assert.match(fit, /terminal\.resetHorizontalScroll\(\)/)
})
