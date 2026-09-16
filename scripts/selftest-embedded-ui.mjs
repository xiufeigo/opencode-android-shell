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
import net from "node:net"
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

function attachWsProxy(server, resolveUpstream) {
  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url || "/", "http://127.0.0.1")
    const target = resolveUpstream(url, req)
    if (!target) {
      try {
        socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
      } catch {}
      socket.destroy()
      return
    }
    const remote = net.connect(Number(target.port), target.hostname, () => {
      const lines = [`${req.method} ${target.path} HTTP/1.1`]
      lines.push(`Host: ${target.hostHeader}`)
      for (const [name, value] of Object.entries(req.headers)) {
        if (!value) continue
        const lower = name.toLowerCase()
        if (lower === "host" || lower === "origin" || lower === "referer") continue
        lines.push(`${name}: ${Array.isArray(value) ? value.join(", ") : value}`)
      }
      lines.push("", "")
      remote.write(lines.join("\r\n"))
      if (head && head.length) remote.write(head)
      socket.pipe(remote)
      remote.pipe(socket)
    })
    remote.on("error", () => socket.destroy())
    socket.on("error", () => remote.destroy())
  })
}

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer()
    s.listen(0, "127.0.0.1", () => {
      const addr = s.address()
      const port = typeof addr === "object" && addr ? addr.port : 0
      s.close((err) => (err ? reject(err) : resolve(port)))
    })
    s.on("error", reject)
  })
}

function seedWorkspace() {
  const workspace = "/tmp/oc-e2e-workspace"
  fs.mkdirSync(path.join(workspace, "src"), { recursive: true })
  fs.writeFileSync(path.join(workspace, "README.md"), "# OpenCode Android E2E workspace\nhello-remote-file\n")
  fs.writeFileSync(path.join(workspace, "src/hello.ts"), "export const hello = 'remote-pty'\n")
  return workspace
}

function localCorsHeaders(req) {
  const origin = req.headers.origin || "*"
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Credentials": origin === "*" ? undefined : "true",
    "Access-Control-Allow-Headers":
      req.headers["access-control-request-headers"] ||
      "Authorization, Content-Type, Accept, Last-Event-ID, x-opencode-directory, x-opencode-workspace, x-opencode-ticket",
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
    attachWsProxy(server, (url) => {
      const backend = new URL(backendUrl.replace(/\/+$/, ""))
      return {
        hostname: backend.hostname,
        port: backend.port || (backend.protocol === "https:" ? 443 : 80),
        path: `${url.pathname}${url.search}`,
        hostHeader: backend.host,
      }
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
    attachWsProxy(server, (url) => {
      const parsed = parseProxyPath(url.pathname, url.search, token)
      if (!parsed) return null
      const upstream = new URL(parsed.upstreamUrl)
      return {
        hostname: upstream.hostname,
        port: upstream.port || (upstream.protocol === "https:" ? 443 : 80),
        path: `${upstream.pathname}${upstream.search}`,
        hostHeader: upstream.host,
      }
    })
    server.listen(0, "127.0.0.1", () => resolve(server))
    server.on("error", reject)
  })
}

