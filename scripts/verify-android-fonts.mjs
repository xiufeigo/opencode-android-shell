import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { startBackend, startUiServer, nativeBridgeSource, ocHostLoaderSource, seedScript, base64url } from "./verify-android-terminal-release.mjs"

const root = fileURLToPath(new URL("../", import.meta.url))
const assets = path.join(root, "webapp/src/main/assets/www/ui/oa")
const fixture = fs.readdirSync(assets).find(name => /^Roboto-Regular-.*\.ttf$/.test(name))
assert.ok(fixture, "Packaged Roboto font fixture is missing")
const idA = `ocsys-${"a".repeat(64)}`
const idB = `ocsys-${"b".repeat(64)}`
const idC = `ocsys-${"c".repeat(64)}`
const choices = [
  { id: idA, name: "Test Regular.ttf" },
  { id: idB, name: "Test Collection.ttc" },
  { id: idC, name: "Slow Font.ttf" },
]
const backend = await startBackend()
backend.workspace = "C:/Android-font-test"
backend.sessionId = "ses_android_font_test"
const server = await startUiServer()
const ui = `http://127.0.0.1:${server.address().port}`
let browser
let releaseSlow
const bootstrap = reply => `${seedScript({ serverOrigin: backend.origin, workspace: backend.workspace, sessionId: backend.sessionId })};${nativeBridgeSource()};(() => {
  const channel = window.__ocPlatform
  const original = channel.postMessage.bind(channel)
  channel.postMessage = function(raw) {
    const request = JSON.parse(raw)
    if (request.method !== "listSystemFonts") return original(raw)
    queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ id: request.id, ...${JSON.stringify(reply)} }) }))
  }
  localStorage.setItem("opencode.window.browser.dat:tabs", JSON.stringify([{ type: "session", server: ${JSON.stringify(backend.origin)}, sessionId: ${JSON.stringify(backend.sessionId)} }]))
  window.__ocTerminalTest = { instances: [], events: [] }
})();${ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8"))}`

