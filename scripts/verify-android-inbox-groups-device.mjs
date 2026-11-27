import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"
import { startBackend } from "./verify-android-terminal-release.mjs"

// Actual-tablet verification for the inbox server/project grouping patch. This
// uses the release APK's real Android WebView and two fixture OpenCode backends.
const serial = process.env.ANDROID_SERIAL || "emulator-5554"
const adb = process.env.ADB || "adb"
// WebView debugging is intentionally compiled out of release. The debug APK is
// the same product/assets with a `.debug` applicationId suffix, which is what
// the repository's other on-device CDP verification scripts use.
const app = process.env.ANDROID_APP_ID || "com.xiufeigo.opencodeshell.debug"
const component = `${app}/com.xiufeigo.opencodeshell.web.WebHostActivity`
const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(
  root,
  "research/tmp/verify-android-inbox-groups",
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
const networkErrors = []
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

let device
let context
let page

async function attach() {
  const deadline = Date.now() + 30_000
  let last
  while (Date.now() < deadline) {
    try {
      const pid = native(["shell", "pidof", app]).trim()
      if (!/^\d+$/.test(pid)) throw new Error(`unexpected pid ${JSON.stringify(pid)}`)
      device ??= (await _android.devices()).find((item) => item.serial() === serial)
      if (!device) throw new Error(`Android device ${serial} is unavailable`)
      const webview = await device.webView({ socketName: `webview_devtools_remote_${pid}` }, { timeout: 2_000 })
      const result = await webview.page()
      result.on("pageerror", (error) => errors.push(error.stack))
      result.on("requestfailed", (request) =>
        networkErrors.push({ url: request.url(), error: request.failure()?.errorText }),
      )
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

async function groupingState() {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('[data-slot="sidebar-inbox-item"]')]
    const groups = [...document.querySelectorAll("[data-tab-group]")]
    const serverGroups = groups.filter(
      (el) => el.dataset.groupKey?.includes("/server:") && !el.dataset.groupKey.includes("/project:"),
    )
    return {
      rowCount: rows.length,
      rowText: rows.map((row) => row.innerText),
      avatars: rows.map((row) => row.querySelector('[data-slot="project-avatar-surface"]') !== null),
      serverIcons: rows.map((row) => row.querySelector("svg[aria-label]")?.getAttribute("aria-label") ?? null),
      groupKeys: groups.map((el) => el.dataset.groupKey),
      groupLabels: [...document.querySelectorAll("[data-tab-group-header]")].map((el) => el.innerText.trim()),
      nested: serverGroups.map((el) =>
        [...el.querySelectorAll(':scope > [data-tab-group-items] > [data-tab-group]')].map(
          (child) => child.dataset.groupKey,
        ),
      ),
    }
  })
}

try {
  for (const port of ports) native(["reverse", `tcp:${port}`, `tcp:${port}`])
  native(["shell", "settings", "put", "system", "screen_off_timeout", "1800000"])
  native(["shell", "input", "keyevent", "224"])
  // Release and debug use the same fixed local UI port by design. They can be
  // installed side by side, but only one may hold that port during a test.
  for (const packageName of ["com.xiufeigo.opencodeshell", "com.xiufeigo.opencodeshell.debug"]) {
    native(["shell", "am", "force-stop", packageName])
  }
  native(["shell", "am", "start", "-W", "-n", component])
  page = await attach()
  context = page.context()

  await page.evaluate(
    ({ alphaUrl, betaUrl }) => {
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
      localStorage.setItem("opencode.global.dat:server", JSON.stringify(server))
      localStorage.setItem("opencode.global.dat:server.v3", JSON.stringify(server))
      localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "en" }))
      localStorage.setItem(
        "settings.v3",
        JSON.stringify({
          general: { tabGroups: true },
          appearance: { tabLayout: "inbox", sidebarOpen: true },
        }),
      )
    },
    { alphaUrl: alpha.origin, betaUrl: beta.origin },
  )
  await page.reload({ waitUntil: "domcontentloaded" })

  await page.locator('[data-slot="sidebar-inbox"]').waitFor({ timeout: 30_000 })
  await page.waitForFunction(() => document.querySelectorAll('[data-slot="sidebar-inbox-item"]').length === 2, undefined, {
    timeout: 30_000,
  })
  pass("debug product APK boots on Pixel Tablet and loads inbox rows from two servers")

  const initial = await groupingState()
  assert.equal(initial.avatars.every(Boolean), true)
  pass("ungrouped inbox rows still show project avatars")

  await page.locator('[data-action="inbox-groups-menu"]').click()
  const serverItem = page.getByRole("menuitemcheckbox", { name: "Server", exact: true })
  const projectItem = page.getByRole("menuitemcheckbox", { name: "Project", exact: true })
  await ensureChecked(serverItem, true)
  await ensureChecked(projectItem, true)
  pass("Server and Project can both be selected without the menu closing")
  await page.keyboard.press("Escape")

  await page.waitForFunction(
    () => {
      const keys = [...document.querySelectorAll("[data-tab-group]")].map((el) => el.dataset.groupKey || "")
      return keys.some((key) => key.includes("/server:")) && keys.some((key) => key.includes("/project:"))
    },
    undefined,
    { timeout: 10_000 },
  )
  const grouped = await groupingState()
  assert.equal(grouped.rowCount, 2)
  assert.equal(grouped.nested.length, 2)
  assert.equal(grouped.nested.every((children) => children.length === 1), true)
  assert.equal(grouped.nested.every((children) => children[0].includes("/project:")), true)
  assert.equal(grouped.avatars.every((value) => value === false), true)
  assert.equal(grouped.rowText.every((text) => /\bnow\b|[0-9]+[smhd]/.test(text)), true)
  assert.equal(grouped.serverIcons.every((value) => value === null), true)
  pass("inbox nests project groups inside server groups")
  pass("project grouping hides redundant project avatars while preserving row time")
  pass("server grouping hides redundant row server icons")
  await page.screenshot({ path: path.join(output, "inbox-server-project-groups.png"), fullPage: false })

  const alphaHeader = visible(page.locator("[data-tab-group-header]").filter({ hasText: "Alpha Server" })).first()
  await alphaHeader.locator("button").click()
  await page.waitForFunction(() => document.querySelectorAll('[data-slot="sidebar-inbox-item"]').length === 1, undefined, {
    timeout: 5_000,
  })
  pass("collapsing a server group hides its project rows and shows only the remaining server")
  await page.screenshot({ path: path.join(output, "inbox-alpha-collapsed.png"), fullPage: false })
  await alphaHeader.locator("button").click()
  await page.waitForFunction(() => document.querySelectorAll('[data-slot="sidebar-inbox-item"]').length === 2, undefined, {
    timeout: 5_000,
  })
  pass("expanding the server group restores its nested project row")

  const firstRow = visible(page.locator('[data-slot="sidebar-inbox-item"]')).first()
  await firstRow.focus()
  await page.keyboard.press("ArrowDown")
  const focusMoved = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('[data-slot="sidebar-inbox-item"]')]
    return rows[1] === document.activeElement
  })
  assert.equal(focusMoved, true)
  pass("keyboard navigation moves through the visible grouped rows")

  await visible(page.locator('[data-slot="sidebar-inbox-item"]')).first().click()
  await page.waitForURL(/\/session\/ses_/, { timeout: 15_000 })
  await page.locator('[data-component="session-timeline"], [data-slot="session-timeline-scroll"], main').first().waitFor({ timeout: 15_000 })
  pass("clicking an inbox row opens its session route")
  await page.screenshot({ path: path.join(output, "session-opened.png"), fullPage: false })

  assert.deepEqual(errors, [])
  pass("no WebView page exceptions")

  const report = {
    scope: "actual debug product APK on Pixel Tablet emulator; real Android WebView; two fixture backends",
    serial,
    checks,
    finalUrl: page.url(),
    grouped,
    backendHits: { alpha: alpha.hits, beta: beta.hits },
    networkErrors,
    consoleErrors,
    screenshots: fs.readdirSync(output).filter((name) => name.endsWith(".png")),
  }
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify(report, null, 2))
  console.log("REPORT", output)
} catch (error) {
  if (page && !page.isClosed()) {
    await page.screenshot({ path: path.join(output, "failure.png"), fullPage: false }).catch(() => {})
    const state = await page
      .evaluate(() => ({ url: location.href, text: document.body.innerText, width: innerWidth, height: innerHeight }))
      .catch(() => undefined)
    fs.writeFileSync(
      path.join(output, "failure.json"),
      JSON.stringify({ checks, error: error.stack, errors, networkErrors, consoleErrors, state }, null, 2),
    )
  }
  console.error(error)
  process.exitCode = 1
} finally {
  if (context) await context.close()
  if (device) await device.close()
  for (const port of ports) {
    try {
      native(["reverse", "--remove", `tcp:${port}`])
    } catch {}
  }
  for (const server of backends) {
    server.server.closeAllConnections()
    await new Promise((resolve) => server.server.close(resolve))
  }
}
