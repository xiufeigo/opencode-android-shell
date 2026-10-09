import fs from "node:fs/promises"
import http from "node:http"
import path from "node:path"
import { fileURLToPath } from "node:url"
import assert from "node:assert/strict"
import WebSocket from "ws"
import { PROTOCOL } from "./transport.mjs"

const url = process.env.OPENCODE_TEST_SERVER_URL
const tokenFile = process.env.OPENCODE_BROWSER_TOKEN_FILE
if (!url || !tokenFile) throw new Error("Set OPENCODE_TEST_SERVER_URL and OPENCODE_BROWSER_TOKEN_FILE; use a test OpenCode v2 server on this computer")
const password = process.env.OPENCODE_TEST_SERVER_PASSWORD
const username = process.env.OPENCODE_TEST_SERVER_USERNAME ?? "opencode"
const headers = { "content-type": "application/json", ...(password ? { authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}` } : {}) }
const output = path.resolve(process.env.OPENCODE_BROWSER_REPORT_DIR ?? fileURLToPath(new URL(`../research/tmp/verify-android-browser/${new Date().toISOString().replace(/[:.]/g, "-")}`, import.meta.url)))
await fs.mkdir(output, { recursive: true })
const checks = []
const proof = []
const events = []
const frames = []
const pending = new Map()
let counter = 0
let session
let ws
const fixture = http.createServer((req, res) => {
  if (req.url.startsWith("/proof")) { proof.push(req.url); res.end("ok"); return }
  res.setHeader("content-type", "text/html; charset=utf-8")
  res.end(`<!doctype html><title>Browser parity</title><style>body{font:20px sans-serif;margin:16px}input,button{display:block;margin:12px 0;height:40px;width:200px}</style>
    <h1>Browser parity</h1><input id="text" aria-label="Text"><button onclick="fetch('/proof?click=1')">Click me</button>
    <iframe src="about:blank" title="nested"></iframe><div style="height:2000px">Scroll fixture</div>`)
})
const wait = async (condition, label) => {
  const deadline = Date.now() + 20_000
  while (Date.now() < deadline) {
    if (condition()) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`Timed out: ${label}`)
}
function enqueue(method, input) {
  const id = String(++counter)
  const promise = new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)) }, 120_000)
    pending.set(id, { resolve, reject, timer })
    ws.send(JSON.stringify({ id, method, input }))
  })
  return { id, promise }
}
const request = (method, input) => enqueue(method, input).promise
const call = (method, input = {}) => request("call", { ipc: "browser.pane", method, input: { binding: "smoke", ...input } })
const command = action => call("command", { command: action })
const pass = name => { checks.push(name); console.log(`PASS ${name}`) }

try {
  const created = await fetch(`${url}/api/session`, { method: "POST", headers, body: JSON.stringify({ title: "Android browser smoke fixture" }) })
  if (!created.ok) throw new Error(`Cannot create test session: HTTP ${created.status}`)
  const json = await created.json()
  session = (json.data ?? json).id
  await new Promise((resolve, reject) => { fixture.once("error", reject); fixture.listen(0, "127.0.0.1", resolve) })
  const address = `http://127.0.0.1:${fixture.address().port}`
  const service = new URL(process.env.OPENCODE_BROWSER_URL ?? "http://127.0.0.1:4100")
  service.protocol = service.protocol === "https:" ? "wss:" : "ws:"
  service.pathname = `${service.pathname.replace(/\/$/, "")}/bridge`
  ws = new WebSocket(service, [PROTOCOL, (await fs.readFile(tokenFile, "utf8")).trim()])
  ws.on("message", (bytes, binary) => {
    if (binary) { frames.push(bytes); return }
    const message = JSON.parse(bytes)
    const call = pending.get(message.id)
    if (!call) { events.push(message); return }
    clearTimeout(call.timer); pending.delete(message.id)
    if (message.error) call.reject(new Error(message.error))
    else call.resolve(message.value)
  })
  ws.on("close", () => {
    for (const call of pending.values()) { clearTimeout(call.timer); call.reject(new Error("Browser connection closed")) }
    pending.clear()
  })
  await new Promise((resolve, reject) => { ws.once("open", resolve); ws.once("error", reject) })
  await wait(() => events.some(event => event.type === "available"), "browser service ready")
  await request("configure", [{ id: "test", url, ...(password ? { username, password } : {}) }])
  await call("register", { server: "test", session })
  pass("real OpenCode session registration")
  await command({ type: "tabs.open", url: address })
  await wait(() => events.some(event => event.data?.event.type === "embed"), "browser embed")
  const embed = events.find(event => event.data?.event.type === "embed").data.event
  const tabID = embed.tabID
  await request("embed", { id: embed.embed, layout: { visible: true, bounds: { width: 800, height: 600 } } })
  await wait(() => frames.length > 0, "live frame")
  const frame = frames.at(-1)
  await fs.writeFile(path.join(output, "browser.jpg"), frame.subarray(2 + frame.readUInt16BE()))
  pass("website through server network tunnel and live JPEG frame")
  await command({ type: "evaluate", tabID, script: 'fetch("/proof?evaluate=1");document.title' })
  await wait(() => proof.some(value => value.includes("evaluate=1")), "evaluate reached the webpage")
  pass("evaluate automation")
  for (const type of ["snapshot", "frames", "console", "network.list"]) { await command({ type, tabID }); pass(type) }
  for (const type of ["mouseDown", "mouseUp"]) await request("input", { id: embed.embed, event: { type, x: 80, y: 190, button: "left", clickCount: 1 } })
  await wait(() => proof.some(value => value.includes("click=1")), "click reached the webpage")
  pass("pointer input")
  for (const type of ["mouseDown", "mouseUp"]) await request("input", { id: embed.embed, event: { type, x: 80, y: 130, button: "left", clickCount: 1 } })
  await request("input", { id: embed.embed, event: { type: "text", text: "你好 Android" } })
  await command({ type: "evaluate", tabID, script: 'fetch("/proof?text="+encodeURIComponent(document.querySelector("#text").value));null' })
  await wait(() => proof.some(value => decodeURIComponent(value).includes("你好 Android")), "Unicode reached the webpage")
  pass("Unicode text input")
  assert.ok(Buffer.from(await request("capture", embed.embed), "base64").length > 1_000)
  await command({ type: "screenshot", tabID, format: "jpeg", maxWidth: 800 })
  pass("capture and screenshot transfer to server")
  await assert.rejects(request("input", { id: "unowned", event: { type: "text", text: "bad" } }), /not visible/)
  await assert.rejects(command({ type: "navigate", tabID, url: "file:///etc/passwd" }))
  pass("surface ownership and remote file boundary")
  const waiting = enqueue("call", { ipc: "browser.pane", method: "command", input: { binding: "smoke", command: { type: "wait", tabID, condition: "text", text: "never-present", timeoutMs: 30_000 } } })
  const canceled = assert.rejects(waiting.promise)
  await new Promise(resolve => setTimeout(resolve, 100))
  await request("cancel", waiting.id)
  await canceled
  pass("request cancellation reaches the browser engine")
  await command({ type: "cpu.start", tabID })
  await command({ type: "evaluate", tabID, script: "Array.from({length:10000},(_,i)=>Math.sqrt(i)).length" })
  await command({ type: "cpu.stop", tabID })
  pass("CPU profile transfer")
  await command({ type: "lighthouse", tabID }); pass("Lighthouse report transfer")
  await command({ type: "heap.snapshot", tabID }); pass("heap snapshot transfer")
  await call("close")
} finally {
  ws?.close()
  fixture.close()
  if (session) {
    const deleted = await fetch(`${url}/api/session/${encodeURIComponent(session)}`, { method: "DELETE", headers })
    if (!deleted.ok) throw new Error(`Cannot remove own test session: HTTP ${deleted.status}`)
  }
  await fs.writeFile(path.join(output, "report.json"), JSON.stringify({ scope: "real OpenCode v2 + official browser engine; not Android device validation", pass: checks.length, checks, frames: frames.length }, null, 2))
}
console.log(`REPORT ${path.join(output, "report.json")}`)
