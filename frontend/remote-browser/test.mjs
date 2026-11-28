#!/usr/bin/env node
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync, spawnSync } from "node:child_process"

const here = path.dirname(fileURLToPath(import.meta.url))
const pin = JSON.parse(fs.readFileSync(path.resolve(here, "../android/upstream.json"), "utf8"))
const bun = process.env.BUN_BINARY || "bun"
const version = execFileSync(bun, ["--version"], { encoding: "utf8", shell: process.platform === "win32" && bun === "bun" }).trim()
if (version !== pin.bun) throw new Error(`Bun ${pin.bun} is required; found ${version}. Set BUN_BINARY to the pinned executable.`)
for (const [command, args] of [
  [process.execPath, [path.join(here, "sync-rpc.mjs")]],
  [bun, ["test"]],
]) {
  const result = spawnSync(command, args, { cwd: here, stdio: "inherit", shell: process.platform === "win32" && command === "bun" })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status ?? 1)
}
