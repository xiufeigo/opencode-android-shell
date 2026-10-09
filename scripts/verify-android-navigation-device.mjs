import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"
import { startBackend } from "./verify-android-terminal-release.mjs"

// Run only on an explicitly selected test device with the debug product APK.
// The script changes that app's test state; it never clears Android app data.
const serial = process.env.ANDROID_SERIAL
if (!serial) throw new Error("Set ANDROID_SERIAL to your isolated Android test device")
const adb = process.env.ADB || "adb"
const component = "com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.web.WebHostActivity"
const app = component.split("/")[0]
const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(root, "research/tmp/verify-android-navigation", new Date().toISOString().replace(/[:.]/g, "-") + "-device")
fs.mkdirSync(output, { recursive: true })
const { _android } = await import(process.env.VERIFY_TERMINAL_PLAYWRIGHT || new URL("../_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs", import.meta.url).href)
const native = args => execFileSync(adb, ["-s", serial, ...args], { encoding: "utf8" })
const visible = locator => locator.filter({ visible: true })
const button = (page, name) => visible(page.getByRole("button", { name, exact: true })).first()
const checks = []
const pass = name => { checks.push(name); console.log("PASS actual Android", name) }
const servers = await Promise.all(Array.from({ length: 5 }, () => startBackend()))
servers.forEach((server, i) => { server.workspace = `C:\\workspace\\android-navigation-${i}`; server.sessionId = `ses_android_navigation_${i}` })
const ports = servers.map(server => new URL(server.origin).port)
let context
let device
let page
const errors = []
const networkErrors = []
const consoleErrors = []
async function attach() {
  const pid = native(["shell", "pidof", app]).trim()
  if (!/^\d+$/.test(pid)) throw new Error("Test APK is not running")
  device ??= (await _android.devices()).find(item => item.serial() === serial)
  if (!device) throw new Error("Selected Android test device is missing")
  const page = await (await device.webView({ socketName: `webview_devtools_remote_${pid}` }, { timeout: 20_000 })).page()
  context = page.context()
  page.on("pageerror", error => errors.push(error.stack))
  page.on("requestfailed", request => networkErrors.push({ url: request.url(), error: request.failure()?.errorText }))
  page.on("console", message => { if (message.type() === "error") consoleErrors.push(message.text()) })
  return page
}
async function settings(page) {
  await page.keyboard.press("Control+,")
  await visible(page.locator(".settings")).waitFor()
  if (await button(page, "Back to settings").isVisible()) await button(page, "Back to settings").click()
}
async function add(page, server, name) {
  await button(page, "Add server").click()
  const dialog = page.getByRole("dialog")
  await dialog.getByRole("button", { name: "Use password", exact: true }).click()
  await dialog.locator("#dialog-server-url").fill(server.origin)
  await dialog.locator("#dialog-server-name").fill(name)
  await dialog.getByRole("button", { name: "Add server", exact: true }).click()
  await dialog.waitFor({ state: "hidden" })
  await button(page, "Back to settings").waitFor()
  await page.locator(".settings-tab-title").filter({ hasText: name }).waitFor()
  await button(page, "Add server").waitFor()
}
const registry = page => page.evaluate(() => JSON.parse(localStorage.getItem("opencode.global.dat:server")))
async function waitForSelectionOnDisk(server) {
  // WebView commits localStorage asynchronously after the JavaScript write.
  // Force-stop only after this fresh test selection reaches the LevelDB log.
  const marker = `"selection":{"server":"${server.origin}"`
  const markers = [marker, JSON.stringify(marker).slice(1, -1)]
  const deadline = Date.now() + 15_000
  while (Date.now() < deadline) {
    const bytes = execFileSync(adb, ["-s", serial, "exec-out", "run-as", app, "cat", "app_webview/Default/Local Storage/leveldb/000003.log"])
    if (bytes.toString().startsWith("cat:")) throw new Error(bytes.toString())
    if (markers.some(value => bytes.includes(Buffer.from(value)) || bytes.includes(Buffer.from(value, "utf16le")))) return
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  throw new Error("Selected server did not reach WebView localStorage on disk")
}
try {
  for (const port of ports) native(["reverse", `tcp:${port}`, `tcp:${port}`])
  native(["shell", "settings", "put", "system", "screen_off_timeout", "1800000"])
  native(["shell", "input", "keyevent", "224"])
  native(["shell", "am", "start", "-W", "-n", component])
  page = await attach()
  await button(page, "Use password").click()
  await page.locator("#server-connect-url").fill(servers[0].origin)
  await button(page, "Connect").click()
  await page.locator('[data-component="home-projects-dropdown"]').waitFor()
  await settings(page)
  await page.locator(".settings-mobile-menu-trigger").click()
  await page.getByRole("menuitemradio", { name: "Server", exact: true }).click()
  for (let i = 1; i < servers.length; i++) await add(page, servers[i], `Device server ${i + 1}`)
  assert.equal((await registry(page)).list.length, 5)
  pass("five servers added continuously in packaged WebView")
  await page.screenshot({ path: path.join(output, "phone-nested-add.png") })

  await settings(page)
  await page.locator(".settings-mobile-menu-trigger").click()
  for (let i = 1; i < servers.length; i++) await page.getByRole("menuitemradio", { name: `Device server ${i + 1}`, exact: true }).waitFor()
  await page.keyboard.press("Escape")
  await page.screenshot({ path: path.join(output, "phone-server-registry.png") })
  await button(page, "Back to app").click()
  await page.locator('[data-component="home-projects-dropdown"]').click()
  const row = visible(page.locator("[data-home-row]")).filter({ hasText: "Device server 4" })
  await row.getByRole("button").first().click()
  await page.locator('[data-component="home-projects-dropdown"]').click()
  await row.locator(":scope[data-selected]").waitFor()
  await page.keyboard.press("Escape")
  await page.waitForFunction(url => JSON.parse(localStorage.getItem("opencode.global.dat:layout"))?.home?.selection?.server === url, servers[3].origin)
  await waitForSelectionOnDisk(servers[3])
  await context.close()
  context = undefined
  native(["shell", "am", "force-stop", app])
  native(["shell", "am", "start", "-W", "-n", component])
  page = await attach()
  await page.locator('[data-component="home-projects-dropdown"]').waitFor()
  assert.equal((await registry(page)).list.length, 5)
  await page.locator('[data-component="home-projects-dropdown"]').click()
  await visible(page.locator("[data-home-row]")).filter({ hasText: "Device server 4" }).locator(":scope[data-selected]").waitFor()
  await page.keyboard.press("Escape")
  pass("force-stop/relaunch restores five servers and selected server")
  assert.deepEqual(errors, [])
  pass("no WebView page exceptions")
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify({ scope: "actual debug APK and Android WebView; five fixture backends; UI driven through CDP", serial, checks, runtime: await page.evaluate(() => ({ userAgent: navigator.userAgent, width: innerWidth, height: innerHeight })), registry: await registry(page), errors }, null, 2))
  console.log("REPORT", output)
} catch (error) {
  const state = page && !page.isClosed() ? await page.evaluate(() => ({ text: document.body.innerText, width: innerWidth, height: innerHeight })) : undefined
  if (page && !page.isClosed()) await page.screenshot({ path: path.join(output, "failure.png") })
  fs.writeFileSync(path.join(output, "failure.json"), JSON.stringify({ checks, error: error.stack, errors, networkErrors, consoleErrors, hits: servers.map(server => server.hits), state }, null, 2))
  console.error("STATE", JSON.stringify(state))
  console.error("NETWORK", JSON.stringify({ networkErrors, consoleErrors, hits: servers.map(server => server.hits) }))
  throw error
} finally {
  if (context) await context.close()
  if (device) await device.close()
  for (const port of ports) native(["reverse", "--remove", `tcp:${port}`])
  for (const server of servers) { server.server.closeAllConnections(); await new Promise(resolve => server.server.close(resolve)) }
}