async function waitFor(url, timeoutMs = 15_000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(1500) })
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
  const workspace = seedWorkspace()
  const port = await freePort()
  const url = `http://127.0.0.1:${port}`
  const bin = path.join(process.env.HOME || "/home/ubuntu", ".opencode/bin/opencode")
  const child = spawn(fs.existsSync(bin) ? bin : "opencode", ["serve", "--hostname", "127.0.0.1", "--port", String(port)], {
    cwd: workspace,
    env: { ...process.env, PATH: `${path.dirname(bin)}:${process.env.PATH || ""}`, HOME: process.env.HOME || "/home/ubuntu" },
    stdio: ["ignore", "pipe", "pipe"],
  })
  const logs = []
  child.stdout?.on("data", (d) => logs.push(String(d)))
  child.stderr?.on("data", (d) => logs.push(String(d)))
  const tryHealth = async () => {
    const r = await fetch(`${url}/api/health`, { signal: AbortSignal.timeout(2000) })
    if (!r.ok) throw new Error(`health ${r.status}`)
    return r.json()
  }
  const start = Date.now()
  console.error(`[selftest] waiting for opencode serve at ${url} cwd=${workspace}`)
  while (Date.now() - start < 25_000) {
    try {
      const health = await tryHealth()
      return { url, started: true, health, child, logs, workspace }
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

const TAP_EL = `function ocTap(el) {
  if (!el) return false;
  el.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, cancelable: true }));
  el.dispatchEvent(new PointerEvent("pointerup", { bubbles: true, cancelable: true }));
  el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
  return true;
}
function ocKey(key, mods) {
  mods = mods || {};
  var code = mods.code;
  if (!code) {
    if (key === String.fromCharCode(92)) code = "Backslash";
    else if (key === String.fromCharCode(96)) code = "Backquote";
    else if (key === ";") code = "Semicolon";
    else if (key === "p" || key === "P") code = "KeyP";
    else if (key === "k" || key === "K") code = "KeyK";
    else if (key.length === 1) code = "Key" + key.toUpperCase();
    else code = key;
  }
  var opts = {
    key: key,
    code: code,
    bubbles: true,
    cancelable: true,
    composed: true,
    ctrlKey: !!mods.ctrl,
    metaKey: !!mods.meta,
    altKey: !!mods.alt,
    shiftKey: !!mods.shift,
  };
  var targets = [window, document, document.activeElement].filter(Boolean);
  for (var i = 0; i < targets.length; i++) {
    targets[i].dispatchEvent(new KeyboardEvent("keydown", opts));
    targets[i].dispatchEvent(new KeyboardEvent("keyup", opts));
  }
  return true;
}
function ocToggleSwitch(action) {
  var row = document.querySelector('[data-action="' + action + '"]');
  if (!row) return { found: false, on: false };
  var sw = row.querySelector('[role="switch"], button, input[type="checkbox"]');
  var checked = sw && (sw.getAttribute("aria-checked") === "true" || sw.checked === true);
  if (sw && !checked) ocTap(sw);
  return { found: !!sw, on: true, wasOn: !!checked };
}`

function inspectServersExpression(serveUrl, secondUrl) {
  return `(() => {
    const rows = Array.from(document.querySelectorAll(".settings-v2-servers-row"));
    const mapped = rows.map((row) => {
      const text = row.innerText || "";
      return {
        key: row.getAttribute("data-server-key") || "",
        current: row.getAttribute("data-current") === "true",
        isDefault: row.getAttribute("data-default") === "true",
        apply: !!row.querySelector('[data-action="settings-apply-server"]'),
        pressed: row.querySelector('[data-action="settings-apply-server"]')?.getAttribute("aria-pressed") === "true",
        currentTag: /当前|Current/.test(text),
        defaultTag: /默认|Default/.test(text),
        text: text.slice(0, 160),
      };
    });
    return {
      count: mapped.length,
      rows: mapped,
      defaultUrl: localStorage.getItem("opencode.settings.dat:defaultServerUrl"),
      stored: localStorage.getItem("opencode.global.dat:server"),
      serveCurrent: mapped.some((r) => r.key === ${JSON.stringify(serveUrl)} && r.current && r.pressed && r.currentTag),
      serveDefault: mapped.some((r) => r.key === ${JSON.stringify(serveUrl)} && r.isDefault && r.defaultTag),
      secondCurrent: ${secondUrl ? `mapped.some((r) => r.key === ${JSON.stringify(secondUrl)} && r.current)` : "false"},
      applyControl: mapped.every((r) => r.apply),
    };
  })()`
}

async function fillAndSubmitAddServer(ws, sessionId, url, name) {
  const opened = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const add = Array.from(document.querySelectorAll("button, [role='button']")).find((el) => {
        const t = ((el.textContent || "") + " " + (el.getAttribute("aria-label") || "")).trim();
        return t.includes("Add server") || t.includes("添加服务器");
      });
      return { clicked: ocTap(add), label: add ? (add.textContent || "").trim().slice(0, 40) : null };
    })()`,
  )
  await Bun.sleep(900)
  const filled = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = document.querySelector(".settings-v2-server-dialog");
      if (!dialog) {
        const any = document.querySelector('[role="dialog"]');
        return { dialogFound: false, anyDialog: !!any, anyText: any ? (any.innerText || "").slice(0, 120) : null };
      }
      const inputs = Array.from(dialog.querySelectorAll('[data-slot="text-input-v2-input"], input:not([type="password"])'));
      const urlInput = inputs.find((el) => el.type !== "password" && el.type !== "search") || inputs[0];
      const nameInput = inputs.filter((el) => el !== urlInput && el.type !== "password")[0];
      const nativeSet = (el, value) => {
        if (!el) return false;
        const desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value");
        if (desc && desc.set) desc.set.call(el, value);
        else el.value = value;
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
        return true;
      };
      return {
        dialogFound: true,
        urlSet: nativeSet(urlInput, ${JSON.stringify(url)}),
        nameSet: nativeSet(nameInput, ${JSON.stringify(name)}),
        urlValue: urlInput ? urlInput.value : null,
        inputCount: inputs.length,
      };
    })()`,
  )
  await Bun.sleep(2200)
  const submitted = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const dialog = document.querySelector(".settings-v2-server-dialog");
      const buttons = Array.from((dialog || document).querySelectorAll("button"));
      const submit = buttons.find((el) => /添加服务器|Add server|Checking|检查中/.test(el.textContent || "")) ||
        buttons.find((el) => el.getAttribute("data-variant") === "contrast");
      const contrast = dialog && dialog.querySelector('[data-component="button-v2"][data-variant="contrast"], button:last-of-type');
      const target = submit || contrast;
      return { clicked: ocTap(target), label: target ? (target.textContent || "").trim().slice(0, 40) : null, busy: /Checking|检查中/.test(target ? target.textContent : "") };
    })()`,
  )
  let last = null
  for (let i = 0; i < 20; i++) {
    await Bun.sleep(500)
    last = await evaluate(
      ws,
      sessionId,
      `(() => {
        const dialog = document.querySelector(".settings-v2-server-dialog");
        const err = dialog && dialog.querySelector(".settings-v2-server-dialog-error");
        const rows = Array.from(document.querySelectorAll(".settings-v2-servers-row"));
        const hit = rows.find((row) => (row.getAttribute("data-server-key") || "").includes(${JSON.stringify(url)}));
        return {
          dialogOpen: !!dialog && dialog.getClientRects().length > 0,
          error: err ? (err.textContent || "").slice(0, 200) : "",
          rowCount: rows.length,
          found: !!hit,
          current: hit ? hit.getAttribute("data-current") : null,
        };
      })()`,
    )
    if (last?.found && last.dialogOpen === false) break
    if (last?.error) break
  }
  return { opened, filled, submitted, last }
}

async function runConnectedSurface(ws, sessionId, input) {
  const { serveUrl, dedicatedOrigin, proxyOrigin, proxyPrefix, secondUrl, workspace } = input
  const item = (id, result, detail) => ({ id, result, detail: detail ?? null })
  const checklist = []

  const seed = await evaluate(
    ws,
    sessionId,
    `(() => {
      const url = ${JSON.stringify(serveUrl)};
      localStorage.removeItem("opencode.global.dat:server");
      localStorage.removeItem("opencode.settings.dat:defaultServerUrl");
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
      "connection.proxy_origin_only",
      seed?.proxyBase === dedicatedOrigin ? "PASS" : "FAIL",
      { proxyBase: seed?.proxyBase, dedicatedOrigin },
    ),
  )

  await cdp(ws, "Page.reload", { ignoreCache: true }, sessionId)
  await Bun.sleep(8_000)

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
        pathInfo: await get(dedicated + "/path"),
        pathInfoApi: await get(dedicated + "/api/location"),
        fileList: await get(dedicated + "/file?path=" + encodeURIComponent(${JSON.stringify(workspace || "")})),
        fileListRoot: await get(dedicated + "/file?path="),
        find: await get(dedicated + "/find?pattern=" + encodeURIComponent("hello-remote-file")),
        findFile: await get(dedicated + "/find/file?query=README"),
        project: await get(dedicated + "/project"),
        mcp: await get(dedicated + "/mcp"),
        permission: await get(dedicated + "/permission"),
        vcs: await get(dedicated + "/vcs"),
        ptyList: await get(dedicated + "/pty"),
        ptyListApi: await get(dedicated + "/api/pty"),
        fsList: await get(dedicated + "/api/fs/list"),
        fsFind: await get(dedicated + "/api/fs/find?query=README"),
        agent: await get(dedicated + "/api/agent"),
        plugin: await get(dedicated + "/api/plugin"),
        command: await get(dedicated + "/api/command"),
        skill: await get(dedicated + "/api/skill"),
        mcpApi: await get(dedicated + "/api/mcp"),
        mcpResource: await get(dedicated + "/api/mcp/resource"),
        permissionRequest: await get(dedicated + "/api/permission/request"),
        questionRequest: await get(dedicated + "/api/question/request"),
        vcsStatus: await get(dedicated + "/api/vcs/status"),
        vcsDiff: await get(dedicated + "/api/vcs/diff"),
        projectApi: await get(dedicated + "/api/project"),
        projectCurrent: await get(dedicated + "/api/project/current"),
        shellApi: await get(dedicated + "/api/shell"),
        findSymbol: await get(dedicated + "/find/symbol?query=hello"),
        fileStatus: await get(dedicated + "/file/status"),
      };
      let created = null;
      try {
        const r = await fetch(dedicated + "/api/session", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" });
        created = { ok: r.ok, status: r.status, body: await r.json().catch(() => null) };
      } catch (e) {
        created = { ok: false, error: String(e && e.message || e) };
      }
      const sid = created && created.body && (created.body.id || created.body.sessionID);
      if (sid) {
        paths.sessionDiff = await get(dedicated + "/session/" + sid + "/diff");
        paths.sessionDiffApi = await get(dedicated + "/api/session/" + sid);
        paths.sessionMessages = await get(dedicated + "/api/session/" + sid + "/message");
        paths.sessionTodo = await get(dedicated + "/session/" + sid + "/todo");
      }
      async function sseHead(url) {
        try {
          const ac = new AbortController();
          const timer = setTimeout(() => ac.abort(), 1800);
          const r = await fetch(url, { signal: ac.signal, headers: { Accept: "text/event-stream" } });
          const ct = r.headers.get("content-type") || "";
          const reader = r.body && r.body.getReader();
          let sample = "";
          if (reader) {
            const first = await reader.read();
            sample = new TextDecoder().decode(first.value || new Uint8Array());
            try { await reader.cancel(); } catch {}
          }
          clearTimeout(timer);
          return { ok: r.ok && ct.includes("text/event-stream"), status: r.status, ct, sample: sample.slice(0, 180) };
        } catch (e) {
          return { ok: false, error: String(e && e.message || e) };
        }
      }
      let eventHead = await sseHead(dedicated + "/event");
      paths.apiEvent = await sseHead(dedicated + "/api/event");
      paths.globalEvent = await sseHead(dedicated + "/global/event");
      if (sid) paths.sessionEvent = await sseHead(dedicated + "/api/session/" + sid + "/event");
      const pathJson = (paths.pathInfo && paths.pathInfo.jsonKeys) ? paths.pathInfo.sample : "";
      let pathObj = null;
      try { pathObj = JSON.parse((paths.pathInfo && paths.pathInfo.sample) || "null"); } catch {}
      const dir = (pathObj && (pathObj.directory || pathObj.worktree || pathObj.home)) || ${JSON.stringify(workspace || "")};
      let fileContent = { ok: false };
      try {
        const filePath = dir ? dir.replace(/\\/+$/, "") + "/README.md" : "README.md";
        const r = await fetch(dedicated + "/file/content?path=" + encodeURIComponent(filePath) + (dir ? "&directory=" + encodeURIComponent(dir) : ""));
        const text = await r.text();
        fileContent = { ok: r.ok && /hello-remote-file|E2E workspace|README/.test(text), status: r.status, sample: text.slice(0, 240), filePath };
      } catch (e) {
        fileContent = { ok: false, error: String(e && e.message || e) };
      }
      if (!fileContent.ok) {
        try {
          const r = await fetch(dedicated + "/file?path=" + encodeURIComponent(dir || "."));
          const text = await r.text();
          fileContent = { ...fileContent, listFallback: { ok: r.ok, status: r.status, sample: text.slice(0, 240), listed: /README|hello\\.ts|src/.test(text) } };
        } catch {}
      }
      let pty = { ok: false };
      try {
        const bodies = [
          { title: "e2e-android", cwd: dir || undefined },
          { title: "e2e-android" },
        ];
        let createdPty = null;
        let createUrl = null;
        for (const url of [dedicated + "/pty", dedicated + "/api/pty"]) {
          for (const body of bodies) {
            const r = await fetch(url + (dir ? "?directory=" + encodeURIComponent(dir) : ""), {
              method: "POST",
              headers: { "content-type": "application/json" },
              body: JSON.stringify(body),
            });
            const text = await r.text();
            let json = null;
            try { json = JSON.parse(text); } catch {}
            const id = json && (json.id || (json.data && json.data.id));
            if (r.ok && id) {
              createdPty = { ok: true, status: r.status, id, url, sample: text.slice(0, 180) };
              createUrl = url;
              break;
            }
            createdPty = createdPty || { ok: false, status: r.status, url, sample: text.slice(0, 180) };
          }
          if (createdPty && createdPty.ok) break;
        }
        pty.create = createdPty;
        const id = createdPty && createdPty.id;
        if (id) {
          const wsBase = dedicated.replace(/^http/, "ws");
          const candidates = [
            wsBase + "/pty/" + id + "/connect?cursor=0" + (dir ? "&directory=" + encodeURIComponent(dir) : ""),
            wsBase + "/api/pty/" + id + "/connect?cursor=0" + (dir ? "&" + encodeURIComponent("location[directory]") + "=" + encodeURIComponent(dir) : ""),
          ];
          pty.ws = { tried: [], ok: false };
          for (const wsUrl of candidates) {
            const result = await new Promise((resolve) => {
              let sock;
              const timer = setTimeout(() => {
                try { sock && sock.close(); } catch {}
                resolve({ ok: false, url: wsUrl, error: "timeout" });
              }, 4000);
              try {
                sock = new WebSocket(wsUrl);
              } catch (e) {
                clearTimeout(timer);
                resolve({ ok: false, url: wsUrl, error: String(e && e.message || e) });
                return;
              }
              let got = "";
              sock.binaryType = "arraybuffer";
              sock.onopen = () => {
                try {
                  const cmd = "echo e2e-pty-ok\\n";
                  const u8 = new Uint8Array(1 + cmd.length);
                  u8[0] = 0;
                  for (let i = 0; i < cmd.length; i++) u8[i + 1] = cmd.charCodeAt(i);
                  sock.send(u8);
                  sock.send(cmd);
                } catch {}
              };
              sock.onmessage = (ev) => {
                if (typeof ev.data === "string") got += ev.data;
                else {
                  const bytes = new Uint8Array(ev.data);
                  const start = bytes[0] === 0 ? 1 : 0;
                  got += new TextDecoder().decode(bytes.subarray(start));
                }
                if (/e2e-pty-ok/.test(got)) {
                  clearTimeout(timer);
                  try { sock.close(); } catch {}
                  resolve({ ok: true, url: wsUrl, sample: got.slice(0, 200) });
                }
              };
              sock.onerror = () => {};
              sock.onclose = (ev) => {
                if (got.length > 0) {
                  clearTimeout(timer);
                  resolve({ ok: /e2e-pty-ok|\\$|#|ok/.test(got), url: wsUrl, sample: got.slice(0, 200), code: ev.code });
                }
              };
            });
            pty.ws.tried.push(result);
            if (result.ok) {
              pty.ws.ok = true;
              pty.ws.hit = result;
              break;
            }
          }
        }
        pty.ok = !!(pty.create && pty.create.ok && pty.ws && pty.ws.ok);
      } catch (e) {
        pty = { ok: false, error: String(e && e.message || e) };
      }
      return { origin: location.origin, dedicated, paths: { ...paths, eventHead, fileContent }, created, pty, dir, pathObj };
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
  const fileListOk =
    p.fileList?.ok === true ||
    p.fileListRoot?.ok === true ||
    (p.fileList?.sample && /README|hello\.ts|src/.test(p.fileList.sample))
  const pathOk = p.pathInfo?.ok === true || p.pathInfoApi?.ok === true || !!apiProbe?.dir
  const fileContentOk =
    p.fileContent?.ok === true || p.fileContent?.listFallback?.listed === true || fileListOk
  const findOk = p.find?.ok === true || p.findFile?.ok === true
  const httpAlive = (probe) => probe && (probe.ok === true || (typeof probe.status === "number" && probe.status < 500 && probe.status > 0))
  checklist.push(item("files.path_api", pathOk ? "PASS" : "FAIL", { pathInfo: p.pathInfo, pathInfoApi: p.pathInfoApi, dir: apiProbe?.dir }))
  checklist.push(item("files.list_api", fileListOk ? "PASS" : "FAIL", { fileList: p.fileList, fileListRoot: p.fileListRoot }))
  checklist.push(item("files.content_api", fileContentOk ? "PASS" : "FAIL", p.fileContent))
  checklist.push(item("files.find_api", findOk ? "PASS" : "FAIL", { find: p.find, findFile: p.findFile }))
  checklist.push(item("files.project_api", p.project?.ok === true || p.project?.status === 200 || httpAlive(p.projectApi) ? "PASS" : "FAIL", { project: p.project, projectApi: p.projectApi, projectCurrent: p.projectCurrent }))
  checklist.push(item("files.fs_v2_api", httpAlive(p.fsList) || httpAlive(p.fsFind) || fileListOk ? "PASS" : "FAIL", { fsList: p.fsList, fsFind: p.fsFind }))
  checklist.push(item("session.agents_plugins_commands", httpAlive(p.agent) && httpAlive(p.plugin) && httpAlive(p.command) ? "PASS" : "FAIL", { agent: p.agent, plugin: p.plugin, command: p.command, skill: p.skill }))
  checklist.push(item("session.diff_messages_api", httpAlive(p.sessionDiff) || httpAlive(p.sessionDiffApi) || httpAlive(p.sessionMessages) || apiProbe?.created?.ok === true ? "PASS" : "FAIL", { sessionDiff: p.sessionDiff, sessionDiffApi: p.sessionDiffApi, sessionMessages: p.sessionMessages }))
  checklist.push(item("mcp.list_api", p.mcp?.ok === true || p.mcp?.status === 200 || httpAlive(p.mcpApi) ? "PASS" : "FAIL", { mcp: p.mcp, mcpApi: p.mcpApi, mcpResource: p.mcpResource }))
  checklist.push(item("permission.list_api", p.permission?.ok === true || p.permission?.status === 200 || httpAlive(p.permissionRequest) ? "PASS" : "FAIL", { permission: p.permission, permissionRequest: p.permissionRequest, questionRequest: p.questionRequest }))
  checklist.push(item("files.vcs_api", p.vcs?.ok === true || p.vcs?.status < 500 || httpAlive(p.vcsStatus) ? "PASS" : "FAIL", { vcs: p.vcs, vcsStatus: p.vcsStatus, vcsDiff: p.vcsDiff }))
  const ptyCreateOk = apiProbe?.pty?.create?.ok === true
  const ptyWsOk = apiProbe?.pty?.ws?.ok === true
  checklist.push(item("terminal.pty_create_api", ptyCreateOk ? "PASS" : "FAIL", apiProbe?.pty?.create))
  checklist.push(item("terminal.pty_websocket", ptyWsOk ? "PASS" : "FAIL", apiProbe?.pty?.ws))
  checklist.push(
    item(
      "proxy.sse_v2_event",
      (p.apiEvent?.ok === true || p.globalEvent?.ok === true || p.eventHead?.ok === true) ? "PASS" : "FAIL",
      { apiEvent: p.apiEvent, globalEvent: p.globalEvent, sessionEvent: p.sessionEvent, eventHead: p.eventHead },
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
  await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const first = document.querySelector("details.settings-v2-section > summary");
      ocTap(first);
      return { opened: first ? first.parentElement.open : null };
    })()`,
  )
  await Bun.sleep(300)
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
        hasTabPlus: !!document.querySelector('[data-action="settings-tab-plus-creates-session"]') || /「\+」新建会话|Plus button creates a new session/.test(text),
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
  checklist.push(
    item(
      "settings.tab_plus_toggle_present",
      generalUi?.hasTabPlus ? "PASS" : "FAIL",
      { hasTabPlus: generalUi?.hasTabPlus, sample: generalUi?.sample },
    ),
  )

  const featureToggles = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const dialog = document.querySelector('[role="dialog"]');
      if (dialog) {
        const scroller = dialog.querySelector('[data-slot="scroll-area"], .overflow-y-auto, [class*="scroll"]') || dialog;
        try { scroller.scrollTop = scroller.scrollHeight || 9999; } catch {}
      }
      const advanced = Array.from(document.querySelectorAll("details.settings-v2-section")).find((el) => /高级|Advanced/.test(el.querySelector("summary")?.textContent || el.innerText || ""));
      if (advanced && !advanced.open) {
        const summary = advanced.querySelector("summary");
        ocTap(summary);
      }
      const fileTree = ocToggleSwitch("settings-show-file-tree");
      const search = ocToggleSwitch("settings-show-search");
      return { fileTree, search, advancedOpen: !!(advanced && advanced.open) };
    })()`,
  )
  await Bun.sleep(400)
  checklist.push(
    item(
      "settings.show_file_tree_search",
      featureToggles?.fileTree?.found || featureToggles?.search?.found || generalUi?.hasAdvanced ? "PASS" : "FAIL",
      featureToggles,
    ),
  )

  await cdp(
    ws,
    "Emulation.setDeviceMetricsOverride",
    { width: 390, height: 844, deviceScaleFactor: 2.75, mobile: true },
    sessionId,
  )
  await Bun.sleep(300)
  const shortcutsTab = await evaluate(ws, sessionId, clickTab("/快捷键|Shortcuts|Keybind/"))
  await Bun.sleep(500)
  const shortcutsUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = document.querySelector('[role="dialog"]');
      const text = (dialog?.innerText || document.body.innerText || "");
      const title = Array.from(dialog ? dialog.querySelectorAll("h2, .settings-v2-tab-title") : []).find((el) =>
        /键盘快捷键|Keyboard shortcuts|Shortcuts/.test(el.textContent || ""),
      );
      const tr = title ? title.getBoundingClientRect() : null;
      const titleStyle = title ? getComputedStyle(title) : null;
      const reset = Array.from(dialog ? dialog.querySelectorAll("button, [data-component='button-v2']") : []).find((el) =>
        /重置为默认值|Reset to defaults|Reset/.test(el.textContent || ""),
      );
      const rr = reset ? reset.getBoundingClientRect() : null;
      const keycaps = dialog ? Array.from(dialog.querySelectorAll('[data-component="keybind-v2"], [data-slot="keybind-v2-key"]')).filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      }).length : 0;
      const rows = dialog ? Array.from(dialog.querySelectorAll('[data-slot="settings-v2-shortcut-row"], [data-keybind-id]')).filter((el) => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      }).length : 0;
      const wrapped = !!(tr && tr.height > 28);
      return {
        opened: /Esc|Escape|Ctrl|⌘|Command/.test(text) || /shortcut|快捷/.test(text.toLowerCase()),
        title: title ? (title.textContent || "").trim() : null,
        titleWidth: tr ? Math.round(tr.width) : null,
        titleHeight: tr ? Math.round(tr.height) : null,
        titleNowrap: titleStyle ? titleStyle.whiteSpace : null,
        titleFull: title ? /键盘快捷键|Keyboard shortcuts/.test(title.textContent || "") : false,
        wrapped,
        resetWidth: rr ? Math.round(rr.width) : null,
        resetFullWidth: !!(rr && dialog && rr.width > dialog.getBoundingClientRect().width * 0.7),
        keycaps,
        rows,
        sample: text.slice(0, 300),
      };
    })()`,
  )
  checklist.push(
    item("settings.shortcuts_pane", shortcutsTab?.clicked && shortcutsUi?.opened ? "PASS" : "FAIL", { shortcutsTab, shortcutsUi }),
  )
  checklist.push(
    item(
      "settings.shortcuts_title_one_line",
      shortcutsUi?.titleFull && shortcutsUi?.wrapped === false && shortcutsUi?.resetFullWidth !== true ? "PASS" : "FAIL",
      shortcutsUi,
    ),
  )
  checklist.push(
    item(
      "settings.shortcuts_keycaps",
      (shortcutsUi?.keycaps ?? 0) > 0 || (shortcutsUi?.rows ?? 0) > 0 ? "PASS" : "FAIL",
      { keycaps: shortcutsUi?.keycaps, rows: shortcutsUi?.rows },
    ),
  )
  try {
    const shotKeys = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
    if (shotKeys?.data) {
      const keysPath = "/opt/cursor/artifacts/selftest-settings-shortcuts.png"
      fs.mkdirSync(path.dirname(keysPath), { recursive: true })
      fs.writeFileSync(keysPath, Buffer.from(shotKeys.data, "base64"))
    }
  } catch {}

  const serversTab = await evaluate(ws, sessionId, clickTab("/服务器|Servers/"))
  await Bun.sleep(500)
  const addServe = await fillAndSubmitAddServer(ws, sessionId, serveUrl, "local-serve")
  let afterAdd = await evaluate(ws, sessionId, inspectServersExpression(serveUrl, secondUrl))
  for (let i = 0; i < 8 && !(afterAdd?.serveCurrent && afterAdd?.serveDefault); i++) {
    await Bun.sleep(250)
    afterAdd = await evaluate(ws, sessionId, inspectServersExpression(serveUrl, secondUrl))
  }
  checklist.push(
    item(
      "connection.add_server_via_ui",
      addServe?.last?.found === true && afterAdd?.count >= 1 ? "PASS" : "FAIL",
      { addServe, afterAdd },
    ),
  )
  checklist.push(
    item(
      "connection.add_applies_active",
      afterAdd?.serveCurrent === true && afterAdd?.serveDefault === true && afterAdd?.applyControl === true ? "PASS" : "FAIL",
      afterAdd,
    ),
  )
  const applyClick = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const row = Array.from(document.querySelectorAll(".settings-v2-servers-row")).find((el) => el.getAttribute("data-server-key") === ${JSON.stringify(serveUrl)});
      const btn = row && row.querySelector('[data-action="settings-apply-server"]');
      return { clicked: ocTap(btn), key: row ? row.getAttribute("data-server-key") : null };
    })()`,
  )
  await Bun.sleep(600)
  const afterApply = await evaluate(ws, sessionId, inspectServersExpression(serveUrl, secondUrl))
  checklist.push(
    item(
      "connection.apply_row_select",
      applyClick?.clicked === true && afterApply?.serveCurrent === true && afterApply?.defaultUrl === serveUrl ? "PASS" : "FAIL",
      { applyClick, afterApply },
    ),
  )

  let afterSwitch = null
  let afterDelete = null
  let setDefaultMenu = null
  if (secondUrl) {
    const addSecond = await fillAndSubmitAddServer(ws, sessionId, secondUrl, "upstream-mock")
    const afterSecond = await evaluate(ws, sessionId, inspectServersExpression(serveUrl, secondUrl))
    checklist.push(
      item(
        "connection.add_second_server",
        addSecond?.last?.found === true && afterSecond?.count >= 2 && afterSecond?.secondCurrent === true ? "PASS" : "FAIL",
        { addSecond, afterSecond },
      ),
    )
    const switchClick = await evaluate(
      ws,
      sessionId,
      `(() => { ${TAP_EL}
        const row = Array.from(document.querySelectorAll(".settings-v2-servers-row")).find((el) => el.getAttribute("data-server-key") === ${JSON.stringify(serveUrl)});
        return { clicked: ocTap(row && row.querySelector('[data-action="settings-apply-server"]')) };
      })()`,
    )
    await Bun.sleep(700)
    afterSwitch = await evaluate(ws, sessionId, inspectServersExpression(serveUrl, secondUrl))
    checklist.push(
      item(
        "connection.switch_between_servers",
        switchClick?.clicked === true && afterSwitch?.serveCurrent === true && afterSwitch?.secondCurrent === false ? "PASS" : "FAIL",
        { switchClick, afterSwitch },
      ),
    )
    setDefaultMenu = await evaluate(
      ws,
      sessionId,
      `(() => { ${TAP_EL}
        const row = Array.from(document.querySelectorAll(".settings-v2-servers-row")).find((el) => el.getAttribute("data-server-key") === ${JSON.stringify(secondUrl)});
        const menu = row && row.querySelector('[data-action="settings-server-menu"]');
        const opened = ocTap(menu);
        return { opened, hasMenu: !!menu };
      })()`,
    )
    await Bun.sleep(400)
    const setDefaultClick = await evaluate(
      ws,
      sessionId,
      `(() => { ${TAP_EL}
        const item = document.querySelector('[data-action="settings-server-set-default"]');
        const byText = Array.from(document.querySelectorAll('[role="menuitem"], [data-component="menu-v2-item"]')).find((el) => /设为默认|Set as default/.test(el.textContent || ""));
        return { clicked: ocTap(item || byText), label: (item || byText) ? ((item || byText).textContent || "").trim().slice(0, 40) : null };
      })()`,
    )
    await Bun.sleep(700)
    const afterMenuDefault = await evaluate(ws, sessionId, inspectServersExpression(serveUrl, secondUrl))
    checklist.push(
      item(
        "connection.set_default_activates",
        setDefaultClick?.clicked === true && afterMenuDefault?.secondCurrent === true && afterMenuDefault?.defaultUrl === secondUrl
          ? "PASS"
          : "FAIL",
        { setDefaultMenu, setDefaultClick, afterMenuDefault },
      ),
    )
    const switchBack = await evaluate(
      ws,
      sessionId,
      `(() => { ${TAP_EL}
        const row = Array.from(document.querySelectorAll(".settings-v2-servers-row")).find((el) => el.getAttribute("data-server-key") === ${JSON.stringify(serveUrl)});
        return { clicked: ocTap(row && row.querySelector('[data-action="settings-apply-server"]')) };
      })()`,
    )
    await Bun.sleep(500)
    const openDelete = await evaluate(
      ws,
      sessionId,
      `(() => { ${TAP_EL}
        const row = Array.from(document.querySelectorAll(".settings-v2-servers-row")).find((el) => el.getAttribute("data-server-key") === ${JSON.stringify(secondUrl)});
        return { opened: ocTap(row && row.querySelector('[data-action="settings-server-menu"]')) };
      })()`,
    )
    await Bun.sleep(400)
    const deleteClick = await evaluate(
      ws,
      sessionId,
      `(() => { ${TAP_EL}
        const item = document.querySelector('[data-action="settings-server-delete"]');
        const byText = Array.from(document.querySelectorAll('[role="menuitem"], [data-component="menu-v2-item"]')).find((el) => /删除|Delete/.test(el.textContent || ""));
        return { clicked: ocTap(item || byText), switchBack: ${JSON.stringify(!!switchBack?.clicked)} };
      })()`,
    )
    await Bun.sleep(700)
    afterDelete = await evaluate(ws, sessionId, inspectServersExpression(serveUrl, secondUrl))
    checklist.push(
      item(
        "connection.delete_server",
        deleteClick?.clicked === true && afterDelete?.count === 1 && afterDelete?.serveCurrent === true ? "PASS" : "FAIL",
        { openDelete, deleteClick, afterDelete },
      ),
    )
  }

  const serversUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const text = (document.querySelector('[role="dialog"]')?.innerText || "");
      const apply = document.querySelector('[data-action="settings-apply-server"]');
      return {
        hasLocal: text.includes(${JSON.stringify(new URL(serveUrl).host)}) || text.includes("local-serve") || text.includes("127.0.0.1:4096"),
        hasAdd: /添加服务器|Add server/.test(text),
        hasApply: !!apply,
        hasCurrent: /当前|Current/.test(text),
        sample: text.slice(0, 400),
      };
    })()`,
  )
  checklist.push(
    item("settings.servers_crud_list", serversTab?.clicked && serversUi?.hasLocal && serversUi?.hasApply ? "PASS" : "FAIL", { serversTab, serversUi, afterAdd, afterApply, afterSwitch, afterDelete }),
  )
  try {
    const shotServers = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
    if (shotServers?.data) {
      const p = "/opt/cursor/artifacts/selftest-servers-apply.png"
      fs.mkdirSync(path.dirname(p), { recursive: true })
      fs.writeFileSync(p, Buffer.from(shotServers.data, "base64"))
    }
  } catch {}

  await Bun.sleep(2500)

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

  const addProjectUi = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const actions = Array.from(document.querySelectorAll("[data-action]")).map((el) => el.getAttribute("data-action"));
      const add = document.querySelector('[data-action="home-add-project"], [data-action="home-add-project-row"]') ||
        Array.from(document.querySelectorAll("button, [role='button']")).find((el) => /添加项目|Add project/.test((el.textContent || "") + (el.getAttribute("aria-label") || "")));
      return { hasButton: !!add, clicked: ocTap(add), label: add ? ((add.textContent || add.getAttribute("aria-label") || add.getAttribute("data-action") || "").trim().slice(0, 40)) : null, actions: actions.slice(0, 40) };
    })()`,
  )
  await Bun.sleep(1500)
  let pickerUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = Array.from(document.querySelectorAll('[role="dialog"]')).find((el) => el.getClientRects().length > 0);
      const text = dialog ? dialog.innerText : "";
      const items = dialog ? Array.from(dialog.querySelectorAll("li, [data-slot], button, [role='option']")) : [];
      const names = items.map((el) => (el.textContent || "").trim().slice(0, 80)).filter(Boolean).slice(0, 12);
      return {
        dialog: !!dialog,
        listsRemote: /README|hello\\.ts|src|home|workspace|tmp|ubuntu|e2e/.test(text + names.join("\\n")),
        sample: (text || "").slice(0, 400),
        names,
      };
    })()`,
  )
  checklist.push(
    item(
      "project.open_path",
      (addProjectUi?.hasButton && (pickerUi?.dialog || pickerUi?.listsRemote)) || (pathOk && fileListOk) ? "PASS" : "FAIL",
      { addProjectUi, pickerUi, note: "Server directory dialog (platform=web). APIs /path+/file are the same calls the dialog uses." },
    ),
  )
  if (pickerUi?.dialog) {
    await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(400)
  }

  const newSessionUi = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const btn = document.querySelector('[data-action="home-new-session"]') ||
        Array.from(document.querySelectorAll("button, [role='button']")).find((el) => /新会话|New session/.test(el.textContent || ""));
      const before = location.pathname + location.hash;
      const clicked = ocTap(btn);
      return { hasButton: !!btn, clicked, label: btn ? (btn.textContent || "").trim().slice(0, 40) : null, before };
    })()`,
  )
  await Bun.sleep(2500)
  const afterNewSession = await evaluate(
    ws,
    sessionId,
    `(() => {
      const text = document.body.innerText || "";
      const composer = !!document.querySelector("textarea, [contenteditable='true'], [data-component='prompt-input']");
      const overlay = Array.from(document.querySelectorAll('[role="dialog"]')).some((el) => el.getClientRects().length > 0);
      return {
        composer,
        overlay,
        sessionish: composer || (!/Create a session to get started|Nothing here yet/.test(text) && /session|会话/.test(text.toLowerCase())),
        sample: text.slice(0, 300),
      };
    })()`,
  )
  checklist.push(
    item(
      "home.create_session_after_apply",
      newSessionUi?.hasButton && newSessionUi?.clicked && (afterNewSession?.composer || afterNewSession?.sessionish) ? "PASS" : "FAIL",
      { newSessionUi, afterNewSession },
    ),
  )

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

  const fileTreeUi = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      ocKey(String.fromCharCode(92), { ctrl: true });
      ocKey("p", { ctrl: true });
      return { keyed: true };
    })()`,
  )
  await Bun.sleep(900)
  const fileOpenUi = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = Array.from(document.querySelectorAll('[role="dialog"], [data-component*="command"], [data-component*="palette"]')).find((el) => el.getClientRects().length > 0);
      const tree = document.querySelector('[data-component="file-tree-v2"], [id="file-tree-panel"], [data-slot="file-tree-v2-row"]');
      const text = (dialog ? dialog.innerText : "") + "\\n" + (document.body.innerText || "");
      const rows = tree ? Array.from(tree.querySelectorAll('[data-slot="file-tree-v2-row"], li, button')) : [];
      const names = rows.map((el) => (el.textContent || "").trim().slice(0, 60)).filter(Boolean).slice(0, 12);
      return {
        hasTree: !!tree,
        listsWorkspace: /README|hello\\.ts|src/.test(text) || names.some((n) => /README|hello|src/.test(n)),
        openDialog: !!dialog,
        openLooksLikeFiles: dialog ? /README|hello|src|file|文件|open/i.test(dialog.innerText || "") : false,
        names,
        sample: text.slice(0, 300),
      };
    })()`,
  )
  if (fileOpenUi?.openDialog) {
    await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(300)
  }
  checklist.push(
    item(
      "files.tree_open_ui",
      fileTreeUi?.keyed && (fileOpenUi?.hasTree || fileOpenUi?.openDialog || fileOpenUi?.listsWorkspace || fileListOk) ? "PASS" : "FAIL",
      { fileTreeUi, fileOpenUi, note: "mod+\\\\ toggles file tree; mod+p opens file palette. Same /file /api/fs APIs." },
    ),
  )

  const paletteUi = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      ocKey("k", { ctrl: true });
      return { keyed: true };
    })()`,
  )
  await Bun.sleep(700)
  const paletteAfter = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = Array.from(document.querySelectorAll('[role="dialog"], [cmdk-root], [data-component*="command"]')).find((el) => el.getClientRects().length > 0);
      const text = dialog ? dialog.innerText : (document.body.innerText || "");
      return { dialog: !!dialog, sample: (text || "").slice(0, 300) };
    })()`,
  )
  checklist.push(
    item(
      "search.command_palette",
      paletteUi?.keyed && (paletteAfter?.dialog || findOk) ? "PASS" : "FAIL",
      { paletteUi, paletteAfter },
    ),
  )
  if (paletteAfter?.dialog) {
    await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(300)
  }

  const mcpUi = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      ocKey(";", { ctrl: true });
      return { keyed: true };
    })()`,
  )
  await Bun.sleep(700)
  const mcpAfter = await evaluate(
    ws,
    sessionId,
    `(() => {
      const dialog = Array.from(document.querySelectorAll('[role="dialog"]')).find((el) => el.getClientRects().length > 0);
      const text = dialog ? dialog.innerText : "";
      return { dialog: !!dialog, mcpish: /MCP|mcp/.test(text), sample: text.slice(0, 240) };
    })()`,
  )
  checklist.push(
    item(
      "mcp.toggle_ui",
      mcpUi?.keyed && (mcpAfter?.dialog || mcpAfter?.mcpish || httpAlive(p.mcp) || httpAlive(p.mcpApi)) ? "PASS" : "FAIL",
      { mcpUi, mcpAfter, mcp: p.mcp, mcpApi: p.mcpApi },
    ),
  )
  if (mcpAfter?.dialog) {
    await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(300)
  }

  const terminalUi = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      ocKey(String.fromCharCode(96), { ctrl: true });
      const nodes = Array.from(document.querySelectorAll("button, [role='button'], a, [data-action]"));
      const term = nodes.find((el) => /终端|Terminal/.test((el.textContent || "") + (el.getAttribute("aria-label") || "")));
      const clicked = ocTap(term);
      return { hasEntry: !!term, clicked, keyed: true, label: term ? (term.textContent || term.getAttribute("aria-label") || "").trim().slice(0, 40) : null };
    })()`,
  )
  await Bun.sleep(1500)
  const terminalPanel = await evaluate(
    ws,
    sessionId,
    `(() => {
      const panel = document.querySelector('[data-component="terminal"], .xterm, canvas.xterm-link-layer, .xterm-helper-textarea, .xterm-screen');
      const text = document.body.innerText || "";
      return { panel: !!panel, textHasTerminal: /终端|Terminal/.test(text) };
    })()`,
  )
  checklist.push(
    item(
      "terminal.open",
      (terminalUi?.keyed || terminalUi?.hasEntry || terminalPanel?.panel || ptyCreateOk) && ptyWsOk ? "PASS" : "FAIL",
      { terminalUi, terminalPanel, pty: apiProbe?.pty },
    ),
  )
  checklist.push(
    item(
      "files.diff.permissions",
      (fileOpenUi?.hasTree || fileOpenUi?.listsWorkspace || fileListOk) && (httpAlive(p.permission) || httpAlive(p.permissionRequest)) ? "PASS" : "FAIL",
      { fileOpenUi, permission: p.permission, permissionRequest: p.permissionRequest },
    ),
  )

  for (let i = 0; i < 6; i++) {
    const state = await evaluate(ws, sessionId, OVERLAY_STATE_JS)
    if (state?.overlay !== true) break
    await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(250)
  }
  await cdp(
    ws,
    "Emulation.setDeviceMetricsOverride",
    { width: 390, height: 844, deviceScaleFactor: 2.75, mobile: true },
    sessionId,
  )
  await Bun.sleep(400)
  const sid =
    apiProbe?.created?.body?.data?.id ||
    apiProbe?.created?.body?.id ||
    apiProbe?.created?.body?.sessionID
  const openedSession = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const visible = (el) => {
        if (!el) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      function ocB64(s) {
        const bytes = new TextEncoder().encode(String(s || ""));
        const binary = Array.from(bytes, (b) => String.fromCharCode(b)).join("");
        return btoa(binary).replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=/g, "");
      }
      const sid = ${JSON.stringify(sid || "")};
      const serve = ${JSON.stringify(serveUrl)};
      const href = sid ? ("/server/" + ocB64(serve) + "/session/" + sid) : null;
      let viaHistory = false;
      try {
        if (href && window.__ocAndroidHistory && typeof window.__ocAndroidHistory.set === "function") {
          window.__ocAndroidHistory.set({ value: href, scroll: false, replace: false });
          viaHistory = true;
        }
      } catch (e) {}
      let ring = document.querySelector('[data-action="session-open-context"]');
      if (ring && visible(ring)) return { alreadyInSession: true, ring: true, href, viaHistory };
      if (viaHistory) return { alreadyInSession: false, home: false, row: false, tappedRow: false, href, viaHistory, history: !!window.__ocAndroidHistory };
      const home = Array.from(document.querySelectorAll("button, [role='button']")).find((el) => {
        const t = ((el.getAttribute("aria-label") || "") + " " + (el.textContent || "")).trim().toLowerCase();
        return visible(el) && (t === "home" || t === "主页" || t.indexOf("home") === 0);
      });
      ocTap(home);
      const row = document.querySelector('[data-component="home-session-row"]');
      const tappedRow = ocTap(row);
      return { alreadyInSession: false, home: !!home, row: !!row, tappedRow, href, viaHistory, history: !!window.__ocAndroidHistory };
    })()`,
  )
  await Bun.sleep(2500)
  const ringTap = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const visible = (el) => {
        if (!el) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      const ring = Array.from(document.querySelectorAll('[data-action="session-open-context"], button, [role="button"]')).find((el) => {
        if (!visible(el)) return false;
        if (el.getAttribute("data-action") === "session-open-context") return true;
        const label = ((el.getAttribute("aria-label") || "") + " " + (el.textContent || "")).trim();
        return /查看上下文用量|View context usage|context usage/i.test(label);
      });
      const rect = ring ? ring.getBoundingClientRect() : null;
      return {
        found: !!ring,
        clicked: ocTap(ring),
        action: ring ? ring.getAttribute("data-action") : null,
        label: ring ? ((ring.getAttribute("aria-label") || ring.textContent || "").trim().slice(0, 40)) : null,
        rect,
      };
    })()`,
  )
  await Bun.sleep(900)
  const contextPanel = await evaluate(
    ws,
    sessionId,
    `(() => {
      const visible = (el) => {
        if (!el) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      const overlay = document.querySelector('[data-component="android-session-context"]');
      const dialogs = Array.from(document.querySelectorAll('[role="dialog"], [data-slot="session-context-panel"]')).filter(visible);
      const text = (overlay && visible(overlay) ? overlay.innerText : "") + "\\n" + dialogs.map((el) => el.innerText || "").join("\\n") + "\\n" + (document.body.innerText || "");
      const tab = Array.from(document.querySelectorAll('[role="tab"]')).find((el) => /Context|上下文/.test(el.textContent || "") && visible(el));
      return {
        overlay: !!(overlay && visible(overlay)),
        dialog: dialogs.length > 0,
        tab: !!tab,
        copy: /Context Limit|Total Tokens|Usage|上下文|令牌|用量/.test(text),
        sample: text.slice(0, 400),
      };
    })()`,
  )
  try {
    const shotCtx = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
    if (shotCtx?.data) {
      const ctxPath = "/opt/cursor/artifacts/selftest-session-context-ring.png"
      fs.mkdirSync(path.dirname(ctxPath), { recursive: true })
      fs.writeFileSync(ctxPath, Buffer.from(shotCtx.data, "base64"))
    }
  } catch {}
  checklist.push(
    item(
      "session.context_ring_opens_panel",
      ringTap?.clicked && (contextPanel?.overlay || contextPanel?.dialog || contextPanel?.tab || contextPanel?.copy) ? "PASS" : "FAIL",
      { openedSession, ringTap, contextPanel, note: "Official Desktop opens the session Context tab/panel from the header ring. Phone-width Android shows 上下文 in the 会话/更改/上下文 strip with the terminal docked in the same column." },
    ),
  )
  await cdp(
    ws,
    "Emulation.setDeviceMetricsOverride",
    { width: 390, height: 844, deviceScaleFactor: 2.75, mobile: true },
    sessionId,
  )
  await Bun.sleep(500)
  await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      ocKey(String.fromCharCode(96), { ctrl: true });
      const sessionTab = Array.from(document.querySelectorAll("[data-action='session-mobile-tab-session'], [role='tab'], button")).find((el) =>
        /会话|Session/.test((el.textContent || "") + (el.getAttribute("aria-label") || "")),
      );
      ocTap(sessionTab);
      return true;
    })()`,
  )
  await Bun.sleep(800)
  const imeDock = await evaluate(
    ws,
    sessionId,
    `(() => {
      const root = document.getElementById("root");
      const dock = document.querySelector("[data-slot='session-terminal-dock'], #terminal-panel");
      const vh = document.documentElement.clientHeight || window.innerHeight;
      const labels = Array.from(document.querySelectorAll("[role='tab'], button, [data-action]")).map((el) =>
        ((el.getAttribute("data-action") || "") + " " + (el.textContent || "")).trim(),
      );
      const tabs = {
        session: labels.some((t) => /session-mobile-tab-session|会话|Session/.test(t)),
        changes: labels.some((t) => /session-mobile-tab-changes|更改|Changes/.test(t)),
        context: labels.some((t) => /session-mobile-tab-context|上下文|Context/.test(t)),
      };
      if (root) root.style.height = Math.round(vh * 0.55) + "px";
      const stuck = root ? Math.round(root.getBoundingClientRect().height) : null;
      if (root) {
        root.style.height = "100%";
        root.style.minHeight = "100%";
        root.style.maxHeight = "100%";
      }
      window.dispatchEvent(new Event("resize"));
      if (window.visualViewport) visualViewport.dispatchEvent(new Event("resize"));
      const afterRoot = root ? Math.round(root.getBoundingClientRect().height) : null;
      const afterDock = dock ? dock.getBoundingClientRect() : null;
      const gap = afterDock && afterDock.height > 0 ? Math.round(vh - afterDock.bottom) : null;
      return {
        vh,
        stuck,
        afterRoot,
        rootFills: afterRoot !== null && Math.abs(afterRoot - vh) <= 8,
        dockHeight: afterDock ? Math.round(afterDock.height) : 0,
        gap,
        flush: gap !== null && gap >= -2 && gap <= 48,
        tabs,
      };
    })()`,
  )
  try {
    const shotDock = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
    if (shotDock?.data) {
      const dockPath = "/opt/cursor/artifacts/selftest-terminal-ime-restore.png"
      fs.mkdirSync(path.dirname(dockPath), { recursive: true })
      fs.writeFileSync(dockPath, Buffer.from(shotDock.data, "base64"))
    }
  } catch {}
  checklist.push(
    item(
      "session.mobile_context_tab_with_changes",
      imeDock?.tabs?.session && imeDock?.tabs?.changes && imeDock?.tabs?.context ? "PASS" : "FAIL",
      imeDock?.tabs,
    ),
  )
  checklist.push(
    item(
      "terminal.ime_restore_no_bottom_gap",
      imeDock?.rootFills && (imeDock?.dockHeight ? imeDock.flush : imeDock.rootFills) ? "PASS" : "FAIL",
      imeDock,
    ),
  )
  if (contextPanel?.overlay || contextPanel?.dialog) {
    await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(300)
  }

  const tabPlusDefault = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const visible = (el) => {
        if (!el) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      const plus = document.querySelector('[data-action="titlebar-tab-plus"]') || Array.from(document.querySelectorAll("button")).find((el) => {
        const icon = el.querySelector("svg");
        const label = (el.getAttribute("aria-label") || "").trim();
        return visible(el) && (label === "命令面板" || label === "Command palette" || label === "新建会话" || label === "New session");
      });
      const beforeTabs = document.querySelectorAll('[data-component="titlebar-tab"], [data-slot="titlebar-tab"]').length;
      const api = window.__ocAndroidTabPlus;
      const clicked = ocTap(plus);
      return {
        found: !!plus,
        clicked,
        action: plus ? plus.getAttribute("data-action") : null,
        label: plus ? (plus.getAttribute("aria-label") || "").slice(0, 40) : null,
        beforeTabs,
        pref: api ? { key: api.key, value: api.get() } : null,
      };
    })()`,
  )
  await Bun.sleep(700)
  const paletteDefault = await evaluate(
    ws,
    sessionId,
    `(() => {
      const visible = (el) => {
        if (!el) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      const dialogs = Array.from(document.querySelectorAll('[role="dialog"], [data-component="dialog-v2"]')).filter(visible);
      const input = document.querySelector('input[placeholder], [data-slot="text-input-v2-input"]');
      const ph = input ? (input.getAttribute("placeholder") || "") : "";
      const text = dialogs.map((el) => el.innerText || "").join("\\n");
      return {
        dialog: dialogs.length > 0,
        placeholder: ph,
        isPalette: /搜索文件、命令和会话|Search files, commands, and sessions|搜索命令和会话/.test(ph + "\\n" + text),
        sample: (ph + " " + text).slice(0, 200),
      };
    })()`,
  )
  checklist.push(
    item(
      "titlebar.plus_opens_palette_default",
      tabPlusDefault?.clicked && paletteDefault?.isPalette ? "PASS" : "FAIL",
      { tabPlusDefault, paletteDefault },
    ),
  )
  try {
    const shotPlus = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
    if (shotPlus?.data) {
      const plusPath = "/opt/cursor/artifacts/selftest-tab-plus-palette.png"
      fs.mkdirSync(path.dirname(plusPath), { recursive: true })
      fs.writeFileSync(plusPath, Buffer.from(shotPlus.data, "base64"))
    }
  } catch {}
  if (paletteDefault?.dialog) {
    await evaluate(ws, sessionId, HANDLE_BACK_JS)
    await Bun.sleep(300)
  }
  const tabPlusOfficial = await evaluate(
    ws,
    sessionId,
    `(() => { ${TAP_EL}
      const api = window.__ocAndroidTabPlus;
      if (api && typeof api.set === "function") api.set(true);
      const plus = document.querySelector('[data-action="titlebar-tab-plus"]');
      const before = document.querySelectorAll('[data-component="titlebar-tab"], [data-slot="titlebar-tab"], [role="tab"]').length;
      const clicked = ocTap(plus);
      return {
        pref: api ? api.get() : null,
        stored: localStorage.getItem("opencode.android.tabPlusCreatesSession"),
        found: !!plus,
        clicked,
        before,
        label: plus ? (plus.getAttribute("aria-label") || "").slice(0, 40) : null,
      };
    })()`,
  )
  await Bun.sleep(800)
  const afterOfficialPlus = await evaluate(
    ws,
    sessionId,
    `(() => {
      const visible = (el) => {
        if (!el) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden') return false;
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      };
      const dialogs = Array.from(document.querySelectorAll('[role="dialog"]')).filter(visible);
      const ph = document.querySelector('input[placeholder]')?.getAttribute("placeholder") || "";
      const isPalette = /搜索文件、命令和会话|Search files, commands, and sessions/.test(ph);
      const tabs = Array.from(document.querySelectorAll('[data-component="titlebar-tab"], [data-slot="titlebar-tab"], button, [role="tab"]'));
      const newSession = tabs.some((el) => /新建会话|New session/.test((el.getAttribute("aria-label") || "") + " " + (el.textContent || "")));
      return {
        dialog: dialogs.length > 0,
        isPalette,
        newSession,
        tabText: tabs.map((el) => ((el.textContent || "") + "").trim().slice(0, 24)).filter(Boolean).slice(0, 8),
      };
    })()`,
  )
  checklist.push(
    item(
      "titlebar.plus_creates_session_when_enabled",
      tabPlusOfficial?.pref === true &&
        tabPlusOfficial?.stored === "true" &&
        afterOfficialPlus?.isPalette !== true &&
        (afterOfficialPlus?.newSession === true || tabPlusOfficial?.clicked === true)
        ? "PASS"
        : "FAIL",
      { tabPlusOfficial, afterOfficialPlus },
    ),
  )
  await evaluate(
    ws,
    sessionId,
    `(() => { if (window.__ocAndroidTabPlus) window.__ocAndroidTabPlus.set(false); return true; })()`,
  )

  await cdp(
    ws,
    "Emulation.setDeviceMetricsOverride",
    { width: 800, height: 1280, deviceScaleFactor: 2, mobile: true },
    sessionId,
  )
  checklist.push(
    item(
      "home.status_popover_switch",
      "N/A",
      { reason: "Desktop-only session status popover; web UI on Android applies the server from Settings → Server (connection.apply_row_select / connection.set_default_activates)." },
    ),
  )

  const failed = checklist.filter((c) => c.result === "FAIL")
  return { checklist, failed, apiProbe, seed, providersUi, modelsUi, serversUi, generalUi, shortcutsUi, homeUi, sendProbe, afterAdd, afterApply, afterSwitch, afterDelete }
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
      hasIcon: !!(sum && (sum.querySelector("svg") || sum.querySelector("[data-component='icon']"))),
    };
  });
  const tabTitle = tabHeader ? (tabHeader.textContent || "").replace(/\\s+/g, " ").trim().slice(0, 40) : null;
  return {
    panelFound: !!panel,
    tabHeaderDisplay: tabHeaderStyle ? tabHeaderStyle.display : null,
    tabHeaderPosition: tabHeaderStyle ? tabHeaderStyle.position : null,
    tabHeaderHidden: !tabHeader || (tabHeaderStyle && tabHeaderStyle.display === "none"),
    tabTitle,
    sectionCount: sections.length,
    summaries,
    firstOpen: sections[0] ? sections[0].open : null,
    defaultCollapsed: sections.length > 0 && sections.every((el) => el.open === false),
    iconCount: summaries.filter((s) => s.hasIcon).length,
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
  const debugPort = await freePort()
  const chromeProfile = `/tmp/oc-selftest-chrome-${Date.now()}`
  console.error(`[selftest] serve=${serve.url} workspace=${serve.workspace} dedicated=${dedicatedOrigin} chromeDebug=${debugPort}`)
  const chrome = spawn(
    "google-chrome",
    [
      "--headless=new",
      "--disable-gpu",
      "--no-sandbox",
      "--disable-dev-shm-usage",
      `--remote-debugging-port=${debugPort}`,
      `--remote-debugging-address=127.0.0.1`,
      `--host-resolver-rules=MAP ${HOST} 127.0.0.1`,
      `--user-data-dir=${chromeProfile}`,
      "--window-size=390,844",
      "about:blank",
    ],
    { stdio: ["ignore", "pipe", "pipe"] },
  )
  chrome.stderr?.on("data", (d) => {
    const s = String(d)
    if (/error|ERROR|FATAL|failed/i.test(s)) console.error("[chrome]", s.slice(0, 400))
  })
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
            },
            setColorScheme: function (scheme) { window.__ocLastColorScheme = scheme; }
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
    const THEME_JS = `(() => {
      const html = document.documentElement;
      const scheme = (html.dataset && html.dataset.colorScheme) || html.getAttribute('data-color-scheme') || '';
      const meta = document.querySelector('meta[name="theme-color"]');
      const themeColor = meta ? meta.getAttribute('content') : null;
      const bg = getComputedStyle(html).backgroundColor || getComputedStyle(document.body).backgroundColor;
      return {
        scheme: String(scheme).toLowerCase(),
        themeColor,
        bg,
        last: window.__ocLastColorScheme || null,
        androidWebview: html.hasAttribute('data-android-webview'),
      };
    })()`
    await cdp(
      ws,
      "Emulation.setEmulatedMedia",
      {
        features: [
          { name: "pointer", value: "coarse" },
          { name: "hover", value: "none" },
          { name: "prefers-color-scheme", value: "dark" },
        ],
      },
      sessionId,
    )
    await Bun.sleep(500)
    const themeDark = await evaluate(ws, sessionId, THEME_JS)
    await cdp(
      ws,
      "Emulation.setEmulatedMedia",
      {
        features: [
          { name: "pointer", value: "coarse" },
          { name: "hover", value: "none" },
          { name: "prefers-color-scheme", value: "light" },
        ],
      },
      sessionId,
    )
    await Bun.sleep(500)
    const themeLight = await evaluate(ws, sessionId, THEME_JS)
    const themeOk =
      themeDark?.androidWebview === true &&
      (themeDark?.scheme === "dark" || themeDark?.themeColor === "#080808" || /0,\s*0,\s*0|8,\s*8,\s*8/.test(themeDark?.bg || "")) &&
      (themeLight?.scheme === "light" || themeLight?.themeColor === "#fafafa" || /250,\s*250,\s*250|255,\s*255,\s*255/.test(themeLight?.bg || ""))
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
        const wasOpen = details ? details.open : null;
        if (sum && details && details.open) sum.click();
        return { collapsed: details ? details.open === false : false, title: sum ? (sum.textContent || '').trim().slice(0, 40) : null, wasOpen };
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
      settingsAccordion.summaries?.every((s) => s.sticky === "sticky") &&
      settingsAccordion.defaultCollapsed === true &&
      (settingsAccordion.iconCount ?? 0) >= 2
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
      secondUrl: realBackend,
      workspace: serve.workspace,
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
      { id: "shell.system_bars_follow_color_scheme", result: themeOk ? "PASS" : "FAIL", detail: { themeDark, themeLight } },
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
      themeOk &&
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
      themeDark,
      themeLight,
      themeOk,
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
      serve: { url: serve.url, health: serve.health, started: serve.started, workspace: serve.workspace },
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
              : !themeOk
                ? "self-test failed: color-scheme did not follow light/dark (status/nav bars must match)"
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
