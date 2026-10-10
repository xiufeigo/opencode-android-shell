import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"
import { createHash } from "node:crypto"
import { startBackend, startUiServer, nativeBridgeSource, ocHostLoaderSource, base64url } from "./verify-android-terminal-release.mjs"

const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(root, "research/tmp/verify-android-back", new Date().toISOString().replace(/[:.]/g, "-"))
fs.mkdirSync(output, { recursive: true })
const { chromium, _android } = await import(new URL("../plugins/server-browser/node_modules/playwright/index.mjs", import.meta.url).href)
const serial = process.env.ANDROID_SERIAL
const adb = process.env.ADB || "adb"
const app = "com.xiufeigo.opencodeshell"
const component = `${app}/${app}.web.WebHostActivity`
const native = args => execFileSync(adb, ["-s", serial, ...args], { encoding: "utf8" })
const backend = await startBackend()
backend.workspace = "C:\\workspace\\android-back-verification"
backend.sessionId = "ses_back_a"
const ids = ["ses_back_a", "ses_back_b", "ses_back_c"]
const checks = [], errors = []
const pass = (profile, name) => { checks.push({ profile, name }); console.log("PASS", profile, name) }
const visible = locator => locator.filter({ visible: true })
const button = (page, name) => visible(page.getByRole("button", { name, exact: true })).first()
const tabs = page => page.evaluate(() => JSON.parse(localStorage.getItem("opencode.window.browser.dat:tabs"))?.map(tab => tab.sessionId) ?? [])

