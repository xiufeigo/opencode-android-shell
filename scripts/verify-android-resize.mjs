import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { startBackend, startUiServer, nativeBridgeSource, ocHostLoaderSource, seedScript, base64url } from "./verify-android-terminal-release.mjs"

const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(root, "research/tmp/verify-android-resize", new Date().toISOString().replace(/[:.]/g, "-"))
fs.mkdirSync(output, { recursive: true })
const report = { scope: "packaged SPA, trusted CDP touch and mouse input; emulated native bridge, not an Android device", checks: [], output }
let backend, uiServer, browser, page, client, horizontal
const errors = []
const visible = locator => locator.filter({ visible: true })
const button = name => visible(page.getByRole("button", { name, exact: true })).first()

async function cleanup(name, run) {
  try { await run() }
  catch (error) { report.checks.push({ name, status: "fail", error: error.stack }); console.error("FAIL", name, error.message) }
}

async function snapshot() {
  return page.evaluate(() => {
    const box = selector => { const el = document.querySelector(selector); const r = el?.getBoundingClientRect(); return r ? { width: r.width, height: r.height, x: r.x, y: r.y } : null }
    return { chat: box('[data-slot="session-chat-panel"]'), terminal: box('#terminal-panel'), body: { userSelect: document.body.style.userSelect, overflow: document.body.style.overflow }, storage: Object.fromEntries(Object.keys(localStorage).filter(key => /layout|tabs.panes/.test(key)).map(key => [key, JSON.parse(localStorage.getItem(key))])), events: window.__resizeEvents.slice(-20) }
  })
}

async function touch(type, points = []) {
  await client.send("Input.dispatchTouchEvent", { type, touchPoints: points.map(point => ({ ...point, radiusX: 8, radiusY: 8, force: 1 })), modifiers: 0 })
}

async function drag(handle, dx, dy, kind) {
  const box = await handle.boundingBox()
  assert.ok(box)
  const from = { x: box.x + box.width / 2, y: box.y + box.height / 2, id: 1 }
  if (kind === "touch") await touch("touchStart", [from])
  else { await page.mouse.move(from.x, from.y); await page.mouse.down() }
  for (let step = 1; step <= 10; step++) {
    const point = { x: from.x + dx * step / 10, y: from.y + dy * step / 10, id: 1 }
    if (kind === "touch") await touch("touchMove", [point])
    else await page.mouse.move(point.x, point.y)
    await new Promise(resolve => setTimeout(resolve, 16))
  }
  if (kind === "touch") await touch("touchEnd")
  else await page.mouse.up()
  await page.waitForTimeout(160)
}

async function check(name, run) {
  try {
    const detail = await run()
    report.checks.push({ name, status: "pass", detail })
    console.log("PASS", name, JSON.stringify(detail))
  } catch (error) {
    report.checks.push({ name, status: "fail", error: error.stack, detail: await snapshot() })
    console.error("FAIL", name, error.message)
  }
}

