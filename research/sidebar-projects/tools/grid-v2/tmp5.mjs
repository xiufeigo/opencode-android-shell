import { execFileSync } from "node:child_process"
const APP = "http://127.0.0.1:4444", SERVER = "http://127.0.0.1:40199"
const PASSWORD = execFileSync("C:\\Users\\xiufe\\.bun\\bin\\opencode.exe", ["service", "get", "password"], { encoding: "utf8" }).trim()
const PLAYWRIGHT = "C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"
const CHROMIUM = "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"
const { chromium } = await import(`file:///${PLAYWRIGHT.replace(/\\/g, "/")}`)
const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: "zh-CN" })
await context.addInitScript(() => {
  const settings = { appearance: { fontSize: 14, tabLayout: "vertical" } }
  for (const key of ["settings.v3", "opencode.global.dat:settings.v3"]) localStorage.setItem(key, JSON.stringify(settings))
  localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "zh" }))
  localStorage.setItem("opencode-color-scheme", "light")
  localStorage.setItem("opencode-theme-id", "oc-2")
  localStorage.removeItem("opencode.panel-grid.v1")
})
const page = await context.newPage()
page.on("console", (m) => console.log("[console." + m.type() + "]", m.text().slice(0, 220)))
page.on("pageerror", (e) => console.log("[pageerror]", String(e).slice(0, 220)))
page.on("websocket", (ws) => {
  console.log("[ws opened]", ws.url().slice(0, 140))
  ws.on("socketerror", (e) => console.log("[ws error]", ws.url().slice(0, 100), String(e).slice(0, 100)))
  ws.on("close", () => console.log("[ws close]", ws.url().slice(0, 100)))
})
const settle = async (ms = 800) => { await page.waitForTimeout(ms); await page.mouse.move(40, 450); await page.waitForTimeout(150) }
await page.goto(`${APP}/`, { waitUntil: "domcontentloaded" })
await settle(1800)
const tab = page.getByText("使用密码", { exact: true })
if (await tab.count()) {
  await tab.first().click(); await settle(400)
  await page.locator('input[name="server"]').fill(SERVER)
  await page.locator('input[name="password"]').fill(PASSWORD)
  await page.locator('button[type="submit"]').first().click(); await settle(2500)
}
const auth = { Authorization: `Basic ${Buffer.from("opencode:" + PASSWORD).toString("base64")}` }
const rows = (await fetch(`${SERVER}/api/session`, { headers: auth }).then(r => r.json())).data
const session = rows.find(item => !item.parentID) ?? rows[0]
const encoded = Buffer.from(SERVER, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "")
await page.goto(`${APP}/server/${encoded}/session/${session.id}`, { waitUntil: "domcontentloaded" })
await page.waitForSelector('[data-slot="session-chat-panel"]', { timeout: 30000 })
await settle(1800)
console.log("---- toggle terminal")
await page.click('[data-action="vertical-panel-terminal"]')
await page.waitForTimeout(2500)
console.log("done")
await browser.close()