try {
  const { chromium } = await import(process.env.VERIFY_TERMINAL_PLAYWRIGHT ||
    new URL("../plugins/server-browser/node_modules/playwright/index.mjs", import.meta.url).href)
  browser = await chromium.launch({ executablePath: process.env.VERIFY_TERMINAL_CHROMIUM, headless: true })
  const context = await browser.newContext({ viewport: { width: 1163, height: 760 }, serviceWorkers: "block", locale: "en-US" })
  let notifySlow
  const slowRequested = new Promise(resolve => { notifySlow = resolve })
  const slowReleased = new Promise(resolve => { releaseSlow = resolve })
  await context.route("**/oc-system-font/**", async route => {
    if (new URL(route.request().url()).pathname.endsWith(`/${idC}`)) {
      notifySlow()
      await slowReleased
    }
    await route.fulfill({ path: path.join(assets, fixture), contentType: "font/ttf" })
  })
  await context.addInitScript({ content: bootstrap({ value: choices }) })

  const errors = []
  const page = await context.newPage()
  page.on("pageerror", error => errors.push(error.message))
  await page.goto(`${ui}/settings?tab=appearance`)
  await page.getByRole("tab", { name: "Appearance", exact: true }).click()
  const settings = page.getByTestId("settings-screen")
  await page.waitForFunction(() => document.querySelector('[data-action="settings-ui-font"] [data-slot="select-v2-value-text"]')?.textContent === "Default")

  const choose = async (title, value) => {
    await settings.getByRole("group", { name: title, exact: true }).getByRole("button").click()
    await page.getByRole("option", { name: value, exact: true }).click()
  }
  await choose("UI Font", "Test Regular.ttf")
  await page.waitForFunction(id => JSON.parse(localStorage.getItem("settings.v3") || "{}").appearance?.sans === id, idA)
  await choose("Code Font", "Test Collection.ttc")
  await page.waitForFunction(id => JSON.parse(localStorage.getItem("settings.v3") || "{}").appearance?.mono === id, idB)
  await choose("Terminal Font", "Test Regular.ttf")
  await page.waitForFunction(id => JSON.parse(localStorage.getItem("settings.v3") || "{}").appearance?.terminal === id, idA)
  assert.equal(await page.evaluate(id => getComputedStyle(document.documentElement).getPropertyValue("--font-family-sans").includes(id), idA), true)
  assert.equal(await page.evaluate(id => getComputedStyle(document.documentElement).getPropertyValue("--font-family-mono").includes(id), idB), true)
  const rightEdges = await page.evaluate(() => [
    "settings-color-scheme",
    "settings-theme",
    "settings-ui-font",
    "settings-code-font",
    "settings-terminal-font",
  ].map(action => document.querySelector(`[data-action="${action}"] [data-component="select-v2"]`)?.getBoundingClientRect().right))
  assert.ok(rightEdges.every(value => value !== undefined) && Math.max(...rightEdges) - Math.min(...rightEdges) <= 1, JSON.stringify(rightEdges))
  const valueAlignments = await page.evaluate(() =>
    ["settings-ui-font", "settings-code-font", "settings-terminal-font"].map(action =>
      getComputedStyle(document.querySelector(`[data-action="${action}"] [data-slot="select-v2-value-text"]`)).textAlign,
    ),
  )
  assert.ok(valueAlignments.every(value => value === "end" || value === "right"), JSON.stringify(valueAlignments))

  // The last selection wins, even when the user returns to the current font while a large one is loading.
  await choose("UI Font", "Slow Font.ttf")
  await slowRequested
  await page.waitForFunction(() => document.querySelector('[data-action="settings-ui-font"] [data-slot="select-v2-value-text"]')?.textContent === "Slow Font.ttf")
  await choose("UI Font", "Test Regular.ttf")
  await page.waitForFunction(() => document.querySelector('[data-action="settings-ui-font"] [data-slot="select-v2-value-text"]')?.textContent === "Test Regular.ttf")
  releaseSlow()
  await page.waitForFunction(id => [...document.fonts].some(face => face.family === id && face.status === "loaded"), idC)
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem("settings.v3")).appearance.sans), idA)

  await settings.getByRole("group", { name: "UI Font", exact: true }).getByRole("button").click()
  const chooser = page.waitForEvent("filechooser")
  await page.getByRole("option", { name: "Import font file", exact: true }).click()
  await (await chooser).setFiles(path.join(assets, fixture))
  await page.waitForFunction(() => JSON.parse(localStorage.getItem("settings.v3") || "{}").appearance?.sans?.startsWith("ocimp-"))

  // A new page proves persisted system and imported selections register and load before settings is reopened.
  const restored = await context.newPage()
  restored.on("pageerror", error => errors.push(error.message))
  await restored.goto(`${ui}/settings?tab=appearance`)
  await restored.waitForFunction(([a, b]) => [...document.fonts].filter(face => face.status === "loaded" && (face.family === a || face.family === b)).length >= 2, [idA, idB])
  await restored.getByRole("tab", { name: "Appearance", exact: true }).click()
  await restored.waitForFunction(() => document.querySelector('[data-action="settings-ui-font"] [data-slot="select-v2-value-text"]')?.textContent?.includes("Roboto-Regular"))
  await restored.getByRole("group", { name: "UI Font", exact: true }).getByRole("button").click()
  await restored.getByRole("option", { name: "Default", exact: true }).click()
  await restored.waitForFunction(() => JSON.parse(localStorage.getItem("settings.v3") || "{}").appearance?.sans === "")
  await restored.waitForFunction(() => document.querySelector('[data-action="settings-ui-font"] [data-slot="select-v2-value-text"]')?.textContent === "Default")

  const session = await context.newPage()
  session.on("pageerror", error => errors.push(error.message))
  await session.goto(`${ui}/server/${base64url(backend.origin)}/session/${backend.sessionId}`)
  await session.getByRole("heading", { name: "verify-android-terminal", exact: true }).waitFor()
  await session.getByRole("textbox", { name: "Prompt", exact: true }).waitFor()
  await session.keyboard.press("Control+Backquote")
  await session.locator('[data-component="terminal"]').waitFor({ state: "visible" })
  await session.waitForFunction(id => window.__ocTerminalTest.instances.some(term => term.options.fontFamily.includes(id)), idA)

  const unsupported = await browser.newContext({ viewport: { width: 1163, height: 760 }, serviceWorkers: "block", locale: "en-US" })
  await unsupported.addInitScript({ content: bootstrap({ error: "System font enumeration requires Android 10 or newer" }) })
  const failure = await unsupported.newPage()
  failure.on("pageerror", error => errors.push(error.message))
  await failure.goto(`${ui}/settings?tab=appearance`)
  await failure.getByRole("tab", { name: "Appearance", exact: true }).click()
  await failure.getByRole("alert").waitFor()
  assert.match(await failure.getByRole("alert").innerText(), /requires Android 10 or newer/)
  assert.equal(await failure.locator('[data-action="settings-ui-font"] [data-component="select-v2"]').getAttribute("data-disabled"), "")
  assert.deepEqual(errors, [])
  console.log("PASS: Android font selection, switching race, terminal, reset, persistence and explicit errors")
} finally {
  releaseSlow?.()
  await browser?.close()
  for (const item of [server, backend.server]) {
    item.closeAllConnections?.()
    await new Promise(resolve => item.close(resolve))
  }
}
