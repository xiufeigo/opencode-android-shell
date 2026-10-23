/** Probe the live session DOM: frame subtree shape + current grid layout. */
import fs from "node:fs"
import path from "node:path"
import { execFileSync } from "node:child_process"

const APP = process.env.REALS_APP || "http://127.0.0.1:4444"
const SERVER = process.env.REALS_SERVER || "http://127.0.0.1:40199"
const PASSWORD =
  process.env.REALS_PASSWORD ||
  execFileSync("C:\\Users\\xiufe\\.bun\\bin\\opencode.exe", ["service", "get", "password"], { encoding: "utf8" }).trim()
const PLAYWRIGHT =
  "C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"
const CHROMIUM = "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"

const { chromium } = await import(`file:///${PLAYWRIGHT.replace(/\\/g, "/")}`)
const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 1,
  locale: "zh-CN",
  colorScheme: "light",
})
await context.addInitScript(() => {
  const settings = { appearance: { fontSize: 14, tabLayout: "vertical" } }
  for (const key of ["settings.v3", "opencode.global.dat:settings.v3"]) {
    localStorage.setItem(key, JSON.stringify(settings))
  }
  localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "zh" }))
  localStorage.setItem("opencode-color-scheme", "light")
  localStorage.setItem("opencode-theme-id", "oc-2")
  localStorage.removeItem("opencode.panel-grid.v1")
})
const page = await context.newPage()
page.on("console", (m) => m.type() === "error" && console.log("[console.error]", m.text()))
page.on("pageerror", (e) => console.log("[pageerror]", String(e)))

const settle = async (ms = 800) => {
  await page.waitForTimeout(ms)
  await page.mouse.move(24, 450)
  await page.waitForTimeout(200)
}

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

const auth = { Authorization: `Basic ${Buffer.from(`opencode:${PASSWORD}`).toString("base64")}` }
const sessions = await fetch(`${SERVER}/api/session`, { headers: auth }).then((r) => r.json())
const rows = sessions.data ?? sessions
const session = rows.find((item) => !item.parentID) ?? rows[0]
console.log("session", session.id, "count", rows.length)
const encoded = Buffer.from(SERVER, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "")
await page.goto(`${APP}/server/${encoded}/session/${session.id}`, { waitUntil: "domcontentloaded" })
await page.waitForSelector('[data-slot="session-chat-panel"]', { timeout: 30000 })
await settle(2000)

const dump = async (label) => {
  const data = await page.evaluate(() => {
    const grid = document.querySelector('[data-slot="session-panel-grid"]')
    const chat = document.querySelector('[data-slot="session-chat-panel"]')
    const frames = [...document.querySelectorAll('[data-slot="session-panel-frame"]')].map((f) => {
      const r = f.getBoundingClientRect()
      const kids = []
      const walk = (el, depth) => {
        if (depth > 2) return
        for (const c of el.children) {
          const cr = c.getBoundingClientRect()
          kids.push({
            depth,
            tag: c.tagName.toLowerCase(),
            slot: c.getAttribute("data-slot"),
            component: c.getAttribute("data-component"),
            cls: (c.className || "").toString().slice(0, 90),
            w: Math.round(cr.width),
            h: Math.round(cr.height),
            text: (c.textContent || "").trim().slice(0, 40),
          })
          walk(c, depth + 1)
        }
      }
      walk(f, 0)
      return {
        panel: f.getAttribute("data-panel"),
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
        html: f.innerHTML.slice(0, 400),
        kids: kids.slice(0, 18),
      }
    })
    return {
      grid: grid
        ? {
            ...(() => {
              const r = grid.getBoundingClientRect()
              return { x: Math.round(r.x), w: Math.round(r.width), h: Math.round(r.height) }
            })(),
            children: [...grid.children].map((c) => {
              const r = c.getBoundingClientRect()
              return {
                cls: (c.className || "").toString().slice(0, 80),
                x: Math.round(r.x),
                w: Math.round(r.width),
                h: Math.round(r.height),
              }
            }),
          }
        : null,
      chat: chat ? (() => {
        const r = chat.getBoundingClientRect()
        return { x: Math.round(r.x), w: Math.round(r.width) }
      })() : null,
      frames,
    }
  })
  fs.writeFileSync(path.resolve("research/sidebar-projects/tools/grid-v2/probe-" + label + ".json"), JSON.stringify(data, null, 2))
  console.log("=== " + label)
  console.log("grid", JSON.stringify(data.grid))
  console.log("chat", JSON.stringify(data.chat))
  for (const f of data.frames) console.log("frame", f.panel, JSON.stringify(f.rect), "| kids:", f.kids.slice(0, 6).map((k) => `${k.tag}${k.slot ? "[" + k.slot + "]" : ""}${k.component ? "{" + k.component + "}" : ""} ${k.w}x${k.h}`).join(" ; "))
}

for (const id of ["terminal", "review", "browser", "file"]) {
  await page.click(`[data-action="vertical-panel-${id}"]`)
  await page.waitForTimeout(1200)
  await dump(`after-${id}`)
}
await browser.close()
