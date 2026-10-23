/**
 * Measure per-toggle cost of the vertical-tabs panel grid against the live dev server.
 *
 *   node research/sidebar-projects/tools/grid-v2/measure.mjs --label before
 *
 * Records, per toggle: click->next-rAF sync cost, time until the frame box exists,
 * time until the panel content is actually painted, long tasks overlapping the window,
 * CDP metric deltas, and whether existing frames were remounted.
 */
import fs from "node:fs"
import path from "node:path"
import { execFileSync } from "node:child_process"

const arg = (name, fallback) => {
  const index = process.argv.indexOf(`--${name}`)
  return index >= 0 ? process.argv[index + 1] : fallback
}

const LABEL = arg("label", "run")
const OUT = path.resolve(arg("out", "research/sidebar-projects/tools/grid-v2"))
const APP = arg("app", process.env.REALS_APP || "http://127.0.0.1:4444")
const SERVER = arg("server", process.env.REALS_SERVER || "http://127.0.0.1:40199")
const PASSWORD =
  arg("password", process.env.REALS_PASSWORD) ||
  execFileSync("C:\\Users\\xiufe\\.bun\\bin\\opencode.exe", ["service", "get", "password"], { encoding: "utf8" }).trim()
const PLAYWRIGHT =
  "C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"
const CHROMIUM = "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"

const SELECTOR = {
  terminal: '[data-component="terminal"]',
  review: '[data-component="session-review"]',
  browser: '[data-slot="server-browser"]',
  file: '[data-slot="session-mobile-files"]',
}

fs.mkdirSync(OUT, { recursive: true })

const { chromium } = await import(`file:///${PLAYWRIGHT.replace(/\\/g, "/")}`)
const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 1,
  locale: "zh-CN",
  colorScheme: "light",
})
const stateScript = () => {
  const settings = { appearance: { fontSize: 14, tabLayout: "vertical" } }
  for (const key of ["settings.v3", "opencode.global.dat:settings.v3"]) {
    localStorage.setItem(key, JSON.stringify(settings))
  }
  localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "zh" }))
  localStorage.setItem("opencode-color-scheme", "light")
  localStorage.setItem("opencode-theme-id", "oc-2")
  localStorage.removeItem("opencode.panel-grid.v1")
  const tasks = []
  window.__gridLongTasks = tasks
  new PerformanceObserver((list) => {
    for (const entry of list.getEntries()) tasks.push({ start: entry.startTime, duration: entry.duration })
  }).observe({ type: "longtask", buffered: true })
  // Count module-level heavies: terminal PTY sockets and iframe loads.
  window.__gridSockets = []
  const RealWS = window.WebSocket
  window.WebSocket = class extends RealWS {
    constructor(...args) {
      super(...args)
      window.__gridSockets.push({ url: String(args[0]).slice(0, 80), at: performance.now(), state: "new" })
    }
  }
  window.__gridIframes = 0
  new MutationObserver((list) => {
    for (const record of list) {
      for (const node of record.addedNodes) {
        if (node.nodeName === "IFRAME") window.__gridIframes++
      }
    }
  }).observe(document, { subtree: true, childList: true })
}
const initScript = `(${stateScript.toString()})()`
await context.addInitScript(initScript)

const page = await context.newPage()
page.on("pageerror", (e) => console.log("[pageerror]", String(e).slice(0, 300)))

