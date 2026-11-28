#!/usr/bin/env node
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
const source = path.resolve(here, "../../plugins/server-browser/src/rpc.ts")
const output = path.join(here, "rpc.ts")
fs.copyFileSync(source, output)
console.log(`${path.relative(process.cwd(), source)} -> ${path.relative(process.cwd(), output)}`)
