import { app, BrowserWindow } from "electron"
import { WebSocketServer } from "ws"
import { createServer } from "node:http"
import fs from "node:fs"
import path from "node:path"
import { randomBytes, createHash, randomUUID } from "node:crypto"
import { Schema } from "effect"
import { createBrowserPane } from "../packages/gui-extensions/src/browser/pane.ts"
import { BrowserPane } from "../packages/gui-extensions/src/browser/ipc.ts"
import { authenticate, endpoint, frame, PROTOCOL } from "./transport.mjs"
import { createGateway } from "./gateway.mjs"
import { dispatchInput } from "./input.mjs"

async function main() {
app.setName("OpenCode Android Browser")
const data = process.env.OPENCODE_BROWSER_DATA ?? app.getPath("userData")
fs.mkdirSync(data, { recursive: true })
// Tabs, Chromium cookies and caches must share the configured instance path.
// Otherwise parallel gateways collide in Electron's default profile.
app.setPath("userData", path.resolve(data))
await app.whenReady()
const tokenFile = path.join(data, "pairing-token")
const gateway = process.env.OPENCODE_BROWSER_UPSTREAM ? createGateway(process.env.OPENCODE_BROWSER_UPSTREAM) : undefined
let token
if (!gateway) {
  if (!fs.existsSync(tokenFile)) fs.writeFileSync(tokenFile, randomBytes(32).toString("base64url"), { mode: 0o600 })
  token = process.env.OPENCODE_BROWSER_TOKEN ?? fs.readFileSync(tokenFile, "utf8").trim()
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw new Error("Pairing token must be 32–256 base64url characters")
}
const storage = {
  store(key, { schema, initial }) {
    const filename = path.join(data, `${createHash("sha256").update(key).digest("hex")}.json`)
    let value = fs.existsSync(filename) ? Schema.decodeUnknownSync(schema)(JSON.parse(fs.readFileSync(filename, "utf8"))) : initial
    const set = next => {
      const encoded = Schema.encodeUnknownSync(schema)(next)
      fs.writeFileSync(`${filename}.tmp`, JSON.stringify(encoded), { mode: 0o600 })
      fs.renameSync(`${filename}.tmp`, filename)
      value = next
    }
    return { get value() { return value }, ready: () => true, set, update(fn) { const draft = structuredClone(value); fn(draft); set(draft) } }
  },
  remove(key) { fs.rmSync(path.join(data, `${createHash("sha256").update(key).digest("hex")}.json`), { force: true }) },
}
const refs = storage.store("refs", { schema: Schema.Int, initial: 0 })
const clients = new Set()
const server = createServer((request, response) => {
  if (gateway) { gateway.handle(request, response); return }
  response.writeHead(200, { "content-type": "application/json" })
  response.end(JSON.stringify({ service: "opencode-android-browser", version: "2.0.26", protocol: PROTOCOL }))
})
const sockets = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 * 1024, handleProtocols: () => PROTOCOL })
server.on("upgrade", (request, socket, head) => {
  if (gateway) {
    gateway.upgrade(request, socket, head, endpoint => sockets.handleUpgrade(request, socket, head, ws => sockets.emit("connection", ws, endpoint)))
    return
  }
  if (request.url !== "/bridge" || !authenticate(request.headers["sec-websocket-protocol"], token)) {
    socket.end("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n")
    return
  }
  sockets.handleUpgrade(request, socket, head, ws => sockets.emit("connection", ws))
})
sockets.on("connection", async (ws, authorizedEndpoint) => {
  // This carrier never has a native display surface; render explicitly offscreen.
  const win = new BrowserWindow({ show: false, width: 1000, height: 700, webPreferences: { offscreen: true, sandbox: true, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false } })
  const closeEarly = () => { if (!win.isDestroyed()) win.destroy() }
  ws.once("close", closeEarly)
  ws.on("error", error => { console.error("Browser WebSocket:", error); ws.close(1002, "Transport error") })
  try { await win.loadURL("data:text/html,<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'\"><title>OpenCode Browser Host</title>") }
  catch (error) { console.error("Browser host initialization:", error); closeEarly(); ws.close(1011, "Initialization failed"); return }
  if (ws.readyState !== 1) { closeEarly(); return }
  const views = new Map()
  const servers = new Map()
  const send = value => { if (ws.readyState === 1) ws.send(JSON.stringify(value)) }
  const embeds = {
    create(view, owner) {
      const id = randomUUID()
      const listeners = new Set()
      const record = { view, visible: false, show: false, layout: false, capturing: false }
      views.set(id, record)
      owner.contentView.addChildView(view)
      const update = () => {
        const visible = record.show && record.layout
        if (record.visible === visible) return
        record.visible = visible
        view.setVisible(visible)
        listeners.forEach(listener => listener(visible))
      }
      record.update = update
      const timer = setInterval(async () => {
        if (!record.visible || record.capturing || ws.bufferedAmount > 2 * 1024 * 1024 || view.webContents.isDestroyed()) return
        record.capturing = true
        try {
          const image = await view.webContents.capturePage(undefined, { stayHidden: false, stayAwake: true })
          if (ws.readyState === 1 && !image.isEmpty()) ws.send(frame(id, image.toJPEG(75)))
        } catch (error) { send({ type: "surfaceError", id, error: error.message }) }
        finally { record.capturing = false }
      }, 100)
      return { id, show(visible) { record.show = visible; update() }, on(_, listener) { listeners.add(listener); return () => listeners.delete(listener) },
        capture: () => record.visible ? view.webContents.capturePage(undefined, { stayHidden: false, stayAwake: true }) : Promise.resolve(undefined),
        dispose() { clearInterval(timer); views.delete(id); owner.contentView.removeChildView(view) } }
    },
  }
  const pane = createBrowserPane({ windows: { get: id => id === win.id ? win : undefined }, serverEndpoints: { get: id => servers.get(id) }, storage, refs, embeds,
    offscreen: true,
    visible: () => [...views.values()].some(record => record.visible),
    emit: (_, value) => send({ type: "event", ipc: BrowserPane.id, name: "event", data: Schema.encodeUnknownSync(BrowserPane.spec.events.event)(value) }) })
  const cleanup = async () => { await pane.dispose(); if (!win.isDestroyed()) win.destroy() }
  clients.add(cleanup)
  ws.once("close", () => { clients.delete(cleanup); void cleanup().catch(error => console.error("Browser cleanup:", error)) })
  ws.off("close", closeEarly)
  const methods = {
    register: input => pane.register(win.id, input.binding, input),
    load: input => pane.load(win.id, input.binding, input.tabID),
    command: (input, signal) => pane.command(win.id, input.binding, input.command, signal),
    inspect: input => pane.inspect(win.id, input.binding, input.tabID, input.enabled),
    highlight: input => pane.highlight(win.id, input.binding, input.tabID, input.ref),
    zoom: input => pane.zoom(win.id, input.binding, input.tabID, input.zoom),
    site: input => pane.site(win.id, input.binding, input.tabID),
    clearSite: input => pane.clearSite(win.id, input.binding, input.tabID),
    close: input => pane.close(win.id, input.binding),
  }
  let queue = Promise.resolve()
  const active = new Map()
  ws.once("close", () => active.forEach(controller => controller.abort()))
  ws.on("message", bytes => {
    let request
    try {
      request = JSON.parse(bytes.toString())
      if (typeof request.id !== "string" || request.id.length > 128) throw new Error("Invalid request id")
      if (request.method === "cancel") { active.get(request.input)?.abort(); send({ id: request.id, value: null }); return }
      if (active.has(request.id)) throw new Error("Duplicate request id")
    } catch (error) { ws.close(1008, "Invalid request"); return }
    const controller = new AbortController()
    active.set(request.id, controller)
    const execute = async () => {
    try {
      controller.signal.throwIfAborted()
      const input = request.input
      let value
      switch (request.method) {
        case "configure": {
          if (!Array.isArray(input) || input.length > 128) throw new Error("Invalid endpoint list")
          if (authorizedEndpoint) {
            if (input.length !== 1 || input[0].id !== authorizedEndpoint.id) throw new Error("Browser ticket belongs to a different OpenCode server")
            servers.clear(); servers.set(authorizedEndpoint.id, authorizedEndpoint)
            break
          }
          const next = input.map(endpoint)
          servers.clear(); next.forEach(value => servers.set(value.id, value))
          break
        }
        case "subscribe": value = { available: input === BrowserPane.id }; break
        case "call": {
          if (input.ipc !== BrowserPane.id || !Object.hasOwn(methods, input.method)) throw new Error("Unknown browser IPC method")
          const spec = BrowserPane.spec.methods[input.method]
          const result = await methods[input.method](Schema.decodeUnknownSync(spec.input)(input.input), controller.signal)
          value = spec.output ? Schema.encodeUnknownSync(spec.output)(result) : undefined
          break
        }
        case "embed": {
          const record = views.get(input.id)
          if (!record) {
            // Hiding an already disposed tab is an idempotent release.
            if (input.layout !== undefined) throw new Error("Unknown browser surface")
            break
          }
          record.layout = !!input.layout?.visible
          if (input.layout?.bounds) {
            const { width, height } = input.layout.bounds
            if (![width, height].every(value => Number.isFinite(value) && value >= 1 && value <= 4096)) throw new Error("Invalid browser viewport")
            record.view.setBounds({ x: 0, y: 0, width: Math.round(width), height: Math.round(height) })
            win.setContentSize(Math.round(width), Math.round(height))
          }
          record.update()
          break
        }
        case "input": {
          const record = views.get(input.id)
          if (!record?.visible) throw new Error("Browser surface is not visible")
          await dispatchInput(record.view.webContents, input.event, () => send({ type: "keyboard", id: input.id }))
          break
        }
        case "capture": {
          const record = views.get(input)
          if (!record?.visible) break
          const image = await record.view.webContents.capturePage(undefined, { stayHidden: false, stayAwake: true })
          value = image.toJPEG(85).toString("base64")
          break
        }
        default: throw new Error(`Unknown companion method: ${request.method}`)
      }
      controller.signal.throwIfAborted()
      send({ id: request.id, value: value ?? null })
    } catch (error) {
      if (request?.id) send({ id: request.id, error: error.message })
      else ws.close(1008, "Invalid request")
    } finally { active.delete(request.id) }
    }
    queue = queue.then(execute)
  })
  send({ type: "available", ipc: BrowserPane.id, available: true })
})
const port = Number(process.env.OPENCODE_BROWSER_PORT ?? 4100)
server.listen(port, process.env.OPENCODE_BROWSER_HOST ?? "127.0.0.1", () => {
  console.log(gateway ? `OpenCode unified browser gateway listening on port ${port}` : `OpenCode browser companion listening on port ${port}; pairing token file: ${tokenFile}`)
})
server.on("error", error => { console.error(error); app.exit(1) })
app.on("window-all-closed", () => {})
app.on("before-quit", () => { server.close(); sockets.close(); clients.forEach(cleanup => void cleanup()) })
}
main().catch(error => { console.error(error); app.exit(1) })
