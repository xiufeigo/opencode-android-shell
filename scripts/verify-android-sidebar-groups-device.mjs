import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"
import { startBackend, base64url } from "./verify-android-terminal-release.mjs"

// Actual-tablet verification for multi-select side-tab grouping, inbox server
// labels, and touch dismissal of context menus. Uses the debug product APK so
// the real Android WebView exposes its CDP socket; assets are the packaged ones.
const serial = process.env.ANDROID_SERIAL || "emulator-5554"
const adb = process.env.ADB || "adb"
const app = process.env.ANDROID_APP_ID || "com.xiufeigo.opencodeshell.debug"
const component = `${app}/com.xiufeigo.opencodeshell.web.WebHostActivity`
const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(
  root,
  "research/tmp/verify-android-sidebar-groups",
  new Date().toISOString().replace(/[:.]/g, "-") + "-" + crypto.randomBytes(2).toString("hex"),
)
fs.mkdirSync(output, { recursive: true })
const { _android } = await import(
  process.env.VERIFY_TERMINAL_PLAYWRIGHT ||
    new URL("../_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs", import.meta.url).href
)
const native = (args) => execFileSync(adb, ["-s", serial, ...args], { encoding: "utf8" })
const visible = (locator) => locator.filter({ visible: true })
const checks = []
const errors = []
const consoleErrors = []
const pass = (name) => {
  checks.push(name)
  console.log("PASS actual tablet", name)
}

const alpha = await startBackend()
const beta = await startBackend()
alpha.workspace = "C:\\workspace\\alpha-project"
beta.workspace = "C:\\workspace\\beta-project"
const backends = [alpha, beta]
const ports = backends.map((server) => new URL(server.origin).port)
const alphaExtra = "ses_alpha_extra"
const betaExtra = "ses_beta_extra"

let device
let devices = []
let page
let cdp
const keepAlive = setInterval(() => {}, 1_000)

async function attach() {
  const deadline = Date.now() + 30_000
  let last
  while (Date.now() < deadline) {
    try {
      const pid = native(["shell", "pidof", app]).trim()
      if (!/^\d+$/.test(pid)) throw new Error(`unexpected pid ${JSON.stringify(pid)}`)
      if (!device) {
        devices = await _android.devices()
        device = devices.find((item) => item.serial() === serial)
      }
      if (!device) throw new Error(`Android device ${serial} is unavailable`)
      const webview = await device.webView({ socketName: `webview_devtools_remote_${pid}` }, { timeout: 2_000 })
      const result = await webview.page()
      result.on("pageerror", (error) => errors.push(error.stack))
      result.on("console", (message) => {
        if (message.type() === "error") consoleErrors.push(message.text())
      })
      return result
    } catch (error) {
      last = error
      await new Promise((resolve) => setTimeout(resolve, 500))
    }
  }
  throw last ?? new Error("WebView did not become debuggable")
}

async function ensureChecked(item, wanted) {
  await item.waitFor()
  const checked = (await item.getAttribute("aria-checked")) === "true"
  if (checked !== wanted) await item.click()
  assert.equal((await item.getAttribute("aria-checked")) === "true", wanted)
}

function tabState() {
  return page.evaluate(() => {
    const groups = [...document.querySelectorAll("[data-tab-group]")]
    const rows = [...document.querySelectorAll("[data-titlebar-tab-slot]")]
    return {
      rowCount: rows.length,
      groupKeys: groups.map((el) => el.dataset.groupKey),
      groupLabels: [...document.querySelectorAll("[data-tab-group-header]")].map((el) => el.innerText.trim()),
      depths: groups.map((el) => el.style.paddingInlineStart),
      collapsed: groups.map((el) => el.dataset.collapsed !== undefined),
    }
  })
}

function blankInboxPoint(offset = 0) {
  return page.evaluate((shift) => {
    const box = document.querySelector('[data-slot="sidebar-inbox-scroll"]')
    if (!box) return undefined
    const rect = box.getBoundingClientRect()
    const x = rect.left + Math.min(48, Math.max(18, rect.width / 3)) + shift
    for (let y = rect.bottom - 18; y > rect.top + 18; y -= 18) {
      const el = document.elementFromPoint(x, y)
      if (el && box.contains(el) && !el.closest('[data-slot="sidebar-inbox-item"]')) return { x, y }
    }
    return undefined
  }, offset)
}

async function longPress(point, ms = 850) {
  cdp ??= await page.context().newCDPSession(page)
  await cdp.send("Input.dispatchTouchEvent", {
    type: "touchStart",
    touchPoints: [{ x: point.x, y: point.y, id: 1 }],
  })
  await page.waitForTimeout(ms)
  await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
}

