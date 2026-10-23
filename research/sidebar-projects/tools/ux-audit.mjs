/**
 * UX / performance audit harness for the vertical-tabs UI (project sidebar + panel grid).
 *
 *   node research/sidebar-projects/tools/ux-audit.mjs \
 *     --out research/sidebar-projects/notes/ux-audit-baseline.json \
 *     --shots research/sidebar-projects/shots/ux-audit
 *
 * It drives the already-running Solid dev server (never starts/restarts it) against a live
 * OpenCode server:
 *
 *   REALS_APP        app dev origin        http://127.0.0.1:4444
 *   REALS_SERVER     running serve origin  http://127.0.0.1:40199
 *   REALS_PASSWORD   service password      `opencode service get password`
 *   REALS_PLAYWRIGHT / REALS_CHROMIUM      module + browser executable
 *
 * Captures (all timings from inside the page, single measurement pass):
 *   1. panel-open latency and long tasks for the 1st..4th open panel
 *   2. column / row / chat-boundary resize drags (+120 / +120 / -120 px)
 *   3. root-screen and draft-row UX checks (vertical layout)
 *   4. screenshots: 0/1/2/4 panels, mid-drag, root
 *
 * Writes one JSON file plus PNGs; prints a compact summary. Re-runnable as-is. It only ever
 * touches the browser context's localStorage/DOM, never the app sources.
 */
import fs from "node:fs"
import path from "node:path"
import { execFileSync } from "node:child_process"

const arg = (name, fallback) => {
  const index = process.argv.indexOf(`--${name}`)
  return index >= 0 ? process.argv[index + 1] : fallback
}

const OUT = path.resolve(arg("out", "research/sidebar-projects/notes/ux-audit.json"))
const SHOTS = path.resolve(arg("shots", "research/sidebar-projects/shots/ux-audit"))
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
const WIDTH = Number(arg("width", "1440"))
const HEIGHT = Number(arg("height", "900"))
const ROUNDS = Number(arg("rounds", "3"))
const CHECKOUT = path.resolve(arg("checkout", "_scratch_root/desktop-parity/checkout"))
const SOURCE_FILES = [
  "packages/app/src/shell/routes/routes.tsx",
  "packages/app/src/shell/titlebar/titlebar.tsx",
  "packages/app/src/shell/titlebar/project-session-sidebar.tsx",
  "packages/app/src/shell/titlebar/vertical-session-toolbar.tsx",
  "packages/app/src/session/screen.tsx",
  "packages/app/src/session/panel-grid.tsx",
  "packages/app/src/session/panel-grid-state.ts",
  "packages/ui/src/components/resize-handle.tsx",
]
const PANEL_IDS = ["terminal", "review", "browser", "file"]
const TOGGLE = (id) => `[data-action="vertical-panel-${id}"]`
const FRAME = '[data-slot="session-panel-frame"]'
const GRID = '[data-slot="session-panel-grid"]'
const CHAT = '[data-slot="session-chat-panel"]'
// The current implementation uses the vendored ResizeHandle ([data-component="resize-handle"]);
// a follow-up implementation may use explicit grid splitters. Support both everywhere.
const SPLITTER = '[data-component="resize-handle"], [data-slot="grid-splitter"]'
// A draft row: the future explicit slot, or the current "new session" row (draft tabs have no
// session id, so data-session is empty).
const DRAFT_ROW = '[data-slot="draft-row"], [data-slot="session-row"][data-session=""]'

const round = (value, digits = 1) => (value == null || Number.isNaN(value) ? null : Math.round(value * 10 ** digits) / 10 ** digits)
const median = (values) => {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}
const blockingTime = (tasks) => tasks.reduce((total, task) => total + Math.max(0, task.duration - 50), 0)
const rectOf = (rect) => ({
  x: round(rect.x),
  y: round(rect.y),
  width: round(rect.width),
  height: round(rect.height),
  right: round(rect.right),
  bottom: round(rect.bottom),
})

fs.mkdirSync(SHOTS, { recursive: true })
fs.mkdirSync(path.dirname(OUT), { recursive: true })

