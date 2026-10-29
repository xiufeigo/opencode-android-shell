import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { execFileSync } from "node:child_process"
import { setTimeout as delay } from "node:timers/promises"
import { fileURLToPath } from "node:url"

// An explicitly selected isolated emulator, the actual APK, and an ordinary OpenCode server.
const serial = process.env.ANDROID_SERIAL
if (!serial?.startsWith("emulator-")) throw new Error("Select an isolated Android emulator with ANDROID_SERIAL")
const adb = process.env.ADB || "adb"
const config = JSON.parse(fs.readFileSync(process.env.TERMINAL_TEST_SERVER_CONFIG, "utf8"))
const upstream = new URL(config.url)
if (upstream.hostname !== "127.0.0.1") throw new Error("Use an isolated loopback OpenCode test server")
const root = new URL("../", import.meta.url)
const out = path.join(fileURLToPath(root), "research/tmp/verify-android-terminal-session-recovery", new Date().toISOString().replace(/[:.]/g, "-"))
fs.mkdirSync(out, { recursive: true })
const report = { environment: "actual Android emulator APK WebView + ordinary OpenCode server", checks: [], errors: [], evidence: {} }
const app = "com.xiufeigo.opencodeshell.debug"
const component = `${app}/com.xiufeigo.opencodeshell.web.WebHostActivity`
const native = args => execFileSync(adb, ["-s", serial, ...args], { encoding: "utf8", windowsHide: true, timeout: 20000 })
const auth = { authorization: `Basic ${Buffer.from(`opencode:${config.password}`).toString("base64")}` }
const { _android } = await import(new URL("../plugins/server-browser/node_modules/playwright/index.mjs", import.meta.url).href)
const sessions = []
const ptys = new Map()
const devices = []
const keepAlive = setInterval(() => {}, 1000)
let device, page, origin
let startRecord
let initialPtys
const save = () => fs.writeFileSync(path.join(out, "report.json"), JSON.stringify(report, null, 2))
const pass = name => { report.checks.push({ name, status: "pass" }); console.log("PASS", name); save() }
async function api(route, { method = "GET", body, directory } = {}) {
  const url = new URL(`/api/${route}`, config.url)
  if (directory) url.searchParams.set("location[directory]", directory)
  const response = await fetch(url, { method, headers: { ...auth, "content-type": "application/json" }, body: body && JSON.stringify(body) })
  if (!response.ok) throw new Error(`Test API ${method} ${route}: HTTP ${response.status}`)
  if (response.status === 204) return
  const text = await response.text()
  if (!text) return
  const value = JSON.parse(text)
  return value.data ?? value
}
async function until(fn, label, timeout = 30000) {
  const end = Date.now() + timeout
  do {
    const value = await fn()
    if (value) return value
    await delay(250)
  } while (Date.now() < end)
  throw new Error(`Timed out: ${label}`)
}
async function attach() {
  const pid = native(["shell", "pidof", app]).trim()
  assert.match(pid, /^\d+$/)
  if (!device) {
    devices.push(...await _android.devices())
    device = devices.find(item => item.serial() === serial)
  }
  if (!device) throw new Error("Selected emulator is unavailable")
  page = await (await device.webView({ socketName: `webview_devtools_remote_${pid}` })).page()
  page.on("pageerror", error => report.errors.push(error.message))
  origin = new URL(page.url()).origin
  return pid
}
async function terminal() {
  return page.evaluate(() => {
    const container = [...document.querySelectorAll("[data-oc-terminal-engine]")].find(el => {
      const r = el.getBoundingClientRect()
      const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)
      return el._ocTerminal && r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight && !!hit && el.contains(hit)
    })
    if (!container) return
    const buffer = container._ocTerminal.buffer.active
    const lines = Array.from({ length: buffer.length }, (_, i) => buffer.getLine(i)?.translateToString(true) ?? "")
    const id = container.closest('[id^="terminal-wrapper-"]')?.id.slice("terminal-wrapper-".length)
    return { id, text: lines.join("\n"), rows: container._ocTerminal.rows }
  })
}
async function openTerminal(session) {
  const server = Buffer.from(config.url).toString("base64url")
  await page.goto(`${origin}/server/${server}/session/${session.id}`, { waitUntil: "domcontentloaded" })
  await page.getByRole("button", { name: "OpenCode menu", exact: true }).filter({ visible: true }).first().waitFor()
  await until(() => page.locator('[role="tab"][data-value="session"], [contenteditable="true"]').count(), "session controls")
  if (await page.evaluate(() => innerWidth < 768)) {
    await page.locator('[role="tab"][data-value="more"]').filter({ visible: true }).first().click()
    await page.locator('[data-slot="session-mobile-view-options"]').getByRole("button", { name: "Terminal", exact: true }).click()
  } else if (!(await terminal())) await page.keyboard.press("Control+Backquote")
  const value = await until(async () => { const value = await terminal(); return value?.id && value.text.trim() && value }, "visible PTY with output")
  ptys.set(value.id, session.directory)
  await until(() => api(`pty/${value.id}`, { directory: session.directory }), "live remote PTY")
  return value
}
async function command(text) {
  const point = await page.evaluate(() => {
    const container = [...document.querySelectorAll("[data-oc-terminal-engine]")].find(el => el._ocTerminal && el.getBoundingClientRect().height > 0)
    if (!container) throw new Error("Visible terminal missing")
    const frame = container._ocTerminal.mobileFrame.getBoundingClientRect()
    return { x: frame.x + frame.width / 2, y: Math.min(innerHeight - 12, frame.bottom - 12), width: innerWidth }
  })
  const display = native(["shell", "wm", "size"])
  const physicalWidth = Number(display.match(/(?:Override|Physical) size: (\d+)x\d+/g).at(-1).match(/(\d+)x/)[1])
  const scale = physicalWidth / point.width
  native(["shell", "input", "tap", String(Math.round(point.x * scale)), String(Math.round(point.y * scale))])
  await until(() => page.evaluate(() => document.activeElement?.classList.contains("xterm-helper-textarea")), "native terminal focus")
  await page.keyboard.insertText(text)
  await page.keyboard.press("Enter")
}
try {
  assert.match(native(["emu", "avd", "name"]), /OC_Desktop_Parity_Phone|OC_Menu_Parity_Tablet/)
  assert.match(native(["shell", "dumpsys", "package", app]), /DEBUGGABLE/)
  native(["reverse", `tcp:${upstream.port}`, `tcp:${upstream.port}`])
  native(["shell", "am", "force-stop", app])
  native(["shell", "am", "start", "-W", "-n", component])
  await attach()
  for (const name of ["A", "B"]) {
    const session = await api("session", { method: "POST", body: { title: `Terminal recovery ${name}` } })
    assert.equal(typeof session.location?.directory, "string", "Session API must report its directory")
    sessions.push({ ...session, directory: session.location.directory })
  }
  assert.equal(sessions[0].directory, sessions[1].directory)
  initialPtys = new Set((await api("pty", { directory: sessions[0].directory })).map(pty => pty.id))
  await page.evaluate(config => {
    localStorage.setItem("opencode-language", "en")
    localStorage.setItem("opencode.global.dat:server", JSON.stringify({ list: [{ type: "http", http: { url: config.url, username: "opencode", password: config.password } }], hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {} }))
  }, config)
  const a1 = await openTerminal(sessions[0])
  await command("echo A_IDLE_TERMINAL")
  await until(async () => (await terminal())?.text.includes("A_IDLE_TERMINAL"), "A output")
  await page.keyboard.press("Control+Alt+t")
  const a2 = await until(async () => { const value = await terminal(); return value?.id && value.id !== a1.id && value }, "second A terminal")
  ptys.set(a2.id, sessions[0].directory)
  const marker = `RECOVERY_${Date.now()}`
  const filename = `.oc-terminal-${marker}.txt`
  startRecord = path.join(sessions[0].directory, filename)
  const code = `require('node:fs').appendFileSync('${filename}',process.pid+'\\n');let n=0;console.log('${marker}_START');const t=setInterval(()=>{console.log('${marker}_PROGRESS_'+(++n));if(n===90)clearInterval(t)},1000)`
  await command(`node -e "eval(Buffer.from('${Buffer.from(code).toString("base64")}','base64').toString())"`)
  await until(async () => (await terminal())?.text.includes(`${marker}_PROGRESS_2`), "running command")
  const originalRemote = await api(`pty/${a2.id}`, { directory: sessions[0].directory })
  const b = await openTerminal(sessions[1])
  assert.notEqual(b.id, a1.id)
  assert.notEqual(b.id, a2.id)
  await command("echo B_IDLE_TERMINAL")
  await until(async () => (await terminal())?.text.includes("B_IDLE_TERMINAL"), "B output")
  assert.ok(!(await terminal()).text.includes(`${marker}_START`))
  pass("same-directory sessions have independent PTYs and output")
  const back = await openTerminal(sessions[0])
  assert.equal(back.id, a2.id)
  assert.ok(back.text.includes(`${marker}_START`))
  pass("returning to session A restores its selected terminal")
  const progress = text => [...text.matchAll(new RegExp(`${marker}_PROGRESS_(\\d+)`, "g"))].map(match => Number(match[1]))
  const before = Math.max(...progress(back.text))
  const oldPid = native(["shell", "pidof", app]).trim()
  native(["shell", "am", "force-stop", app])
  const absent = execFileSync(adb, ["-s", serial, "shell", `pidof ${app} || true`], { encoding: "utf8", windowsHide: true }).trim()
  assert.equal(absent, "")
  await delay(6000)
  const detachedRemote = await api(`pty/${a2.id}`, { directory: sessions[0].directory })
  assert.equal(detachedRemote.pid, originalRemote.pid)
  assert.equal(detachedRemote.status, "running")
  pass("force-stopped App leaves the same remote PTY process running")
  native(["shell", "am", "start", "-W", "-n", component])
  const newPid = await attach()
  assert.notEqual(newPid, oldPid)
  const restored = await openTerminal(sessions[0])
  assert.equal(restored.id, a2.id)
  const after = Math.max(...progress(restored.text))
  assert.ok(after >= before + 5, `Progress must advance while App is dead: ${before} -> ${after}`)
  const starts = fs.readFileSync(startRecord, "utf8").trim().split("\n")
  assert.equal(starts.length, 1, "The real command process must start exactly once")
  assert.equal(restored.text.split(`${marker}_START`).length - 1, 1)
  const remoteAfter = await api(`pty/${a2.id}`, { directory: sessions[0].directory })
  assert.equal(remoteAfter.pid, originalRemote.pid)
  const list = await api("pty", { directory: sessions[0].directory })
  assert.deepEqual(list.map(pty => pty.id).filter(id => !initialPtys.has(id)).sort(), [...ptys.keys()].sort())
  report.evidence = { sessionIds: sessions.map(s => s.id), ptyIds: { a1: a1.id, a2: a2.id, b: b.id }, remotePid: remoteAfter.pid, appPids: { old: oldPid, cold: newPid }, progressBefore: before, progressAfter: after, startCount: 1, forceStoppedMilliseconds: 6000 }
  pass("cold App reconnects the same PTY and replays continuing output without rerunning the command")
  const restoredB = await openTerminal(sessions[1])
  assert.equal(restoredB.id, b.id)
  assert.ok(restoredB.text.includes("B_IDLE_TERMINAL"))
  assert.ok(!restoredB.text.includes(`${marker}_START`))
  pass("session B remains isolated after App cold start")
  assert.deepEqual(report.errors, [])
  report.status = "pass"
} catch (error) {
  report.status = "fail"
  report.errors.push(error.stack)
  if (page && !page.isClosed()) {
    report.terminalFailure = await terminal()
    report.diagnostics = await page.evaluate(() => ({ url: location.href, text: document.body.innerText, tabs: [...document.querySelectorAll('[role="tab"]')].map(el => ({ value: el.getAttribute("data-value"), selected: el.getAttribute("aria-selected"), text: el.textContent })), terminals: [...document.querySelectorAll('[data-oc-terminal-engine],[id^="terminal-wrapper-"]')].map(el => ({ id: el.id, engine: !!el._ocTerminal, rect: el.getBoundingClientRect().toJSON() })) }))
    await page.screenshot({ path: path.join(out, "failure.png") })
  }
  process.exitCode = 1
} finally {
  // A failed mount can create a server PTY before the UI exposes its ID.
  // This explicitly isolated test server's baseline keeps pre-existing PTYs safe.
  if (initialPtys) {
    try {
      const directory = sessions[0].directory
      for (const pty of await api("pty", { directory })) {
        if (!initialPtys.has(pty.id)) ptys.set(pty.id, directory)
      }
    } catch (error) { report.errors.push(`PTY discovery cleanup: ${error.message}`); process.exitCode = 1 }
  }
  for (const [id, directory] of ptys) {
    try { await api(`pty/${id}`, { method: "DELETE", directory }) } catch (error) { report.errors.push(`PTY cleanup: ${error.message}`); process.exitCode = 1 }
  }
  for (const session of sessions) {
    try { await api(`session/${session.id}`, { method: "DELETE" }) } catch (error) { report.errors.push(`Session cleanup: ${error.message}`); process.exitCode = 1 }
  }
  if (startRecord && fs.existsSync(startRecord)) fs.unlinkSync(startRecord)
  for (const item of devices) await item.close()
  native(["reverse", "--remove", `tcp:${upstream.port}`])
  clearInterval(keepAlive)
  if (report.errors.length) report.status = "fail"
  save()
  console.log(`${report.status}: ${report.checks.length} checks; report ${path.join(out, "report.json")}`)
}
