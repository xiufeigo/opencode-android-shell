import test from "node:test"
import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"

const source = await readFile(join(dirname(fileURLToPath(import.meta.url)), "terminal.js"), "utf8")

test("mobile xterm scrollbar narrows the painted thumb while preserving its hit area", () => {
  assert.match(source, /@media \(max-width: 767px\)/)
  assert.match(source, /width: \$\{MOBILE_SCROLLBAR_THUMB_WIDTH\}px !important/)
  assert.match(source, /left: \$\{XTERM_SCROLLBAR_WIDTH - MOBILE_SCROLLBAR_THUMB_WIDTH\}px !important/)
  assert.match(source, /const XTERM_SCROLLBAR_WIDTH = 14/)
  assert.match(source, /const MOBILE_SCROLLBAR_THUMB_WIDTH = 8/)
  assert.doesNotMatch(source, /overviewRuler\s*:/)
})