const { chromium } = await import(`file:///${PLAYWRIGHT.replace(/\\/g, "/")}`)
const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })
const context = await browser.newContext({
  viewport: { width: WIDTH, height: HEIGHT },
  deviceScaleFactor: 2,
  locale: "zh-CN",
  colorScheme: "light",
})
await context.addInitScript(() => {
  // Seed the same client state real-screens.mjs uses: vertical tabs, zh, light, no stale grid.
  const settings = { appearance: { fontSize: 14, tabLayout: "vertical" } }
  for (const key of ["settings.v3", "opencode.global.dat:settings.v3"]) {
    localStorage.setItem(key, JSON.stringify(settings))
  }
  localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: "zh" }))
  localStorage.setItem("opencode-color-scheme", "light")
  localStorage.setItem("opencode-theme-id", "oc-2")
  localStorage.removeItem("opencode.panel-grid.v1")

  // Long-task buffer for the whole document; the harness reads new entries per measurement.
  window.__uxAudit = { longTasks: [], lastToggleClickAt: null }
  if (typeof PerformanceObserver !== "undefined" && PerformanceObserver.supportedEntryTypes?.includes("longtask")) {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        window.__uxAudit.longTasks.push({ startTime: entry.startTime, duration: entry.duration })
      }
    }).observe({ type: "longtask", buffered: true })
  }
  // Event→paint is measured from the real toggle click dispatch, not from a synthetic click().
  document.addEventListener(
    "click",
    (event) => {
      const target = event.target instanceof Element ? event.target.closest('[data-action^="vertical-panel-"]') : null
      if (target) window.__uxAudit.lastToggleClickAt = performance.now()
    },
    true,
  )
})

const page = await context.newPage()
const consoleErrors = []
page.on("console", (message) => {
  if (message.type() === "error") consoleErrors.push(message.text())
})
page.on("pageerror", (error) => consoleErrors.push(String(error)))

const shots = []
const shot = async (name) => {
  const file = path.join(SHOTS, `${name}.png`)
  await page.screenshot({ path: file })
  shots.push(file)
  return file
}

// The dev server is a live HMR session that may be mid-edit (the Vite overlay shows a crash
// screen for a half-applied module update). Reload until the app is healthy again so a run is
// not lost to an unrelated transient edit; every retry is recorded in the report.
const retries = []
const crashed = () =>
  page
    .evaluate(() => {
      const text = document.body?.innerText ?? ""
      return text.includes("加载应用程序时发生错误") || text.includes("出了点问题")
    })
    .catch(() => false)

async function gotoApp(url, waitMs = 2200) {
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    await page.goto(url, { waitUntil: "domcontentloaded" })
    await page.waitForTimeout(waitMs)
    if (!(await crashed())) return true
    retries.push({ phase: "load", url, attempt, at: new Date().toISOString() })
    console.warn(`[ux-audit] app error screen at ${url} (attempt ${attempt}/4); retrying`)
    await page.waitForTimeout(1200)
  }
  return false
}

const onConnectScreen = () =>
  page.evaluate(() => !!document.querySelector('input[name="password"]')).catch(() => false)

function sourceSnapshot() {
  return SOURCE_FILES.map((relative) => {
    try {
      const stat = fs.statSync(path.join(CHECKOUT, relative))
      return { path: relative, size: stat.size, mtime: stat.mtime.toISOString() }
    } catch {
      return { path: relative, missing: true }
    }
  })
}

const settle = async (ms = 900) => {
  await page.waitForTimeout(ms)
  // Park the pointer away from panels/toolbars so hover-reveal chrome does not leak into shots.
  await page.mouse.move(24, Math.round(HEIGHT / 2))
  await page.waitForTimeout(250)
}

const frames = () => page.locator(FRAME).count()

async function connect() {
  await gotoApp(`${APP}/`, 1800)
  await settle(500)

  const passwordTab = page.getByText("使用密码", { exact: true })
  if (await passwordTab.count()) {
    await passwordTab.first().click()
    await settle(400)
    await page.locator('input[name="server"]').fill(SERVER)
    await page.locator('input[name="password"]').fill(PASSWORD)
    await page.locator('button[type="submit"]').first().click()
    await settle(2500)
  }

  // Drop stale server entries the dev app discovered (e.g. an old 4096 service); home and the
  // project tree otherwise scope their queries to the wrong server.
  const cleaned = await page.evaluate((origin) => {
    let changed = false
    for (const key of ["opencode.global.dat:server", "opencode.global.dat:server.v3"]) {
      const raw = localStorage.getItem(key)
      if (!raw) continue
      const state = JSON.parse(raw)
      const list = (state.list ?? []).filter((entry) => entry.type !== "http" || entry.http?.url?.replace(/\/$/, "") === origin)
      if (list.length !== (state.list ?? []).length) {
        state.list = list
        localStorage.setItem(key, JSON.stringify(state))
        changed = true
      }
    }
    return changed
  }, SERVER)
  if (cleaned) {
    await gotoApp(`${APP}/`, 2200)
    await settle(500)
  }
}

async function openSessionRoute(url) {
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    const loaded = await gotoApp(url, 2200)
    if (!loaded) continue
    if (await onConnectScreen()) {
      retries.push({ phase: "session-connect", url, attempt, at: new Date().toISOString() })
      await connect()
      continue
    }
    const chat = await page
      .waitForSelector(CHAT, { timeout: 15000 })
      .then(() => true)
      .catch(() => false)
    if (!chat) continue
    await page.waitForSelector(TOGGLE("terminal"), { timeout: 15000 }).catch(() => undefined)
    await settle(1600)
    return (await page.locator(TOGGLE("terminal")).count()) > 0
  }
  return false
}

