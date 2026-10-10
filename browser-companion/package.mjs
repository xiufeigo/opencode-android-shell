import fs from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"

const here = path.dirname(fileURLToPath(import.meta.url))
const pkg = JSON.parse(await fs.readFile(path.join(here, "package.json"), "utf8"))
const stage = path.join(here, "dist/distribution")
const names = ["dist/main.mjs", "dist/upstream.json", "start.mjs", "package.json", "package-lock.json", "README.md"]
for (const name of names) {
  await fs.mkdir(path.dirname(path.join(stage, name)), { recursive: true })
  await fs.copyFile(path.join(here, name), path.join(stage, name))
}
const output = path.join(here, `dist/opencode-browser-gateway-${pkg.version}.tar.gz`)
execFileSync("tar", ["-czf", output, "-C", stage, ...names], { stdio: "inherit" })
console.log(`Browser gateway distribution: ${output}`)
