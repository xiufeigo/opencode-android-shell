import { spawn } from "node:child_process"
import { fileURLToPath } from "node:url"
import fs from "node:fs/promises"
import path from "node:path"
import { createRequire } from "node:module"

const require = createRequire(import.meta.url)
let executable = require("electron")
const args = [fileURLToPath(new URL("dist/main.mjs", import.meta.url))]
if (process.platform === "win32") {
  if (!process.env.LOCALAPPDATA) throw new Error("LOCALAPPDATA is required to install the browser runtime")
  const base = path.join(process.env.LOCALAPPDATA, "OpenCode Android Browser")
  const version = require("electron/package.json").version
  const runtime = path.join(base, `runtime-${version}-${process.arch}`)
  const marker = path.join(runtime, ".installed")
  let installed = false
  try { await fs.access(marker); installed = true } catch (error) { if (error.code !== "ENOENT") throw error }
  if (!installed) {
    // Chromium's sandbox cannot start from some protected development-directory
    // ACLs. Install our runtime under the user's normal application directory.
    await fs.mkdir(runtime, { recursive: true })
    await fs.cp(path.dirname(executable), runtime, { recursive: true })
    await fs.writeFile(marker, version)
  }
  executable = path.join(runtime, "electron.exe")
  args.unshift(`--user-data-dir=${path.join(base, "profile")}`)
}
// Pipe handles also work when the caller has no Windows console.
const child = spawn(executable, args, {
  stdio: ["ignore", "pipe", "pipe"], windowsHide: true,
})
child.stdout.pipe(process.stdout)
child.stderr.pipe(process.stderr)
child.on("error", error => { console.error(error); process.exitCode = 1 })
child.on("exit", (code, signal) => {
  process.exitCode = code ?? 1
  if (signal || code) console.error(`Browser service stopped: ${signal ?? `exit ${code} (0x${code.toString(16)})`}`)
})
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill())
