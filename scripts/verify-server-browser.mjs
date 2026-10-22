import assert from "node:assert/strict"
import fs from "node:fs/promises"
import path from "node:path"
import http from "node:http"
import { randomUUID } from "node:crypto"

const root = path.resolve(import.meta.dirname, "..")
const config = JSON.parse(await fs.readFile(path.join(root, "_scratch_root/server-browser-test/connection.json"), "utf8"))
const headers = { authorization: `Basic ${Buffer.from(`opencode:${config.password}`).toString("base64")}`, "content-type": "application/json" }
const checks = []
const out = path.join(root, "research/tmp/verify-server-browser", new Date().toISOString().replace(/[:.]/g, "-"))
await fs.mkdir(out, { recursive: true })
async function api(route, body, authorize = true) {
  const url = new URL(`/api/${route}`, config.url)
  url.searchParams.set("location[directory]", config.directory)
  const response = await fetch(url, { method: body ? "POST" : "GET", headers: authorize ? headers : { "content-type": "application/json" }, body: body && JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.replaceAll(config.password, "[redacted]")}`)
  if (!text) return
  const value = JSON.parse(text)
  return value.data ?? value
}
const rpc = (method, input) => api(`rpc/xiufeigo.server-browser/${method}`, { input }).then(value => value.output)
const fixture = http.createServer((request, response) => {
  response.setHeader("content-type", "text/html; charset=utf-8")
  response.end('<title>Server Browser Test</title><label>Message<input aria-label="Message"></label><button onclick="document.querySelector(\'output\').textContent=document.querySelector(\'input\').value">Submit</button><output>Ready</output><script>setInterval(()=>document.body.dataset.progress=String(Number(document.body.dataset.progress||0)+1),100)</script>')
})
await new Promise(resolve => fixture.listen(0, "127.0.0.1", resolve))
const sessions = []
try {
  await assert.rejects(api("rpc/xiufeigo.server-browser/state", { sessionID: "invalid" }, false), /HTTP 401/)
  checks.push("RPC uses original OpenCode authentication")
  const session = await api("session", { title: "Server browser integration" })
  sessions.push(session.id)
  await rpc("state", { sessionID: session.id })
  checks.push("Real OpenCode server loads the local v2 plugin")
  const probe = (await api("rpc/xiufeigo.server-browser-test/check", { input: { sessionID: session.id } })).output
  assert.equal(probe.tools.length, 29)
  assert.equal(probe.listed.tabs[0].id, probe.tabID)
  assert.deepEqual((await rpc("state", { sessionID: session.id })).tabs, [])
  checks.push("Real server registry exposes 29 authorized model tools; registered executors open, list and close actual Chromium")
  const url = `http://127.0.0.1:${fixture.address().port}`
  const tab = await rpc("command", { sessionID: session.id, requestID: randomUUID(), action: { type: "tabs.open", url } })
  assert.equal(tab.title, "Server Browser Test")
  const snapshot = await rpc("command", { sessionID: session.id, requestID: randomUUID(), action: { type: "snapshot", tabID: tab.id } })
  assert.match(snapshot.content, /textbox "Message"/)
  checks.push("Server Chromium reaches server-local pages and returns live accessibility data")
  const frame = await rpc("frame", { sessionID: session.id, tabID: tab.id })
  assert.ok(frame.image.length > 100)
  checks.push("Remote page pixels are available on the original authenticated RPC")
  const retained = await rpc("state", { sessionID: session.id })
  assert.equal(retained.tabs[0].id, tab.id)
  await assert.rejects(rpc("frame", { sessionID: "invalid", tabID: tab.id }), /HTTP 400|not found|Not found/)
  checks.push("Session validation rejects foreign requests")
  const report = { environment: "actual OpenCode server + installed server plugin + real Chromium", cli: config.executable, checks, tab: { id: tab.id, title: tab.title }, serverPort: new URL(config.url).port }
  await fs.writeFile(path.join(out, "report.json"), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
} finally {
  for (const id of sessions) {
    const url = new URL(`/api/session/${id}`, config.url)
    url.searchParams.set("location[directory]", config.directory)
    await fetch(url, { method: "DELETE", headers })
  }
  await new Promise(resolve => fixture.close(resolve))
}
