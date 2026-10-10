import test from "node:test"
import assert from "node:assert/strict"
import { browserTicket, createBrowserTransport } from "./browser-transport.mjs"

test("discovery uses the chosen OpenCode origin and auth, never a second configured address", async () => {
  const seen = []
  const fetcher = async (url, options) => {
    seen.push({ url: url.href, options })
    return Response.json(seen.length === 1 ? { service: "opencode-android-browser", version: 1, protocol: "opencode-browser-v1" } : { ticket: "t".repeat(43), path: "/api/android-browser/bridge", protocol: "opencode-browser-v1" })
  }
  const value = await browserTicket({ id: "one", url: "https://server.example", password: "中文测试" }, fetcher)
  assert.equal(value.url, "wss://server.example/api/android-browser/bridge")
  assert.equal(seen[0].url, "https://server.example/api/android-browser/capabilities")
  assert.equal(Buffer.from(seen[0].options.headers.authorization.slice(6), "base64").toString(), "opencode:中文测试")
  assert.deepEqual(JSON.parse(seen[1].options.body), { server: "one" })
  await assert.rejects(browserTicket({ id: "one", url: "http://server.example" }, async () => new Response("missing", { status: 404 })), /no built-in browser gateway/)
})

test("simultaneous registrations share each server connection and route later calls to their owner", async () => {
  const sockets = []
  const calls = []
  const nativeCalls = []
  class Socket {
    readyState = 1
    constructor(url) { this.url = url; sockets.push(this); queueMicrotask(() => this.onmessage({ data: JSON.stringify({ type: "available", available: true }) })) }
    send(value) {
      const request = JSON.parse(value)
      calls.push({ url: this.url, ...request })
      queueMicrotask(() => this.onmessage({ data: JSON.stringify({ id: request.id, value: null }) }))
    }
    close() { this.readyState = 3 }
  }
  const fetcher = async url => Response.json(url.pathname.endsWith("capabilities") ? { service: "opencode-android-browser", version: 1, protocol: "opencode-browser-v1" } : { ticket: "t".repeat(43), path: "/api/android-browser/bridge", protocol: "opencode-browser-v1" })
  const transport = createBrowserTransport({ native: async method => { nativeCalls.push(method); return null }, publish: () => {}, surface: () => {}, keyboard: () => {}, status: () => {}, WebSocketClass: Socket, fetcher })
  transport.configure([{ id: "a", url: "https://a.example", password: "secret-a" }, { id: "b", url: "https://b.example", password: "secret-b" }])
  const register = (server, binding) => transport.call({ ipc: "browser.pane", method: "register", input: { server, binding, session: "ses-test" } })
  await Promise.all([register("a", "a1"), register("a", "a2"), register("b", "b1")])
  assert.equal(sockets.length, 2)
  assert.deepEqual(nativeCalls, ["browserConfig"])
  for (const call of calls.filter(call => call.method === "configure")) {
    assert.equal(call.input.length, 1)
    assert.equal(new URL(call.url).host, `${call.input[0].id}.example`)
  }
  await transport.call({ ipc: "browser.pane", method: "command", input: { binding: "a1", command: { type: "tabs.open" } } })
  assert.equal(calls.at(-1).url, "wss://a.example/api/android-browser/bridge")
  transport.configure([{ id: "b", url: "https://b.example", password: "secret-b" }])
  await assert.rejects(transport.call({ ipc: "browser.pane", method: "command", input: { binding: "a1" } }), /not registered/)
  assert.equal(sockets[1].readyState, 1)
  transport.stop()
})

test("removing a server or hiding the page aborts a stalled browser handshake immediately", async () => {
  for (const action of ["remove", "stop"]) {
    let socket
    let created
    const started = new Promise(resolve => { created = resolve })
    class Socket {
      readyState = 0
      constructor() { socket = this; created() }
      close() { this.readyState = 3 }
    }
    const fetcher = async url => Response.json(url.pathname.endsWith("capabilities")
      ? { service: "opencode-android-browser", version: 1, protocol: "opencode-browser-v1" }
      : { ticket: "t".repeat(43), path: "/api/android-browser/bridge", protocol: "opencode-browser-v1" })
    const transport = createBrowserTransport({ native: async () => null, publish: () => {}, surface: () => {}, keyboard: () => {}, status: () => {}, WebSocketClass: Socket, fetcher })
    transport.configure([{ id: "test", url: "https://test.example" }])
    const opening = transport.connect("test")
    const rejected = assert.rejects(opening, /Browser connection closed/)
    await started
    if (action === "remove") transport.configure([])
    else transport.stop()
    await Promise.race([rejected, new Promise((_, reject) => {
      const timer = setTimeout(() => reject(new Error("Closed browser handshake still waiting")), 500)
      timer.unref()
    })])
    assert.equal(socket.readyState, 3)
  }
})

test("removing a server aborts in-flight capability discovery", async () => {
  let observed
  const started = new Promise(resolve => { observed = resolve })
  const fetcher = async (url, { signal }) => new Promise((_, reject) => {
    signal.addEventListener("abort", () => reject(signal.reason), { once: true })
    observed()
  })
  const transport = createBrowserTransport({ native: async () => null, publish: () => {}, surface: () => {}, keyboard: () => {}, status: () => {}, fetcher, WebSocketClass: class {} })
  transport.configure([{ id: "test", url: "https://test.example" }])
  const opening = assert.rejects(transport.connect("test"), /Browser connection closed/)
  await started
  transport.configure([])
  await opening
})