function seed({ origin, ids }) {
  localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "en" }))
  localStorage.setItem("opencode.global.dat:server", JSON.stringify({ list: [{ type: "http", http: { url: origin } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }))
  localStorage.setItem("opencode.window.browser.dat:tabs", JSON.stringify(ids.map(sessionId => ({ type: "session", server: origin, sessionId }))))
  for (const key of ["tabs.recent", "tabs.closed", "tabs.panes"]) localStorage.removeItem(`opencode.window.browser.dat:${key}`)
}

async function exercise(page, profile, pressBack) {
  page.on("pageerror", error => errors.push({ profile, error: error.stack }))
  const mobile = await page.evaluate(() => innerWidth < 768)
  const slot = id => visible(page.locator("[data-titlebar-tab-slot]").filter({ has: page.locator(`a[href$="/session/${id}"]`) })).first()
  const showTabs = async () => {
    if (mobile && await page.locator('[data-slot="mobile-tabs-trigger"]').getAttribute("aria-expanded") !== "true") {
      await page.locator('[data-slot="mobile-drawer-content"][data-closing]').waitFor({ state: "hidden" })
      await page.locator('[data-slot="mobile-tabs-trigger"]').click()
      await visible(page.locator('[data-slot="mobile-drawer-content"]:not([data-closed]):not([data-closing])')).waitFor()
    }
    if (mobile) await page.waitForFunction(() => {
      const el = document.querySelector('[data-slot="mobile-drawer-content"][data-open]')
      return el && !el.hasAttribute("data-transitioning") && el.getBoundingClientRect().top < innerHeight
    })
  }
  const open = async id => {
    await showTabs()
    await slot(id).locator("a").click()
    await page.waitForURL(`**/session/${id}`)
    if (mobile) await page.locator('[data-slot="mobile-drawer-content"][data-closed]').waitFor({ state: "attached" })
  }
  const close = async id => {
    const active = page.url().endsWith(`/session/${id}`)
    await showTabs()
    await slot(id).locator('[data-slot="tab-close"] button').click()
    await page.waitForFunction(id => !JSON.parse(localStorage.getItem("opencode.window.browser.dat:tabs")).some(tab => tab.sessionId === id), id)
    if (mobile && active) await page.locator('[data-slot="mobile-drawer-content"][data-closed]').waitFor({ state: "attached" })
  }
  const home = () => page.waitForURL(url => url.pathname === "/")
  await button(page, "OpenCode menu").waitFor()
  assert.deepEqual(await tabs(page), ids)
  await open(ids[1])
  await open(ids[2])
  assert.ok(await page.evaluate(() => history.length) > 2)
  await close(ids[1])
  await close(ids[2])
  await page.waitForURL(`**/session/${ids[0]}`)
  await pressBack()
  await home()
  assert.deepEqual(await tabs(page), [ids[0]])
  pass(profile, "Back after closing two session tabs returns home without reopening either")

  await button(page, "OpenCode menu").click()
  await visible(page.getByRole("menu")).first().waitFor()
  await pressBack()
  await visible(page.getByRole("menu")).first().waitFor({ state: "hidden" })
  await home()
  pass(profile, "Back closes application menu and stays on home")

  await page.keyboard.press("Control+,")
  await visible(page.locator(".settings")).waitFor()
  await pressBack()
  await home()
  pass(profile, "Back leaves root settings for home")

  await open(ids[0])
  await page.keyboard.press("Control+Shift+p")
  await visible(page.getByRole("dialog")).first().waitFor()
  await pressBack()
  await visible(page.getByRole("dialog")).first().waitFor({ state: "hidden" })
  await page.locator('[data-component="dialog-overlay"]').waitFor({ state: "detached" })
  assert.ok(page.url().endsWith(`/session/${ids[0]}`))
  pass(profile, "Back dismisses command dialog without leaving its session")

  if (mobile) {
    await showTabs()
    await pressBack()
    await visible(page.locator('[data-slot="mobile-tabs-drawer"]')).waitFor({ state: "hidden" })
    assert.ok(page.url().endsWith(`/session/${ids[0]}`))
    pass(profile, "Back closes mobile session drawer without leaving its session")
  }
  await close(ids[0])
  await home()
  assert.deepEqual(await tabs(page), [])
  assert.equal(await page.evaluate(() => window.__ocAndroid.onBack()), false)
  pass(profile, "Closing last tab leaves home ready to exit despite retained WebView history")

  await page.keyboard.press("Control+Shift+t")
  await page.waitForURL(`**/session/${ids[0]}`)
  await page.waitForFunction(id => JSON.parse(localStorage.getItem("opencode.window.browser.dat:tabs")).some(tab => tab.sessionId === id), ids[0])
  assert.deepEqual(await tabs(page), [ids[0]])
  await close(ids[0])
  await home()
  pass(profile, "Only explicit Reopen Closed Tab restores a closed session")
  await page.screenshot({ path: path.join(output, `${profile}-closed-tabs-home.png`) })
}

let browser, device, ui, page
const contexts = []
const keepAlive = setInterval(() => {}, 1_000)
try {
  if (serial) {
    const apk = path.join(root, "webapp/build/outputs/apk/debug/webapp-debug.apk")
    native(["install", "-r", apk])
    const installed = native(["shell", "pm", "path", app]).trim().replace(/^package:/, "")
    assert.match(installed, /^\/data\/app\/[^\r\n]+\.apk$/)
    const hash = createHash("sha256").update(fs.readFileSync(apk)).digest("hex")
    assert.equal(native(["shell", "sha256sum", installed]).trim().split(/\s+/)[0], hash)
    pass("Android", "Installed APK bytes match the current debug build")
    native(["reverse", `tcp:${new URL(backend.origin).port}`, `tcp:${new URL(backend.origin).port}`])
    native(["shell", "input", "keyevent", "224"])
    native(["shell", "am", "start", "-W", "-n", component])
    device = (await _android.devices()).find(item => item.serial() === serial)
    if (!device) throw new Error("Selected isolated test device is unavailable")
    const attach = async () => {
      const pid = native(["shell", "pidof", app]).trim()
      assert.match(pid, /^\d+$/)
      return (await device.webView({ socketName: `webview_devtools_remote_${pid}` })).page()
    }
    page = await attach()
    await page.evaluate(seed, { origin: backend.origin, ids })
    await page.goto(`http://127.0.0.1:4173/server/${base64url(backend.origin)}/session/${ids[0]}`)
    const profile = await page.evaluate(() => innerWidth < 768 ? "Android-phone" : "Android-tablet")
    await exercise(page, profile, async () => { native(["shell", "input", "keyevent", "4"]) })
    const resumed = () => {
      const lines = native(["shell", "dumpsys", "activity", "activities"]).split(/\r?\n/).filter(line => /mResumedActivity|topResumedActivity/.test(line))
      assert.ok(lines.length, "Device does not report a recognizable resumed Activity")
      return lines
    }
    assert.ok(resumed().some(line => line.includes(app)))
    native(["shell", "input", "keyevent", "4"])
    const deadline = Date.now() + 10_000
    while (resumed().some(line => line.includes(app))) {
      if (Date.now() > deadline) throw new Error("Back at home did not leave the Android Activity")
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    pass(profile, "Real Android Back leaves the App")
    await device.close()
    device = (await _android.devices()).find(item => item.serial() === serial)
    if (!device) throw new Error("Selected test device disappeared after exit")
    native(["shell", "am", "start", "-W", "-n", component])
    page = await attach()
    await button(page, "OpenCode menu").waitFor()
    assert.deepEqual(await tabs(page), [])
    assert.equal(new URL(page.url()).pathname, "/")
    pass(profile, "Relaunch keeps all closed tabs closed")
    await device.close()
    native(["shell", "am", "force-stop", app])
    native(["shell", "am", "start", "-W", "-n", component])
    device = (await _android.devices()).find(item => item.serial() === serial)
    if (!device) throw new Error("Selected test device disappeared after process restart")
    page = await attach()
    await button(page, "OpenCode menu").waitFor()
    assert.deepEqual(await tabs(page), [])
    assert.equal(new URL(page.url()).pathname, "/")
    pass(profile, "Process restart rebinds the UI origin and preserves closed tabs")
  } else {
    ui = await startUiServer()
    browser = await chromium.launch({ headless: true })
    for (const profile of [{ name: "tablet", width: 1280, height: 900 }, { name: "phone", width: 411, height: 914 }]) {
      const context = await browser.newContext({ viewport: profile, hasTouch: true, isMobile: profile.name === "phone" })
      contexts.push(context)
      page = await context.newPage()
      await page.addInitScript({ content: nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
      await page.addInitScript(seed, { origin: backend.origin, ids })
      await page.goto(`http://127.0.0.1:${ui.address().port}/server/${base64url(backend.origin)}/session/${ids[0]}`)
      await exercise(page, profile.name, async () => assert.equal(await page.evaluate(() => window.__ocAndroid.onBack()), true))
    }
  }
  assert.deepEqual(errors, [])
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify({ scope: serial ? "actual Android debug APK, native system Back and packaged SPA; fixture backend" : "packaged SPA in Chromium with emulated Android host; fixture backend", serial, checks, errors }, null, 2))
  console.log("REPORT", output)
} catch (error) {
  if (page && !page.isClosed()) await page.screenshot({ path: path.join(output, "failure.png") })
  fs.writeFileSync(path.join(output, "failure.json"), JSON.stringify({ error: error.stack, checks, errors, url: page?.url() }, null, 2))
  if (page && !page.isClosed() && /^http/.test(page.url())) fs.writeFileSync(path.join(output, "state.json"), JSON.stringify(await page.evaluate(() => ({ text: document.body.innerText, layers: [...document.querySelectorAll('[role="menu"],[role="dialog"],[aria-expanded="true"]')].map(el => ({ html: el.outerHTML.slice(0, 800), rect: el.getBoundingClientRect().toJSON(), visibility: getComputedStyle(el).visibility, opacity: getComputedStyle(el).opacity, excluded: el.closest("[aria-hidden='true'],[inert],[data-closed]")?.outerHTML.slice(0, 180) })) })), null, 2))
  throw error
} finally {
  clearInterval(keepAlive)
  for (const context of contexts) await context.close()
  if (device) await device.close()
  if (browser) await browser.close()
  if (serial) native(["reverse", "--remove", `tcp:${new URL(backend.origin).port}`])
  for (const server of [backend.server, ui].filter(Boolean)) {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
  }
}