async function tap(point) {
  try {
    await page.touchscreen.tap(point.x, point.y)
  } catch {
    cdp ??= await page.context().newCDPSession(page)
    await cdp.send("Input.dispatchTouchEvent", {
      type: "touchStart",
      touchPoints: [{ x: point.x, y: point.y, id: 1 }],
    })
    await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] })
  }
}

async function openContextAt(point, input) {
  if (input === "mouse") await page.mouse.click(point.x, point.y, { button: "right" })
  else await longPress(point)
  await visible(page.locator('[data-component="menu-v2-content"]')).first().waitFor({ timeout: 5_000 })
}

async function expectTouchDismisses(input) {
  const rowBox = await visible(page.locator('[data-slot="sidebar-inbox-item"]')).first().boundingBox()
  assert.ok(rowBox, "inbox row exists")
  const first = { x: rowBox.x + Math.min(48, rowBox.width / 2), y: rowBox.y + rowBox.height / 2 }
  const second = await blankInboxPoint(36)
  assert.ok(second, "second blank inbox point exists")
  await openContextAt(first, input)
  await tap(second)
  await page.waitForTimeout(1_050)
  assert.equal(await visible(page.locator('[data-component="menu-v2-content"]')).count(), 0)
}

try {
  for (const port of ports) native(["reverse", `tcp:${port}`, `tcp:${port}`])
  native(["shell", "settings", "put", "system", "screen_off_timeout", "1800000"])
  native(["shell", "input", "keyevent", "224"])
  for (const packageName of ["com.xiufeigo.opencodeshell", "com.xiufeigo.opencodeshell.debug"]) {
    native(["shell", "am", "force-stop", packageName])
  }
  native(["shell", "am", "start", "-W", "-n", component])
  page = await attach()

  await page.evaluate(
    ({ alphaUrl, betaUrl, alphaSession, betaSession, alphaExtra, betaExtra }) => {
      const server = {
        list: [
          { type: "http", http: { url: alphaUrl }, displayName: "Alpha Server" },
          { type: "http", http: { url: betaUrl }, displayName: "Beta Server" },
        ],
        hidden: {},
        lastProjectID: {},
        usedProjectIDs: {},
        hostnames: {},
        projects: {},
      }
      localStorage.clear()
      localStorage.setItem("opencode.global.dat:server", JSON.stringify(server))
      localStorage.setItem("opencode.global.dat:server.v3", JSON.stringify(server))
      localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "en" }))
      localStorage.setItem(
        "settings.v3",
        JSON.stringify({
          general: { tabGroups: true, sidebarOrganize: "list" },
          appearance: { tabLayout: "vertical", sidebarOpen: true },
        }),
      )
      const tabs = [
        { type: "session", server: alphaUrl, sessionId: alphaSession },
        { type: "session", server: alphaUrl, sessionId: alphaExtra },
        { type: "session", server: betaUrl, sessionId: betaSession },
        { type: "session", server: betaUrl, sessionId: betaExtra },
      ]
      localStorage.setItem("opencode.window.browser.dat:tabs", JSON.stringify(tabs))
      localStorage.setItem(
        "opencode.window.browser.dat:tabs.groups",
        JSON.stringify({
          mode: "server",
          dimensions: ["server"],
          groupOrder: [],
          collapsed: {},
          overrides: {},
          manualGroups: [],
          manualAssignments: {},
          colors: {},
        }),
      )
    },
    {
      alphaUrl: alpha.origin,
      betaUrl: beta.origin,
      alphaSession: alpha.sessionId,
      betaSession: beta.sessionId,
      alphaExtra,
      betaExtra,
    },
  )

  const route = `/server/${base64url(alpha.origin)}/session/${alpha.sessionId}`
  await page.evaluate((href) => {
    location.href = href
  }, route)
  await page.locator('[data-slot="vertical-tabs"]').waitFor({ timeout: 30_000 })
  await page.waitForFunction(() => document.querySelectorAll("[data-titlebar-tab-slot]").length === 4, undefined, {
    timeout: 30_000,
  })
  pass("debug product APK boots on Pixel Tablet and loads side tabs from two servers")

  const groupingTrigger = page.locator('[data-action="tab-groups-menu"]')
  await groupingTrigger.click({ force: true })
  const serverItem = page.getByRole("menuitemcheckbox", { name: "Server", exact: true })
  try {
    await serverItem.waitFor({ timeout: 1_500 })
  } catch {
    await groupingTrigger.press("Enter")
  }
  const projectItem = page.getByRole("menuitemcheckbox", { name: "Project", exact: true })
  await ensureChecked(serverItem, true)
  await ensureChecked(projectItem, true)
  pass("side tab grouping menu keeps Server and Project both selected without closing")
  await page.keyboard.press("Escape")

  await page.waitForFunction(
    () => {
      const keys = [...document.querySelectorAll("[data-tab-group]")].map((el) => el.dataset.groupKey || "")
      return keys.some((key) => key.startsWith("server:")) && keys.some((key) => key.includes("/project:"))
    },
    undefined,
    { timeout: 10_000 },
  )
  const grouped = await tabState()
  assert.equal(grouped.rowCount, 4)
  assert.equal(grouped.groupKeys.length, 4)
  assert.deepEqual(grouped.depths, ["", "14px", "", "14px"])
  assert.equal(grouped.groupKeys.filter((key) => key?.includes("/project:")).length, 2)
  assert.equal(grouped.groupLabels.some((label) => label.includes("Alpha Server")), true)
  assert.equal(grouped.groupLabels.some((label) => label.includes("alpha-project")), true)
  pass("side tabs nest project groups under their server with visual indentation")
  await page.screenshot({ path: path.join(output, "side-tabs-server-project-groups.png"), fullPage: false })

  const alphaHeader = visible(page.locator("[data-tab-group-header]").filter({ hasText: "Alpha Server" })).first()
  await alphaHeader.locator("button").first().click()
  await page.waitForFunction(() => document.querySelectorAll("[data-titlebar-tab-slot]").length === 2, undefined, {
    timeout: 5_000,
  })
  pass("collapsing a server folder hides its nested project tabs")
  await page.screenshot({ path: path.join(output, "side-tabs-alpha-collapsed.png"), fullPage: false })
  await alphaHeader.locator("button").first().click()
  await page.waitForFunction(() => document.querySelectorAll("[data-titlebar-tab-slot]").length === 4, undefined, {
    timeout: 5_000,
  })
  pass("expanding the server folder restores its nested project tabs")

  const projectHeader = visible(page.locator("[data-tab-group-header]").filter({ hasText: "alpha-project" })).first()
  await projectHeader.locator("button").first().click()
  await page.waitForFunction(() => document.querySelectorAll("[data-titlebar-tab-slot]").length === 2, undefined, {
    timeout: 5_000,
  })
  pass("collapsing a nested project folder hides only that project's tabs")
  await projectHeader.locator("button").first().click()
  await page.waitForFunction(() => document.querySelectorAll("[data-titlebar-tab-slot]").length === 4, undefined, {
    timeout: 5_000,
  })

  await page.evaluate(() => {
    const settings = JSON.parse(localStorage.getItem("settings.v3"))
    settings.appearance.tabLayout = "inbox"
    localStorage.setItem("settings.v3", JSON.stringify(settings))
  })
  await page.reload({ waitUntil: "domcontentloaded" })
  await page.locator('[data-slot="sidebar-inbox"]').waitFor({ timeout: 30_000 })
  await page.waitForFunction(() => document.querySelectorAll('[data-slot="sidebar-inbox-item"]').length >= 2, undefined, {
    timeout: 30_000,
  })
  const inboxText = await page.locator('[data-slot="sidebar-inbox"]').innerText()
  assert.equal(inboxText.includes("[Alpha Server : alpha-project]"), true)
  assert.equal(inboxText.includes("[Beta Server : beta-project]"), true)
  assert.equal(await page.locator('[data-action="inbox-groups-menu"]').count(), 0)
  pass("inbox uses the official ungrouped layout with server names before project directories")
  await page.screenshot({ path: path.join(output, "inbox-server-labels.png"), fullPage: false })

  await expectTouchDismisses("mouse")
  pass("touch outside closes a mouse-opened inbox context menu without reopening it at the touch point")
  await expectTouchDismisses("touch")
  pass("touch outside closes a long-press inbox context menu without reopening it at the touch point")

  assert.deepEqual(errors, [])
  pass("no WebView page exceptions")

  const report = {
    scope: "actual debug product APK, Pixel Tablet Android WebView, two fixture OpenCode backends",
    serial,
    viewport: await page.evaluate(() => ({ width: innerWidth, height: innerHeight })),
    checks,
    consoleErrors,
    errors,
  }
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify(report, null, 2))
  console.log("REPORT", output)
} catch (error) {
  if (page && !page.isClosed()) await page.screenshot({ path: path.join(output, "failure.png") })
  fs.writeFileSync(
    path.join(output, "failure.json"),
    JSON.stringify(
      {
        checks,
        error: error.stack,
        errors,
        consoleErrors,
        text: page && !page.isClosed() ? await page.locator("body").innerText() : undefined,
      },
      null,
      2,
    ),
  )
  throw error
} finally {
  try {
    native(["shell", "am", "force-stop", app])
    native(["shell", "am", "force-stop", "com.microsoft.playwright.androiddriver"])
    for (const item of devices) await item.close()
    for (const port of ports) native(["reverse", "--remove", `tcp:${port}`])
    for (const backend of backends) backend.server?.close()
    console.log("CLEANUP test Activity stopped")
  } finally {
    clearInterval(keepAlive)
  }
}
