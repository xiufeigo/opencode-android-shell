#!/usr/bin/env node
/**
 * Preview harness for the OpenCode Android shell.
 *
 * Boots a real built SPA (packaged assets or a fresh Vite build) against a mock
 * OpenCode v2 backend that serves several projects and sessions, seeds the
 * exact localStorage shape the production persistence layer reads, and takes
 * Playwright screenshots at 2560x1540 (1280x770 @2x).
 *
 * The mock backend intentionally mirrors `scripts/verify-android-terminal-release.mjs`
 * (same route set, same envelope shapes) so a page that boots there boots here.
 *
 * Usage:
 *   node preview.mjs --ui <ui-root> --out <dir> --name baseline
 *   node preview.mjs --ui <ui-root> --out <dir> --name after --actions '[{"click":"[data-slot=project-row][data-project=...]"}]'
 */
import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import crypto from "node:crypto"
import { fileURLToPath } from "node:url"

function findRepo(start) {
  let dir = start
  for (let index = 0; index < 8; index++) {
    if (fs.existsSync(path.join(dir, "frontend/android/upstream.json"))) return dir
    dir = path.dirname(dir)
  }
  return start
}

const REPO = findRepo(path.dirname(fileURLToPath(import.meta.url)))
const PLAYWRIGHT =
  process.env.PREVIEW_PLAYWRIGHT ||
  "C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"
const CHROMIUM =
  process.env.PREVIEW_CHROMIUM || "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"

const argv = process.argv.slice(2)
const arg = (name, fallback) => {
  const index = argv.indexOf(`--${name}`)
  return index === -1 ? fallback : argv[index + 1]
}

const UI_ROOT = path.resolve(arg("ui", path.join(REPO, "webapp/src/main/assets/www/ui")))
const OUT_DIR = path.resolve(arg("out", path.join(REPO, "research/sidebar-projects/shots")))
const NAME = arg("name", "preview")
const WIDTH = Number(arg("width", 1280))
const HEIGHT = Number(arg("height", 770))
const DSF = Number(arg("dsf", 2))
const THEME = arg("theme", "light")
const LOCALE = arg("locale", "zh")
const ROUTE = arg("route", "") // explicit route, else session route
const ACTIONS = (() => {
  const file = arg("actions-file", "")

  if (file) return JSON.parse(fs.readFileSync(file, "utf8"))
  return JSON.parse(arg("actions", "[]"))
})()
const HOST_JS = path.join(REPO, "webapp/src/main/assets/www/oc-host.js")

fs.mkdirSync(OUT_DIR, { recursive: true })

/* ------------------------------------------------------------------ data */

const PROGRAMS = "C:/sensorsdata/main/program"
const PROJECTS = [
  { id: "prj_shell", worktree: `${PROGRAMS}/opencode-android-shell` },
  { id: "prj_ui", worktree: `${PROGRAMS}/opencode-ui` },
  { id: "prj_claude", worktree: `${PROGRAMS}/opencode-claudeUI` },
  { id: "prj_dsh", worktree: `${PROGRAMS}/deepseek-harness` },
  { id: "prj_yiheda", worktree: `${PROGRAMS}/yiheda` },
]
const projectID = (directory) =>
  PROJECTS.find((project) => directory.replaceAll("\\", "/").startsWith(project.worktree))?.id ?? "prj_shell"

