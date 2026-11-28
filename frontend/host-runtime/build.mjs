#!/usr/bin/env node
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, "../..")
const target = path.join(root, "webapp/src/main/assets/www")
const copies = ["index.html", "oc-perf.js", "oc-preflight.js"]
const hostPieces = ["android-facade.js", "notification-shim.js", "background-state-shim.js", "editable-arrow-guard.js", "round-power-sensor.js", "surface-color-tail.js", "overlay-focus.js", "terminal-settings.js"]

for (const file of copies) {
  const source = path.join(here, file)
  const output = path.join(target, file)
  fs.mkdirSync(path.dirname(output), { recursive: true })
  fs.copyFileSync(source, output)
  console.log(`${path.relative(root, source)} -> ${path.relative(root, output)}`)
}

const hostSource = hostPieces.map(file => fs.readFileSync(path.join(here, file), "utf8")).join("")
fs.writeFileSync(path.join(target, "oc-host.js"), hostSource, "utf8")
console.log(`${hostPieces.map(file => `frontend/host-runtime/${file}`).join(" + ")} -> webapp/src/main/assets/www/oc-host.js`)
