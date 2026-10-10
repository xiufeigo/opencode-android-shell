import http from "node:http"
import https from "node:https"
import { randomBytes } from "node:crypto"
import { PROTOCOL } from "./transport.mjs"

export const BROWSER_PATH = "/api/android-browser"
const TICKET_TTL = 30_000
const MAX_TICKETS = 256
const MAX_BODY = 32_768
const HOP_HEADERS = ["connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"]

function headers(input) {
  const result = { ...input }
  const connection = String(result.connection ?? "").split(",").map(value => value.trim().toLowerCase())
  for (const name of [...HOP_HEADERS, ...connection, "host"]) delete result[name]
  return result
}

function json(response, status, value) {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" })
  response.end(JSON.stringify(value))
}

async function body(request) {
  const chunks = []
  let length = 0
  for await (const chunk of request) {
    length += chunk.length
    if (length > MAX_BODY) throw Object.assign(new Error("Browser ticket request exceeds 32 KiB"), { status: 413 })
    chunks.push(chunk)
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")) }
  catch { throw Object.assign(new Error("Invalid browser ticket JSON"), { status: 400 }) }
}

/** One public OpenCode address. Browser tickets use the upstream's existing authentication. */
export function createGateway(upstream, { fetchInfo = fetch, now = Date.now } = {}) {
  const base = new URL(upstream)
  if (!["http:", "https:"].includes(base.protocol) || base.username || base.password || base.search || base.hash || base.pathname !== "/")
    throw new Error("OPENCODE_BROWSER_UPSTREAM must be an HTTP(S) OpenCode origin without credentials or a path")
  const transport = base.protocol === "https:" ? https : http
  const tickets = new Map()
  const target = request => {
    if (!request.url?.startsWith("/") || request.url.startsWith("//")) throw new Error("Invalid gateway request target")
    const value = new URL(request.url, base)
    if (value.origin !== base.origin) throw new Error("Invalid gateway request origin")
    return value
  }
  const authorize = async request => {
    const authorization = request.headers.authorization
    const auth = authorization ? { authorization } : {}
    const result = await fetchInfo(new URL("/api/info", base), { headers: auth, redirect: "manual", signal: AbortSignal.timeout(10_000) })
    if (result.status !== 200) return { status: result.status === 401 || result.status === 403 ? result.status : 502 }
    const info = await result.json()
    if (typeof info.version !== "string" && typeof info.data?.version !== "string") throw new Error("Upstream is not an OpenCode v2 server")
    return { status: 200, headers: auth }
  }
  const cors = (request, response) => {
    response.setHeader("access-control-allow-origin", request.headers.origin ?? "*")
    response.setHeader("vary", "Origin")
    response.setHeader("access-control-allow-methods", "GET, POST, OPTIONS")
    response.setHeader("access-control-allow-headers", "Authorization, Content-Type")
  }
  async function browser(request, response, pathname) {
    cors(request, response)
    if (request.method === "OPTIONS") { response.writeHead(204); response.end(); return }
    const auth = await authorize(request)
    if (auth.status !== 200) return json(response, auth.status, { error: "OpenCode authentication failed or upstream unavailable" })
    if (pathname === `${BROWSER_PATH}/capabilities` && request.method === "GET")
      return json(response, 200, { service: "opencode-android-browser", version: 1, protocol: PROTOCOL })
    if (pathname !== `${BROWSER_PATH}/ticket` || request.method !== "POST") return json(response, 404, { error: "Unknown browser route" })
    const input = await body(request)
    if (typeof input.server !== "string" || !input.server || input.server.length > 16_384) return json(response, 400, { error: "Invalid server id" })
    for (const [id, value] of tickets) if (value.expires <= now()) tickets.delete(id)
    if (tickets.size >= MAX_TICKETS) return json(response, 429, { error: "Too many unused browser tickets" })
    const ticket = randomBytes(32).toString("base64url")
    tickets.set(ticket, { expires: now() + TICKET_TTL, endpoint: { id: input.server, url: base.origin, headers: auth.headers, local: false } })
    json(response, 200, { ticket, expiresIn: TICKET_TTL, path: `${BROWSER_PATH}/bridge`, protocol: PROTOCOL })
  }
  function proxy(request, response) {
    const outgoing = transport.request(target(request), { method: request.method, headers: headers(request.headers) }, incoming => {
      response.writeHead(incoming.statusCode, headers(incoming.headers))
      incoming.on("error", error => response.destroy(error))
      incoming.pipe(response)
    })
    outgoing.on("error", () => {
      if (response.headersSent) { response.destroy(); return }
      json(response, 502, { error: "OpenCode upstream connection failed" })
    })
    request.on("aborted", () => outgoing.destroy())
    response.on("close", () => { if (!response.writableEnded) outgoing.destroy() })
    request.pipe(outgoing)
  }
  function relay(request, socket, head) {
    const outgoing = transport.request(target(request), { method: "GET", headers: { ...headers(request.headers), connection: "Upgrade", upgrade: "websocket" } })
    outgoing.on("upgrade", (response, peer, peerHead) => {
      socket.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n`)
      for (let index = 0; index < response.rawHeaders.length; index += 2) socket.write(`${response.rawHeaders[index]}: ${response.rawHeaders[index + 1]}\r\n`)
      socket.write("\r\n")
      if (peerHead.length) socket.write(peerHead)
      if (head.length) peer.write(head)
      socket.on("error", () => peer.destroy())
      peer.on("error", () => socket.destroy())
      socket.on("close", () => peer.destroy())
      peer.on("close", () => socket.destroy())
      socket.pipe(peer).pipe(socket)
    })
    outgoing.on("response", response => { socket.end(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\nConnection: close\r\n\r\n`); response.resume() })
    outgoing.on("error", () => socket.end("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"))
    socket.on("close", () => outgoing.destroy())
    socket.on("error", () => outgoing.destroy())
    outgoing.end()
  }
  return {
    handle(request, response) {
      let url
      try { url = target(request) } catch (error) { json(response, 400, { error: error.message }); return }
      if (url.pathname.startsWith(`${BROWSER_PATH}/`)) {
        void browser(request, response, url.pathname).catch(error => {
          console.error("Browser gateway:", error.message)
          if (!response.headersSent) json(response, error.status ?? 502, { error: error.message })
          else response.destroy(error)
        })
        return
      }
      proxy(request, response)
    },
    upgrade(request, socket, head, accept) {
      let url
      try { url = target(request) } catch { socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"); return }
      if (url.pathname !== `${BROWSER_PATH}/bridge`) { relay(request, socket, head); return }
      const protocols = String(request.headers["sec-websocket-protocol"] ?? "").split(",").map(value => value.trim())
      const ticket = protocols[0] === PROTOCOL && protocols.length === 2 ? tickets.get(protocols[1]) : undefined
      if (!ticket || ticket.expires <= now()) { socket.end("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n"); return }
      tickets.delete(protocols[1])
      accept(ticket.endpoint)
    },
  }
}
