import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFile } from "node:child_process"
import { promisify } from "node:util"
import { DevicePage } from "./android-webview-cdp.mjs"
import { startBackend, seedScript, base64url } from "./verify-android-terminal-release.mjs"

const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(root, "research/tmp/verify-android-resize-device", new Date().toISOString().replace(/[:.]/g, "-"))
fs.mkdirSync(output, { recursive: true })
const adbPath = process.env.ADB || "C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe"
const serial = process.env.ANDROID_SERIAL || "emulator-5636"
const cdpUrl = "http://127.0.0.1:9226"
const host = "http://127.0.0.1:4173"
const exec = promisify(execFile)
const adb = async args => (await exec(adbPath, ["-s", serial, ...args], { windowsHide: true, timeout: 20000, maxBuffer: 4 * 1024 * 1024 })).stdout.trim()
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function waitFor(fn, timeout = 20000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const result = await fn(); if (result) return result; await delay(150) }
  throw new Error("Android resize condition did not settle")
}
const report = { environment: "actual APK and Android WebView on emulator; real native bridge, adb touch input; mock backend only", serial, output, checks: [], errors: [] }
const record = (name, detail) => { report.checks.push({ name, status: "pass", detail }); console.log("PASS", name, JSON.stringify(detail)) }
let state, reversePort, original, page, originalStorage, reverseInstalled = false

// Each cleanup failure remains visible, while independent restoration continues.
async function attempt(name, run) {
  try { await run(); return true }
  catch (error) { report.checks.push({ name, status: "fail", error: error.stack }); console.error("FAIL", name, error.message); return false }
}

async function probe() {
  return page.evaluate(() => {
    const box = selector => { const el = [...document.querySelectorAll(selector)].find(el => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 }); const r = el?.getBoundingClientRect(); return r ? { x: r.x, y: r.y, width: r.width, height: r.height } : null }
    const hostInfo = window.__ocAndroidNative ? JSON.parse(window.__ocAndroidNative.hostInfo()) : null
    return { width: innerWidth, height: innerHeight, native: !!window.__ocAndroidNative, fakeBridge: !!window.__ocNativeCalls, nativeIdentity: hostInfo ? { appId: hostInfo.appId, scheme: hostInfo.scheme } : null, userAgent: navigator.userAgent,
      chat: box('[data-slot="session-chat-panel"]'), terminal: box('#terminal-panel'), horizontal: box('[data-slot="session-chat-panel"] [data-component="resize-handle"]'), vertical: box('[data-slot="session-side-panel-gap"] [data-component="resize-handle"], [data-slot="terminal-panel-presence"] [data-component="resize-handle"]'),
      styles: { select: document.body.style.userSelect, overflow: document.body.style.overflow }, events: window.__resizeEvents, hostError: window.__ocHostError ?? null }
  })
}
async function physicalPoint(point) {
  const targets = await fetch(cdpUrl + "/json").then(response => response.json())
  const target = targets.find(target => target.url?.startsWith(host))
  const bounds = JSON.parse(target.description)
  assert.ok(bounds.width > 0)
  const scale = bounds.width / (await probe()).width
  return { x: Math.round(bounds.screenX + point.x * scale), y: Math.round(bounds.screenY + point.y * scale) }
}
async function tap(selector) {
  const box = await waitFor(() => page.evaluate(`(() => {const el=document.querySelector(${JSON.stringify(selector)}); const r=el?.getBoundingClientRect(); return r?.width>0?{x:r.x+r.width/2,y:r.y+r.height/2}:null})()`))
  const point = await physicalPoint(box)
  await adb(["shell", "input", "tap", String(point.x), String(point.y)])
}
async function swipe(handle, dx, dy) {
  const from = { x: handle.x + handle.width / 2, y: handle.y + handle.height / 2 }
  const a = await physicalPoint(from)
  const b = await physicalPoint({ x: from.x + dx, y: from.y + dy })
  await adb(["shell", "input", "swipe", String(a.x), String(a.y), String(b.x), String(b.y), "600"])
  await delay(300)
}

