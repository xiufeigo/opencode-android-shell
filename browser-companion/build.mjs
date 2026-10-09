import fs from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"

const here = path.dirname(fileURLToPath(import.meta.url))
const checkout = path.resolve(here, "../_scratch_root/desktop-parity/checkout")
const pin = JSON.parse(await fs.readFile(path.resolve(here, "../frontend/android/upstream.json"), "utf8"))
if (execFileSync("git", ["rev-parse", "HEAD"], { cwd: checkout, encoding: "utf8" }).trim() !== pin.commit)
  throw new Error("Build the pinned Android UI before building the browser companion")
// The companion depends on the host visibility and cancellation adaptations.
execFileSync("git", ["apply", "--reverse", "--check", path.resolve(here, "../frontend/android/upstream.patch")], { cwd: checkout, stdio: "inherit" })
const bun = process.env.BUN_BINARY ?? "bun"
if (execFileSync(bun, ["--version"], { encoding: "utf8" }).trim() !== pin.bun) throw new Error(`Bun ${pin.bun} required`)
const stage = path.join(checkout, "packages/gui-extensions/.android-browser")
await fs.mkdir(stage, { recursive: true })
await fs.copyFile(path.join(here, "transport.mjs"), path.join(stage, "transport.mjs"))
await fs.writeFile(path.join(stage, "main.mjs"), (await fs.readFile(path.join(here, "main.mjs"), "utf8")).replaceAll("../packages/gui-extensions/src/", "../src/"))
await fs.mkdir(path.join(here, "dist"), { recursive: true })
execFileSync(bun, ["build", path.join(stage, "main.mjs"), "--target=node", "--format=esm", "--external=electron", "--external=ws", "--external=lighthouse", "--outfile", path.join(here, "dist/main.mjs")], { cwd: checkout, stdio: "inherit" })
await fs.writeFile(path.join(here, "dist/upstream.json"), JSON.stringify(pin, null, 2) + "\n")
