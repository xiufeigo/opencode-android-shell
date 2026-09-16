#!/usr/bin/env bun
/**
 * Headless Chrome self-test for the embedded OpenCode assets.
 *
 * Serves files at the same pathname WebViewAssetLoader uses:
 *   /assets/opencode-app/index.html  (hostname appassets.androidplatform.net)
 *
 * Official Desktop boots with MemoryRouter at "/". A Browser Router against the
 * asset file path paints Titlebar (grid-plus / +) and never mounts NewHome.
 */
import { spawn } from "node:child_process"
import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)))
const assets = path.join(root, "app/src/main/assets/opencode-app")
const HOST = "appassets.androidplatform.net"
const ANDROID_MOUNT = "/assets/opencode-app"
const reportPath = process.env.SELFTEST_REPORT || path.join(root, "selftest-embedded-ui.json")
const screenshotPath =
  process.env.SELFTEST_SCREENSHOT || "/opt/cursor/artifacts/selftest-home-android-path.png"
const portraitScreenshotPath =
  process.env.SELFTEST_PORTRAIT_SCREENSHOT || "/opt/cursor/artifacts/selftest-settings-portrait.png"
const portraitScrolledScreenshotPath =
  process.env.SELFTEST_PORTRAIT_SCROLLED_SCREENSHOT ||
  "/opt/cursor/artifacts/selftest-settings-portrait-scrolled.png"
const portraitCollapsedScreenshotPath =
  process.env.SELFTEST_PORTRAIT_COLLAPSED_SCREENSHOT ||
  "/opt/cursor/artifacts/selftest-settings-portrait-collapsed.png"
const homeEmptyScreenshotPath =
  process.env.SELFTEST_HOME_EMPTY_SCREENSHOT ||
  "/opt/cursor/artifacts/selftest-home-empty-portrait.png"

function contentType(file) {
  if (file.endsWith(".html")) return "text/html; charset=utf-8"
  if (file.endsWith(".js")) return "application/javascript; charset=utf-8"
  if (file.endsWith(".css")) return "text/css; charset=utf-8"
  if (file.endsWith(".svg")) return "image/svg+xml"
  if (file.endsWith(".png")) return "image/png"
  if (file.endsWith(".woff2")) return "font/woff2"
  if (file.endsWith(".json") || file.endsWith(".webmanifest")) return "application/json"
  return "application/octet-stream"
}

function startServer() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url || "/", `http://${HOST}`)
      let rel = decodeURIComponent(url.pathname)
      if (rel.startsWith(ANDROID_MOUNT)) rel = rel.slice(ANDROID_MOUNT.length) || "/"
      if (rel === "/") rel = "/index.html"
      const file = path.join(assets, rel)
      if (!file.startsWith(assets) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
        res.writeHead(404)
        res.end("not found")
        return
      }
      res.writeHead(200, { "content-type": contentType(file) })
      fs.createReadStream(file).pipe(res)
    })
    server.listen(0, "127.0.0.1", () => resolve(server))
    server.on("error", reject)
  })
}

function parseProxyPath(pathname, search, token) {
  const prefix = "/opencode-proxy/"
  if (!pathname.startsWith(prefix)) return null
  const rest = pathname.slice(prefix.length)
  const slash = rest.indexOf("/")
  if (slash <= 0) return null
  if (rest.slice(0, slash) !== token) return null
  const after = rest.slice(slash + 1)
  const next = after.indexOf("/")
  const encoded = next < 0 ? after : after.slice(0, next)
  const upstreamPath = next < 0 ? "/" : after.slice(next) || "/"
  let backend
  try {
    backend = decodeURIComponent(encoded).replace(/\/+$/, "")
  } catch {
    return null
  }
  if (!/^https?:\/\//i.test(backend)) return null
  const query = search.startsWith("?") ? search.slice(1) : search
  const upstreamUrl = query ? `${backend}${upstreamPath}?${query}` : `${backend}${upstreamPath}`
  return { upstreamUrl }
}

function localCorsHeaders(req) {
  const origin = req.headers.origin || "*"
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Credentials": origin === "*" ? undefined : "true",
    "Access-Control-Allow-Headers":
      req.headers["access-control-request-headers"] || "Authorization, Content-Type, Accept, Last-Event-ID",
    "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS",
    "Access-Control-Allow-Private-Network": "true",
    Vary: "Origin",
  }
}

/** Upstream OpenCode mock: NEVER sends Access-Control-Allow-Origin. */
function startNoCorsUpstream() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url || "/", "http://127.0.0.1")
      if (req.method === "OPTIONS") {
        res.writeHead(204)
        res.end()
        return
      }
      if (url.pathname === "/event") {
        res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-store" })
        res.write("data: one\n\n")
        setTimeout(() => {
          res.write("data: two\n\n")
          res.end()
        }, 280)
        return
      }
      if (url.pathname === "/api/health" || url.pathname === "/global/health") {
        if (url.searchParams.get("auth") === "1") {
          res.writeHead(401, {
            "content-type": "application/json",
            "www-authenticate": 'Basic realm="Secure Area"',
          })
          res.end(JSON.stringify({ message: "Authentication required" }))
          return
        }
        res.writeHead(200, { "content-type": "application/json" })
        res.end(
          JSON.stringify({
            healthy: true,
            version: "selftest",
            origin: req.headers.origin || null,
          }),
        )
        return
      }
      res.writeHead(404)
      res.end("not found")
    })
    server.listen(0, "127.0.0.1", () => resolve(server))
    server.on("error", reject)
  })
}

/** Dedicated origin-only reverse-proxy (matches LocalGateway.originFor). */
function startDedicatedOrigin(backendUrl) {
  const backend = backendUrl.replace(/\/+$/, "")
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const cors = Object.fromEntries(Object.entries(localCorsHeaders(req)).filter(([, v]) => v != null))
      if (req.method === "OPTIONS") {
        res.writeHead(204, cors)
        res.end()
        return
      }
      const url = new URL(req.url || "/", "http://127.0.0.1")
      const upstream = new URL(backend)
      const headers = { ...req.headers }
      delete headers.origin
      delete headers.host
      delete headers.referer
      delete headers.connection
      headers.host = upstream.host
      const p = http.request(
        {
          hostname: upstream.hostname,
          port: upstream.port,
          path: `${url.pathname}${url.search}`,
          method: req.method,
          headers,
        },
        (upRes) => {
          const out = { ...cors }
          for (const [name, value] of Object.entries(upRes.headers)) {
            if (!value) continue
            const lower = name.toLowerCase()
            if (lower === "access-control-allow-origin" || lower === "connection" || lower === "transfer-encoding") {
              continue
            }
            out[name] = value
          }
          res.writeHead(upRes.statusCode || 502, out)
          upRes.on("data", (chunk) => res.write(chunk))
          upRes.on("end", () => res.end())
        },
      )
      p.on("error", (err) => {
        if (!res.headersSent) {
          res.writeHead(502, { ...cors, "content-type": "application/json" })
        }
        res.end(JSON.stringify({ error: String(err.message || err) }))
      })
      req.pipe(p)
    })
    server.listen(0, "127.0.0.1", () => resolve(server))
    server.on("error", reject)
  })
}

function startLocalProxy(token) {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const cors = Object.fromEntries(Object.entries(localCorsHeaders(req)).filter(([, v]) => v != null))
      const url = new URL(req.url || "/", "http://127.0.0.1")
      if (req.method === "OPTIONS" && url.pathname.startsWith("/opencode-proxy/")) {
        res.writeHead(204, cors)
        res.end()
        return
      }
      const parsed = parseProxyPath(url.pathname, url.search, token)
      if (!parsed) {
        res.writeHead(404, { ...cors, "content-type": "application/json" })
        res.end(JSON.stringify({ error: "unknown proxy path" }))
        return
      }
      const upstream = new URL(parsed.upstreamUrl)
      const headers = { ...req.headers }
      delete headers.origin
      delete headers.host
      delete headers.referer
      delete headers.connection
      headers.host = upstream.host
      const p = http.request(
        {
          hostname: upstream.hostname,
          port: upstream.port,
          path: `${upstream.pathname}${upstream.search}`,
          method: req.method,
          headers,
        },
        (upRes) => {
          const out = { ...cors }
          for (const [name, value] of Object.entries(upRes.headers)) {
            if (!value) continue
            const lower = name.toLowerCase()
            if (lower === "access-control-allow-origin" || lower === "connection" || lower === "transfer-encoding") {
              continue
            }
            out[name] = value
          }
          res.writeHead(upRes.statusCode || 502, out)
          upRes.on("data", (chunk) => res.write(chunk))
          upRes.on("end", () => res.end())
        },
      )
      p.on("error", (err) => {
        if (!res.headersSent) {
          res.writeHead(502, { ...cors, "content-type": "application/json" })
        }
        res.end(JSON.stringify({ error: String(err.message || err) }))
      })
      req.pipe(p)
    })
    server.listen(0, "127.0.0.1", () => resolve(server))
    server.on("error", reject)
  })
}