const now = Date.now()
const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR
/** [id, directory, title, age ms] — the first session is the active route. */
const SESSION_ROWS = [
  ["ses_settings_reset", PROJECTS[0].worktree, "给会话设置添加重置按钮", 4 * MIN],
  ["ses_branch_check", PROJECTS[0].worktree, "检查本地分支是否为最新版本", 13 * MIN],
  ["ses_claude_branch", PROJECTS[0].worktree, "新建 claude-UI 分支", 2 * HOUR],
  ["ses_terminal_resize", PROJECTS[0].worktree, "修复 Android 终端 resize 抖动", 5 * HOUR],
  ["ses_vertical_tree", PROJECTS[0].worktree, "把垂直标签页改成项目树", 7 * HOUR + 40 * MIN],
  ["ses_patch_audit", PROJECTS[0].worktree, "整理 OpenCode 上游补丁清单", 2 * DAY],
  ["ses_ui_hierarchy", PROJECTS[1].worktree, "设置页信息层级梳理", 40 * MIN],
  ["ses_unread_badge", PROJECTS[1].worktree, "通知中心未读数不一致", 4 * HOUR],
  ["ses_tab_drag", PROJECTS[1].worktree, "标签拖拽排序回归", 3 * DAY],
  ["ses_sidebar_research", PROJECTS[2].worktree, "左侧栏项目分组调研", 1 * HOUR],
  ["ses_history_search", PROJECTS[3].worktree, "历史会话搜索命中错误", 6 * HOUR],
  ["ses_remote_reconnect", PROJECTS[4].worktree, "验证远程终端重连", 2 * DAY],
]
const SESSIONS = SESSION_ROWS.map(([id, directory, title, age]) => ({
  id,
  projectID: projectID(directory),
  directory,
  title,
  created: now - age - 12 * MIN,
  updated: now - age,
}))
const ACTIVE_SESSION = SESSIONS[0]

/** A short conversation so the main pane is not a blank page. */
const CONVERSATION = {
  [ACTIVE_SESSION.id]: [
    {
      id: "msg_user_1",
      type: "user",
      time: { created: ACTIVE_SESSION.updated - 9 * MIN },
      text: "垂直标签页的左侧栏现在只有一排打开的标签，想找某个项目的旧会话还得先回主页。能不能把主页的项目列表搬进左侧栏，点开项目就展开它下面的会话？",
    },
    {
      id: "msg_assistant_1",
      type: "assistant",
      time: { created: ACTIVE_SESSION.updated - 8 * MIN, completed: ACTIVE_SESSION.updated - 7 * MIN },
      agent: "build",
      model: { id: "deepseek-v4.1-flash", providerID: "deepseek" },
      finish: "stop",
      content: [
        {
          type: "text",
          text: "可以。左侧栏可以复用主页的 Project 数据（本地项目列表）和 Session 索引，按项目分组、可折叠展开；点击会话直接开标签页。这样垂直布局下就不用回到主页找会话了，预计改动集中在一个侧栏组件和一处挂载点。",
        },
      ],
    },
  ],
}

const sessionInfo = (session) => ({
  id: session.id,
  projectID: session.projectID,
  cost: 0,
  tokens: { input: 0, output: 0, reasoning: 0, cache: { read: 0, write: 0 } },
  time: { created: session.created, updated: session.updated },
  title: session.title,
  location: { directory: session.directory },
})

const projectInfo = (project) => ({
  id: project.id,
  worktree: project.worktree,
  vcs: true,
  time: { created: now - 30 * DAY, initialized: now - 30 * DAY },
})

const directoryOf = (req, fallback) => {
  const raw = req.headers["x-opencode-directory"]
  if (!raw) return fallback
  try {
    return decodeURIComponent(String(raw))
  } catch {
    return fallback
  }
}

/* ------------------------------------------------------------- ui server */

const contentTypeOf = (file) => {
  if (file.endsWith(".html")) return "text/html; charset=utf-8"
  if (file.endsWith(".js") || file.endsWith(".mjs")) return "text/javascript; charset=utf-8"
  if (file.endsWith(".css")) return "text/css; charset=utf-8"
  if (file.endsWith(".json") || file.endsWith(".webmanifest")) return "application/json; charset=utf-8"
  if (file.endsWith(".svg")) return "image/svg+xml"
  if (file.endsWith(".png")) return "image/png"
  if (file.endsWith(".ico")) return "image/x-icon"
  if (file.endsWith(".woff2")) return "font/woff2"
  if (file.endsWith(".woff")) return "font/woff"
  if (file.endsWith(".ttf")) return "font/ttf"
  return "application/octet-stream"
}

