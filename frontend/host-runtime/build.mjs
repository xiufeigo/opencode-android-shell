#!/usr/bin/env node
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, "../..")
const target = path.join(root, "webapp/src/main/assets/www")
const files = ["index.html", "oc-host.js", "oc-perf.js", "oc-preflight.js"]

for (const file of files) {
  const source = path.join(here, file)
  const output = path.join(target, file)
  fs.mkdirSync(path.dirname(output), { recursive: true })
  fs.copyFileSync(source, output)
  console.log(`${path.relative(root, source)} -> ${path.relative(root, output)}`)
}