async function pickSession() {
  const auth = { Authorization: `Basic ${Buffer.from(`opencode:${PASSWORD}`).toString("base64")}` }
  const response = await fetch(`${SERVER}/api/session`, { headers: auth })
  if (!response.ok) throw new Error(`GET /api/session -> ${response.status}`)
  const payload = await response.json()
  const rows = Array.isArray(payload) ? payload : (payload.data ?? [])
  const top = rows
    .filter((row) => !row.parentID)
    .sort((a, b) => (b.time?.updated ?? b.time?.created ?? 0) - (a.time?.updated ?? a.time?.created ?? 0))
  const session = top[0]
  if (!session) throw new Error("live server has no top-level session")
  return session
}

async function panelButton(id) {
  return page.evaluate((selector) => {
    const element = document.querySelector(selector)
    if (!element) return { exists: false }
    return {
      exists: true,
      disabled: element.disabled === true || element.getAttribute("aria-disabled") === "true",
      pressed:
        element.getAttribute("aria-pressed") === "true" ||
        element.getAttribute("aria-expanded") === "true" ||
        element.getAttribute("data-state") === "pressed",
      label: element.getAttribute("aria-label"),
    }
  }, TOGGLE(id))
}

async function closeAllPanels() {
  for (let pass = 0; pass < 3; pass++) {
    const open = await page.evaluate((selector) =>
      [...document.querySelectorAll(selector)].filter(
        (element) =>
          element.getAttribute("aria-pressed") === "true" ||
          element.getAttribute("aria-expanded") === "true" ||
          element.getAttribute("data-state") === "pressed",
      ).map((element) => element.getAttribute("data-action")), '[data-action^="vertical-panel-"]')
    if (!open.length) break
    for (const action of open) {
      await page.click(`[data-action="${action}"]`)
      await page.waitForTimeout(250)
    }
  }
  await settle(400)
  return frames()
}

/** Click a toggle with real pointer events; latency is (paint after double-rAF) - (click dispatch). */
async function measureToggle(id) {
  const selector = TOGGLE(id)
  const cursor = await page.evaluate(() => window.__uxAudit.longTasks.length)
  await page.evaluate(() => {
    window.__uxAudit.lastToggleClickAt = null
  })
  const startedAt = Date.now()
  await page.locator(selector).click()
  const measurement = await page.evaluate(async () => {
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    return { clickedAt: window.__uxAudit.lastToggleClickAt, paintedAt: performance.now() }
  })
  const method = measurement.clickedAt == null ? "pre-click" : "click-capture"
  await page.waitForTimeout(Math.max(0, 1500 - (Date.now() - startedAt)))
  const tasks = await page.evaluate((from) => window.__uxAudit.longTasks.slice(from), cursor)
  return {
    latencyMs: measurement.clickedAt == null ? null : measurement.paintedAt - measurement.clickedAt,
    latencyMethod: method,
    longTaskCount: tasks.length,
    totalBlockingMs: round(blockingTime(tasks)),
    longTaskDurations: tasks.map((task) => round(task.duration)),
  }
}

/**
 * Locate splitters in the panel grid and classify them geometrically as well as by
 * data-direction, so both the vendored ResizeHandle and a future grid-splitter work.
 */
async function splitterCandidates() {
  return page.evaluate(({ gridSelector, splitterSelector }) => {
    const grid = document.querySelector(gridSelector)
    if (!grid) return { grid: false, items: [] }
    const items = [...grid.querySelectorAll(splitterSelector)].map((element, index) => {
      const rect = element.getBoundingClientRect()
      return {
        index,
        rect: {
          x: rect.x,
          y: rect.y,
          width: rect.width,
          height: rect.height,
          right: rect.right,
          bottom: rect.bottom,
        },
        centerX: rect.x + rect.width / 2,
        centerY: rect.y + rect.height / 2,
        direction: element.getAttribute("data-direction"),
        edge: element.getAttribute("data-edge"),
        slot: element.getAttribute("data-slot"),
        component: element.getAttribute("data-component"),
        className: typeof element.className === "string" ? element.className.slice(0, 120) : "",
      }
    })
    return { grid: true, items }
  }, { gridSelector: GRID, splitterSelector: SPLITTER })
}