function startUiServer() {
  const server = http.createServer((req, res) => {
    const pathname = decodeURIComponent(new URL(req.url || "/", "http://127.0.0.1").pathname)
    // Android assets cannot keep `_`-prefixed directories, so the packaged build
    const serve = (file) => {
      try {
        const body = fs.readFileSync(file)
        res.writeHead(200, { "content-type": contentTypeOf(file), "cache-control": "no-cache" })
        res.end(req.method === "HEAD" ? undefined : body)
        return true
      } catch {
        return false
      }
    }
    if (pathname === "/oc-host.js") {
      if (serve(HOST_JS)) return
    }
    // `/_assets/*` lives under `oa/*` in the packaged Android UI, but a plain
    // Vite build (e.g. a prototype build) keeps `_assets/*`. Try both.
    const candidates = []
    if (pathname.startsWith("/_assets/")) {
      const rest = pathname.slice("/_assets/".length)
      candidates.push(path.join(UI_ROOT, "oa", rest), path.join(UI_ROOT, "_assets", rest))
    } else if (pathname !== "/") {
      candidates.push(path.join(UI_ROOT, pathname.slice(1)))
    }
    for (const candidate of candidates) {
      if (candidate.startsWith(UI_ROOT) && !pathname.endsWith("/") && serve(candidate)) return
    }
    if (serve(path.join(UI_ROOT, "index.html"))) return
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" })
    res.end("not found")
  })
  return listen(server)
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.on("error", reject)
    server.listen(0, "127.0.0.1", () => resolve(server))
  })
}

/* --------------------------------------------------------- mock backend */

