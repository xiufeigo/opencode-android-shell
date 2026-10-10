const IPC = "browser.pane"
const PROTOCOL = "opencode-browser-v1"
const PATH = "/api/android-browser"
const TIMEOUT = 120_000

export async function browserTicket(server, fetcher = fetch, signal) {
  const headers = { "content-type": "application/json" }
  if (server.password) {
    const bytes = new TextEncoder().encode(`${server.username ?? "opencode"}:${server.password}`)
    headers.authorization = `Basic ${btoa(String.fromCharCode(...bytes))}`
  }
  const base = new URL(server.url)
  const timeout = AbortSignal.timeout(15_000)
  const options = { headers, redirect: "error", signal: signal ? AbortSignal.any([signal, timeout]) : timeout }
  const capabilities = await fetcher(new URL(`${PATH}/capabilities`, base), options)
  if (capabilities.status === 404) throw new Error("此 OpenCode 服务器未启用内置浏览器。请启动配套统一浏览器服务，并将服务器地址改为它的地址。 / This server has no built-in browser gateway.")
  if (!capabilities.ok) throw new Error(`Browser discovery failed: HTTP ${capabilities.status}`)
  const info = await capabilities.json()
  if (info.service !== "opencode-android-browser" || info.version !== 1 || info.protocol !== PROTOCOL) throw new Error("Incompatible OpenCode browser gateway")
  const response = await fetcher(new URL(`${PATH}/ticket`, base), { ...options, method: "POST", body: JSON.stringify({ server: server.id }) })
  if (!response.ok) throw new Error(`Browser authentication failed: HTTP ${response.status}`)
  const value = await response.json()
  if (value.protocol !== PROTOCOL || value.path !== `${PATH}/bridge` || !/^[A-Za-z0-9_-]{32,256}$/.test(value.ticket)) throw new Error("Invalid browser connection ticket")
  const url = new URL(value.path, base)
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:"
  return { url: url.href, token: value.ticket }
}