/** Mark the splitter and the element it resizes; returns their geometry. */
async function markSplitter(selector, index) {
  return page.evaluate(({ selector, index, gridSelector }) => {
    for (const element of document.querySelectorAll("[data-uxaudit-splitter],[data-uxaudit-target]")) {
      element.removeAttribute("data-uxaudit-splitter")
      element.removeAttribute("data-uxaudit-target")
    }
    const grid = document.querySelector(gridSelector)
    if (!grid) return { error: "grid missing" }
    const element = [...grid.querySelectorAll(selector)][index]
    if (!element) return { error: "splitter missing" }
    element.setAttribute("data-uxaudit-splitter", "")

    // Row/height handles live inside their frame; column handles live on the column.
    const frame = element.closest('[data-slot="session-panel-frame"]')
    let target = frame && frame.closest(gridSelector) ? frame : undefined
    if (!target) {
      let node = element.previousElementSibling
      while (node && node !== grid && node.parentElement !== grid) node = node.parentElement
      if (node && node.parentElement === grid) target = node
    }
    if (!target) {
      const rect = element.getBoundingClientRect()
      let candidate = document.elementFromPoint(
        Math.max(0, Math.min(window.innerWidth - 1, rect.left - 6)),
        Math.max(0, Math.min(window.innerHeight - 1, rect.top + rect.height / 2)),
      )
      while (candidate && candidate !== grid && candidate.parentElement !== grid) candidate = candidate.parentElement
      if (candidate && candidate.parentElement === grid) target = candidate
    }
    if (!target) return { error: "target missing" }
    target.setAttribute("data-uxaudit-target", "")
    const splitterRect = element.getBoundingClientRect()
    const targetRect = target.getBoundingClientRect()
    return {
      splitterRect: {
        x: splitterRect.x,
        y: splitterRect.y,
        width: splitterRect.width,
        height: splitterRect.height,
      },
      targetRect: {
        x: targetRect.x,
        y: targetRect.y,
        width: targetRect.width,
        height: targetRect.height,
      },
      targetSlot: target.getAttribute("data-slot"),
      targetPanel: target.getAttribute("data-panel"),
      hitIsSplitter: (() => {
        const hit = document.elementFromPoint(splitterRect.x + splitterRect.width / 2, splitterRect.y + splitterRect.height / 2)
        return !!hit && (hit === element || element.contains(hit) || hit.contains(element))
      })(),
    }
  }, { selector, index, gridSelector: GRID })
}

const measureTarget = (dimension) =>
  page.$eval("[data-uxaudit-target]", (element, dimension) => element.getBoundingClientRect()[dimension], dimension)

const clearMarkers = () =>
  page.evaluate(() => {
    for (const element of document.querySelectorAll("[data-uxaudit-splitter],[data-uxaudit-target]")) {
      element.removeAttribute("data-uxaudit-splitter")
      element.removeAttribute("data-uxaudit-target")
    }
  })

async function dragMarked({ dx, dy, dimension, steps = 25, midShot }) {
  const splitter = await page.$("[data-uxaudit-splitter]")
  if (!splitter) return { status: "missing" }
  const box = await splitter.boundingBox()
  if (!box) return { status: "missing" }
  const centerX = box.x + box.width / 2
  const centerY = box.y + box.height / 2
  const offscreen =
    centerX < 0 || centerX > WIDTH - 1 || centerY < 0 || centerY > HEIGHT - 1
      ? "offscreen"
      : null
  if (offscreen) return { status: offscreen, center: { x: round(centerX), y: round(centerY) } }

  const initial = await measureTarget(dimension).catch(() => null)
  if (initial == null) return { status: "no-target" }

  await page.mouse.move(centerX, centerY)
  await page.mouse.down()
  await page.waitForTimeout(60)
  const cursor = await page.evaluate(() => window.__uxAudit.longTasks.length)
  const series = [initial]
  for (let step = 1; step <= steps; step += 1) {
    await page.mouse.move(centerX + (dx * step) / steps, centerY + (dy * step) / steps)
    await page.waitForTimeout(16)
    series.push(await measureTarget(dimension).catch(() => null))
    if (midShot && step === 5) await shot(midShot)
  }
  await page.mouse.up()
  await page.waitForTimeout(250)
  const tasks = await page.evaluate((from) => window.__uxAudit.longTasks.slice(from), cursor)

  const valid = series.every((value) => typeof value === "number")
  const requestedDelta = dimension === "width" ? dx : dy
  let changedSteps = 0
  let maxDeviation = 0
  for (let step = 1; step < series.length; step += 1) {
    if (series[step] == null || series[step - 1] == null) continue
    if (Math.abs(series[step] - series[step - 1]) >= 0.5) changedSteps += 1
    if (valid) maxDeviation = Math.max(maxDeviation, Math.abs(series[step] - series[0] - (requestedDelta * step) / steps))
  }
  const final = series[series.length - 1]
  return {
    status: valid && changedSteps > 0 ? "ok" : "no-change",
    dimension,
    requestedDelta,
    steps,
    initial: round(initial),
    final: round(final),
    totalDelta: round(final == null ? null : final - initial),
    changedSteps,
    maxDeviation: round(maxDeviation),
    longTaskCount: tasks.length,
    totalBlockingMs: round(blockingTime(tasks)),
    longTaskDurations: tasks.map((task) => round(task.duration)),
    series: series.map((value) => round(value)),
  }
}