const backendHits = []
function startBackend(origin) {
  const hits = backendHits
  const json = (res, status, body, cors) => {
    const text = JSON.stringify(body)
    res.writeHead(status, { ...cors, "content-type": "application/json", "content-length": Buffer.byteLength(text) })
    res.end(text)
  }
  const corsOf = (req) => {
    const origin_ = req.headers.origin || "*"
    return {
      "access-control-allow-origin": origin_,
      "access-control-allow-credentials": origin_ === "*" ? undefined : "true",
      "access-control-allow-headers":
        req.headers["access-control-request-headers"] ||
        "Authorization, Content-Type, Accept, Last-Event-ID, x-opencode-directory, x-opencode-workspace, x-opencode-ticket",
      "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS",
      vary: "Origin",
    }
  }

  const server = http.createServer((req, res) => {
    const cors = Object.fromEntries(Object.entries(corsOf(req)).filter(([, value]) => value != null))
    const url = new URL(req.url || "/", "http://127.0.0.1")
    const p = url.pathname
    const method = req.method || "GET"
    hits.push(`${method} ${p}`)
    if (method === "OPTIONS") {
      res.writeHead(204, cors)
      res.end()
      return
    }
    const send = (body, status = 200) => json(res, status, body, cors)
    const directory = directoryOf(req, ACTIVE_SESSION.directory)

    if (p === "/api/health" || p === "/global/health" || p === "/health") {
      send({ healthy: true, version: "preview", time: Date.now() })
      return
    }
    if (p === "/api/info" || p === "/global/info" || p === "/info") {
      send({ version: "0.0.0-preview", pid: 1, urls: [origin], paths: { tmp: directory } })
      return
    }
    if (p === "/event" || p === "/global/event" || p === "/api/event") {
      res.writeHead(200, {
        ...cors,
        "content-type": "text/event-stream",
        "cache-control": "no-store",
        connection: "keep-alive",
      })
      // The client only reports `connected` when the stream's first event is
      // server.connected (packages/client/src/solid/connection.ts). Without it
      // every query gated on connection.status() stays disabled.
      res.write(`data: ${JSON.stringify({ id: `evt_${crypto.randomUUID()}`, type: "server.connected", data: {} })}\n\n`)
      const timer = setInterval(() => res.write(": ping\n\n"), 10_000)
      req.on("close", () => clearInterval(timer))
      return
    }
    if (p === "/config" || p === "/global/config" || p === "/api/config") {
      send([])
      return
    }
    if (p === "/api/config/shell") return send([{ path: "/bin/bash", name: "bash", acceptable: true }])
    if (p === "/api/provider" || p === "/provider") return send({ providers: [], default: {} })
    if (p === "/api/model" || p === "/model") return send({ provider: {}, models: {} })
    if (p === "/api/agent" || p === "/agent") return send([])
    if (p === "/api/command" || p === "/command") return send([])
    if (p === "/api/skill" || p === "/skill") return send([])
    if (p === "/api/mcp" || p === "/mcp") return send({})
    if (p === "/api/plugin" || p === "/plugin") return send([])
    if (p === "/api/permission/request" || p === "/permission/request") return send([])
    if (p === "/api/question/request" || p === "/question/request") return send([])
    // GET /api/vcs is Location.response(Vcs.Info): `data.branch` is required. The status
    // route returns a FileStatus[] instead, so the two shapes must not be merged.
    if (p === "/api/vcs" || p === "/vcs") {
      return send({ location: { directory }, data: { branch: { current: "dev", default: "dev" } } })
    }
    if (p === "/api/vcs/status" || p === "/vcs/status") {
      return send({ location: { directory }, data: [] })
    }
    if (p === "/api/vcs/diff" || p === "/vcs/diff") return send({ location: { directory }, data: [] })
    if (p === "/api/project" || p === "/project") return send(PROJECTS.map(projectInfo))
    if (p === "/api/project/current" || p === "/project/current") {
      const project = PROJECTS.find((item) => directory.startsWith(item.worktree)) ?? PROJECTS[0]
      return send(projectInfo(project))
    }
    if (p === "/path" || p === "/api/location" || p === "/location") {
      const project = PROJECTS.find((item) => directory.startsWith(item.worktree)) ?? PROJECTS[0]
      return send({
        directory,
        project: { id: project.id, directory: project.worktree, canonical: project.worktree },
      })
    }
    // fs.list / fs.find answer Location.response(FileSystem.Entry[]): the directory
    // listing is under `data`, so a bare array fails the client's decode.
    if (p === "/file" || p === "/api/fs/list" || p === "/api/fs/find") {
      return send({ location: { directory }, data: [] })
    }
    if (p === "/file/status") return send([])
    if (p.startsWith("/file/content")) return send({ content: "" })
    if (p === "/api/pty" || p === "/pty") return send({ data: [] })
    if (p === "/api/worktree" && method === "GET") return send([])
    if (p === "/api/worktree/refresh") {
      res.writeHead(204, cors)
      res.end()
      return
    }
    if (/^\/api\/pty\/[^/]+$/.test(p) || /^\/pty\/[^/]+$/.test(p)) {
      return send({ location: { directory }, data: { id: p.split("/").pop(), title: "shell", cwd: directory } })
    }

    if (p === "/api/session/active") return send({ data: {} })
    if (p.startsWith("/api/rpc/")) {
      // Extension RPC answers `{ output }`, not `{ data }`. The server-browser state is
      // a {version, tabs, focusedTabID} struct; the other methods only need a JSON value.
      return send({ output: p.endsWith("/state") ? { version: 1, tabs: [], focusedTabID: null } : null })
    }
    if (p === "/api/experimental/session/import" && method === "POST") return send({ data: sessionInfo(ACTIVE_SESSION) })
    if (p === "/api/session" || p === "/session") {
      if (method === "POST") {
        return send({
          data: sessionInfo({
            id: `ses_${crypto.randomUUID().slice(0, 8)}`,
            projectID: projectID(directory),
            directory,
            title: "New session",
            created: Date.now(),
            updated: Date.now(),
          }),
          cursor: {},
        })
      }
      return send({ data: SESSIONS.map(sessionInfo), cursor: {} })
    }
    const sessionMatch = /^\/api\/session\/([^/]+)$/.exec(p) || /^\/session\/([^/]+)$/.exec(p)
    if (sessionMatch) {
      const id = decodeURIComponent(sessionMatch[1])
      const session = SESSIONS.find((item) => item.id === id) ?? {
        ...ACTIVE_SESSION,
        id,
        directory,
        title: "Unknown session",
      }
      if (method === "DELETE") {
        res.writeHead(204, cors)
        res.end()
        return
      }
      return send({ data: sessionInfo(session) })
    }
    if (/message$/.test(p)) {
      const id = decodeURIComponent(p.split("/").slice(-2)[0] ?? "")
      return send({ data: CONVERSATION[id] ?? [], cursor: {} })
    }
    if (/\/(inbox|context|permission|form|child|session)$/.test(p)) return send({ data: [] })
    if (/todo$/.test(p)) return send({ data: { todos: [] } })
    if (/(diff|shell)$/.test(p)) return send({ data: [] })
    return send({ data: [] })
  })
  return listen(server)
}

