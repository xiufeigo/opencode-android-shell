import test from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import { randomBytes } from "node:crypto"
import WebSocket, { WebSocketServer } from "ws"
import { createGateway, BROWSER_PATH } from "./gateway.mjs"
import { PROTOCOL } from "./transport.mjs"

test("single OpenCode address preserves auth, HTTP streams, upgrades and one-use browser tickets", async () => {
  const authorization = `Basic ${Buffer.from(`opencode:${randomBytes(32).toString("base64url")}`).toString("base64")}`
  const upstream = http.createServer((request, response) => {
    if (request.headers.authorization !== authorization) { response.writeHead(401); response.end(); return }
    if (request.url === "/api/info") { response.end(JSON.stringify({ version: "2.0.26" })); return }
    response.writeHead(200, { "content-type": "application/octet-stream" })
    request.pipe(response)
  })
  const peer = new WebSocketServer({ noServer: true })
  upstream.on("upgrade", (request, socket, head) => {
    assert.equal(request.headers.authorization, authorization)
    peer.handleUpgrade(request, socket, head, ws => ws.on("message", value => ws.send(value)))
  })
  await new Promise(resolve => upstream.listen(0, "127.0.0.1", resolve))
  let time = Date.now()
  const gateway = createGateway(`http://127.0.0.1:${upstream.address().port}`, { now: () => time })
  const server = http.createServer(gateway.handle)
  const browser = new WebSocketServer({ noServer: true, handleProtocols: () => PROTOCOL })
  server.on("upgrade", (request, socket, head) => gateway.upgrade(request, socket, head, endpoint => browser.handleUpgrade(request, socket, head, ws => { ws.send(JSON.stringify({ id: endpoint.id, authorized: endpoint.headers.authorization === authorization })); ws.close() })))
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve))
  const origin = `http://127.0.0.1:${server.address().port}`
  const headers = { authorization, "content-type": "application/json" }
  const ticket = async id => (await fetch(`${origin}${BROWSER_PATH}/ticket`, { method: "POST", headers, body: JSON.stringify({ server: id }) })).json()
  try {
    assert.equal((await fetch(`${origin}${BROWSER_PATH}/capabilities`)).status, 401)
    assert.equal((await fetch(`${origin}/api/info`)).status, 401)
    assert.equal((await fetch(`${origin}${BROWSER_PATH}/ticket`, { method: "POST", headers: { authorization: "Basic invalid" }, body: "{}" })).status, 401)
    const discovery = await fetch(`${origin}${BROWSER_PATH}/capabilities`, { headers: { ...headers, origin: "http://127.0.0.1:4173" } })
    assert.equal(discovery.headers.get("access-control-allow-origin"), "http://127.0.0.1:4173")
    assert.equal((await discovery.json()).protocol, PROTOCOL)
    const bytes = randomBytes(2_097_175)
    const echoed = await fetch(`${origin}/api/test?query=1`, { method: "POST", headers, body: bytes })
    assert.deepEqual(Buffer.from(await echoed.arrayBuffer()), bytes)
    const ws = new WebSocket(origin.replace("http:", "ws:") + "/api/pty/test/connect?ticket=test", { headers })
    await new Promise((resolve, reject) => { ws.once("open", resolve); ws.once("error", reject) })
    const echo = new Promise(resolve => ws.once("message", resolve))
    ws.send("PTY relay")
    assert.equal((await echo).toString(), "PTY relay")
    ws.close()
    const first = await ticket("server-a")
    const connection = new WebSocket(origin.replace("http:", "ws:") + first.path, [PROTOCOL, first.ticket])
    const result = await new Promise((resolve, reject) => { connection.once("message", value => resolve(JSON.parse(value))); connection.once("error", reject) })
    assert.deepEqual(result, { id: "server-a", authorized: true })
    const rejected = value => new Promise(resolve => {
      const socket = new WebSocket(origin.replace("http:", "ws:") + value.path, [PROTOCOL, value.ticket])
      socket.once("unexpected-response", (_, response) => { resolve(response.statusCode); response.resume(); socket.terminate() })
      socket.on("error", () => {})
    })
    assert.equal(await rejected(first), 401)
    const expired = await ticket("server-b")
    time += 30_001
    assert.equal(await rejected(expired), 401)
    assert.equal((await fetch(`${origin}${BROWSER_PATH}/ticket`, { method: "POST", headers, body: JSON.stringify({ server: "" }) })).status, 400)
    assert.equal((await fetch(`${origin}${BROWSER_PATH}/ticket`, { method: "POST", headers, body: "invalid JSON" })).status, 400)
    assert.equal((await fetch(`${origin}${BROWSER_PATH}/ticket`, { method: "POST", headers, body: JSON.stringify({ server: "a".repeat(32_769) }) })).status, 413)
  } finally {
    for (const ws of peer.clients) ws.terminate()
    for (const ws of browser.clients) ws.terminate()
    peer.close(); browser.close()
    server.closeAllConnections(); upstream.closeAllConnections()
    await Promise.all([new Promise(resolve => server.close(resolve)), new Promise(resolve => upstream.close(resolve))])
  }
})

test("gateway never accepts a credential-bearing URL or backend path", () => {
  for (const url of ["http://user:password@localhost", "file:///tmp", "http://localhost/backend", "http://localhost/?token=private"])
    assert.throws(() => createGateway(url), /OpenCode origin/)
})
