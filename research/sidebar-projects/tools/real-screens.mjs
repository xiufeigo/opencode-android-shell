/**
 * Screenshots of the real dev app against a live OpenCode server.
 *
 *   node research/sidebar-projects/tools/real-screens.mjs --out research/sidebar-projects/shots/real
 *
 * Env (defaults match this machine):
 *   REALS_APP      app dev origin        http://127.0.0.1:4444
 *   REALS_SERVER   running serve origin  http://127.0.0.1:40199
 *   REALS_PASSWORD service password (opencode service get password)
 *   REALS_PLAYWRIGHT / REALS_CHROMIUM
 */
import fs from "node:fs"
import path from "node:path"
import { execFileSync } from "node:child_process"

const arg = (name, fallback) => {
  const index = process.argv.indexOf(`--${name}`)
  return index >= 0 ? process.argv[index + 1] : fallback
}

const OUT = path.resolve(arg("out", "research/sidebar-projects/shots/real"))
const APP = arg("app", process.env.REALS_APP || "http://127.0.0.1:4444")
const SERVER = arg("server", process.env.REALS_SERVER || "http://127.0.0.1:40199")
const PASSWORD =
  arg("password", process.env.REALS_PASSWORD) ||
  execFileSync("C:\\Users\\xiufe\\.bun\\bin\\opencode.exe", ["service", "get", "password"], { encoding: "utf8" }).trim()
const PLAYWRIGHT =
  process.env.REALS_PLAYWRIGHT ||
  "C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"
const CHROMIUM =
  process.env.REALS_CHROMIUM || "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"
const THEME = arg("theme", "light")
const WIDTH = Number(arg("width", "1440"))
const HEIGHT = Number(arg("height", "900"))

fs.mkdirSync(OUT, { recursive: true })

const { chromium } = await import(`file:///${PLAYWRIGHT.replace(/\\/g, "/")}`)
const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })
const context = await browser.newContext({
  viewport: { width: WIDTH, height: HEIGHT },
  deviceScaleFactor: 2,
  locale: "zh-CN",
  colorScheme: THEME === "dark" ? "dark" : "light",
})
await context.addInitScript((theme) => {
  const settings = { appearance: { fontSize: 14, tabLayout: "vertical" } }
  for (const key of ["settings.v3", "opencode.global.dat:settings.v3"]) {
    localStorage.setItem(key, JSON.stringify(settings))
  }
  localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "zh" }))
  localStorage.setItem("opencode-color-scheme", theme)
  localStorage.setItem("opencode-theme-id", "oc-2")
  localStorage.removeItem("opencode.panel-grid.v1")
}, THEME)
const page = await context.newPage()

const consoleErrors = []
page.on("console", (message) => {
  if (message.type() === "error") consoleErrors.push(message.text())
})
page.on("pageerror", (error) => consoleErrors.push(String(error)))

const shots = []
const shot = async (name) => {
  const file = path.join(OUT, `${name}.png`)
  await page.screenshot({ path: file })
  shots.push(file)
  console.log(`shot ${file}`)
}

const settle = async (ms = 900) => {
  await page.waitForTimeout(ms)
  await page.mouse.move(24, Math.round(HEIGHT / 2))
  await page.waitForTimeout(300)
}

const connect = async () => {
  await page.goto(`${APP}/`, { waitUntil: "domcontentloaded" })
  await settle(1800)

  const passwordTab = page.getByText("使用密码", { exact: true })
  if (await passwordTab.count()) {
    await passwordTab.first().click()
    await settle(400)
    await page.locator('input[name="server"]').fill(SERVER)
    await page.locator('input[name="password"]').fill(PASSWORD)
    await page.locator('button[type="submit"]').first().click()
    await settle(2500)
  }

  // Drop any stale server entries the dev app discovered (e.g. an old 4096 service);
  // home and the project tree otherwise scope their queries to the wrong one.
  const clean = await page.evaluate((origin) => {
    let changed = false
    for (const key of ["opencode.global.dat:server", "opencode.global.dat:server.v3"]) {
      const raw = localStorage.getItem(key)
      if (!raw) continue
      const state = JSON.parse(raw)
      const list = (state.list ?? []).filter(
        (entry) => entry.type !== "http" || entry.http?.url?.replace(/\/$/, "") === origin,
      )
      if (list.length !== (state.list ?? []).length) {
        state.list = list
        localStorage.setItem(key, JSON.stringify(state))
        changed = true
      }
    }
    return changed
  }, SERVER)
  if (clean) {
    await page.goto(`${APP}/`, { waitUntil: "domcontentloaded" })
    await settle(2200)
  }
}

await connect()

// Open the newest session on the live server directly by route.
const auth = { Authorization: `Basic ${Buffer.from(`opencode:${PASSWORD}`).toString("base64")}` }
const sessions = await fetch(`${SERVER}/api/session`, { headers: auth }).then((response) => response.json())
const rows = sessions.data ?? sessions
const session = rows.find((item) => !item.parentID) ?? rows[0]
if (!session) throw new Error("live server has no session")
const encoded = Buffer.from(SERVER, "utf8")
  .toString("base64")
  .replace(/\+/g, "-")
  .replace(/\//g, "_")
  .replace(/=+$/g, "")
await page.goto(`${APP}/server/${encoded}/session/${session.id}`, { waitUntil: "domcontentloaded" })
await page.waitForSelector('[data-slot="session-chat-panel"]', { timeout: 30000 })
await settle(2000)

const toggle = async (id) => {
  await page.click(`[data-action="vertical-panel-${id}"]`)
  await settle()
}

// 1) Session, no panel frames
await shot("01-session-default")

// 2) One frame
await toggle("terminal")
await shot("02-session-one-panel")
await toggle("terminal")
await settle()

// 3) Two frames -> two columns
await toggle("review")
await toggle("file")
await shot("03-session-two-panels")
await toggle("review")
await toggle("file")
await settle()

// 4) Four frames -> 2x2
for (const id of ["terminal", "review", "browser", "file"]) await toggle(id)
await shot("04-session-four-panels")

// 5) Sidebar collapsed, four frames
await page.click('[data-action="vertical-sidebar-collapse"]')
await settle()
await shot("05-session-collapsed-four-panels")
await page.click('[data-action="vertical-sidebar-collapse"]')
await settle()

// 6) Home with the first project expanded
await page.goto(`${APP}/`, { waitUntil: "domcontentloaded" })
await settle(2200)
const serverLabel = SERVER.replace(/^https?:\/\//, "")
const homeServer = page.getByText(serverLabel, { exact: true }).first()
if (await homeServer.count()) {
  await homeServer.click()
  await settle(1500)
}
const projectRow = page.locator('[data-slot="project-row"]').first()
if (await projectRow.count()) {
  await projectRow.click()
  await settle(1200)
}
await shot("06-home")

// 7) Settings
await page.goto(`${APP}/settings`, { waitUntil: "domcontentloaded" })
await settle(1400)
await shot("07-settings")

fs.writeFileSync(
  path.join(OUT, "report.json"),
  JSON.stringify(
    { app: APP, server: SERVER, viewport: { width: WIDTH, height: HEIGHT }, shots, consoleErrors },
    null,
    2,
  ),
)
console.log(`report ${path.join(OUT, "report.json")}`)
console.log(`console errors: ${consoleErrors.length}`)
console.log(consoleErrors.slice(0, 10).join("\n"))
await browser.close()
