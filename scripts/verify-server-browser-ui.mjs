import assert from "node:assert/strict"
import fs from "node:fs/promises"
import path from "node:path"
import http from "node:http"
import { execFileSync } from "node:child_process"
import { chromium, _android } from "../plugins/server-browser/node_modules/playwright/index.mjs"

const root = path.resolve(import.meta.dirname, "..")
const config = JSON.parse(await fs.readFile(path.join(root, "_scratch_root/server-browser-test/connection.json"), "utf8"))
const headers = { authorization: `Basic ${Buffer.from(`opencode:${config.password}`).toString("base64")}`, "content-type": "application/json" }
const serial = process.env.ANDROID_SERIAL
const out = path.join(root, "research/tmp/verify-server-browser-ui", `${new Date().toISOString().replace(/[:.]/g, "-")}-${serial ? "android" : "web"}`)
await fs.mkdir(out, { recursive: true })
const report = { environment: serial ? "actual Android APK WebView + installed server plugin" : "built shared Web UI + installed server plugin", checks: [], errors: [], commands: [] }
const save = () => fs.writeFile(path.join(out, "report.json"), JSON.stringify(report, null, 2))
const pass = async name => { report.checks.push(name); console.log("PASS", name); await save() }
const api = async (route, body) => {
  const url = new URL(`/api/${route}`, config.url)
  url.searchParams.set("location[directory]", config.directory)
  const response = await fetch(url, { method: body ? "POST" : "GET", headers, body: body && JSON.stringify(body) })
  if (!response.ok) throw new Error(`API ${route} HTTP ${response.status}: ${await response.text()}`)
  const value = await response.json()
  return value.data ?? value
}
const rpc = async (session, method, input) => (await api(`rpc/xiufeigo.server-browser/${method}`, { input: { sessionID: session.id, ...input } })).output
const fixture = http.createServer((request, response) => {
  response.setHeader("content-type", "text/html; charset=utf-8")
  response.end('<title>Shared browser fixture</title><style>body{font:20px sans-serif;padding:20px}input,button{font:inherit;padding:10px;margin:10px}</style><label>Name<input aria-label="Name"></label><button onclick="document.querySelector(\'output\').textContent=document.querySelector(\'input\').value">Submit</button><output>Ready</output><script>window.starts=1;setInterval(()=>document.body.dataset.progress=String(Number(document.body.dataset.progress||0)+1),100)</script>')
})
await new Promise(resolve => fixture.listen(0, "127.0.0.1", resolve))
let session, browser, peerBrowser, device, page, origin
const adb = process.env.ADB ?? "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
const native = args => execFileSync(adb, ["-s", serial, ...args], { encoding: "utf8", windowsHide: true, timeout: 20_000 })
const until = async (check, label) => {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) { const value = await check(); if (value) return value; await new Promise(resolve => setTimeout(resolve, 150)) }
  throw new Error(`Timed out: ${label}`)
}
const attachAndroid = async () => {
  const pid = native(["shell", "pidof", "com.xiufeigo.opencodeshell"]).trim()
  assert.match(pid, /^\d+$/)
  page = await (await device.webView({ socketName: `webview_devtools_remote_${pid}` })).page()
  return pid
}
const openPanel = async () => {
  if (await page.evaluate(() => innerWidth < 768)) {
    await page.locator('[role="tab"][data-value="more"]').filter({ visible: true }).click()
    await page.locator('[data-slot="session-mobile-view-options"]').getByRole("button", { name: /^(Browser|浏览器)$/ }).click()
  } else await page.keyboard.press("Control+Shift+b")
  await page.locator('[data-component="server-browser"]').filter({ visible: true }).waitFor()
}
try {
  session = await api("session", { title: `Shared browser ${serial ? "Android" : "Web"}` })
  if (serial) {
    assert.match(native(["emu", "avd", "name"]), /OC_Desktop_Parity_Phone|OC_Menu_Parity_Tablet/)
    native(["reverse", "tcp:54106", "tcp:54106"])
    native(["shell", "am", "force-stop", "com.xiufeigo.opencodeshell"])
    native(["shell", "am", "start", "-W", "-n", "com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.web.WebHostActivity"])
    device = (await _android.devices()).find(value => value.serial() === serial)
    assert.ok(device)
    await until(async () => { try { return await attachAndroid() } catch { return false } }, "APK WebView")
    origin = new URL(page.url()).origin
  } else {
    browser = await chromium.launch({ executablePath: process.env.SERVER_BROWSER_CHROMIUM })
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    origin = "http://127.0.0.1:4175"
    await page.goto(origin)
  }
  page.on("pageerror", error => report.errors.push(error.message))
  page.on("request", request => {
    if (!request.url().includes("/api/rpc/xiufeigo.server-browser/command")) return
    const action = request.postDataJSON()?.input?.action
    if (action) report.commands.push({ type: action.type, ...action.url ? { url: action.url } : {} })
  })
  await page.evaluate(config => {
    localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "en" }))
    localStorage.setItem("opencode.global.dat:server", JSON.stringify({ list: [{ type: "http", http: { url: config.url, username: "opencode", password: config.password } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }))
  }, config)
  const route = `${origin}/server/${Buffer.from(config.url).toString("base64url")}/session/${session.id}`
  await page.goto(route)
  await page.locator('[contenteditable="true"]').filter({ visible: true }).first().waitFor()
  await openPanel()
  assert.equal(await page.getByText("Connect built-in browser service", { exact: true }).count(), 0)
  await pass("Browser opens through the normal UI without gateway pairing")
  const address = page.locator('[data-component="server-browser"] .browser-address').filter({ visible: true })
  const navigationURL = `http://127.0.0.1:${fixture.address().port}/`
  await address.fill(navigationURL)
  assert.equal(await address.inputValue(), navigationURL)
  await address.press("Enter")
  await page.locator('[data-component="server-browser"] img').filter({ visible: true }).waitFor()
  await until(async () => (await rpc(session, "state", {})).tabs.some(tab => tab.title === "Shared browser fixture"), "remote tab loaded")
  await pass("Address bar navigates the server browser and displays actual remote pixels")
  const state = await rpc(session, "state", {})
  const tab = state.tabs.find(tab => tab.title === "Shared browser fixture")
  const duplicate = await rpc(session, "command", { requestID: crypto.randomUUID(), action: { type: "tabs.open", url: navigationURL } })
  const switchTab = async index => {
    const tabs = serial ? page.locator(".browser-mobile-tabs").getByRole("tab") : page.locator("#review-panel").getByRole("tab", { name: /Shared browser fixture/ })
    await tabs.nth(index).click()
    const id = index === 0 ? tab.id : duplicate.id
    await until(async () => (await rpc(session, "state", {})).focusedTabID === id, "selected browser tab")
  }
  await switchTab(1)
  await address.fill("https://draft.invalid/not-submitted")
  await switchTab(0)
  await until(async () => await address.inputValue() === navigationURL, "same-URL tabs keep address drafts separate")
  await rpc(session, "command", { requestID: crypto.randomUUID(), action: { type: "tabs.close", tabID: duplicate.id } })
  await page.locator('[data-component="server-browser"] img').filter({ visible: true }).waitFor()
  await pass("Switching between tabs at the same URL does not transfer an unsubmitted address draft")
  const remote = script => rpc(session, "command", { requestID: crypto.randomUUID(), action: { type: "evaluate", tabID: tab.id, script } }).then(value => value.value)
  const click = async (selector, client = page, nativeTouch = !!serial) => {
    const rect = await remote(`(()=>{const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`)
    const bounds = await client.locator('[data-component="server-browser"] img').filter({ visible: true }).boundingBox()
    const x = bounds.x + rect.x * bounds.width / tab.width
    const y = bounds.y + rect.y * bounds.height / tab.height
    if (!nativeTouch) { await client.mouse.click(x, y); return }
    const width = Number(native(["shell", "wm", "size"]).match(/(?:Override|Physical) size: (\d+)x\d+/g).at(-1).match(/(\d+)x/)[1])
    const scale = width / await page.evaluate(() => innerWidth)
    native(["shell", "input", "tap", String(Math.round(x * scale)), String(Math.round(y * scale))])
  }
  await click("input")
  await until(() => page.evaluate(() => document.activeElement?.classList.contains("browser-keyboard")), "page input keyboard focus")
  await page.keyboard.insertText("中文 shared")
  await until(async () => await remote("document.querySelector('input').value") === "中文 shared", "Chinese text on server")
  if (serial) { native(["shell", "input", "keyevent", "4"]); await new Promise(resolve => setTimeout(resolve, 400)) }
  await click("button")
  await until(async () => await remote("document.querySelector('output').textContent") === "中文 shared", "server button response")
  await pass("Visible page clicks and Chinese keyboard input reach the server page exactly once")
  await page.screenshot({ path: path.join(out, "browser.png") })
  const before = await remote("Number(document.body.dataset.progress)")
  if (serial) {
    const oldPID = native(["shell", "pidof", "com.xiufeigo.opencodeshell"]).trim()
    native(["shell", "am", "force-stop", "com.xiufeigo.opencodeshell"])
    await new Promise(resolve => setTimeout(resolve, 1500))
    native(["shell", "am", "start", "-W", "-n", "com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.web.WebHostActivity"])
    await until(async () => { try { return await attachAndroid() } catch { return false } }, "restarted APK")
    assert.notEqual(native(["shell", "pidof", "com.xiufeigo.opencodeshell"]).trim(), oldPID)
    await page.goto(route)
    await page.locator('[role="tab"][data-value="more"]').filter({ visible: true }).first().waitFor()
    await openPanel()
  } else await page.reload()
  await page.locator('[data-component="server-browser"] img').filter({ visible: true }).waitFor()
  const restored = await rpc(session, "state", {})
  assert.equal(restored.tabs.length, state.tabs.length)
  assert.ok(restored.tabs.some(value => value.id === tab.id))
  assert.equal(await remote("document.querySelector('output').textContent"), "中文 shared")
  assert.equal(await remote("window.starts"), 1)
  assert.ok(await remote("Number(document.body.dataset.progress)") > before)
  await pass("Cold client reconnect restores the same tab, input and running page without reload")
  if (serial) {
    peerBrowser = await chromium.launch({ executablePath: process.env.SERVER_BROWSER_CHROMIUM })
    const peer = await peerBrowser.newPage({ viewport: { width: 1440, height: 900 } })
    peer.on("pageerror", error => report.errors.push(error.message))
    const web = "http://127.0.0.1:4175"
    await peer.goto(web)
    await peer.evaluate(config => {
      localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "en" }))
      localStorage.setItem("opencode.global.dat:server", JSON.stringify({ list: [{ type: "http", http: { url: config.url, username: "opencode", password: config.password } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }))
    }, config)
    await peer.goto(`${web}/server/${Buffer.from(config.url).toString("base64url")}/session/${session.id}`)
    const review = peer.getByRole("button", { name: "Toggle review", exact: true }).filter({ visible: true })
    await review.waitFor()
    if (await review.getAttribute("aria-expanded") !== "true") await review.click()
    await peer.locator("#review-panel").getByRole("tab", { name: /Shared browser fixture/ }).click()
    await peer.locator('[data-component="server-browser"] img').filter({ visible: true }).waitFor()
    const oldImage = await page.locator('[data-component="server-browser"] img').filter({ visible: true }).getAttribute("src")
    await click("input", peer, false)
    await until(() => peer.evaluate(() => document.activeElement?.classList.contains("browser-keyboard")), "shared Web keyboard focus")
    await peer.keyboard.press("Control+a")
    await peer.keyboard.insertText("网页接续 Android")
    await until(async () => await remote("document.querySelector('input').value") === "网页接续 Android", "shared Web input on server")
    await click("button", peer, false)
    await until(async () => await remote("document.querySelector('output').textContent") === "网页接续 Android", "shared Web button response")
    await until(async () => await page.locator('[data-component="server-browser"] img').filter({ visible: true }).getAttribute("src") !== oldImage, "shared Android pixels update")
    const shared = await rpc(session, "state", {})
    assert.equal(shared.tabs.length, state.tabs.length)
    assert.equal(shared.tabs[0].id, tab.id)
    assert.equal(await remote("window.starts"), 1)
    await pass("Android and Web share one live tab; Web actions update Android without creating or reloading a page")
  }
  if (!serial) {
    const frames = "**/api/rpc/xiufeigo.server-browser/frame*"
    await page.route(frames, route => route.abort("failed"))
    await page.locator('[data-component="server-browser"] [role="alert"]').filter({ visible: true }).waitFor()
    await page.unroute(frames)
    const oldImage = await page.locator('[data-component="server-browser"] img').getAttribute("src")
    await remote("document.body.style.background='rgb(220,255,220)'")
    await page.locator('[data-component="server-browser"] [role="alert"]').getByRole("button", { name: /^(Reconnect|重新连接)$/ }).click()
    await until(async () => { const image = page.locator('[data-component="server-browser"] img').filter({ visible: true }); return await image.count() && await image.getAttribute("src") !== oldImage }, "frame subscription after reconnect")
    assert.equal(await remote("window.starts"), 1)
    await pass("Reconnect restarts failed frame requests without reloading the remote page")
  }
  assert.deepEqual(report.errors, [])
  await pass("No client page exceptions")
  await save()
} catch (error) {
  report.errors.push(String(error))
  await save()
  if (page) { await page.screenshot({ path: path.join(out, "failure.png") }).catch(() => {}); await fs.writeFile(path.join(out, "failure.html"), await page.content().catch(() => "")) }
  throw error
} finally {
  if (session) {
    const url = new URL(`/api/session/${session.id}`, config.url)
    url.searchParams.set("location[directory]", config.directory)
    await fetch(url, { method: "DELETE", headers })
  }
  await browser?.close()
  await peerBrowser?.close()
  await device?.close()
  await new Promise(resolve => fixture.close(resolve))
}
