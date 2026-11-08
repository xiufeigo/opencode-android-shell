import { spawn } from "node:child_process"
import fs from "node:fs/promises"
import path from "node:path"

const directory = import.meta.dirname
await fs.access(path.join(directory, "dist/index.js"))
const run = (command, args) => new Promise((resolve, reject) => {
  const child = spawn(command, args, { cwd: directory, stdio: "inherit" })
  child.on("error", reject)
  child.on("exit", code => code === 0 ? resolve() : reject(new Error(`${command} exited with ${code}`)))
})
if (process.platform === "win32") await run(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c", "npm.cmd ci --omit=dev --ignore-scripts"])
else await run("npm", ["ci", "--omit=dev", "--ignore-scripts"])
await run(process.execPath, [path.join(directory, "node_modules/playwright/cli.js"), "install", "chromium"])
console.log("This package is deprecated. OpenCode 2.2.6 already includes @opencode/plugin-browser. New servers should use https://github.com/anomalyco/browse and leave opencode.json unchanged.")
console.log("Dependencies are installed for an existing entry only. Keep this package path solely on a server that already loads it, then restart that OpenCode server:")
console.log(JSON.stringify({ plugins: [{ package: directory.replaceAll("\\", "/") }] }, null, 2))
console.log("Model automation stays off unless that existing entry already sets options.automation to true. See README.md.")