/** Each server owns its transport. Bindings and surfaces retain their server when tabs change. */
export function createBrowserTransport({ native, publish, surface, keyboard, status, WebSocketClass = WebSocket, fetcher = fetch }) {
  let servers = []
  let pairing
  let stopped = false
  let sequence = 0
  const connections = new Map()
  const bindings = new Map()
  const embeds = new Map()
  let legacy = native("browserConfig").then(value => { pairing = value })
  const fail = (connection, message) => {
    connection.pending.forEach(call => { call.cleanup(); call.reject(new Error(message)) })
    connection.pending.clear()
    for (const [id, owner] of embeds) if (owner === connection) surface(id, message)
    for (const [binding, owner] of bindings) if (owner === connection)
      publish({ type: "event", ipc: IPC, name: "event", data: { binding, event: { type: "state", state: null, error: "browser.pane.registration.closed" } } })
    status(message)
  }
  const close = connection => {
    connection.closed = true
    connection.controller?.abort(new Error("Browser connection closed"))
    const socket = connection.socket
    if (socket) { socket.onclose = socket.onerror = socket.onmessage = null; socket.close() }
    fail(connection, "Browser connection closed")
    for (const [id, owner] of embeds) if (owner === connection) embeds.delete(id)
    for (const [id, owner] of bindings) if (owner === connection) bindings.delete(id)
  }
  function request(connection, method, input, signal) {
    const socket = connection.socket
    if (socket?.readyState !== 1) return Promise.reject(new Error("Browser service is disconnected"))
    if (signal?.aborted) return Promise.reject(new Error("Browser request aborted"))
    const id = String(++sequence)
    return new Promise((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); signal?.removeEventListener("abort", abort) }
      const cancel = error => {
        connection.pending.delete(id); cleanup()
        if (socket.readyState === 1) socket.send(JSON.stringify({ id: `cancel-${id}`, method: "cancel", input: id }))
        reject(error)
      }
      const abort = () => cancel(new Error("Browser request aborted"))
      const timer = setTimeout(() => cancel(new Error(`Browser ${method} timed out; cancellation requested`)), TIMEOUT)
      connection.pending.set(id, { resolve, reject, cleanup })
      signal?.addEventListener("abort", abort, { once: true })
      try { socket.send(JSON.stringify({ id, method, input })) }
      catch (error) { connection.pending.delete(id); cleanup(); reject(error) }
    })
  }
  async function connect(connection) {
    const controller = new AbortController()
    connection.controller = controller
    const config = pairing ? { url: new URL("bridge", pairing.url.replace(/\/?$/, "/")).href.replace(/^http/, "ws"), token: pairing.token } : await browserTicket(connection.server, fetcher, controller.signal)
    if (stopped || connection.closed) throw new Error("Browser connection closed")
    const socket = new WebSocketClass(config.url, [PROTOCOL, config.token])
    socket.binaryType = "arraybuffer"
    connection.socket = socket
    await new Promise((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); controller.signal.removeEventListener("abort", abort) }
      const abort = () => { cleanup(); reject(controller.signal.reason) }
      const timer = setTimeout(() => { cleanup(); socket.close(); reject(new Error("Browser connection timed out")) }, 15_000)
      controller.signal.addEventListener("abort", abort, { once: true })
      socket.onerror = () => { cleanup(); reject(new Error("Cannot connect to OpenCode browser gateway")) }
      socket.onclose = () => { cleanup(); fail(connection, "Browser service disconnected"); reject(new Error("Browser service disconnected")) }
      socket.onmessage = event => {
        try {
          if (event.data instanceof ArrayBuffer) {
            if (event.data.byteLength < 2) throw new Error("Invalid browser frame")
            const bytes = new Uint8Array(event.data)
            const size = new DataView(event.data).getUint16(0)
            if (bytes.length < size + 2) throw new Error("Incomplete browser frame")
            const header = JSON.parse(new TextDecoder().decode(bytes.subarray(2, 2 + size)))
            if (embeds.get(header.id) === connection) surface(header.id, bytes.subarray(2 + size))
            return
          }
          const message = JSON.parse(event.data)
          const call = connection.pending.get(message.id)
          if (call) {
            connection.pending.delete(message.id); call.cleanup()
            if (message.error) call.reject(new Error(message.error))
            else call.resolve(message.value)
            return
          }
          if (message.type === "available") { if (message.available) { cleanup(); resolve() } return }
          if (message.type === "surfaceError") { surface(message.id, message.error); return }
          if (message.type === "keyboard") { keyboard(message.id); return }
          if (message.type === "event" && message.data?.event?.type === "embed") embeds.set(message.data.event.embed, connection)
          publish(message)
        } catch (error) { cleanup(); socket.close(1002, "Invalid browser message"); fail(connection, error.message); reject(error) }
      }
    })
    if (stopped || connection.closed || socket.readyState !== 1) throw new Error("Browser connection closed")
    // A gateway ticket only authorizes this server. No other server credentials are sent to it.
    await request(connection, "configure", [connection.server])
    status("Browser service connected")
  }
  async function ready(serverID) {
    await legacy
    if (stopped) throw new Error("Browser page closed")
    const server = servers.find(value => value.id === serverID)
    if (!server) throw new Error("OpenCode server endpoint is not configured")
    let connection = connections.get(serverID)
    if (!connection) {
      connection = { server, pending: new Map(), closed: false }
      connections.set(serverID, connection)
    }
    if (connection.socket?.readyState === 1 && !connection.opening) return connection
    if (!connection.opening) {
      status("Connecting to browser service…")
      connection.opening = connect(connection).catch(error => { status(error.message); throw error }).finally(() => { connection.opening = undefined })
    }
    await connection.opening
    return connection
  }
  return {
    async connect(serverID) { await ready(serverID) },
    async call(input, signal) {
      if (input.ipc !== IPC) throw new Error("Unknown browser IPC")
      if (input.method === "register") {
        const connection = await ready(input.input.server)
        bindings.set(input.input.binding, connection)
        try { return await request(connection, "call", input, signal) }
        catch (error) { bindings.delete(input.input.binding); throw error }
      }
      const connection = bindings.get(input.input.binding)
      if (input.method === "close") {
        bindings.delete(input.input.binding)
        if (!connection || connection.socket?.readyState !== 1) return
      }
      if (!connection) throw new Error("Browser session is not registered")
      return request(connection, "call", input, signal)
    },
    configure(values) {
      servers = values
      for (const [id, connection] of connections) {
        const next = values.find(value => value.id === id)
        if (JSON.stringify(next) === JSON.stringify(connection.server)) continue
        connections.delete(id); close(connection)
      }
    },
    async configureLegacy() {
      const config = await native("configureBrowser")
      if (config === false) return false
      pairing = config
      legacy = Promise.resolve()
      for (const connection of connections.values()) close(connection)
      connections.clear()
      status(config ? "Legacy browser connection configured" : "Browser uses the selected OpenCode server")
      return true
    },
    surface(id, method, input) {
      const connection = embeds.get(id)
      if (!connection && method === "embed" && !input.layout) return Promise.resolve()
      if (!connection) return Promise.reject(new Error("Unknown browser surface"))
      return request(connection, method, input)
    },
    stop() { stopped = true; for (const connection of connections.values()) close(connection); connections.clear() },
    resume() { stopped = false },
  }
}