async function waitFor(url, timeoutMs = 15_000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url)
      if (res.ok) return await res.json()
    } catch {}
    await Bun.sleep(200)
  }
  throw new Error(`timeout waiting for ${url}`)
}

async function cdp(ws, method, params = {}, sessionId) {
  const id = Math.floor(Math.random() * 1e9)
  const payload = sessionId ? { id, method, params, sessionId } : { id, method, params }
  ws.send(JSON.stringify(payload))
  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.removeEventListener("message", onMsg)
      reject(new Error(`timeout ${method}`))
    }, 45_000)
    const onMsg = (ev) => {
      const data = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data)
      let msg
      try {
        msg = JSON.parse(data)
      } catch {
        return
      }
      if (msg.id !== id) return
      clearTimeout(timer)
      ws.removeEventListener("message", onMsg)
      if (msg.error) reject(new Error(`${method}: ${JSON.stringify(msg.error)}`))
      else resolve(msg.result)
    }
    ws.addEventListener("message", onMsg)
  })
}

async function evaluate(ws, sessionId, expression) {
  const result = await cdp(
    ws,
    "Runtime.evaluate",
    { expression, awaitPromise: true, returnByValue: true },
    sessionId,
  )
  return result?.result?.value
}

async function ensureOpencodeServe() {
  const port = 4096
  const url = `http://127.0.0.1:${port}`
  const tryHealth = async () => {
    const r = await fetch(`${url}/api/health`)
    if (!r.ok) throw new Error(`health ${r.status}`)
    return r.json()
  }
  try {
    const health = await tryHealth()
    return { url, started: false, health, child: null }
  } catch {}
  const bin = path.join(process.env.HOME || "/home/ubuntu", ".opencode/bin/opencode")
  const cwd = "/tmp/oc-serve-home"
  fs.mkdirSync(cwd, { recursive: true })
  const child = spawn(fs.existsSync(bin) ? bin : "opencode", ["serve", "--hostname", "127.0.0.1", "--port", String(port)], {
    cwd,
    env: { ...process.env, PATH: `${path.dirname(bin)}:${process.env.PATH || ""}` },
    stdio: ["ignore", "pipe", "pipe"],
  })
  const logs = []
  child.stdout?.on("data", (d) => logs.push(String(d)))
  child.stderr?.on("data", (d) => logs.push(String(d)))
  const start = Date.now()
  while (Date.now() - start < 20_000) {
    try {
      const health = await tryHealth()
      return { url, started: true, health, child, logs }
    } catch {
      if (child.exitCode != null) {
        throw new Error(`opencode serve exited ${child.exitCode}: ${logs.join("")}`)
      }
      await Bun.sleep(250)
    }
  }
  child.kill("SIGKILL")
  throw new Error(`timeout waiting for opencode serve: ${logs.join("")}`)
}