const settle = async (ms = 700) => {
  await page.waitForTimeout(ms)
  await page.mouse.move(40, 450)
  await page.waitForTimeout(150)
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
const encoded = Buffer.from(SERVER, "utf8")
  .toString("base64")
  .replace(/\+/g, "-")
  .replace(/\//g, "_")
  .replace(/=+$/g, "")

const cdp = await context.newCDPSession(page)
await cdp.send("Performance.enable")
const metrics = async () => {
  const { metrics: list } = await cdp.send("Performance.getMetrics")
  return Object.fromEntries(list.map((entry) => [entry.name, entry.value]))
}

const openSession = async () => {
  await page.goto(`${APP}/server/${encoded}/session/${session.id}`, { waitUntil: "domcontentloaded" })
  await page.waitForSelector('[data-slot="session-chat-panel"]', { timeout: 30000 })
  await settle(1800)
}

// In-page: click, then time frame box + content paint; tag pre-existing frames for remount detection.
const measureToggle = async (id, direction) => {
  return page.evaluate(
    async ({ id, direction, selector }) => {
      const before = performance.now()
      const frameSel = `[data-slot="session-panel-frame"][data-panel="${id}"]`
      const existed = !!document.querySelector(frameSel)
      const framesBefore = [...document.querySelectorAll('[data-slot="session-panel-frame"]')]
      for (const [index, frame] of framesBefore.entries()) frame.setAttribute("data-remount-probe", `p${index}`)
      const tagCount = framesBefore.length
      const countsBefore = (() => ({
        terminals: document.querySelectorAll('[data-component="terminal"]').length,
        terminalWrappers: document.querySelectorAll('[id^="terminal-wrapper-"]').length,
        iframes: document.querySelectorAll("iframe").length,
        sockets: (window.__gridSockets ?? []).length,
        nodes: document.getElementsByTagName("*").length,
      }))()
      const button = document.querySelector(`[data-action="vertical-panel-${id}"]`)
      if (!button) throw new Error("missing toggle " + id)
      const boxSeen = []
      const nextFrame = () =>
        new Promise((resolve) => requestAnimationFrame(() => resolve(performance.now())))
      button.click()
      const afterClick = performance.now()
      const syncCost = afterClick - before
      const raf1 = await nextFrame()
      const boxAt = await new Promise((resolve) => {
        const started = performance.now()
        let firstBox = 0
        const tick = () => {
          const frame = document.querySelector(frameSel)
          const shown = direction === "open" ? !!frame : !frame
          if (shown) {
            if (frame) {
              const rect = frame.getBoundingClientRect()
              if (!firstBox && rect.width > 0 && rect.height > 0) firstBox = performance.now()
            }
            resolve(firstBox || performance.now())
            return
          }
          if (performance.now() - started > 15000) return resolve(-1)
          requestAnimationFrame(tick)
        }
        tick()
      })
      const contentAt = await new Promise((resolve) => {
        const started = performance.now()
        const tick = () => {
          if (direction === "open") {
            const frame = document.querySelector(frameSel)
            const content = frame?.querySelector(selector)
            if (content) {
              const rect = content.getBoundingClientRect()
              if (rect.width > 0 && rect.height > 0) return resolve(performance.now())
            }
            if (performance.now() - started > 15000) return resolve(-1)
          } else {
            if (!document.querySelector(frameSel)) return resolve(performance.now())
            if (performance.now() - started > 15000) return resolve(-1)
          }
          requestAnimationFrame(tick)
        }
        tick()
      })
      const allFrames = [...document.querySelectorAll('[data-slot="session-panel-frame"]')]
      const remounted = allFrames.filter((frame) => !frame.hasAttribute("data-remount-probe")).length
      for (const frame of allFrames) frame.removeAttribute("data-remount-probe")
      const openPanels = allFrames.map((frame) => frame.getAttribute("data-panel"))
      const count = () => ({
        terminals: document.querySelectorAll('[data-component="terminal"]').length,
        terminalWrappers: document.querySelectorAll('[id^="terminal-wrapper-"]').length,
        iframes: document.querySelectorAll("iframe").length,
        sockets: (window.__gridSockets ?? []).length,
        nodes: document.getElementsByTagName("*").length,
      })
      return {
        syncCost,
        clickToRaf: raf1 - afterClick,
        clickToBox: boxAt < 0 ? -1 : boxAt - before,
        clickToContent: contentAt < 0 ? -1 : contentAt - before,
        existed,
        tagCount,
        remountedExisting: remounted,
        openPanels,
        countsAfter: count(),
        countsBefore,
        at: before,
      }
    },
    { id, direction, selector: SELECTOR[id] },
  )
}

const readTasks = async () =>
  page.evaluate(() => {
    const tasks = window.__gridLongTasks ?? []
    window.__gridLongTasks = []
    return tasks.map((task) => ({ start: task.start, duration: Math.round(task.duration * 10) / 10 }))
  })

const runToggle = async (id) => {
  const direction = await page.evaluate(
    ({ id }) => (document.querySelector(`[data-slot="session-panel-frame"][data-panel="${id}"]`) ? "close" : "open"),
    { id },
  )
  await readTasks()
  const metricsBefore = await metrics()
  const result = await measureToggle(id, direction)
  const metricsAfter = await metrics()
  const tasks = await readTasks()
  const delta = {}
  for (const key of Object.keys(metricsAfter)) delta[key] = +(metricsAfter[key] - (metricsBefore[key] ?? 0)).toFixed(4)
  const overlapping = tasks.filter(
    (task) => task.start < result.at + 5000 && task.start + task.duration > result.at - 50,
  )
  return {
    id,
    direction,
    ...result,
    longTasks: overlapping,
    longTaskMs: Math.round(overlapping.reduce((sum, task) => sum + task.duration, 0)),
    taskDuration: delta.TaskDuration,
    scriptDuration: delta.ScriptDuration,
    layoutDuration: delta.LayoutDuration,
    recalcStyleDuration: delta.RecalcStyleDuration,
    jsHeapUsedSize: Math.round(delta.JSHeapUsedSize ?? 0),
  }
}

const reset = async () => {
  await page.evaluate(() => localStorage.removeItem("opencode.panel-grid.v1"))
  await openSession()
  await readTasks()
}

const results = []
await reset()
for (const id of ["terminal", "review", "browser", "file"]) {
  const step = await runToggle(id)
  results.push(step)
  await page.waitForTimeout(400)
}

fs.writeFileSync(path.join(OUT, `measure-${LABEL}.json`), JSON.stringify(results, null, 2))
for (const step of results) {
  console.log(
    `${step.id.padEnd(8)} ${step.direction.padEnd(5)} frames=${step.openPanels.length} sync=${step.syncCost.toFixed(1)}ms raf=${step.clickToRaf.toFixed(1)} box=${step.clickToBox.toFixed(1)} content=${step.clickToContent.toFixed(1)} longTask=${step.longTaskMs}ms task=${(step.taskDuration * 1000).toFixed(1)}ms script=${(step.scriptDuration * 1000).toFixed(1)}ms layout=${(step.layoutDuration * 1000).toFixed(1)}ms style=${(step.recalcStyleDuration * 1000).toFixed(1)}ms remounted=${step.remountedExisting} nodes=${step.countsBefore.nodes}->${step.countsAfter.nodes} term=${step.countsBefore.terminalWrappers}->${step.countsAfter.terminalWrappers} iframes=${step.countsBefore.iframes}->${step.countsAfter.iframes} ws=${step.countsBefore.sockets}->${step.countsAfter.sockets}`,
  )
}
await browser.close()