try {
  const { chromium } = await import(process.env.VERIFY_TERMINAL_PLAYWRIGHT || new URL("../_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs", import.meta.url).href)
  backend = await startBackend()
  backend.workspace = "C:\\workspace\\resize"
  backend.sessionId = "ses_resize_acceptance"
  uiServer = await startUiServer()
  const ui = `http://127.0.0.1:${uiServer.address().port}`
  browser = await chromium.launch({ executablePath: process.env.VERIFY_TERMINAL_CHROMIUM || "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe", headless: true })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, locale: "en-US", hasTouch: true, reducedMotion: "reduce" })
  page = await context.newPage()
  client = await context.newCDPSession(page)
  page.on("pageerror", error => errors.push(error.stack))
  await page.addInitScript({ content: seedScript({ serverOrigin: backend.origin, workspace: backend.workspace, sessionId: backend.sessionId }) + ";" + nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
  await page.addInitScript(() => {
    window.__resizeEvents = []
    for (const type of ["pointerdown", "pointermove", "pointerup", "pointercancel", "lostpointercapture"]) document.addEventListener(type, event => {
      if (!(event.target instanceof Element) || !event.target.closest('[data-component="resize-handle"]')) return
      window.__resizeEvents.push({ type, trusted: event.isTrusted, pointerType: event.pointerType, id: event.pointerId, primary: event.isPrimary })
    }, true)
  })
  horizontal = page.locator('[data-slot="session-chat-panel"] [data-component="resize-handle"][data-direction="horizontal"]')
  await page.goto(`${ui}/server/${base64url(backend.origin)}/session/${backend.sessionId}`, { waitUntil: "domcontentloaded" })
  await button("Toggle review").waitFor()
  if (await button("Toggle review").getAttribute("aria-expanded") !== "true") await button("Toggle review").click()
  await horizontal.waitFor()
  for (const kind of ["mouse", "touch"]) await check(kind + "-chat-width", async () => {
    const before = await snapshot()
    await drag(horizontal, kind === "mouse" ? 70 : -70, 0, kind)
    const after = await snapshot()
    const delta = after.chat.width - before.chat.width
    assert.ok(kind === "mouse" ? delta > 40 : delta < -40, `chat width delta=${delta}`)
    assert.deepEqual(after.body, { userSelect: "", overflow: "" })
    return { before: before.chat, after: after.chat, events: after.events }
  })
  await page.keyboard.press("Control+Backquote")
  await visible(page.locator("#terminal-panel")).waitFor()
  for (const kind of ["mouse", "touch"]) await check(kind + "-terminal-width", async () => {
    const before = await snapshot()
    await drag(horizontal, kind === "mouse" ? 70 : -70, 0, kind)
    const after = await snapshot()
    const delta = after.terminal.width - before.terminal.width
    assert.ok(kind === "mouse" ? delta < -40 : delta > 40, `terminal width delta=${delta}`)
    assert.deepEqual(after.body, { userSelect: "", overflow: "" })
    return { before: before.terminal, after: after.terminal, events: after.events }
  })
  await check("touch-target-and-primary-pointer", async () => {
    assert.equal(await horizontal.evaluate(element => getComputedStyle(element).touchAction), "none")
    const box = await horizontal.boundingBox()
    const first = { x: box.x + box.width / 2, y: box.y + 100, id: 1 }
    const second = { x: first.x + 20, y: first.y + 80, id: 2 }
    await touch("touchStart", [first])
    await touch("touchMove", [{ ...first, x: first.x + 20 }])
    const before = await snapshot()
    await touch("touchStart", [{ ...first, x: first.x + 20 }, second])
    await touch("touchMove", [{ ...first, x: first.x + 20 }, { ...second, x: second.x + 200 }])
    const secondary = await snapshot()
    assert.ok(Math.abs(secondary.chat.width - before.chat.width) < 2)
    await touch("touchMove", [{ ...first, x: first.x + 55 }, { ...second, x: second.x + 200 }])
    await touch("touchEnd")
    const after = await snapshot()
    assert.ok(after.chat.width - before.chat.width > 20)
    assert.deepEqual(after.body, { userSelect: "", overflow: "" })
    assert.ok(after.events.some(event => event.pointerType === "touch" && !event.primary && event.trusted))
    return { before: before.chat.width, secondary: secondary.chat.width, after: after.chat.width }
  })
  await check("touch-cancel-restores-styles-without-collapse", async () => {
    await page.evaluate(() => { document.body.style.userSelect = "text"; document.body.style.overflow = "clip" })
    const box = await horizontal.boundingBox()
    const point = { x: box.x + box.width / 2, y: box.y + box.height / 2, id: 1 }
    await touch("touchStart", [point])
    await touch("touchMove", [{ ...point, x: point.x + 80 }])
    await touch("touchCancel")
    const after = await snapshot()
    assert.deepEqual(after.body, { userSelect: "text", overflow: "clip" })
    assert.equal(await page.locator("#terminal-panel").isVisible(), true)
    assert.ok(after.events.some(event => event.type === "pointercancel" && event.trusted))
    await page.evaluate(() => { document.body.style.userSelect = ""; document.body.style.overflow = "" })
    return { terminalOpened: true, width: after.terminal.width, restored: after.body }
  })
  await check("terminal-width-limits", async () => {
    await drag(horizontal, 1400, 0, "touch")
    const narrowTerminal = await snapshot()
    assert.ok(narrowTerminal.terminal.width >= 315 && narrowTerminal.terminal.width < 335)
    await drag(horizontal, -1400, 0, "touch")
    const narrowChat = await snapshot()
    assert.ok(narrowChat.chat.width >= 315 && narrowChat.chat.width < 335)
    return { narrowTerminal: narrowTerminal.terminal.width, narrowChat: narrowChat.chat.width }
  })
  await check("dimensions-persist-after-reload", async () => {
    const before = await snapshot()
    const panes = before.storage["opencode.window.browser.dat:tabs.panes"]
    const saved = Object.values(panes)[0]
    assert.ok(Math.abs(saved.sessionWidth - before.chat.width) < 2)
    await page.reload({ waitUntil: "domcontentloaded" })
    await horizontal.waitFor()
    await visible(page.locator("#terminal-panel")).waitFor()
    await page.waitForTimeout(200)
    const after = await snapshot()
    assert.ok(Math.abs(after.chat.width - before.chat.width) < 2)
    assert.ok(Math.abs(after.terminal.width - before.terminal.width) < 2)
    return { saved, before: { chat: before.chat.width, terminal: before.terminal.width }, after: { chat: after.chat.width, terminal: after.terminal.width } }
  })
  await check("terminal-hide-and-reopen", async () => {
    await page.keyboard.press("Control+Backquote")
    await page.locator("#terminal-panel").waitFor({ state: "hidden" })
    await page.keyboard.press("Control+Backquote")
    await visible(page.locator("#terminal-panel")).waitFor()
    return { reopened: true, width: (await snapshot()).terminal.width }
  })
  await check("no-page-errors", async () => { assert.deepEqual(errors, []); assert.equal(await page.evaluate(() => window.__ocHostError ?? null), null); return { errors } })
  await page.screenshot({ path: path.join(output, "tablet-resize.png"), fullPage: true })
} catch (error) {
  report.checks.push({ name: "resize-runner", status: "fail", error: error.stack })
  console.error(error.stack)
} finally {
  if (page) await cleanup("final-snapshot", async () => { report.final = await snapshot() })
  if (browser) await cleanup("close-browser", () => browser.close())
  if (backend) for (const socket of backend.sockets.values()) socket.socket.destroy()
  for (const server of [backend?.server, uiServer].filter(Boolean)) await cleanup("close-server", async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) })
  report.totals = { pass: report.checks.filter(check => check.status === "pass").length, fail: report.checks.filter(check => check.status === "fail").length }
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify(report, null, 2))
  console.log("REPORT", output, JSON.stringify(report.totals))
  process.exitCode = report.totals.fail ? 1 : 0
}