/* -------------------------------------------------------------- seeding */

function seedSource(origin) {
  const state = {
    list: [{ type: "http", http: { url: origin } }],
    hidden: {},
    projects: { [origin]: PROJECTS.map((project) => ({ worktree: project.worktree, expanded: true })) },
    lastProject: { [origin]: PROJECTS[0].worktree },
    recentlyClosed: {},
  }
  const tabs = [SESSIONS[0], SESSIONS[1], SESSIONS[2], SESSIONS[3]].map((session) => ({
    type: "session",
    server: origin,
    sessionId: session.id,
  }))
  const layout = {
    sidebar: { opened: false, width: 344, workspaces: {}, workspacesDefault: false },
    terminal: { height: 280, opened: false },
    review: { diffStyle: "split", panelOpened: false },
    fileTree: { opened: false, width: 200, tab: "changes" },
    session: { width: 600 },
    mobileSidebar: { opened: false },
    home: { selection: { server: origin } },
  }
  return `(() => {
  try {
    const server = ${JSON.stringify(state)}
    localStorage.setItem("opencode.global.dat:server", JSON.stringify(server))
    localStorage.setItem("opencode.global.dat:server.v3", JSON.stringify(server))
    const settings = ${JSON.stringify({
      appearance: { fontSize: 14, mono: "", sans: "", terminal: "", tabLayout: "vertical", showProjectName: false },
    })}
    // Settings persist through persisted({ key: "settings.v3" }), which has no
    // storage namespace: it reads plain localStorage. Keep the prefixed copy as
    // well so builds that namespace it still open vertically.
    localStorage.setItem("settings.v3", JSON.stringify(settings))
    localStorage.setItem("opencode.global.dat:settings.v3", JSON.stringify(settings))
    localStorage.setItem("opencode.global.dat:layout", JSON.stringify(${JSON.stringify(layout)}))
    localStorage.setItem("opencode.global.dat:language", JSON.stringify({ locale: ${JSON.stringify(LOCALE)} }))
    localStorage.setItem("opencode.window.browser.dat:tabs", JSON.stringify(${JSON.stringify(tabs)}))
    localStorage.setItem("opencode.window.browser.dat:tabs.recent", JSON.stringify({ key: ${JSON.stringify(
      `${origin}\n/server/${Buffer.from(origin, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "")}/session/${SESSIONS[0].id}`,
    )} }))
    localStorage.setItem("opencode-color-scheme", ${JSON.stringify(THEME)})
    localStorage.setItem("opencode-theme-id", "oc-2")
  } catch (error) {
    window.__previewSeedError = String(error)
  }
})()`
}

