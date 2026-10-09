import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"

// Only an explicitly selected, isolated emulator and debug product APK.
// Real Android platform bridge, native pairing dialog and real browser companion;
// no bridge mocks. Credentials are read locally and never written to the report.
const serial = process.env.ANDROID_SERIAL
if (!serial) throw new Error("Set ANDROID_SERIAL to an isolated test emulator")
const profile = process.env.MENU_TEST_PROFILE
if (!["phone", "tablet"].includes(profile)) throw new Error("Set MENU_TEST_PROFILE to phone or tablet with a matching emulator viewport")
const adb = process.env.ADB || "adb"
const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(root, "research/tmp/verify-android-menus", new Date().toISOString().replace(/[:.]/g, "-") + "-device")
fs.mkdirSync(output, { recursive: true })
const config = JSON.parse(fs.readFileSync(process.env.MENU_TEST_SERVER_CONFIG, "utf8"))
const browserUrl = process.env.MENU_TEST_BROWSER_URL
const token = fs.readFileSync(process.env.MENU_TEST_BROWSER_TOKEN_FILE, "utf8").trim()
const auth = { authorization: `Basic ${Buffer.from(`opencode:${config.password}`).toString("base64")}` }
const { _android } = await import(new URL("../_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs", import.meta.url).href)
const app = "com.xiufeigo.opencodeshell"
const component = `${app}/${app}.web.WebHostActivity`
const native = args => execFileSync(adb, ["-s", serial, ...args], { encoding: "utf8" })
const checks = []
const errors = []
const pass = name => { checks.push(name); console.log("PASS actual Android", name) }
const visible = locator => locator.filter({ visible: true })
const button = (page, name) => visible(page.getByRole("button", { name, exact: true })).first()
let device, page, session
let devices = []
// Playwright's Android WebView wait uses unref'ed handles while a restarted
// process creates its debugger socket. Keep this bounded test alive until teardown.
const keepAlive = setInterval(() => {}, 1_000)
async function attach() {
  const pid = native(["shell", "pidof", app]).trim()
  assert.match(pid, /^\d+$/)
  if (!device) {
    devices = await _android.devices()
    device = devices.find(item => item.serial() === serial)
  }
  if (!device) throw new Error("Selected test emulator is missing")
  page = await (await device.webView({ socketName: `webview_devtools_remote_${pid}` })).page()
  page.on("pageerror", error => errors.push(error.stack))
}
const item = name => visible(page.getByRole("menuitem").filter({ has: page.getByText(name, { exact: true }) })).first()
async function select(group, name) {
  await button(page, "OpenCode menu").click()
  await item(group).click()
  await item(name).click()
}
const ports = [new URL(config.url).port, new URL(browserUrl).port]
try {
  for (const port of ports) native(["reverse", `tcp:${port}`, `tcp:${port}`])
  native(["shell", "settings", "put", "system", "screen_off_timeout", "1800000"])
  native(["shell", "input", "keyevent", "224"])
  native(["shell", "am", "force-stop", app])
  native(["shell", "am", "start", "-W", "-n", component])
  await attach()
  const installTrace = () => {
    window.__menuBrowserTrace = []
    const native = window.__ocPlatform
    const post = native.postMessage.bind(native)
    const receive = native.onmessage
    native.postMessage = data => {
      const value = JSON.parse(data)
      window.__menuBrowserTrace.push({ direction: "native-send", method: value.method, id: value.id })
      post(data)
    }
    native.onmessage = event => {
      const value = JSON.parse(event.data)
      window.__menuBrowserTrace.push({ direction: "native-receive", id: value.id, kind: value.value === null ? "null" : typeof value.value, error: !!value.error })
      receive(event)
    }
    const send = WebSocket.prototype.send
    WebSocket.prototype.send = function (data) {
      if (!this.__menuObserved) {
        this.__menuObserved = true
        this.addEventListener("message", event => {
          if (typeof event.data !== "string") return
          const value = JSON.parse(event.data)
          window.__menuBrowserTrace.push({ direction: "receive", type: value.type, ipc: value.ipc, name: value.name, error: !!value.error, reply: value.id })
        })
      }
      if (typeof data === "string") {
        const value = JSON.parse(data)
        window.__menuBrowserTrace.push({ direction: "send", method: value.method, ipc: value.input?.ipc, action: value.input?.method, command: value.input?.input?.command?.type })
      }
      return send.call(this, data)
    }
  }
  const response = await fetch(`${config.url}/api/session`, { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify({ title: "Android menu device verification" }) })
  if (!response.ok) throw new Error(`Test session HTTP ${response.status}`)
  const value = await response.json()
  session = (value.data ?? value).id
  await page.evaluate(({ config, session }) => {
    localStorage.setItem("opencode-language", "en")
    localStorage.setItem("opencode.global.dat:server", JSON.stringify({ list: [{ type: "http", http: { url: config.url, username: "opencode", password: config.password } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }))
    const server = btoa(config.url).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "")
    location.href = `/server/${server}/session/${session}`
  }, { config, session })
  await button(page, "OpenCode menu").waitFor()
  await page.evaluate(installTrace)
  if (profile === "phone") {
    assert.ok(await page.evaluate(() => innerWidth) < 768, "Phone profile needs a narrow emulator")
    await select("File", "Settings")
    await button(page, "Back to app").waitFor()
    await button(page, "Back to app").click()
    pass("phone official application menu opens settings")
    const editor = visible(page.locator('[contenteditable="true"]')).first()
    await editor.fill("real Android clipboard proof")
    await editor.press("Control+a")
    await select("Edit", "Copy")
    await select("Edit", "Delete")
    await editor.fill("replace this selection")
    await editor.press("Control+a")
    await select("Edit", "Paste")
    await page.waitForFunction(() => [...document.querySelectorAll('[contenteditable="true"]')].some(node => node.textContent === "real Android clipboard proof"))
    pass("phone native clipboard copy and paste preserve editor selection")
    await editor.fill("")
    native(["shell", "input", "keyevent", "111"])
    const waitBars = async visible => {
      const deadline = Date.now() + 15_000
      while (Date.now() < deadline) {
        const state = native(["shell", "dumpsys", "window"])
        if (new RegExp(`type=statusBars[^\\r\\n]*visible=${visible}`).test(state)) return
        await new Promise(resolve => setTimeout(resolve, 100))
      }
      throw new Error(`Android status bars did not become visible=${visible}`)
  }
  await waitBars(true)
  await select("View", "Toggle Full Screen")
  await waitBars(false)
  await page.screenshot({ path: path.join(output, "phone-fullscreen.png") })
  await select("View", "Toggle Full Screen")
  await waitBars(true)
  pass("phone native fullscreen hides and restores system bars")
  }
  if (profile === "tablet") {
    assert.ok(await page.evaluate(() => innerWidth) >= 768, "Tablet profile needs a wide emulator")
    await button(page, "Toggle review").waitFor()
    if (await button(page, "Toggle review").getAttribute("aria-expanded") !== "true") await button(page, "Toggle review").click()
    const plus = visible(page.locator("#review-panel").getByRole("button", { name: "Add tab", exact: true }))
    await plus.click()
    await item("Open file").click()
    await visible(page.locator('#review-panel input[aria-label="Filter files"]')).waitFor()
    pass("tablet official plus menu opens remote workspace file picker")
    // Remove any previous test pairing through the real native dialog before
    // verifying initial pairing from the plus menu.
    await select("File", "Settings")
    await button(page, "Connect browser service").click()
    await device.tap({ text: "断开", clazz: "android.widget.Button" })
    await button(page, "Back to app").click()
    await plus.click()
    await item("Browser").waitFor()
    await page.screenshot({ path: path.join(output, "tablet-plus-menu.png") })
    await item("Browser").click()
    await device.fill({ clazz: "android.widget.EditText", desc: "浏览器服务地址" }, browserUrl)
    await device.fill({ clazz: "android.widget.EditText", desc: "配对令牌" }, token)
    native(["shell", "input", "keyevent", "111"])
    await device.tap({ text: "连接", clazz: "android.widget.Button" })
    await page.waitForFunction(() => window.__menuBrowserTrace.some(entry => entry.direction === "native-receive" && entry.kind === "object" && !entry.error))
    await visible(page.locator('[data-slot="browser-address-input"]')).waitFor({ timeout: 60_000 })
    pass("tablet Browser menu pairs native dialog and opens real official browser pane")
    await page.screenshot({ path: path.join(output, "tablet-browser-menu.png") })
    await button(page, "OpenCode menu").click()
    await page.screenshot({ path: path.join(output, "tablet-application-menu.png") })
    await page.keyboard.press("Escape")
  }
  assert.deepEqual(errors, [])
  pass("no actual WebView page exceptions")
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify({ scope: "actual debug product APK, Android WebView, system clipboard/bars/pairing, real OpenCode v2 and Electron browser companion", serial, profile, viewport: await page.evaluate(() => ({ width: innerWidth, height: innerHeight })), checks, errors }, null, 2))
  console.log("REPORT", output)
} catch (error) {
  if (page && !page.isClosed()) await page.screenshot({ path: path.join(output, "failure.png") })
  fs.writeFileSync(path.join(output, "failure.json"), JSON.stringify({ checks, error: error.stack, errors, trace: page && !page.isClosed() ? await page.evaluate(() => window.__menuBrowserTrace) : undefined, text: page && !page.isClosed() ? await page.locator("body").innerText() : undefined }, null, 2))
  throw error
} finally {
  try {
    // Android owns this WebView; Browser.close is not a supported host command.
    // Stop our test Activity before detaching the device transport.
    native(["shell", "am", "force-stop", app])
    console.log("CLEANUP test Activity stopped")
    // Enumeration opens polling transports for every device, including those
    // not selected by this test. Release all of our connections.
    for (const item of devices) await item.close()
    console.log("CLEANUP device transports closed")
    for (const port of ports) native(["reverse", "--remove", `tcp:${port}`])
    if (session) {
      const response = await fetch(`${config.url}/api/session/${session}`, { method: "DELETE", headers: auth, signal: AbortSignal.timeout(30_000) })
      if (!response.ok) throw new Error(`Test session cleanup HTTP ${response.status}`)
    }
    console.log("CLEANUP test session removed")
  } finally { clearInterval(keepAlive) }
}
