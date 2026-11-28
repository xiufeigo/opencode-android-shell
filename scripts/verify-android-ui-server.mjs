#!/usr/bin/env node
// Shared packaged-UI origin server for Android verification runners. It mirrors
// LocalUiOriginServer's logical /_assets URL to packaged ui/oa disk mapping.

import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { listen } from "./verify-android-server-lifecycle.mjs"

const REPO = fileURLToPath(new URL("../", import.meta.url))
const WWW = path.join(REPO, "webapp/src/main/assets/www")
const UI_ROOT = path.join(WWW, "ui")
const BUNDLE_DIR = path.join(UI_ROOT, "oa")
const TERMINAL_MODULE = path.join(WWW, "oc-terminal.js")

function resolveTerminalModule(env = process.env) {
  const override = env.VERIFY_TERMINAL_MODULE || ""
  return {
    terminalModule: override ? path.resolve(override) : TERMINAL_MODULE,
    terminalModuleOverride: override || null,
  }
}

const DEFAULT_TERMINAL_MODULE = resolveTerminalModule()

function contentTypeOf(p) {
  if (p.endsWith(".html")) return "text/html; charset=utf-8"
  if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8"
  if (p.endsWith(".css")) return "text/css; charset=utf-8"
  if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json; charset=utf-8"
  if (p.endsWith(".svg")) return "image/svg+xml"
  if (p.endsWith(".png")) return "image/png"
  if (p.endsWith(".ico")) return "image/x-icon"
  if (p.endsWith(".webp")) return "image/webp"
  if (p.endsWith(".woff2")) return "font/woff2"
  if (p.endsWith(".woff")) return "font/woff"
  if (p.endsWith(".ttf")) return "font/ttf"
  if (p.endsWith(".wasm")) return "application/wasm"
  if (p.endsWith(".txt")) return "text/plain; charset=utf-8"
  return "application/octet-stream"
}

function normalize(p) {
  if (!p.startsWith("/")) p = `/${p}`
  const parts = []
  for (const seg of p.split("/")) {
    if (seg === "" || seg === ".") continue
    if (seg === "..") parts.pop()
    else parts.push(seg)
  }
  return `/${parts.join("/")}`
}

/**
 * The UI origin, mirroring `LocalUiOriginServer` plus the lead's module
 * replacement routes. SPA fallback to index.html, matching the real server.
 */
function startUiServer({ terminalModule = DEFAULT_TERMINAL_MODULE.terminalModule, record = () => {} } = {}) {
  const server = http.createServer((req, res) => {
    let pathname = "/"
    try {
      pathname = decodeURIComponent(new URL(req.url || "/", "http://127.0.0.1").pathname)
    } catch {}

    const serve = (file, note) => {
      try {
        const body = fs.readFileSync(file)
        record({ path: pathname, status: 200, bytes: body.length, via: note })
        res.writeHead(200, {
          "content-type": contentTypeOf(file),
          "content-length": body.length,
          "cache-control": "no-cache",
        })
        res.end(req.method === "HEAD" ? undefined : body)
        return true
      } catch {
        return false
      }
    }

    // 1. Terminal replacement slot. Matched before the /_assets/ bundle mapping
    //    because this is a real chunk name that also exists on disk.
    if (pathname === "/oc-terminal.js") {
      if (serve(terminalModule, "terminal:oc-terminal.mjs")) return
      record({ path: pathname, status: 404, via: "terminal-module-missing" })
      res.writeHead(404, { "content-type": "text/plain; charset=utf-8" })
      res.end(`replacement terminal module missing at ${terminalModule}`)
      return
    }

    // 2. AGP drops `_`-prefixed dirs, so /_assets/x lives on disk at oa/x.
    const disk = pathname.startsWith("/_assets/")
      ? path.join(BUNDLE_DIR, pathname.slice("/_assets/".length))
      : path.join(UI_ROOT, normalize(pathname))

    if (disk.startsWith(UI_ROOT) && serve(disk, pathname.startsWith("/_assets/") ? "ui:oa/*" : "ui:*")) return

    // 3. SPA fallback.
    const index = path.join(UI_ROOT, "index.html")
    if (serve(index, "ui:index.html(spa)")) return

    record({ path: pathname, status: 404, via: "ui:miss" })
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" })
    res.end("not found")
  })
  return listen(server)
}

export { startUiServer, resolveTerminalModule }