function nativeBridgeSource() {
  return `(() => {
  const calls = []
  window.__ocNativeCalls = calls
  window.__ocPlatform = {
    onmessage: null,
    postMessage(raw) {
      const request = JSON.parse(raw)
      calls.push({ name: request.method, args: request.payload })
      const value = request.method === "readClipboardImage" ? null : true
      queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ id: request.id, value }) }))
    },
  }
  const record = (name, args) => { try { calls.push({ name, args }) } catch {} }
  const secret = new Map()
  window.__ocAndroidNative = {
    hostInfo: () => JSON.stringify({ model: "Chromium-Preview", sdk: 34, brand: "preview" }),
    hasNotificationPermission: () => 1,
    requestNotificationPermission: () => record("requestNotificationPermission"),
    postMessage: (t, b, s) => { record("postMessage", [t, b, s]); return 0 },
    pickFile: (a, m) => record("pickFile", [a, m]),
    readPickedFile: () => JSON.stringify({ error: "no file" }),
    saveContent: (n, m, b) => record("saveContent", [n, m, b]),
    clearPickedFile: () => record("clearPickedFile"),
    share: (t, s) => { record("share", [t, s]); return true },
    getSecret: (k) => (secret.has(k) ? secret.get(k) : null),
    setSecret: (k, v) => { secret.set(k, v); return true },
    setKeepScreenOn: (on) => record("setKeepScreenOn", [on]),
    setImeMode: (m) => { record("setImeMode", [m]); window.__ocImeMode = m },
    getImeMode: () => window.__ocImeMode || "resize",
    retry: () => record("retry"),
    reportTheme: (dark) => record("reportTheme", [dark]),
    proxyOrigin: () => ${JSON.stringify("http://127.0.0.1:0")},
    proxyPrefix: () => "/opencode-proxy/unused",
  }
})()`
}

function ocHostLoaderSource(hostSource) {
  return `(() => {
  if (window.__ocHostInstalled) return
  const run = () => {
    if (window.__ocHostInstalled) return true
    const root = document.documentElement
    if (!root) return false
    window.__ocHostInstalled = true
    try {
      ;(0, eval)(window.__ocHostSource)
    } catch (err) {
      window.__ocHostError = String((err && err.stack) || err)
    }
    return true
  }
  window.__ocHostSource = ${JSON.stringify(hostSource)}
  if (run()) return
  const obs = new MutationObserver(() => { if (run()) obs.disconnect() })
  obs.observe(document, { childList: true, subtree: true })
  document.addEventListener("readystatechange", run, { once: true })
  document.addEventListener("DOMContentLoaded", run, { once: true })
})()`
}

/* ---------------------------------------------------------------- main */

const backend = await startBackend("http://127.0.0.1")
const backendOrigin = `http://127.0.0.1:${backend.address().port}`
const ui = await startUiServer()
const uiOrigin = `http://127.0.0.1:${ui.address().port}`
// The mock backend advertised `origin` before it knew its port; every response
// that embeds it (info.urls) is informational only.
const report = { name: NAME, uiRoot: UI_ROOT, uiOrigin, backendOrigin, viewport: { width: WIDTH, height: HEIGHT, dsf: DSF }, theme: THEME, steps: [], errors: [] }

const b64 = (value) => Buffer.from(value, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "")
const route = ROUTE || `/server/${b64(backendOrigin)}/session/${ACTIVE_SESSION.id}`
const url = `${uiOrigin}${route}`

