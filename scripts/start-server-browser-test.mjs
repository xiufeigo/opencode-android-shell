import { spawn } from "node:child_process"
import fs from "node:fs/promises"
import path from "node:path"
import { randomBytes } from "node:crypto"

const root = path.resolve(import.meta.dirname, "..")
const directory = path.join(root, "_scratch_root/server-browser-test")
await fs.mkdir(directory, { recursive: true })
const executable = process.env.OPENCODE_TEST_BINARY ?? path.join(process.env.APPDATA, "ai.opencode.desktop/cli/2.0.26/opencode-cli.exe")
const port = process.env.OPENCODE_TEST_PORT ?? "54106"
const password = randomBytes(24).toString("base64url")
const plugin = process.env.OPENCODE_TEST_PLUGIN ?? path.join(root, "plugins/server-browser")
await fs.writeFile(path.join(directory, "opencode.json"), JSON.stringify({ plugins: [
  { package: plugin, options: { executablePath: process.env.SERVER_BROWSER_CHROMIUM, automation: true } },
  { package: path.join(root, "scripts/fixtures/browser-tool-probe") },
] }))
const child = spawn(executable, ["serve", "--hostname", "127.0.0.1", "--port", port], {
  cwd: directory, windowsHide: true,
  env: { ...process.env, OPENCODE_CONFIG_DIR: path.join(directory, "config"), XDG_DATA_HOME: path.join(directory, "data"), XDG_STATE_HOME: path.join(directory, "state"), OPENCODE_SERVER_PASSWORD: password },
})
await fs.writeFile(path.join(directory, "connection.json"), JSON.stringify({ url: `http://127.0.0.1:${port}`, password, directory }))
const redact = data => process.stdout.write(data.toString().replace(/server password \S+/g, "server password [redacted]").replaceAll(password, "[redacted]"))
child.stdout.on("data", redact)
child.stderr.on("data", redact)
child.on("exit", code => { console.log("Browser test server exited:", code); process.exitCode = code })
process.on("SIGINT", () => child.kill())
