/**
 * Page-level check of the server browser panel when the server cannot serve it: a server without the browser plugin
 * (and, through the same RPC failure path, a server whose Chromium cannot start). The panel must open and show the
 * failure and the install hint, not nothing.
 *
 * Prerequisites: a web build (`node frontend/android/build.mjs --web`) at UI_DIST, the OpenCode CLI on PATH (or
 * OPENCODE_TEST_BINARY), and the Playwright install that `plugins/server-browser/install.mjs` prepares.
 */
import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { randomBytes } from "node:crypto"
import fs from "node:fs/promises"
import http from "node:http"
import path from "node:path"
import { chromium } from "../plugins/server-browser/node_modules/playwright/index.mjs"

const root = path.resolve(import.meta.dirname, "..")
const dist = path.resolve(process.env.UI_DIST ?? path.join(root, "frontend/web/dist"))
const executable = process.env.OPENCODE_TEST_BINARY ?? "opencode"
await fs.mkdir(path.join(root, "_scratch_root"), { recursive: true })
const work = await fs.mkdtemp(path.join(root, "_scratch_root/server-browser-unavailable-"))
const password = randomBytes(24).toString("base64url")
const headers = { authorization: `Basic ${Buffer.from(`opencode:${password}`).toString("base64")}`, "content-type": "application/json" }
const types = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
  ".json": "application/json", ".webmanifest": "application/manifest+json", ".png": "image/png", ".jpg": "image/jpeg",
  ".svg": "image/svg+xml", ".woff2": "font/woff2", ".wasm": "application/wasm", ".ico": "image/x-icon",
}
const until = async (check, label, deadline = 30_000) => {
  const end = Date.now() + deadline
  while (Date.now() < end) {
    const value = await check()
    if (value) return value
    await new Promise(resolve => setTimeout(resolve, 150))
  }
  throw new Error(`Timed out: ${label}`)
}
const port = async () => {
  const probe = http.createServer()
  await new Promise(resolve => probe.listen(0, "127.0.0.1", resolve))
  const value = probe.address().port
  await new Promise(resolve => probe.close(resolve))
  return value
}

await fs.access(path.join(dist, "index.html")).catch(() => {
  throw new Error(`No web build at ${dist}. Run: node frontend/android/build.mjs --web`)
})
const directory = path.join(work, "project")
await fs.mkdir(directory, { recursive: true })
// No plugins: the server has no browser RPC to answer with.
await fs.writeFile(path.join(directory, "opencode.json"), JSON.stringify({ $schema: "https://opencode.ai/config.json", plugins: [] }, null, 2))

const assets = http.createServer(async (request, response) => {
  const pathname = decodeURIComponent(new URL(request.url, "http://127.0.0.1").pathname)
  const file = path.join(dist, pathname)
  const resolved = file.startsWith(dist) && (await fs.stat(file).then(value => value.isFile(), () => false))
    ? file
    : path.join(dist, "index.html")
  response.writeHead(200, { "content-type": types[path.extname(resolved)] ?? "application/octet-stream" })
  response.end(await fs.readFile(resolved))
})
const uiPort = await port()
await new Promise(resolve => assets.listen(uiPort, "127.0.0.1", resolve))
const origin = `http://127.0.0.1:${uiPort}`

const apiPort = await port()
const server = spawn(executable, ["serve", "--hostname", "127.0.0.1", "--port", String(apiPort)], {
  cwd: directory, windowsHide: true,
  env: {
    ...process.env,
    HOME: work,
    USERPROFILE: work,
    OPENCODE_CONFIG_DIR: path.join(work, "config"),
    XDG_CONFIG_HOME: path.join(work, "config"),
    XDG_DATA_HOME: path.join(work, "data"),
    XDG_STATE_HOME: path.join(work, "state"),
    OPENCODE_SERVER_PASSWORD: password,
  },
})
const redact = data => process.stdout.write(data.toString().replaceAll(password, "[redacted]"))
server.stdout.on("data", redact)
server.stderr.on("data", redact)
server.on("error", error => { throw error })
const exit = new Promise(resolve => server.on("exit", code => resolve(code)))
// `opencode serve` outlives a plain kill on Windows: its children keep the project directory open.
const stop = async () => {
  if (process.platform === "win32" && server.pid) {
    await new Promise(resolve => spawn("taskkill", ["/pid", String(server.pid), "/T", "/F"], { windowsHide: true, stdio: "ignore" }).on("exit", resolve))
  } else server.kill()
  await Promise.race([exit, new Promise(resolve => setTimeout(resolve, 5000))])
}

const url = `http://127.0.0.1:${apiPort}`
const request = async (route, body) => {
  const target = new URL(`/api/${route}`, url)
  target.searchParams.set("location[directory]", directory)
  const response = await fetch(target, { method: body ? "POST" : "GET", headers, body: body && JSON.stringify(body) })
  if (!response.ok) throw new Error(`API ${route} HTTP ${response.status}: ${(await response.text()).replaceAll(password, "[redacted]")}`)
  return (await response.json()).data
}

let browser, page
let passed = false
const errors = []
try {
  await until(() => fetch(`${url}/api/info`, { headers }).then(response => response.ok, () => false), "isolated server")
  const session = await request("session", { title: "Server browser unavailable" })

  browser = await chromium.launch()
  page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.on("pageerror", error => errors.push(error.message))
  await page.goto(origin)
  await page.evaluate(({ url, password }) => {
    localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "en" }))
    localStorage.setItem("opencode.global.dat:server", JSON.stringify({ list: [{ type: "http", http: { url, username: "opencode", password } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }))
  }, { url, password })
  await page.goto(`${origin}/server/${Buffer.from(url).toString("base64url")}/session/${session.id}`)
  await page.locator('[contenteditable="true"]').filter({ visible: true }).first().waitFor()

  const review = page.getByRole("button", { name: "Toggle review", exact: true }).filter({ visible: true })
  await review.waitFor()
  if (await review.getAttribute("aria-expanded") !== "true") await review.click()
  const launcher = page.locator("#review-panel").getByRole("button", { name: /^Browser\b/ }).filter({ visible: true })
  await launcher.first().waitFor()
  await launcher.first().click()

  // The panel opens before the server answers, so the failure has somewhere to show.
  const panel = page.locator('[data-component="server-browser"]').filter({ visible: true })
  await panel.waitFor()
  await panel.getByText("Install the OpenCode server browser plugin on this server to use this panel.", { exact: true }).waitFor()
  const alert = panel.getByRole("alert")
  await alert.waitFor()
  const failure = (await alert.innerText()).trim()
  // The failure names the missing RPC, not merely "something went wrong".
  assert.match(failure, /xiufeigo\.server-browser/, "the alert names the RPC the server does not provide")
  const retry = panel.getByRole("button", { name: "Reconnect", exact: true })
  await retry.waitFor()
  await retry.click()
  await alert.waitFor()
  await panel.waitFor()
  assert.deepEqual(errors, [])
  console.log(`PASS the browser panel opens and shows the failure when the server has no browser plugin (${failure})`)
  passed = true
} catch (error) {
  if (page) {
    await page.screenshot({ path: path.join(work, "failure.png") }).catch(() => {})
    await fs.writeFile(path.join(work, "failure.html"), await page.content().catch(() => "")).catch(() => {})
  }
  throw error
} finally {
  await browser?.close()
  await new Promise(resolve => assets.close(resolve))
  await stop()
  if (passed) await fs.rm(work, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 })
  else console.error(`Evidence in ${work}`)
}