const { chromium } = await import(`file:///${PLAYWRIGHT.replace(/\\/g, "/")}`)
const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })
let context
try {
  context = await browser.newContext({
    viewport: { width: WIDTH, height: HEIGHT },
    deviceScaleFactor: DSF,
    locale: LOCALE === "zh" ? "zh-CN" : "en-US",
    colorScheme: THEME === "dark" ? "dark" : "light",
    isMobile: false,
    hasTouch: false,
  })
  const page = await context.newPage()
  page.on("pageerror", (error) => report.errors.push(String(error.stack || error)))
  page.on("console", (message) => {
    const type = message.type()
    if (type !== "error" && type !== "warning" && type !== "log") return
    report.steps.push({ console: type, text: message.text().slice(0, 4000) })
  })
  page.on("requestfailed", (request) => {
    report.steps.push({ requestFailed: request.url().slice(0, 200), error: request.failure()?.errorText })
  })
  await page.addInitScript({
    content:
      seedSource(backendOrigin) +
      ";" +
      nativeBridgeSource() +
      ";" +
      ocHostLoaderSource(fs.readFileSync(HOST_JS, "utf8")),
  })
  await page.goto(url, { waitUntil: "domcontentloaded", timeout: 60_000 })
  const startedAt = Date.now()
  const responses = []
  page.on("response", (response) => {
    const target = response.url()
    if (!target.startsWith(backendOrigin)) return
    responses.push({ t: Date.now() - startedAt, path: target.slice(backendOrigin.length), status: response.status() })
  })
  const toastAt = await page
    .waitForFunction(() => document.body.innerText.includes("请求失败"), null, { timeout: 6_000 })
    .then(() => Date.now() - startedAt)
    .catch(() => null)
  if (toastAt !== null) report.toast = { at: toastAt, responses: responses.slice(-14) }
  await page.waitForSelector(
    '[data-slot="vertical-tabs-sidebar"], [data-slot="titlebar-tabs"], [data-slot="mobile-tabs-trigger"]',
    { timeout: 30_000 },
  )
  await page.waitForTimeout(1200)
  const state = await page.evaluate(() => ({
    url: location.pathname,
    vertical: !!document.querySelector('[data-slot="vertical-tabs-sidebar"]'),
    homeTabText: document.querySelector('[data-slot="titlebar-tab-list"]')?.textContent ?? null,
    bodyText: document.body.innerText.slice(0, 400),
    seedError: window.__previewSeedError ?? null,
    hostError: window.__ocHostError ?? null,
    settings: localStorage.getItem("opencode.global.dat:settings.v3"),
  }))
  report.steps.push({ boot: state })
  for (const action of ACTIONS) {
    if (action.click) await page.click(action.click, { timeout: 10_000 })
    if (action.hover) await page.hover(action.hover, { timeout: 10_000 })
    if (action.move) await page.mouse.move(action.move[0], action.move[1])
    if (action.drag) {
      await page.mouse.move(action.drag[0][0], action.drag[0][1])
      await page.mouse.down()
      await page.mouse.move(action.drag[1][0], action.drag[1][1], { steps: 12 })
      await page.mouse.up()
    }
    if (action.clickAt) await page.mouse.click(action.clickAt[0], action.clickAt[1])
    if (action.key) await page.keyboard.press(action.key)
    if (action.wait) await page.waitForTimeout(action.wait)
    if (action.selector) await page.waitForSelector(action.selector, { timeout: 20_000 })
    if (action.eval) report.steps.push({ eval: action.eval, value: await page.evaluate(`(${action.eval})()`) })
    report.steps.push({ action })
  }
  await page.waitForTimeout(400)
  const shot = path.join(OUT_DIR, `${NAME}.png`)
  await page.screenshot({ path: shot })
  report.screenshot = shot
  report.backendHits = backendHits.slice()
  report.state = await page.evaluate(() => ({
    vertical: !!document.querySelector('[data-slot="vertical-tabs-sidebar"]'),
    sidebarText: document.querySelector('[data-slot="vertical-tabs-sidebar"]')?.innerText.slice(0, 1500) ?? null,
  }))
  console.log(JSON.stringify(report, null, 2))
} catch (error) {
  report.fatal = String(error.stack || error)
  if (context) {
    const pages = context.pages()
    const page = pages[0]
    if (page) {
      try {
        await page.screenshot({ path: path.join(OUT_DIR, `${NAME}-failure.png`) })
        report.failureText = (await page.locator("body").innerText()).slice(0, 2000)
        report.failureUrl = page.url()
      } catch (shotError) {
        report.failureShotError = String(shotError.message || shotError)
      }
    }
  }
  console.error(JSON.stringify(report, null, 2))
  process.exitCode = 1
} finally {
  if (context) await context.close().catch(() => {})
  await browser.close().catch(() => {})
  await new Promise((resolve) => backend.close(resolve))
  await new Promise((resolve) => ui.close(resolve))
}