try {
  state = await startBackend()
  state.workspace = "C:/workspace/native-resize"
  state.sessionId = "ses_native_resize"
  reversePort = new URL(state.origin).port
  original = { size: await adb(["shell", "wm", "size"]), density: await adb(["shell", "wm", "density"]), rotation: await adb(["shell", "settings", "get", "system", "user_rotation"]), accelerometer: await adb(["shell", "settings", "get", "system", "accelerometer_rotation"]) }
  report.originalDisplay = original
  const packageInfo = await adb(["shell", "dumpsys", "package", "com.xiufeigo.opencodeshell"])
  report.package = { appId: "com.xiufeigo.opencodeshell", versionName: packageInfo.match(/versionName=(\S+)/)?.[1], versionCode: packageInfo.match(/versionCode=(\d+)/)?.[1] }
  await adb(["shell", "wm", "size", "1600x1000"])
  await adb(["shell", "wm", "density", "160"])
  await adb(["shell", "settings", "put", "system", "accelerometer_rotation", "0"])
  await adb(["shell", "settings", "put", "system", "user_rotation", "0"])
  await adb(["shell", "am", "force-stop", "com.xiufeigo.opencodeshell"])
  await adb(["shell", "am", "start", "-n", "com.xiufeigo.opencodeshell/.web.WebHostActivity"])
  const pid = await waitFor(async () => (await adb(["shell", "ps", "-A"])).split(/\r?\n/).find(line => line.endsWith(" com.xiufeigo.opencodeshell"))?.trim().split(/\s+/)[1])
  await waitFor(async () => (await adb(["shell", "cat", "/proc/net/unix"])).includes("webview_devtools_remote_" + pid))
  await adb(["forward", "tcp:9226", "localabstract:webview_devtools_remote_" + pid])
  await adb(["reverse", "tcp:" + reversePort, "tcp:" + reversePort])
  reverseInstalled = true
  const target = await waitFor(async () => (await fetch(cdpUrl + "/json").then(response => response.json())).find(target => target.type === "page" && target.url?.startsWith(host)))
  const socket = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }) })
  page = new DevicePage(socket, waitFor)
  await page.send("Page.enable")
  await page.send("Runtime.enable")
  page.on("pageerror", error => report.errors.push(String(error)))
  page.on("console", message => { if (message.type() === "error") report.errors.push(message.text()) })
  originalStorage = await page.evaluate(() => Object.fromEntries(Object.keys(localStorage).filter(key => /server|tabs\.|layout|opencode-color-scheme|opencode-theme-id|tabPlusCreatesSession/.test(key)).map(key => [key, localStorage.getItem(key)])))
  await page.evaluate(seedScript({ serverOrigin: state.origin, workspace: state.workspace, sessionId: state.sessionId }))
  const route = `${host}/server/${base64url(state.origin)}/session/${state.sessionId}`
  await page.goto(route)
  await waitFor(() => page.evaluate(() => !!document.querySelector('button[aria-controls="review-panel"]')))
  if (await page.evaluate(() => document.querySelector('button[aria-controls="review-panel"]').getAttribute("aria-expanded")) !== "true") await tap('button[aria-controls="review-panel"]')
  await waitFor(async () => (await probe()).horizontal)
  await page.evaluate(() => {
    window.__resizeEvents = []
    for (const type of ["pointerdown", "pointermove", "pointerup", "pointercancel"]) document.addEventListener(type, event => {
      if (event.target instanceof Element && event.target.closest('[data-component="resize-handle"]')) window.__resizeEvents.push({ type, trusted: event.isTrusted, pointer: event.pointerType, x: event.clientX, y: event.clientY })
    }, true)
  })
  await delay(700)
  const initial = await probe()
  assert.ok(initial.native && !initial.fakeBridge && initial.width >= 1500)
  assert.deepEqual(initial.nativeIdentity, { appId: "com.xiufeigo.opencodeshell", scheme: "WebHostSkeleton/1" })
  record("actual-native-tablet-webview", { pid, width: initial.width, height: initial.height, nativeIdentity: initial.nativeIdentity, userAgent: initial.userAgent })
  for (const delta of [65, -65]) {
    const before = await probe()
    await swipe(before.horizontal, delta, 0)
    const after = await probe()
    report.checks.push({ name: "adb-width-measurement", status: "info", detail: { before, after, requested: delta } })
    assert.ok(Math.abs(after.chat.width - before.chat.width - delta) < 8, `width delta=${after.chat.width - before.chat.width}, swipe=${delta}`)
    assert.deepEqual(after.styles, { select: "", overflow: "" })
    record("adb-touch-chat-width", { before: before.chat.width, after: after.chat.width })
  }
  await page.send("Input.dispatchKeyEvent", { type: "rawKeyDown", key: "`", code: "Backquote", windowsVirtualKeyCode: 192, modifiers: 2 })
  await page.send("Input.dispatchKeyEvent", { type: "keyUp", key: "`", code: "Backquote", windowsVirtualKeyCode: 192, modifiers: 2 })
  await waitFor(async () => (await probe()).vertical)
  await delay(700)
  const imeVisible = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--oc-ime-visible").trim() === "1")
  if (imeVisible) await adb(["shell", "input", "keyevent", "4"])
  await waitFor(async () => (await probe()).height >= 950)
  await delay(250)
  for (const delta of [-75, 75]) {
    const before = await probe()
    await swipe(before.vertical, 0, delta)
    const after = await probe()
    const change = after.terminal.height - before.terminal.height
    assert.ok(Math.sign(change) === -Math.sign(delta) && Math.abs(change) > 50 && Math.abs(change) < 85, `height delta=${change}, swipe=${delta}`)
    assert.deepEqual(after.styles, { select: "", overflow: "" })
    record("adb-touch-terminal-height", { before: before.terminal.height, after: after.terminal.height })
  }
  const beforeReload = await probe()
  await page.goto(route)
  await waitFor(async () => (await probe()).horizontal && (await probe()).vertical)
  const afterReload = await probe()
  assert.ok(Math.abs(afterReload.chat.width - beforeReload.chat.width) < 2 && Math.abs(afterReload.terminal.height - beforeReload.terminal.height) < 2)
  record("native-dimensions-persist", { chat: afterReload.chat.width, terminal: afterReload.terminal.height })
  assert.ok(beforeReload.events.some(event => event.type === "pointermove" && event.pointer === "touch" && event.trusted))
  assert.equal(beforeReload.events.filter(event => event.type === "pointercancel").length, 0)
  assert.equal(afterReload.hostError, null)
  assert.deepEqual(report.errors, [])
  record("trusted-touch-and-no-runtime-errors", { events: beforeReload.events.length, errors: report.errors })
  await adb(["shell", "screencap", "-p", "/sdcard/Download/oc-resize-native.png"])
  await adb(["pull", "/sdcard/Download/oc-resize-native.png", path.join(output, "tablet-native.png")])
} catch (error) {
  report.checks.push({ name: "native-resize", status: "fail", error: error.stack })
  console.error(error.stack)
  if (page) await attempt("failure-probe", async () => { report.failurePage = await probe() })
} finally {
  if (page && originalStorage) await attempt("restore-storage", () => page.evaluate(`(() => { const previous=${JSON.stringify(originalStorage)}; for(const key of Object.keys(localStorage).filter(key=>/server|tabs\\.|layout|opencode-color-scheme|opencode-theme-id|tabPlusCreatesSession/.test(key))) { if(Object.hasOwn(previous,key)) localStorage.setItem(key,previous[key]); else localStorage.removeItem(key) } })()`))
  if (page) await attempt("detach-cdp", () => page.detach())
  if (original) {
    const restored = []
    restored.push(await attempt("restore-size", () => adb(["shell", "wm", "size", original.size.match(/Override size: (\d+x\d+)/)?.[1] || "reset"])))
    restored.push(await attempt("restore-density", () => adb(["shell", "wm", "density", original.density.match(/Override density: (\d+)/)?.[1] || "reset"])))
    for (const [key, value] of [["user_rotation", original.rotation], ["accelerometer_rotation", original.accelerometer]]) restored.push(await attempt("restore-" + key, () => adb(["shell", "settings", value === "null" ? "delete" : "put", "system", key, ...(value === "null" ? [] : [value])])))
    report.displayRestored = restored.every(Boolean)
  }
  if (reverseInstalled) await attempt("remove-backend-reverse", () => adb(["reverse", "--remove", "tcp:" + reversePort]))
  if (state) await attempt("close-backend", async () => {
    for (const socket of state.sockets.values()) socket.socket.destroy()
    state.server.closeAllConnections()
    await new Promise(resolve => state.server.close(resolve))
  })
  report.totals = { pass: report.checks.filter(check => check.status === "pass").length, fail: report.checks.filter(check => check.status === "fail").length }
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify(report, null, 2))
  console.log("REPORT", output, JSON.stringify(report.totals))
  process.exitCode = report.totals.fail ? 1 : 0
}
