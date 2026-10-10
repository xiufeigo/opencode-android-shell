import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"

// Only an explicitly selected, isolated emulator and debug product APK.
// Real Android platform bridge and a plain OpenCode server;
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
const ports = [new URL(config.url).port]
try {
  for (const port of ports) native(["reverse", `tcp:${port}`, `tcp:${port}`])
  native(["shell", "settings", "put", "system", "screen_off_timeout", "1800000"])
  native(["shell", "input", "keyevent", "224"])
  native(["shell", "am", "force-stop", app])
  native(["shell", "am", "start", "-W", "-n", component])
  await attach()
  const installTrace = () => {
    window.__menuNativeTrace = []
    const native = window.__ocPlatform
    const post = native.postMessage.bind(native)
    const receive = native.onmessage
    native.postMessage = data => {
      const value = JSON.parse(data)
      window.__menuNativeTrace.push({ direction: "native-send", method: value.method, id: value.id })
      post(data)
    }
    native.onmessage = event => {
      const value = JSON.parse(event.data)
      window.__menuNativeTrace.push({ direction: "native-receive", id: value.id, kind: value.value === null ? "null" : typeof value.value, error: !!value.error })
      receive(event)
    }
  }
  const response = await fetch(`${config.url}/api/session`, { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify({ title: "Android menu device verification" }) })
  if (!response.ok) throw new Error(`Test session HTTP ${response.status}`)
  const value = await response.json()
  session = (value.data ?? value).id
  await page.evaluate(({ config, session }) => {
    localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "en" }))
    localStorage.setItem("opencode.global.dat:server", JSON.stringify({ list: [{ type: "http", http: { url: config.url, username: "opencode", password: config.password } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }))
    const server = btoa(config.url).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "")
    location.href = `/server/${server}/session/${session}`
  }, { config, session })
  await button(page, "OpenCode menu").waitFor()
  await select("File", "Settings")
  assert.equal(await page.locator('[data-action="settings-android-browser"]').count(), 0)
  assert.equal(await button(page, "Legacy browser connection").count(), 0)
  pass(`${profile} settings has no legacy browser pairing`)
  await button(page, "Back to app").click()
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
    await visible(page.locator("#review-panel").getByRole("button", { name: "Add tab", exact: true })).click()
    await page.getByRole("menuitem", { name: /^Browser\b/ }).waitFor()
    await page.getByRole("menuitem", { name: /^Open file\b/ }).click()
    await visible(page.locator('#review-panel input[aria-label="Filter files"]')).waitFor()
    pass("tablet plus opens the official remote workspace file picker")
  }
  if (profile === "phone") {
    const more = visible(page.getByText("More...", { exact: true }))
    if (await more.count()) {
      await more.click()
      await visible(page.getByText("Browser", { exact: true })).waitFor()
      await page.locator('[data-slot="mobile-drawer-overlay"][data-open]').click({ position: { x: 10, y: 10 } })
      await page.locator('[data-slot="mobile-drawer-overlay"][data-open]').waitFor({ state: "hidden" })
    }
  }
  const consumed = await page.evaluate(() => {
    const event = new KeyboardEvent("keydown", { key: "b", code: "KeyB", ctrlKey: true, shiftKey: true, bubbles: true, cancelable: true })
    document.dispatchEvent(event)
    return event.defaultPrevented
  })
  assert.equal(consumed, true)
  await visible(page.locator('[data-component="server-browser"]')).waitFor()
  assert.equal(await page.locator('[data-android-browser-surface]').count(), 0)
  assert.equal(await page.evaluate(() => window.__menuNativeTrace.some(entry => ["browserConfig", "configureBrowser"].includes(entry.method))), false)
  pass(`${profile} browser opens without legacy native pairing requests`)
  await page.screenshot({ path: path.join(output, `${profile}-server-browser.png`) })
  if (profile === "tablet") {
    await button(page, "OpenCode menu").click()
    await page.screenshot({ path: path.join(output, "tablet-application-menu.png") })
    await page.keyboard.press("Escape")
  }
  assert.deepEqual(errors, [])
  pass("no actual WebView page exceptions")
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify({ scope: "actual debug product APK, Android WebView, system clipboard/bars and OpenCode v2; DOM menu actions and server browser entry checks", serial, profile, viewport: await page.evaluate(() => ({ width: innerWidth, height: innerHeight })), checks, errors }, null, 2))
  console.log("REPORT", output)
} catch (error) {
  if (page && !page.isClosed()) await page.screenshot({ path: path.join(output, "failure.png") })
  fs.writeFileSync(path.join(output, "failure.json"), JSON.stringify({ checks, error: error.stack, errors, trace: page && !page.isClosed() ? await page.evaluate(() => window.__menuNativeTrace) : undefined, text: page && !page.isClosed() ? await page.locator("body").innerText() : undefined }, null, 2))
  throw error
} finally {
  try {
    // Android owns this WebView; Browser.close is not a supported host command.
    // Stop our test Activity before detaching the device transport.
    native(["shell", "am", "force-stop", app])
    // Playwright 1.59.1 marks a device closed before reading its driver,
    // leaving the instrumentation socket alive. Stop only our test driver.
    native(["shell", "am", "force-stop", "com.microsoft.playwright.androiddriver"])
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