async function findChatSplitter() {
  return page.evaluate((chatSelector) => {
    const chat = document.querySelector(chatSelector)
    if (!chat) return { chat: false }
    const chatRect = chat.getBoundingClientRect()
    const hits = [...document.querySelectorAll('[data-component="resize-handle"], [data-slot="grid-splitter"]')]
      .map((element) => {
        const rect = element.getBoundingClientRect()
        const centerX = rect.x + rect.width / 2
        const overlap = Math.min(chatRect.bottom, rect.bottom) - Math.max(chatRect.top, rect.top)
        return { element, rect, distance: Math.abs(centerX - chatRect.right), overlap }
      })
      .filter((item) => item.distance <= 20 && item.overlap > 40)
      .sort((a, b) => a.distance - b.distance)
    const midpoint = document.elementFromPoint(
      Math.max(0, Math.min(window.innerWidth - 1, chatRect.right - 1)),
      Math.max(0, Math.min(window.innerHeight - 1, chatRect.top + chatRect.height / 2)),
    )
    const describe = (element) =>
      element
        ? {
            tag: element.tagName,
            slot: element.getAttribute("data-slot"),
            component: element.getAttribute("data-component"),
            direction: element.getAttribute("data-direction"),
          }
        : null
    if (!hits.length) {
      return {
        chat: true,
        found: false,
        chatRect: {
          x: chatRect.x,
          y: chatRect.y,
          width: chatRect.width,
          height: chatRect.height,
          right: chatRect.right,
        },
        hitAtChatEdge: describe(midpoint),
      }
    }
    const best = hits[0]
    best.element.setAttribute("data-uxaudit-splitter", "")
    chat.setAttribute("data-uxaudit-target", "")
    return {
      chat: true,
      found: true,
      distance: best.distance,
      overlap: best.overlap,
      splitterRect: {
        x: best.rect.x,
        y: best.rect.y,
        width: best.rect.width,
        height: best.rect.height,
      },
      chatRect: {
        x: chatRect.x,
        y: chatRect.y,
        width: chatRect.width,
        height: chatRect.height,
        right: chatRect.right,
      },
      hitAtChatEdge: describe(midpoint),
    }
  }, CHAT)
}

async function geometrySnapshot() {
  return page.evaluate(({ gridSelector, chatSelector, frameSelector, splitterSelector }) => {
    const describe = (element) => {
      if (!element) return null
      const rect = element.getBoundingClientRect()
      return {
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        right: Math.round(rect.right),
        bottom: Math.round(rect.bottom),
        inViewport:
          rect.right > 0 && rect.bottom > 0 && rect.left < window.innerWidth && rect.top < window.innerHeight,
      }
    }
    const grid = document.querySelector(gridSelector)
    return {
      grid: describe(grid),
      chat: describe(document.querySelector(chatSelector)),
      frames: [...document.querySelectorAll(frameSelector)].map((element) => ({
        panel: element.getAttribute("data-panel"),
        ...describe(element),
      })),
      splitters: [...document.querySelectorAll(splitterSelector)].map((element) => ({
        direction: element.getAttribute("data-direction"),
        edge: element.getAttribute("data-edge"),
        slot: element.getAttribute("data-slot"),
        ...describe(element),
      })),
      viewport: { width: window.innerWidth, height: window.innerHeight },
    }
  }, { gridSelector: GRID, chatSelector: CHAT, frameSelector: FRAME, splitterSelector: SPLITTER })
}

const rootSnapshot = () =>
  page.evaluate(
    ({ draftRowSelector }) => {
      const query = (selector) => document.querySelector(selector)
      const draftList = query('[data-slot="draft-list"]')
      const groups = [...document.querySelectorAll('[data-slot="project-group"]')]
      const allDrafts = [...document.querySelectorAll(draftRowSelector)]
      const inList = draftList ? [...draftList.querySelectorAll(draftRowSelector)] : []
      const inGroups = groups.flatMap((group) => [...group.querySelectorAll(draftRowSelector)])
      let containerText = ""
      if (draftList) {
        let node = draftList
        for (let depth = 0; node && depth < 3; depth += 1, node = node.parentElement) {
          containerText += ` ${node.innerText ?? ""}`
        }
      }
      const draftListAriaLabel = draftList?.getAttribute("aria-label") ?? ""
      return {
        url: location.href,
        showsNewSession: !!query('[data-component="new-session"]'),
        showsHome: !!query('[data-slot="home-panel"]'),
        sidebarPresent: !!query('[data-slot="project-sidebar"]'),
        sidebarCollapsed:
          !!query('[data-slot="vertical-sidebar-toolbar"]') &&
          query('[data-slot="vertical-sidebar-toolbar"]')?.getAttribute("aria-expanded") === "false",
        draftListPresent: !!draftList,
        draftListAriaLabel,
        draftRowsTotal: allDrafts.length,
        draftRowsInDraftList: inList.length,
        draftRowsInProjectGroups: inGroups.length,
        draftListMentionsNewSession: /新建会话|New session/i.test(`${draftListAriaLabel} ${containerText}`),
        sampleText: document.body.innerText.slice(0, 600),
      }
    },
    { draftRowSelector: DRAFT_ROW },
  )

