import http from "node:http"
import fs from "node:fs/promises"
import path from "node:path"
import { parseArgs } from "node:util"

const { values } = parseArgs({ options: { host: { type: "string", default: "127.0.0.1" }, port: { type: "string", default: "4175" } } })
const root = path.resolve(import.meta.dirname, "dist")
await fs.access(path.join(root, "index.html"))
const mime = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png", ".ico": "image/x-icon", ".woff": "font/woff", ".woff2": "font/woff2", ".wasm": "application/wasm", ".webmanifest": "application/manifest+json" }
const server = http.createServer(async (request, response) => {
  if (!["GET", "HEAD"].includes(request.method)) { response.writeHead(405).end(); return }
  try {
    const name = decodeURIComponent(new URL(request.url, "http://localhost").pathname)
    if (name === "/api" || name.startsWith("/api/") || name.includes("\\") || name.includes("\0")) { response.writeHead(404).end(); return }
    const target = path.resolve(root, `.${name}`)
    if (target !== root && !target.startsWith(root + path.sep)) { response.writeHead(400).end(); return }
    let filename = target
    try { if ((await fs.stat(filename)).isDirectory()) filename = path.join(filename, "index.html") }
    catch (error) {
      if (error.code !== "ENOENT") throw error
      if (path.extname(target)) { response.writeHead(404).end(); return }
      filename = path.join(root, "index.html")
    }
    const body = await fs.readFile(filename)
    response.writeHead(200, { "content-type": mime[path.extname(filename)] ?? "application/octet-stream", "x-content-type-options": "nosniff", "cache-control": "no-cache" })
    response.end(request.method === "HEAD" ? undefined : body)
  } catch (error) {
    console.error("[web-ui] request failed", error)
    response.writeHead(500).end("Static UI request failed")
  }
})
server.listen(Number(values.port), values.host, () => console.log(`Static Web UI: http://${values.host}:${values.port}; API connects directly to your OpenCode server.`))