async function runConnectedSurface(ws, sessionId, input) {
  const { serveUrl, dedicatedOrigin, proxyOrigin, proxyPrefix } = input
  const item = (id, result, detail) => ({ id, result, detail: detail ?? null })
  const checklist = []

  const seed = await evaluate(
    ws,
    sessionId,
    `(() => {
      const url = ${JSON.stringify(serveUrl)};
      localStorage.setItem("opencode.global.dat:server", JSON.stringify({
        list: [{ type: "http", displayName: "local-serve", http: { url } }],
        projects: {},
        lastProject: {},
        recentlyClosed: {},
      }));
      localStorage.setItem("opencode.settings.dat:defaultServerUrl", url);
      return {
        stored: localStorage.getItem("opencode.global.dat:server"),
        defaultUrl: localStorage.getItem("opencode.settings.dat:defaultServerUrl"),
        proxyBase: window.OpenCodeAndroid && window.OpenCodeAndroid.proxyBaseUrl
          ? window.OpenCodeAndroid.proxyBaseUrl(url)
          : null,
      };
    })()`,
  )
  checklist.push(
    item(
      "connection.seed_real_url",
      seed?.defaultUrl === serveUrl && seed?.stored?.includes(serveUrl) ? "PASS" : "FAIL",
      seed,
    ),
  )
  checklist.push(
    item(
      "connection.proxy_origin_only",
      seed?.proxyBase === dedicatedOrigin ? "PASS" : "FAIL",
      { proxyBase: seed?.proxyBase, dedicatedOrigin },
    ),
  )

  await cdp(ws, "Page.reload", { ignoreCache: true }, sessionId)
  await Bun.sleep(16_000)

  const apiProbe = await evaluate(
    ws,
    sessionId,
    `(async () => {
      const dedicated = ${JSON.stringify(dedicatedOrigin)};
      const real = ${JSON.stringify(serveUrl)};
      const mux = ${JSON.stringify(proxyOrigin + proxyPrefix + encodeURIComponent(serveUrl))};
      async function get(url) {
        try {
          const r = await fetch(url, { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(8000) });
          const ct = r.headers.get("content-type") || "";
          const text = await r.text();
          let json = null;
          try { json = JSON.parse(text); } catch {}
          return { ok: r.ok, status: r.status, ct, bytes: text.length, jsonKeys: json && typeof json === "object" ? Object.keys(json).slice(0, 12) : null, sample: text.slice(0, 180) };
        } catch (e) {
          return { ok: false, status: 0, error: String(e && e.message || e) };
        }
      }
      const paths = {
        health: await get(dedicated + "/api/health"),
        globalHealth: await get(dedicated + "/global/health"),
        providerV2: await get(dedicated + "/api/provider"),
        model: await get(dedicated + "/api/model"),
        config: await get(dedicated + "/config"),
        globalConfig: await get(dedicated + "/global/config"),
        sessionList: await get(dedicated + "/api/session"),
        muxDroppedProvider: await get(${JSON.stringify(proxyOrigin)} + "/api/provider"),
        muxPrefixedProvider: await get(mux + "/api/provider"),
        realDirect: await get(real + "/api/health"),
      };
      let created = null;
      try {
        const r = await fetch(dedicated + "/api/session", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" });
        created = { ok: r.ok, status: r.status, body: await r.json().catch(() => null) };
      } catch (e) {
        created = { ok: false, error: String(e && e.message || e) };
      }
      let eventHead = { ok: false };
      try {
        const ac = new AbortController();
        const timer = setTimeout(() => ac.abort(), 1800);
        const r = await fetch(dedicated + "/event", { signal: ac.signal, headers: { Accept: "text/event-stream" } });
        const ct = r.headers.get("content-type") || "";
        const reader = r.body && r.body.getReader();
        let sample = "";
        if (reader) {
          const first = await reader.read();
          sample = new TextDecoder().decode(first.value || new Uint8Array());
          try { await reader.cancel(); } catch {}
        }
        clearTimeout(timer);
        eventHead = { ok: r.ok && ct.includes("text/event-stream") && sample.length >= 0, status: r.status, ct, sample: sample.slice(0, 180) };
      } catch (e) {
        eventHead = { ok: false, error: String(e && e.message || e) };
      }
      return { origin: location.origin, dedicated, paths: { ...paths, eventHead }, created };
    })()`,
  )

  const p = apiProbe?.paths || {}
  const providerCount = (() => {
    const v1 = p.providerV1?.jsonKeys
    const sample = p.providerV1?.sample || ""
    if (p.providerV2?.ok) return "v2"
    return null
  })()

  checklist.push(item("connection.health_via_dedicated", p.health?.ok === true ? "PASS" : "FAIL", p.health))
  checklist.push(item("connection.global_health_via_dedicated", p.globalHealth?.ok === true ? "PASS" : "FAIL", p.globalHealth))
  checklist.push(
    item(
      "connection.no_cors_on_direct",
      p.realDirect?.ok === false || p.realDirect?.error ? "PASS" : "FAIL",
      p.realDirect,
    ),
  )
  checklist.push(
    item(
      "proxy.dropped_prefix_404",
      p.muxDroppedProvider?.ok === false && (p.muxDroppedProvider?.status === 404 || p.muxDroppedProvider?.status === 0)
        ? "PASS"
        : "FAIL",
      p.muxDroppedProvider,
    ),
  )
  checklist.push(
    item(
      "settings.providers_api",
      p.providerV1?.ok === true || p.providerV2?.ok === true ? "PASS" : "FAIL",
      { providerV1: p.providerV1, providerV2: p.providerV2, providerCount },
    ),
  )
  checklist.push(item("settings.models_api", p.model?.ok === true ? "PASS" : "FAIL", p.model))
  checklist.push(item("settings.config_api", p.config?.ok === true || p.globalConfig?.ok === true ? "PASS" : "FAIL", { config: p.config, globalConfig: p.globalConfig }))
  checklist.push(item("home.session_list_api", p.sessionList?.ok === true ? "PASS" : "FAIL", p.sessionList))
  checklist.push(item("home.session_create_api", apiProbe?.created?.ok === true ? "PASS" : "FAIL", apiProbe?.created))
  checklist.push(
    item(
      "proxy.sse_event",
      p.eventHead?.ok === true && (p.eventHead?.ct || "").includes("text/event-stream") ? "PASS" : "FAIL",
      p.eventHead,
    ),
  )

  await evaluate(ws, sessionId, CLICK_SETTINGS_JS)
  await Bun.sleep(900)

  const clickTab = (labelRe) => `(() => {
    const re = ${labelRe};
    const nodes = Array.from(document.querySelectorAll('[role="tab"], button, [role="button"]'));
    const el = nodes.find((n) => re.test((n.textContent || "") + " " + (n.getAttribute("aria-label") || "")));
    if (!el) return { clicked: false };
    el.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, cancelable: true }));
    el.dispatchEvent(new PointerEvent("pointerup", { bubbles: true, cancelable: true }));
    el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    return { clicked: true, label: ((el.textContent || "") + "").trim().slice(0, 40) };
  })()`

  const generalTab = await evaluate(ws, sessionId, clickTab("/^(通用|General)$/"))
  await Bun.sleep(400)
  const generalUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = document.querySelector('[role="dialog"]');
      const text = dialog ? dialog.innerText : (document.body.innerText || "");
      const sections = document.querySelectorAll("details.settings-v2-section").length;
      return {
        dialog: !!dialog,
        sections,
        hasAppearance: /外观|Appearance|主题|Theme/.test(text),
        hasNotifications: /通知|Notification/.test(text),
        hasAdvanced: /高级|Advanced/.test(text),
        sample: text.slice(0, 400),
      };
    })()`,
  )
  checklist.push(
    item(
      "settings.general_appearance_notifications",
      generalUi?.dialog && (generalUi.sections >= 2 || generalUi.hasAppearance) ? "PASS" : "FAIL",
      { generalTab, generalUi },
    ),
  )

  const shortcutsTab = await evaluate(ws, sessionId, clickTab("/快捷键|Shortcuts|Keybind/"))
  await Bun.sleep(500)
  const shortcutsUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const text = (document.querySelector('[role="dialog"]')?.innerText || document.body.innerText || "");
      return { opened: /Esc|Escape|Ctrl|⌘|Command/.test(text) || /shortcut|快捷/.test(text.toLowerCase()), sample: text.slice(0, 300) };
    })()`,
  )
  checklist.push(
    item("settings.shortcuts_pane", shortcutsTab?.clicked && shortcutsUi?.opened ? "PASS" : "FAIL", { shortcutsTab, shortcutsUi }),
  )

  const serversTab = await evaluate(ws, sessionId, clickTab("/服务器|Servers/"))
  await Bun.sleep(500)
  const serversUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const text = (document.querySelector('[role="dialog"]')?.innerText || "");
      return {
        hasLocal: text.includes(${JSON.stringify(new URL(serveUrl).host)}) || text.includes("local-serve") || text.includes("127.0.0.1:4096"),
        hasAdd: /添加服务器|Add server/.test(text),
        sample: text.slice(0, 400),
      };
    })()`,
  )
  checklist.push(
    item("settings.servers_crud_list", serversTab?.clicked && serversUi?.hasLocal ? "PASS" : "FAIL", { serversTab, serversUi }),
  )

  const providersTab = await evaluate(ws, sessionId, clickTab("/提供商|Providers/"))
  await Bun.sleep(4000)
  const providersUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = document.querySelector('[role="dialog"]');
      const text = dialog ? dialog.innerText : "";
      const rows = Array.from(dialog ? dialog.querySelectorAll(".settings-v2-provider-row") : []);
      const popular = rows.filter((r) => /Connect|连接/.test(r.innerText)).length;
      const connectedEmpty = /还没有连接|No providers connected|connected empty|尚未连接/.test(text) || !!dialog?.querySelector(".settings-v2-provider-empty");
      const broken = rows.length === 0 && !connectedEmpty && !/OpenAI|Anthropic|OpenCode|提供商|Providers/.test(text);
      return {
        title: /提供商|Providers/.test(text),
        rows: rows.length,
        popular,
        connectedEmpty,
        names: rows.slice(0, 8).map((r) => (r.innerText || "").split("\\n")[0].slice(0, 40)),
        broken,
        sample: text.slice(0, 500),
      };
    })()`,
  )
  checklist.push(
    item(
      "settings.providers_ui",
      providersUi?.title && providersUi.broken !== true && (providersUi.rows > 0 || providersUi.connectedEmpty) ? "PASS" : "FAIL",
      { providersTab, providersUi },
    ),
  )

  const modelsTab = await evaluate(ws, sessionId, clickTab("/模型|Models/"))
  await Bun.sleep(4000)
  const modelsUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = document.querySelector('[role="dialog"]');
      const text = dialog ? dialog.innerText : "";
      const items = Array.from(dialog ? dialog.querySelectorAll("li, .settings-v2-provider-row, [data-component]") : []);
      const hasSearch = !!dialog?.querySelector("input[type='search'], input[placeholder]");
      const modelish = /gpt|claude|gemini|deepseek|muse|OpenAI|Anthropic|模型/.test(text);
      const emptyIntentional = /No models|没有模型|暂无/.test(text);
      return {
        title: /模型|Models/.test(text),
        hasSearch,
        modelish,
        emptyIntentional,
        sample: text.slice(0, 500),
      };
    })()`,
  )
  checklist.push(
    item(
      "settings.models_ui",
      modelsUi?.title && (modelsUi.modelish || modelsUi.emptyIntentional || modelsUi.hasSearch) ? "PASS" : "FAIL",
      { modelsTab, modelsUi },
    ),
  )

  const backFromSettings = await evaluate(ws, sessionId, HANDLE_BACK_JS)
  await Bun.sleep(300)
  const afterBack = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
  checklist.push(
    item(
      "shell.android_back_from_settings",
      backFromSettings === "overlay" || afterBack?.overlay !== true ? "PASS" : "FAIL",
      { backFromSettings, afterBack },
    ),
  )

  const homeUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const text = document.body.innerText || "";
      const newSession = Array.from(document.querySelectorAll("button, [role='button']")).find((el) => /新会话|New session|创建会话|Create/.test(el.textContent || ""));
      return {
        homeCopy: /项目|Projects|会话|Sessions|设置|Settings/.test(text),
        newSession: !!(newSession && (newSession.textContent || "").slice(0, 40)),
        sample: text.slice(0, 400),
      };
    })()`,
  )
  checklist.push(item("home.connected_layout", homeUi?.homeCopy ? "PASS" : "FAIL", homeUi))

  const sendProbe = await evaluate(
    ws,
    sessionId,
    `(async () => {
      const dedicated = ${JSON.stringify(dedicatedOrigin)};
      const created = ${JSON.stringify(apiProbe?.created?.body || null)};
      const id = created && created.data && created.data.id || created && created.id;
      if (!id) return { skipped: true, reason: "no session id" };
      try {
        const r = await fetch(dedicated + "/api/session/" + id + "/prompt", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ parts: [{ type: "text", text: "ping" }] }),
        });
        const text = await r.text();
        return { ok: r.status < 500, status: r.status, sample: text.slice(0, 240) };
      } catch (e) {
        return { ok: false, error: String(e && e.message || e) };
      }
    })()`,
  )
  if (sendProbe?.skipped) {
    checklist.push(item("home.send_message", "N/A", sendProbe))
  } else {
    checklist.push(
      item(
        "home.send_message",
        sendProbe?.ok === true ? "PASS" : "FAIL",
        { note: "4xx without API keys is still a successful API round-trip if status < 500", sendProbe },
      ),
    )
  }

  const terminalUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const nodes = Array.from(document.querySelectorAll("button, [role='button'], a"));
      const term = nodes.find((el) => /终端|Terminal/.test((el.textContent || "") + (el.getAttribute("aria-label") || "")));
      return { hasEntry: !!term, label: term ? (term.textContent || "").trim().slice(0, 40) : null };
    })()`,
  )
  checklist.push(
    item(
      "terminal.open",
      terminalUi?.hasEntry ? "N/A" : "N/A",
      { reason: "PTY/terminal is Desktop/server-local; Android WebView has no host PTY. ESC pass-through covered separately.", terminalUi },
    ),
  )
  checklist.push(
    item(
      "project.open_path",
      "N/A",
      { reason: "Android WebView cannot browse the device filesystem like Desktop; Add project uses the official directory dialog which is not wired to Android storage." },
    ),
  )
  checklist.push(
    item(
      "files.diff.permissions",
      "N/A",
      { reason: "File tree / diff / permission prompts require an open project+session on the server host; not exposed as a standalone Android pane." },
    ),
  )

  const failed = checklist.filter((c) => c.result === "FAIL")
  return { checklist, failed, apiProbe, seed, providersUi, modelsUi, serversUi, generalUi, shortcutsUi, homeUi, sendProbe }
}

const TAP_JS = `(() => {
  const visible = (el) => {
    if (!el) return false;
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const labelOf = (el) =>
    ((el.getAttribute('data-action') || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '')).trim();
  const nodes = Array.from(document.querySelectorAll('button, [role="button"], [data-action], a'));
  const visibleButtons = nodes.filter(visible);
  const settings = nodes.find((el) => {
    const label = labelOf(el).toLowerCase();
    return visible(el) && (el.getAttribute('data-action') === 'home-open-settings' || label.includes('settings') || label.includes('设置'));
  });
  const add = nodes.find((el) => {
    const label = labelOf(el).toLowerCase();
    return visible(el) && (el.getAttribute('data-action') === 'home-add-project' || el.getAttribute('data-action') === 'home-add-project-row' || label.includes('add project') || label.includes('添加项目'));
  });
  const home = nodes.find((el) => {
    const label = labelOf(el).toLowerCase();
    return visible(el) && (label.includes('home') || label.includes('主页') || el.getAttribute('aria-label')?.toLowerCase() === 'home');
  });
  const fired = [];
  const click = (el, name) => {
    if (!el) return false;
    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
    el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    fired.push(name);
    return true;
  };
  const emptyCopy = Array.from(document.querySelectorAll('div, p, span')).find((el) => {
    const t = (el.textContent || '').trim();
    return visible(el) && t.length > 0 && t.length < 80 && /创建一个会话|Create a session|Nothing here yet/.test(t);
  });
  const homeEmptyGapPx =
    emptyCopy && settings
      ? Math.round(settings.getBoundingClientRect().top - emptyCopy.getBoundingClientRect().bottom)
      : null;
  const homeOk = click(home, 'home');
  const settingsOk = click(settings, 'settings');
  const addOk = click(add, 'add-project');
  const dialog = document.querySelector('[role="dialog"], [data-component*="dialog"], [data-component*="settings"]');
  const bodyText = (document.body.innerText || '');
  const homeCopy = /Projects|项目|Nothing here yet|Get started|还没有|Create a session|创建会话|Settings|设置/.test(bodyText);
  return {
    hostname: location.hostname,
    pathname: location.pathname,
    title: document.title,
    hasRoot: !!document.getElementById('root'),
    rootChildCount: document.getElementById('root')?.childElementCount ?? 0,
    androidShell: !!window.__ocAndroidShell,
    settingsFound: !!settings,
    settingsLabel: settings ? labelOf(settings).slice(0, 80) : null,
    addFound: !!add,
    addLabel: add ? labelOf(add).slice(0, 80) : null,
    homeFound: !!home,
    visibleButtonCount: visibleButtons.length,
    clicked: fired,
    settingsClicked: settingsOk,
    addClicked: addOk,
    homeClicked: homeOk,
    dialogAfterClick: !!dialog,
    pointerEvents: getComputedStyle(document.documentElement).pointerEvents,
    bodyText: bodyText.slice(0, 400),
    homeCopy,
    hasScopeError: /Cannot read properties of undefined/.test(bodyText) || /reading 'scope'/.test(bodyText),
    viewportFitCover: /viewport-fit\\s*=\\s*cover/.test(document.querySelector('meta[name="viewport"]')?.content || ''),
    titlebarOnly: visibleButtons.length > 0 && visibleButtons.length <= 2 && !homeCopy,
    homeEmptyGapPx,
  };
})()`

const SETTINGS_LAYOUT_JS = `(() => {
  const tabs = document.querySelector('[data-component="tabs-v2"][data-variant="settings"]');
  const list = document.querySelector('[data-slot="tabs-v2-list"]');
  const contents = Array.from(document.querySelectorAll('[data-slot="tabs-v2-content"]'));
  const content = contents.find((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return s.display !== 'none' && r.width > 0 && r.height > 0;
  }) || contents[0];
  const select = document.querySelector('[data-component="select-v2-root"]');
  if (!tabs || !list) return { found: false, viewport: { w: innerWidth, h: innerHeight } };
  const tr = tabs.getBoundingClientRect();
  const lr = list.getBoundingClientRect();
  const cr = content ? content.getBoundingClientRect() : { width: 0, height: 0, top: 0, left: 0 };
  const dir = getComputedStyle(tabs).flexDirection;
  const stacked = dir === 'column' || lr.width >= tr.width * 0.85 || cr.top >= lr.bottom - 4;
  const selectWidth = select ? select.getBoundingClientRect().width : 0;
  return {
    found: true,
    viewport: { w: innerWidth, h: innerHeight },
    flexDirection: dir,
    tabs: { w: Math.round(tr.width), h: Math.round(tr.height) },
    list: { w: Math.round(lr.width), h: Math.round(lr.height), top: Math.round(lr.top) },
    content: { w: Math.round(cr.width), h: Math.round(cr.height), top: Math.round(cr.top), left: Math.round(cr.left) },
    stacked,
    selectWidth: Math.round(selectWidth),
    contentReadable: cr.width >= 280,
    hasGeneral: /通用|General/.test(document.body.innerText || ''),
  };
})()`

const SETTINGS_ACCORDION_JS = `(() => {
  const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return s.display !== 'none' && r.width > 0 && r.height > 0;
  });
  const panel = panels[0];
  const tabHeader = panel ? panel.querySelector('.settings-v2-tab-header') : null;
  const tabHeaderStyle = tabHeader ? getComputedStyle(tabHeader) : null;
  const sections = panel ? Array.from(panel.querySelectorAll('details.settings-v2-section')) : [];
  const summaries = sections.map((el) => {
    const sum = el.querySelector('summary.settings-v2-section-title');
    return {
      title: (sum && sum.textContent || '').trim().slice(0, 40),
      open: el.open,
      sticky: sum ? getComputedStyle(sum).position : null,
    };
  });
  const tabTitle = tabHeader ? (tabHeader.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 40) : null;
  return {
    panelFound: !!panel,
    tabHeaderDisplay: tabHeaderStyle ? tabHeaderStyle.display : null,
    tabHeaderPosition: tabHeaderStyle ? tabHeaderStyle.position : null,
    tabHeaderHidden: !tabHeader || (tabHeaderStyle && tabHeaderStyle.display === 'none'),
    tabTitle,
    sectionCount: sections.length,
    summaries,
    firstOpen: sections[0] ? sections[0].open : null,
  };
})()`

const SETTINGS_SCROLL_JS = `(() => {
  const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return s.display !== 'none' && r.width > 0 && r.height > 0;
  });
  const panel = panels[0];
  if (!panel) return { scrolled: false };
  const appearance = Array.from(panel.querySelectorAll('summary, h3')).find((el) => /外观|Appearance/.test(el.textContent || ''));
  if (appearance) appearance.scrollIntoView({ block: 'start' });
  else panel.scrollTop = Math.min(panel.scrollHeight, 420);
  const tabHeader = panel.querySelector('.settings-v2-tab-header');
  const tabHeaderStyle = tabHeader ? getComputedStyle(tabHeader) : null;
  const sticky = Array.from(panel.querySelectorAll('summary.settings-v2-section-title, h3.settings-v2-section-title')).filter((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const pr = panel.getBoundingClientRect();
    return s.position === 'sticky' && r.height > 0 && Math.abs(r.top - pr.top) < 8;
  });
  return {
    scrolled: true,
    scrollTop: Math.round(panel.scrollTop),
    tabHeaderHidden: !tabHeader || (tabHeaderStyle && tabHeaderStyle.display === 'none'),
    stickyTitles: sticky.map((el) => (el.textContent || '').trim().slice(0, 40)),
    lockedGeneral: sticky.some((el) => /^(通用|General)$/.test((el.textContent || '').trim())) && sticky.length === 1 && /^(通用|General)$/.test((sticky[0].textContent || '').trim()) && panel.scrollTop > 80,
  };
})()`

const SETTINGS_DENSITY_JS = `(() => {
  const box = (el) => {
    if (!el) return null;
    return { scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, extra: Math.max(0, el.scrollHeight - el.clientHeight) };
  };
  const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return s.display !== 'none' && r.width > 0 && r.height > 0;
  });
  const panel = panels[0];
  if (!panel) return { found: false };
  const sections = Array.from(panel.querySelectorAll('details.settings-v2-section'));
  for (const el of sections) el.open = false;
  panel.scrollTop = 0;
  const dialogContent = document.querySelector('[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-content"]');
  const dialogContainer = document.querySelector('[data-component="dialog-v2"][data-variant="settings"] [data-slot="dialog-container"]');
  const sums = sections.map((el) => el.querySelector('summary.settings-v2-section-title')).filter(Boolean);
  const gaps = [];
  const titleGaps = [];
  for (let i = 0; i < sums.length - 1; i++) {
    const a = sums[i].getBoundingClientRect();
    const b = sums[i + 1].getBoundingClientRect();
    gaps.push(Math.round(b.top - a.bottom));
    titleGaps.push(Math.round(b.top - a.top));
  }
  const last = sums[sums.length - 1];
  const lastBottom = last ? last.getBoundingClientRect().bottom : 0;
  const panelBottom = panel.getBoundingClientRect().bottom;
  return {
    found: true,
    allCollapsed: sections.length > 0 && sections.every((el) => el.open === false),
    sectionCount: sections.length,
    titles: sums.map((el) => (el.textContent || '').trim().slice(0, 40)),
    headerHeights: sums.map((el) => Math.round(el.getBoundingClientRect().height)),
    boxGapsPx: gaps,
    maxBoxGapPx: gaps.length ? Math.max.apply(null, gaps) : null,
    minBoxGapPx: gaps.length ? Math.min.apply(null, gaps) : null,
    titlePitchPx: titleGaps,
    panel: box(panel),
    dialogContent: box(dialogContent),
    dialogContainer: dialogContainer ? { h: Math.round(dialogContainer.getBoundingClientRect().height) } : null,
    blankBelowLastHeaderPx: Math.round(panelBottom - lastBottom),
    tabBodyGap: (() => {
      const body = panel.querySelector('.settings-v2-tab-body');
      return body ? getComputedStyle(body).gap : null;
    })(),
  };
})()`

const HOME_EMPTY_JS = `(() => {
  const visible = (el) => {
    if (!el) return false;
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.top < innerHeight && r.bottom > 0 && r.left < innerWidth && r.right > 0;
  };
  const box = (el) => el ? {
    scrollHeight: el.scrollHeight,
    clientHeight: el.clientHeight,
    extra: Math.max(0, el.scrollHeight - el.clientHeight),
  } : null;
  const viewports = Array.from(document.querySelectorAll('.scroll-view__viewport, [data-scrollable]')).filter((el) => {
    const r = el.getBoundingClientRect();
    return r.width > 40 && r.height > 40;
  });
  const homeVp = viewports.slice().sort((a, b) => b.clientHeight - a.clientHeight)[0] || null;
  const settings = Array.from(document.querySelectorAll('[data-action="home-open-settings"]'));
  const help = Array.from(document.querySelectorAll('button, [role="button"], [data-action]')).filter((el) => {
    const t = ((el.getAttribute('data-action') || '') + ' ' + (el.textContent || '')).trim();
    return /home-open-help|sidebar.help|^Help$|帮助/.test(t);
  });
  const body = document.querySelector('[data-slot="home-sessions-body"]');
  const shell = document.querySelector('[data-slot="app-shell"]');
  const shellPad = shell ? getComputedStyle(shell) : null;
  return {
    viewport: { w: innerWidth, h: innerHeight },
    androidWebview: document.documentElement.dataset.androidWebview === '',
    sessionsBodyMinHeight: body ? getComputedStyle(body).minHeight : null,
    homeViewport: box(homeVp),
    doc: box(document.documentElement),
    settingsCount: settings.length,
    settingsVisibleCount: settings.filter(visible).length,
    settingsInView: settings.some(visible),
    helpInView: help.some(visible),
    helpCount: help.length,
    shellPaddingTop: shellPad ? shellPad.paddingTop : null,
    shellPaddingBottom: shellPad ? shellPad.paddingBottom : null,
    maxExtra: Math.max(
      document.documentElement.scrollHeight - document.documentElement.clientHeight,
      homeVp ? homeVp.scrollHeight - homeVp.clientHeight : 0,
      0,
    ),
  };
})()`

const HANDLE_BACK_JS = `(() => {
  var visible = function (el) {
    if (!el) return false;
    var s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  var overlayOpen = function () {
    var sels = [
      '[data-component="dialog-overlay"]',
      '[data-component="dialog-v2"]',
      '[data-slot="dialog-content"]',
      '[role="dialog"]',
      '[data-component="popover-content"]',
      '[role="menu"]',
      '[role="listbox"]'
    ];
    for (var i = 0; i < sels.length; i++) {
      var nodes = document.querySelectorAll(sels[i]);
      for (var j = 0; j < nodes.length; j++) {
        if (visible(nodes[j])) return true;
      }
    }
    return false;
  };
  var fireEscape = function () {
    var opts = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
    try { window.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e1) {}
    try { document.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e2) {}
  };
  window.__ocAndroidHandleBack = function () {
    if (overlayOpen()) {
      fireEscape();
      return 'overlay';
    }
    return 'root';
  };
  return window.__ocAndroidHandleBack();
})()`

const OVERLAY_STATE_JS = `(() => {
  var visible = function (el) {
    if (!el) return false;
    var s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  var dialogs = Array.from(document.querySelectorAll('[role="dialog"], [data-component="dialog-v2"], .settings-v2-server-dialog')).filter(visible);
  return {
    hostname: location.hostname,
    pathname: location.pathname,
    overlay: dialogs.length > 0,
    dialogCount: dialogs.length,
    homeCopy: /Projects|项目|Nothing here yet|Settings|设置/.test(document.body.innerText || ''),
  };
})()`

const CLICK_SETTINGS_JS = `(() => {
  const nodes = Array.from(document.querySelectorAll('button, [role="button"], [data-action]'));
  const settings = nodes.find((el) => {
    const label = ((el.getAttribute('data-action') || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '')).trim().toLowerCase();
    return el.getAttribute('data-action') === 'home-open-settings' || label.includes('settings') || label.includes('设置');
  });
  if (!settings) return { clicked: false };
  settings.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
  settings.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
  settings.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  return { clicked: true };
})()`


async function main() {
  if (!fs.existsSync(path.join(assets, "index.html"))) {
    throw new Error(`missing embedded assets at ${assets}`)
  }
  const server = await startServer()
  const upstream = await startNoCorsUpstream()
  const proxyToken = "selftest-token"
  const proxy = await startLocalProxy(proxyToken)
  const serve = await ensureOpencodeServe()
  const dedicated = await startDedicatedOrigin(serve.url)
  const port = server.address().port
  const upstreamPort = upstream.address().port
  const proxyPort = proxy.address().port
  const dedicatedPort = dedicated.address().port
  const realBackend = `http://127.0.0.1:${upstreamPort}`
  const proxyOrigin = `http://127.0.0.1:${proxyPort}`
  const proxyPrefix = `/opencode-proxy/${proxyToken}/`
  const dedicatedOrigin = `http://127.0.0.1:${dedicatedPort}`
  const pageUrl = `http://${HOST}:${port}${ANDROID_MOUNT}/index.html`
  const debugPort = 9222
  const chrome = spawn(
    "google-chrome",
    [
      "--headless=new",
      "--disable-gpu",
      "--no-sandbox",
      "--disable-dev-shm-usage",
      `--remote-debugging-port=${debugPort}`,
      `--host-resolver-rules=MAP ${HOST} 127.0.0.1`,
      "--user-data-dir=/tmp/oc-selftest-chrome-1312",
      "--window-size=390,844",
      "about:blank",
    ],
    { stdio: "ignore" },
  )
  try {
    const version = await waitFor(`http://127.0.0.1:${debugPort}/json/version`)
    const ws = new WebSocket(version.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      ws.addEventListener("open", resolve)
      ws.addEventListener("error", reject)
    })
    const { targetId } = await cdp(ws, "Target.createTarget", { url: "about:blank" })
    const { sessionId } = await cdp(ws, "Target.attachToTarget", { targetId, flatten: true })
    await cdp(ws, "Page.enable", {}, sessionId)
    await cdp(ws, "Runtime.enable", {}, sessionId)
    await cdp(
      ws,
      "Page.addScriptToEvaluateOnNewDocument",
      {
        source: `
          window.OpenCodeAndroid = {
            proxyOrigin: function () { return ${JSON.stringify(proxyOrigin)}; },
            proxyPrefix: function () { return ${JSON.stringify(proxyPrefix)}; },
            proxyBaseUrl: function (real) {
              var n = String(real || "").replace(/\\/+$/, "");
              if (n === ${JSON.stringify(serve.url)}) return ${JSON.stringify(dedicatedOrigin)};
              return ${JSON.stringify(proxyOrigin)} + ${JSON.stringify(proxyPrefix)} + encodeURIComponent(n);
            }
          };
          window.__ocAndroidProxy = { origin: ${JSON.stringify(proxyOrigin)}, prefix: ${JSON.stringify(proxyPrefix)} };
          window.__ocAndroidShell = true;
        `,
      },
      sessionId,
    )
    await cdp(
      ws,
      "Emulation.setDeviceMetricsOverride",
      { width: 390, height: 844, deviceScaleFactor: 2.75, mobile: true },
      sessionId,
    )
    await cdp(ws, "Emulation.setTouchEmulationEnabled", { enabled: true }, sessionId)
    await cdp(
      ws,
      "Emulation.setEmulatedMedia",
      { features: [{ name: "pointer", value: "coarse" }, { name: "hover", value: "none" }] },
      sessionId,
    )
    const exceptions = []
    ws.addEventListener("message", (ev) => {
      const data = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data)
      let msg
      try {
        msg = JSON.parse(data)
      } catch {
        return
      }
      if (msg.method === "Runtime.exceptionThrown" && msg.sessionId === sessionId) {
        const desc = msg.params?.exceptionDetails?.exception?.description || msg.params?.exceptionDetails?.text || ""
        exceptions.push(desc)
      }
    })
    await cdp(ws, "Page.navigate", { url: pageUrl }, sessionId)
    await Bun.sleep(8_000)
    const homeEmptyPhone = await evaluate(ws, sessionId, HOME_EMPTY_JS)
    try {
      const shotHome = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shotHome?.data) {
        fs.mkdirSync(path.dirname(homeEmptyScreenshotPath), { recursive: true })
        fs.writeFileSync(homeEmptyScreenshotPath, Buffer.from(shotHome.data, "base64"))
      }
    } catch {}
    await cdp(
      ws,
      "Emulation.setDeviceMetricsOverride",
      { width: 800, height: 1280, deviceScaleFactor: 2, mobile: true },
      sessionId,
    )
    await Bun.sleep(400)
    const homeEmptyWide = await evaluate(ws, sessionId, HOME_EMPTY_JS)
    const probe = await evaluate(ws, sessionId, TAP_JS)
    await Bun.sleep(800)
    const settingsLayout = await evaluate(ws, sessionId, SETTINGS_LAYOUT_JS)
    const settingsAccordion = await evaluate(ws, sessionId, SETTINGS_ACCORDION_JS)
    try {
      const shot = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shot?.data) {
        fs.mkdirSync(path.dirname(screenshotPath), { recursive: true })
        fs.writeFileSync(screenshotPath, Buffer.from(shot.data, "base64"))
        fs.writeFileSync(portraitScreenshotPath, Buffer.from(shot.data, "base64"))
      }
    } catch {}
    if (settingsAccordion?.sectionCount >= 2 && settingsAccordion.summaries?.[1]) {
      const collapseJs = `(() => {
        const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
          const s = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          return s.display !== 'none' && r.width > 0 && r.height > 0;
        });
        const panel = panels[0];
        const details = panel && panel.querySelectorAll('details.settings-v2-section')[1];
        const sum = details && details.querySelector('summary');
        if (sum) sum.click();
        return { collapsed: details ? details.open === false : false, title: sum ? (sum.textContent || '').trim().slice(0, 40) : null };
      })()`
      settingsAccordion.collapseProbe = await evaluate(ws, sessionId, collapseJs)
      await Bun.sleep(250)
      const reopenJs = `(() => {
        const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
          const s = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          return s.display !== 'none' && r.width > 0 && r.height > 0;
        });
        const details = panels[0] && panels[0].querySelectorAll('details.settings-v2-section')[1];
        const sum = details && details.querySelector('summary');
        if (sum && details && !details.open) sum.click();
        return { open: details ? details.open : null };
      })()`
      settingsAccordion.reopenProbe = await evaluate(ws, sessionId, reopenJs)
    }
    const settingsScroll = await evaluate(ws, sessionId, SETTINGS_SCROLL_JS)
    await Bun.sleep(300)
    try {
      const shot2 = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shot2?.data) {
        fs.mkdirSync(path.dirname(portraitScrolledScreenshotPath), { recursive: true })
        fs.writeFileSync(portraitScrolledScreenshotPath, Buffer.from(shot2.data, "base64"))
      }
    } catch {}
    const settingsDensity = await evaluate(ws, sessionId, SETTINGS_DENSITY_JS)
    await Bun.sleep(250)
    try {
      const shot3 = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shot3?.data) {
        fs.mkdirSync(path.dirname(portraitCollapsedScreenshotPath), { recursive: true })
        fs.writeFileSync(portraitCollapsedScreenshotPath, Buffer.from(shot3.data, "base64"))
      }
    } catch {}
    await cdp(
      ws,
      "Emulation.setDeviceMetricsOverride",
      { width: 1280, height: 800, deviceScaleFactor: 2, mobile: true },
      sessionId,
    )
    await Bun.sleep(400)
    const settingsLandscape = await evaluate(ws, sessionId, SETTINGS_LAYOUT_JS)
    const imeCss = await evaluate(
      ws,
      sessionId,
      `(() => {
        const sheets = Array.from(document.styleSheets).flatMap((s) => {
          try { return Array.from(s.cssRules || []).map((r) => r.cssText); } catch { return []; }
        }).join('\\n');
        const html = document.documentElement;
        return {
          androidWebview: html.hasAttribute('data-android-webview'),
          interactiveWidget: (document.querySelector('meta[name="viewport"]')?.content || '').includes('interactive-widget=resizes-content'),
          dialogMaxHeight: sheets.includes('100dvh') && sheets.includes('data-android-webview'),
        };
      })()`,
    )
    const proxyProbe = await evaluate(
      ws,
      sessionId,
      `(async () => {
        const real = ${JSON.stringify(realBackend)};
        const prefix = ${JSON.stringify(proxyOrigin + proxyPrefix + encodeURIComponent(realBackend))};
        async function probe(url) {
          try {
            const r = await fetch(url, { method: 'GET', headers: { Accept: 'application/json' } });
            const acao = r.headers.get('access-control-allow-origin');
            return {
              ok: r.ok,
              status: r.status,
              error: null,
              body: r.ok ? await r.json().catch(() => null) : null,
              acao,
            };
          } catch (e) {
            return { ok: false, status: 0, error: String(e && e.message || e), body: null, acao: null };
          }
        }
        const direct = await probe(real + '/api/health');
        const health = await probe(prefix + '/api/health');
        let sse = { ok: false, combined: '', firstChunk: '', restDelayMs: 0, error: null };
        try {
          const r = await fetch(prefix + '/event');
          const reader = r.body.getReader();
          const dec = new TextDecoder();
          const first = await reader.read();
          const firstChunk = dec.decode(first.value || new Uint8Array());
          const t1 = performance.now();
          let rest = '';
          while (true) {
            const next = await reader.read();
            if (next.done) break;
            rest += dec.decode(next.value || new Uint8Array());
          }
          const restDelayMs = performance.now() - t1;
          sse = {
            ok: r.ok && firstChunk.includes('data: one') && (firstChunk + rest).includes('data: two'),
            combined: firstChunk + rest,
            firstChunk,
            restDelayMs,
            error: null,
          };
        } catch (e) {
          sse.error = String(e && e.message || e);
        }
        return {
          origin: location.origin,
          hostname: location.hostname,
          proxyBase: prefix,
          directBlocked: direct.ok === false && !!direct.error,
          direct,
          healthOk: health.ok === true && health.status === 200 && health.body && health.body.healthy === true,
          healthNoRemoteOrigin: !health.body || health.body.origin == null,
          health,
          sse,
        };
      })()`,
    )
    const addServerUi = await evaluate(
      ws,
      sessionId,
      `(() => {
        const click = (el) => {
          if (!el) return false;
          el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
          el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
          el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
          return true;
        };
        const labelOf = (el) => ((el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '')).trim();
        const nodes = Array.from(document.querySelectorAll('button, [role="tab"], [role="button"], a'));
        const serversTab = nodes.find((el) => /^(Servers|服务器)$/.test(labelOf(el).trim()) || /^Servers$|^服务器$/.test((el.textContent || '').trim()));
        click(serversTab);
        return { serversTab: !!serversTab, serversLabel: serversTab ? labelOf(serversTab).slice(0, 40) : null };
      })()`,
    )
    await Bun.sleep(500)
    const addServerForm = await evaluate(
      ws,
      sessionId,
      `(() => {
        const click = (el) => {
          if (!el) return false;
          el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
          el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
          el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
          return true;
        };
        const add = Array.from(document.querySelectorAll('button, [role="button"]')).find((el) => {
          const t = ((el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '')).trim();
          return t.includes('Add server') || t.includes('添加服务器');
        });
        click(add);
        return { addClicked: !!add, addLabel: add ? (add.textContent || '').trim().slice(0, 40) : null };
      })()`,
    )
    await Bun.sleep(500)
    const addServerFields = await evaluate(
      ws,
      sessionId,
      `(() => {
        const dialog = document.querySelector('.settings-v2-server-dialog') || document.querySelector('[role="dialog"]');
        const input = dialog && dialog.querySelector('input:not([type="password"])');
        const hint = document.querySelector('.settings-v2-server-dialog-hint');
        const real = 'https://backend.opencode.xiufeigo.xyz';
        if (input) {
          const desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
          if (desc && desc.set) desc.set.call(input, real);
          else input.value = real;
          input.dispatchEvent(new Event('input', { bubbles: true }));
          input.dispatchEvent(new Event('change', { bubbles: true }));
        }
        const hintText = hint ? (hint.textContent || '') : '';
        return {
          dialogFound: !!dialog,
          inputFound: !!input,
          placeholder: input ? input.placeholder : null,
          value: input ? input.value : null,
          realUrlKept: input ? input.value === real : false,
          hint: hintText.slice(0, 240),
          hintMentionsProxy: /反向代理|prox/i.test(hintText),
          hintDoesNotRequireCors: hintText.length === 0 || !/MUST allow|必须允许/.test(hintText),
        };
      })()`,
    )
    try {
      const shotAdd = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shotAdd?.data) {
        const addPath = "/opt/cursor/artifacts/selftest-add-server.png"
        fs.mkdirSync(path.dirname(addPath), { recursive: true })
        fs.writeFileSync(addPath, Buffer.from(shotAdd.data, "base64"))
      }
    } catch {}
    const backFromAdd = await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(280)
    const afterBackFromAdd = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
    const backCloses = []
    let lastBack = backFromAdd
    for (let i = 0; i < 5; i++) {
      lastBack = await evaluate(ws, sessionId, HANDLE_BACK_JS)
      backCloses.push(lastBack)
      await Bun.sleep(200)
      const state = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
      if (lastBack === "root" && state?.overlay !== true) break
    }
    const atHomeAfterBack = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
    await evaluate(ws, sessionId, CLICK_SETTINGS_JS)
    await Bun.sleep(700)
    const settingsReopened = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
    const backFromSettings = await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(280)
    const afterBackFromSettings = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
    await evaluate(ws, sessionId, CLICK_SETTINGS_JS)
    await Bun.sleep(700)
    const settingsForEsc = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
    await evaluate(
      ws,
      sessionId,
      `(() => {
        var opts = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
        window.dispatchEvent(new KeyboardEvent('keydown', opts));
        document.dispatchEvent(new KeyboardEvent('keydown', opts));
        return true;
      })()`,
    )
    await Bun.sleep(280)
    const afterEsc = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
    const rootBack = await evaluate(ws, sessionId, HANDLE_BACK_JS)
    const escPassThrough = await evaluate(
      ws,
      sessionId,
      `(() => {
        var visible = function (el) {
          if (!el) return false;
          var s = getComputedStyle(el);
          if (s.display === 'none' || s.visibility === 'hidden') return false;
          var r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0;
        };
        var overlay = function () {
          return Array.from(document.querySelectorAll('[role="dialog"], [data-component="dialog-v2"]')).some(visible);
        };
        var ta = document.createElement('textarea');
        ta.id = 'oc-selftest-vim';
        ta.setAttribute('data-terminal', '1');
        document.body.appendChild(ta);
        ta.focus();
        var before = { host: location.hostname, path: location.pathname, overlay: overlay(), active: document.activeElement === ta };
        ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true }));
        var after = { host: location.hostname, path: location.pathname, overlay: overlay(), active: document.activeElement === ta };
        ta.remove();
        return {
          before: before,
          after: after,
          stayed: after.host === before.host && after.path === before.path,
          didNotOpenOverlay: after.overlay === false,
          note: 'ESC pass-through: focused terminal/editor keeps Esc; shell has no app-wide ESC shortcut. Android Back (not Esc) closes Settings.',
        };
      })()`,
    )
    try {
      const shotBack = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shotBack?.data) {
        const backPath = "/opt/cursor/artifacts/selftest-back-from-settings.png"
        fs.mkdirSync(path.dirname(backPath), { recursive: true })
        fs.writeFileSync(backPath, Buffer.from(shotBack.data, "base64"))
      }
    } catch {}
    await cdp(
      ws,
      "Emulation.setDeviceMetricsOverride",
      { width: 800, height: 1280, deviceScaleFactor: 2, mobile: true },
      sessionId,
    )
    const scopeCrash =
      exceptions.some((e) => /reading 'scope'/.test(e)) || !!probe?.hasScopeError
    const whiteOnly = !probe?.homeCopy || probe?.titlebarOnly || (probe?.visibleButtonCount ?? 0) < 3
    const settingsPortraitOk =
      !!settingsLayout?.found &&
      settingsLayout.stacked === true &&
      (settingsLayout.contentReadable === true || settingsLayout.content?.w >= 280)
    const accordionOk =
      !!settingsAccordion?.panelFound &&
      (settingsAccordion.sectionCount ?? 0) >= 2 &&
      settingsAccordion.tabHeaderHidden === true &&
      settingsAccordion.summaries?.every((s) => s.sticky === "sticky")
    const collapseOk = settingsAccordion?.collapseProbe?.collapsed === true
    const scrollOk =
      !!settingsScroll?.scrolled &&
      settingsScroll.tabHeaderHidden === true &&
      settingsScroll.lockedGeneral !== true
    const densityOk =
      !!settingsDensity?.found &&
      settingsDensity.allCollapsed === true &&
      (settingsDensity.maxBoxGapPx ?? 99) <= 16 &&
      (settingsDensity.panel?.extra ?? 99) <= 32 &&
      (settingsDensity.dialogContent?.extra ?? 99) <= 32
    const landscapeOk =
      !!settingsLandscape?.found &&
      settingsLandscape.stacked !== true &&
      settingsLandscape.flexDirection === "row"
    const homeEmptyOk =
      !!homeEmptyPhone &&
      homeEmptyPhone.androidWebview === true &&
      homeEmptyPhone.settingsInView === true &&
      (homeEmptyPhone.helpCount === 0 || homeEmptyPhone.helpInView === true) &&
      (homeEmptyPhone.maxExtra ?? 99) <= 8 &&
      (homeEmptyPhone.sessionsBodyMinHeight === "0px" || homeEmptyPhone.sessionsBodyMinHeight === "0") &&
      !!homeEmptyWide &&
      (homeEmptyWide.maxExtra ?? 99) <= 8 &&
      homeEmptyWide.settingsInView === true
    const imeOk =
      !!imeCss &&
      imeCss.androidWebview === true &&
      imeCss.interactiveWidget === true &&
      imeCss.dialogMaxHeight === true
    const proxyOk =
      !!proxyProbe &&
      proxyProbe.hostname === HOST &&
      proxyProbe.directBlocked === true &&
      proxyProbe.healthOk === true &&
      proxyProbe.healthNoRemoteOrigin === true &&
      proxyProbe.sse?.ok === true &&
      (proxyProbe.sse?.restDelayMs ?? 0) >= 80
    const addServerOk =
      !!addServerFields &&
      addServerFields.inputFound === true &&
      addServerFields.realUrlKept === true &&
      addServerFields.placeholder === "http://localhost:4096" &&
      addServerFields.hintDoesNotRequireCors === true &&
      addServerFields.hintMentionsProxy === true
    const backOk =
      afterBackFromAdd?.hostname === HOST &&
      atHomeAfterBack?.hostname === HOST &&
      atHomeAfterBack?.homeCopy === true &&
      atHomeAfterBack?.overlay !== true &&
      settingsReopened?.overlay === true &&
      backFromSettings === "overlay" &&
      afterBackFromSettings?.overlay !== true &&
      afterBackFromSettings?.hostname === HOST &&
      afterBackFromSettings?.homeCopy === true &&
      rootBack === "root"
    const escOk =
      settingsForEsc?.overlay === true &&
      afterEsc?.overlay !== true &&
      afterEsc?.hostname === HOST &&
      afterEsc?.homeCopy === true
    const escPassThroughOk =
      escPassThrough?.stayed === true &&
      escPassThrough?.didNotOpenOverlay === true &&
      escPassThrough?.before?.host === HOST
    const surface = await runConnectedSurface(ws, sessionId, {
      serveUrl: serve.url,
      dedicatedOrigin,
      proxyOrigin,
      proxyPrefix,
    })
    try {
      const shotSurface = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shotSurface?.data) {
        const surfacePath = "/opt/cursor/artifacts/selftest-providers-models.png"
        fs.mkdirSync(path.dirname(surfacePath), { recursive: true })
        fs.writeFileSync(surfacePath, Buffer.from(shotSurface.data, "base64"))
      }
    } catch {}
    const surfaceOk = Array.isArray(surface?.checklist) && surface.checklist.every((c) => c.result !== "FAIL")
    const regressionChecklist = [
      { id: "connection.add_server_ui_real_url", result: addServerOk ? "PASS" : "FAIL", detail: addServerFields },
      { id: "connection.proxy_without_remote_cors", result: proxyOk ? "PASS" : "FAIL", detail: proxyProbe },
      { id: "shell.android_back_in_app", result: backOk ? "PASS" : "FAIL" },
      { id: "shell.esc_passthrough", result: escPassThroughOk ? "PASS" : "FAIL", detail: escPassThrough },
      { id: "home.empty_layout_no_bogus_scroll", result: homeEmptyOk ? "PASS" : "FAIL" },
      { id: "regression.ime_dialog_css", result: imeOk ? "PASS" : "FAIL", detail: imeCss },
      { id: "regression.portrait_settings_accordion", result: accordionOk && settingsPortraitOk ? "PASS" : "FAIL" },
      { id: "regression.proxy_sse_chunked", result: proxyProbe?.sse?.ok === true && (proxyProbe?.sse?.restDelayMs ?? 0) >= 80 ? "PASS" : "FAIL", detail: proxyProbe?.sse },
    ]
    const fullChecklist = [...regressionChecklist, ...(surface?.checklist || [])]
    const ok =
      probe &&
      probe.hostname === HOST &&
      probe.pathname === `${ANDROID_MOUNT}/index.html` &&
      probe.settingsFound &&
      probe.settingsClicked &&
      probe.homeCopy &&
      !whiteOnly &&
      (probe.dialogAfterClick || probe.settingsClicked) &&
      !scopeCrash &&
      probe.viewportFitCover === true &&
      homeEmptyOk &&
      settingsPortraitOk &&
      accordionOk &&
      collapseOk &&
      scrollOk &&
      densityOk &&
      landscapeOk &&
      imeOk &&
      proxyOk &&
      addServerOk &&
      backOk &&
      escOk &&
      escPassThroughOk &&
      surfaceOk &&
      fullChecklist.every((c) => c.result !== "FAIL")
    const report = {
      ok,
      pageUrl,
      probe,
      homeEmptyPhone,
      homeEmptyWide,
      settingsLayout,
      settingsAccordion,
      settingsScroll,
      settingsDensity,
      settingsLandscape,
      clicked: probe?.clicked ?? [],
      responded: !!(probe?.settingsClicked && (probe?.dialogAfterClick || probe.settingsFound)),
      exceptions,
      scopeCrash,
      whiteOnly,
      homeEmptyOk,
      settingsPortraitOk,
      accordionOk,
      collapseOk,
      scrollOk,
      densityOk,
      landscapeOk,
      imeCss,
      imeOk,
      proxyProbe,
      proxyOk,
      addServerUi,
      addServerForm,
      addServerFields,
      addServerOk,
      backFromAdd,
      afterBackFromAdd,
      backCloses,
      atHomeAfterBack,
      settingsReopened,
      backFromSettings,
      afterBackFromSettings,
      settingsForEsc,
      afterEsc,
      rootBack,
      backOk,
      escOk,
      escPassThrough,
      escPassThroughOk,
      serve: { url: serve.url, health: serve.health, started: serve.started },
      dedicatedOrigin,
      surface,
      surfaceOk,
      fullChecklist,
      screenshotPath: fs.existsSync(screenshotPath) ? screenshotPath : null,
      homeEmptyScreenshotPath: fs.existsSync(homeEmptyScreenshotPath) ? homeEmptyScreenshotPath : null,
      portraitScreenshotPath: fs.existsSync(portraitScreenshotPath) ? portraitScreenshotPath : null,
      portraitScrolledScreenshotPath: fs.existsSync(portraitScrolledScreenshotPath)
        ? portraitScrolledScreenshotPath
        : null,
      portraitCollapsedScreenshotPath: fs.existsSync(portraitCollapsedScreenshotPath)
        ? portraitCollapsedScreenshotPath
        : null,
      addServerScreenshotPath: fs.existsSync("/opt/cursor/artifacts/selftest-add-server.png")
        ? "/opt/cursor/artifacts/selftest-add-server.png"
        : null,
    }
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2))
    console.log(JSON.stringify(report, null, 2))
    if (!ok) {
      throw new Error(
        scopeCrash
          ? "self-test failed: LayoutProvider TypeError reading scope"
          : whiteOnly
            ? "self-test failed: titlebar-only / white home (asset-path router mismatch)"
            : !homeEmptyOk
              ? "self-test failed: empty home still overflows / Settings not in view"
              : !settingsPortraitOk
                ? "self-test failed: Settings is still a squished two-column layout in portrait"
                : !accordionOk
                  ? "self-test failed: Settings category title still sticky-locked or sections are not accordions"
                  : !collapseOk
                    ? "self-test failed: Settings section did not collapse"
                    : !scrollOk
                      ? "self-test failed: 「通用」 still locked after scroll"
                      : !densityOk
                        ? "self-test failed: portrait Settings accordion gaps or blank scroll still excessive"
                        : !landscapeOk
                          ? "self-test failed: landscape Settings is no longer two-column"
                          : !imeOk
                            ? "self-test failed: IME dialog CSS / interactive-widget missing"
                            : !proxyOk
                              ? "self-test failed: native proxy health/SSE from appassets origin still depends on remote CORS"
                              : !addServerOk
                                ? "self-test failed: Add Server UI does not keep the real backend URL / proxy hint"
                                : !backOk
                                  ? "self-test failed: Back from Settings did not stay in-app / did not close overlay"
                                  : !escOk
                                    ? "self-test failed: OpenCode UI did not close Settings on Esc (page-owned, not a shell shortcut)"
                                    : !escPassThroughOk
                                      ? "self-test failed: Esc on a focused terminal-like field navigated away (shell must pass Esc through)"
                                      : !surfaceOk
                                        ? "self-test failed: connected OpenCode surface (Providers/Models/API via proxy) had FAIL items"
                                      : "self-test failed: Settings control was not found or did not handle the tap",
      )
    }
    ws.close()
    chrome.kill("SIGKILL")
    server.close()
    upstream.close()
    proxy.close()
    dedicated.close()
    process.exit(0)
  } finally {
    chrome.kill("SIGKILL")
    server.close()
    try {
      upstream.close()
    } catch {}
    try {
      proxy.close()
    } catch {}
    try {
      dedicated.close()
    } catch {}
    if (serve?.started && serve.child) {
      try {
        serve.child.kill("SIGKILL")
      } catch {}
    }
  }
}

await main()