const result = {
  generatedAt: new Date().toISOString(),
  app: APP,
  server: SERVER,
  viewport: { width: WIDTH, height: HEIGHT },
  checkout: path.relative(process.cwd(), CHECKOUT),
  sourceSnapshot: sourceSnapshot(),
  panelPerf: [],
  resize: {},
  ux: {},
  checks: {},
  shots,
  consoleErrors,
  retries,
}

try {
  await connect()

  const session = await pickSession()
  const encoded = Buffer.from(SERVER, "utf8")
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "")
  const sessionUrl = `${APP}/server/${encoded}/session/${session.id}`
  const ready = await openSessionRoute(sessionUrl)
  if (!ready) {
    throw new Error(`session screen never became ready (${sessionUrl}); app may be mid-HMR`)
  }
  await settle(800)

  result.session = {
    id: session.id,
    title: session.title,
    updated: session.time?.updated,
    created: session.time?.created,
  }
  result.layout = await page.evaluate(() => {
    let tabLayout
    try {
      tabLayout = JSON.parse(localStorage.getItem("settings.v3") ?? "{}")?.appearance?.tabLayout
    } catch {}
    return {
      tabLayout,
      grid: !!document.querySelector('[data-slot="session-panel-grid"]'),
      toolbar: !!document.querySelector('[data-slot="vertical-session-toolbar"]'),
      sidebar: !!document.querySelector('[data-slot="project-sidebar"]'),
    }
  })

  const closed = await closeAllPanels()
  result.layout.framesAfterReset = closed
  result.geometry = { panels0: await geometrySnapshot() }
  await settle(500)
  await shot("panels-0")

  // --- 2) panel-open performance; the first (cold) open sequence is the primary baseline ---
  // Extra rounds re-open from zero after the resize checks, to show warm/repeat variance. The
  // machine and the live session are shared, so raw per-round samples are kept beside the results.
  result.perfRounds = Math.max(1, ROUNDS)
  const samples = new Map()
  const runPerfRound = async (currentRound, capture) => {
    for (const id of PANEL_IDS) {
      const state = await panelButton(id)
      if (!state.exists || state.disabled) {
        const key = `na:${id}`
        if (!samples.has(key)) {
          samples.set(key, { count: null, panel: id, status: state.exists ? "disabled" : "missing", rounds: [] })
        }
        continue
      }
      if (state.pressed) {
        const key = `open:${id}`
        if (!samples.has(key)) samples.set(key, { count: await frames(), panel: id, status: "already-open", rounds: [] })
        continue
      }
      const before = await frames()
      const measurement = await measureToggle(id)
      const after = await frames()
      const key = `${after}:${id}`
      const bucket = samples.get(key) ?? { count: after, panel: id, rounds: [] }
      bucket.rounds.push({ round: currentRound, opened: after > before, ...measurement })
      samples.set(key, bucket)
      if (capture && after > before) {
        await settle(400)
        await shot(`panels-${after}`)
        result.geometry[`panels${after}`] = await geometrySnapshot()
      }
    }
  }
  await runPerfRound(1, true)
  result.openPanels = await frames()

  // --- 3) resize handfeel ---
  // Idle long-task sample: shows whether external load is polluting the drag measurements.
  const idleCursor = await page.evaluate(() => window.__uxAudit.longTasks.length)
  await page.waitForTimeout(1000)
  const idleTasks = await page.evaluate((from) => window.__uxAudit.longTasks.slice(from), idleCursor)
  result.resize.idle = {
    windowMs: 1000,
    longTaskCount: idleTasks.length,
    totalBlockingMs: round(blockingTime(idleTasks)),
  }
  await settle(400)
  const candidates = await splitterCandidates()
  result.resize.candidates = candidates
  if (!candidates.grid) {
    result.resize.column = { status: "missing-grid" }
    result.resize.row = { status: "missing-grid" }
  } else {
    const column = candidates.items.find((item) => item.direction === "horizontal" || item.rect.height > item.rect.width)
    if (!column) {
      result.resize.column = { status: "missing", candidates: candidates.items }
    } else {
      try {
        const marked = await markSplitter('[data-component="resize-handle"], [data-slot="grid-splitter"]', column.index)
        const drag = marked.error ? { status: "error", error: marked.error } : await dragMarked({
          dx: 120,
          dy: 0,
          dimension: "width",
          midShot: "mid-drag-column",
        })
        result.resize.column = {
          selector: THE_SPLITTER(column),
          ...marked,
          ...drag,
        }
      } catch (error) {
        result.resize.column = { status: "error", error: String(error) }
      }
    }

    const row = candidates.items.find((item) => item.direction === "vertical" || item.rect.width > item.rect.height)
    if (!row) {
      result.resize.row = { status: "missing", candidates: candidates.items }
    } else {
      try {
        const marked = await markSplitter('[data-component="resize-handle"], [data-slot="grid-splitter"]', row.index)
        const drag = marked.error ? { status: "error", error: marked.error } : await dragMarked({
          dx: 0,
          dy: 120,
          dimension: "height",
        })
        result.resize.row = {
          selector: THE_SPLITTER(row),
          ...marked,
          ...drag,
        }
      } catch (error) {
        result.resize.row = { status: "error", error: String(error) }
      }
    }
  }

  // Chat boundary: the edge handle may not exist yet; that is a recorded outcome, not a failure.
  try {
    await clearMarkers()
    const chatSplitter = await findChatSplitter()
    if (!chatSplitter.chat) {
      result.resize.chatBoundary = { status: "missing-chat" }
    } else if (!chatSplitter.found) {
      result.resize.chatBoundary = { status: "missing", ...chatSplitter }
    } else {
      const drag = await dragMarked({ dx: -120, dy: 0, dimension: "width" })
      result.resize.chatBoundary = { ...chatSplitter, ...drag }
    }
  } catch (error) {
    result.resize.chatBoundary = { status: "error", error: String(error) }
  }
  await clearMarkers()

  // --- 3b) warm/repeat samples: close every frame and repeat the open sequence ---
  for (let currentRound = 2; currentRound <= Math.max(1, ROUNDS); currentRound += 1) {
    const resetFrames = await closeAllPanels()
    if (resetFrames !== 0) {
      retries.push({ phase: "perf-reset", round: currentRound, frames: resetFrames, at: new Date().toISOString() })
    }
    await runPerfRound(currentRound, false)
  }

  const perfEntries = [...samples.values()]
    .sort((a, b) => (a.count ?? 99) - (b.count ?? 99))
    .map((bucket) => {
      const opened = bucket.rounds.filter((sample) => sample.opened)
      const first = opened.find((sample) => sample.round === 1) ?? opened[0]
      const warm = opened.filter((sample) => sample !== first)
      const latencies = opened.map((sample) => sample.latencyMs).filter((value) => typeof value === "number")
      const taskCounts = opened.map((sample) => sample.longTaskCount).filter((value) => typeof value === "number")
      const blocking = opened.map((sample) => sample.totalBlockingMs).filter((value) => typeof value === "number")
      return {
        count: bucket.count,
        panel: bucket.panel,
        status: bucket.rounds.length ? (opened.length ? "ok" : "no-frame-opened") : bucket.status,
        latencyMs: round(first?.latencyMs ?? null),
        warmLatencyMs: round(
          median(
            warm.map((sample) => sample.latencyMs).filter((value) => typeof value === "number"),
          ),
        ),
        latencyMedianMs: round(median(latencies)),
        latencySamples: latencies.map((value) => round(value)),
        longTaskCount: first?.longTaskCount ?? null,
        longTaskCountMax: taskCounts.length ? Math.max(...taskCounts) : null,
        totalBlockingMs: round(first?.totalBlockingMs ?? null),
        totalBlockingMsMax: blocking.length ? round(Math.max(...blocking)) : null,
        blockingSamples: blocking.map((value) => round(value)),
        longTaskDurations: opened.flatMap((sample) => sample.longTaskDurations ?? []),
        latencyMethod: first?.latencyMethod,
        rounds: bucket.rounds.map((sample) => ({
          round: sample.round,
          opened: sample.opened,
          latencyMs: round(sample.latencyMs),
          longTaskCount: sample.longTaskCount,
          totalBlockingMs: sample.totalBlockingMs,
        })),
      }
    })
  // A count of 0 means the click did not mount a frame (extension missing/errored or the page
  // reloaded mid-measurement). Keep those separate instead of mixing them into the 1..4 table.
  result.panelPerf = perfEntries.filter((entry) => entry.count !== 0)
  result.panelPerfFailures = perfEntries.filter((entry) => entry.count === 0)

  // --- 4) root-screen / draft-row UX checks ---
  let root = null
  for (let attempt = 1; attempt <= 4 && !root; attempt += 1) {
    const loaded = await gotoApp(`${APP}/`, 2400)
    if (!loaded) continue
    if (await onConnectScreen()) {
      retries.push({ phase: "root-connect", attempt, at: new Date().toISOString() })
      await connect()
      continue
    }
    let snapshot = await rootSnapshot()
    if (!snapshot.showsNewSession && !snapshot.showsHome) {
      // With vertical tabs the root renders nothing until the draft redirect settles.
      await page.waitForTimeout(2400)
      snapshot = await rootSnapshot()
    }
    if (snapshot.showsNewSession || snapshot.showsHome) root = snapshot
    else retries.push({ phase: "root-not-rendered", attempt, at: new Date().toISOString() })
  }
  if (!root) throw new Error("root screen never rendered (neither new-session nor home)")
  await shot("root")
  result.ux.root = root
  result.checks.rootShowsNewSession = root.showsNewSession && !root.showsHome
  result.checks.draftUnderNewSession =
    root.draftListPresent && root.draftRowsInDraftList >= 1 && root.draftRowsInProjectGroups === 0
} catch (error) {
  result.fatalError = String(error?.stack ?? error)
  throw error
} finally {
  try {
    result.consoleErrorCount = consoleErrors.length
    result.extensionErrors = consoleErrors.filter((message) => message.includes("[extension]"))
    result.runtimeHealthy = result.extensionErrors.length === 0
    const connectionErrors = consoleErrors.filter((message) =>
      /ERR_CONNECTION_REFUSED|ERR_CONNECTION_RESET|ERR_INSUFFICIENT_RESOURCES|status of 50[03]/.test(message),
    )
    result.environment = {
      connectionErrorCount: connectionErrors.length,
      sourceReloadRetries: retries.filter((entry) => entry.phase === "load").length,
      unstable: connectionErrors.length > 0 || retries.some((entry) => entry.phase === "load"),
    }
    fs.writeFileSync(OUT, JSON.stringify(result, null, 2))
    const relative = (file) => path.relative(process.cwd(), file)
    console.log(`\nwrote ${relative(OUT)}`)
    console.log(`shots (${shots.length}):`)
    for (const file of shots) console.log(`  ${relative(file)}`)

    console.log(`\nPanel open performance (event -> paint; cold=first open, warm=median of ${Math.max(0, result.perfRounds - 1)} re-open round(s))`)
    console.log("count  panel     coldMs  warmMs  samples  coldLT  blockCold  maxBlock  status")
    for (const entry of result.panelPerf) {
      console.log(
        `${String(entry.count ?? "-").padEnd(6)} ${entry.panel.padEnd(9)} ${String(entry.latencyMs ?? "-").padEnd(7)} ` +
          `${String(entry.warmLatencyMs ?? "-").padEnd(7)} ${String(entry.latencySamples?.length ?? "-").padEnd(8)} ` +
          `${String(entry.longTaskCount ?? "-").padEnd(7)} ${String(entry.totalBlockingMs ?? "-").padEnd(10)} ` +
          `${String(entry.totalBlockingMsMax ?? "-").padEnd(9)} ${entry.status}`,
      )
    }

    console.log("\nResize handfeel")
    if (result.resize.idle) {
      console.log(
        `idle 1s window: longTasks=${result.resize.idle.longTaskCount} blockingMs=${result.resize.idle.totalBlockingMs}`,
      )
    }
    const summarize = (name, drag) => {
      if (!drag) return
      console.log(
        `${name.padEnd(12)} status=${(drag.status ?? "?").padEnd(9)} initial=${drag.initial ?? "-"} final=${drag.final ?? "-"} ` +
          `delta=${drag.totalDelta ?? "-"} changedSteps=${drag.changedSteps ?? "-"}/${drag.steps ?? "-"} ` +
          `maxDev=${drag.maxDeviation ?? "-"} longTasks=${drag.longTaskCount ?? "-"} blockingMs=${drag.totalBlockingMs ?? "-"}` +
          (drag.error ? ` error=${drag.error}` : ""),
      )
    }
    summarize("column", result.resize.column)
    summarize("row", result.resize.row)
    summarize("chat", result.resize.chatBoundary)

    console.log("\nUX checks (vertical layout)")
    console.log(`  root shows new-session draft screen : ${result.checks.rootShowsNewSession ? "PASS" : "FAIL"} (home=${result.ux.root?.showsHome})`)
    console.log(
      `  draft under 新建会话, outside groups : ${result.checks.draftUnderNewSession ? "PASS" : "FAIL"} ` +
        `(list=${result.ux.root?.draftListPresent}, inList=${result.ux.root?.draftRowsInDraftList}, ` +
        `inGroups=${result.ux.root?.draftRowsInProjectGroups})`,
    )
    console.log(`\nconsole errors: ${consoleErrors.length} (extension: ${result.extensionErrors?.length ?? 0})`)
    if (result.panelPerfFailures?.length) {
      console.log(
        `failed toggle measurements: ${result.panelPerfFailures.map((entry) => `${entry.panel}(${entry.status})`).join(", ")}`,
      )
    }
    if (result.environment?.unstable) {
      console.log(
        `\nWARNING: environment unstable (connectionErrorCount=${result.environment.connectionErrorCount}, ` +
          `sourceReloadRetries=${result.environment.sourceReloadRetries}); treat the numbers as indicative and rerun.`,
      )
    }
  } catch (error) {
    console.error(`failed to write report: ${error}`)
  }
  await browser.close()
}

function THE_SPLITTER(item) {
  return item && item.slot === "grid-splitter"
    ? `[data-slot="grid-splitter"]`
    : `[data-component="resize-handle"]${item?.direction ? `[data-direction="${item.direction}"]` : ""}`
}
