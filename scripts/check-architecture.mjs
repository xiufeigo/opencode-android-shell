#!/usr/bin/env node
import fs from "node:fs"

const failures = []
const read = (path) => fs.readFileSync(path, "utf8")
const requireAbsent = (text, needle, label) => {
  if (text.includes(needle)) failures.push(`${label}: forbidden legacy implementation is present: ${needle}`)
}
const requirePresent = (text, needle, label) => {
  if (!text.includes(needle)) failures.push(`${label}: required canonical implementation is missing: ${needle}`)
}

const host = read("webapp/src/main/assets/www/oc-host.js")
for (const needle of [
  "installTerminalFontScale",
  "installTerminalTouchScroll",
  "ocTerminalRenderScale",
  "ocRawMeasureText",
  "ocScaleFontPx",
  "OC-TERM-TOUCH",
  "proto.measureText = function",
]) requireAbsent(host, needle, "oc-host terminal boundary")

const terminal = read("frontend/terminal/terminal.js")
for (const needle of [
  "oc.settings.terminalFontScale",
  "pointerdown",
  "pointermove",
  "fling(velocity)",
  "class FitAddon",
]) requirePresent(terminal, needle, "xterm terminal adapter")

for (const path of [
  "webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalFontScaleTest.kt",
  "webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalTouchScrollTest.kt",
]) {
  if (fs.existsSync(path)) failures.push(`obsolete Ghostty fallback test still exists: ${path}`)
}

if (failures.length) {
  console.error(failures.join("\n"))
  process.exit(1)
}
console.log("architecture checks passed")
