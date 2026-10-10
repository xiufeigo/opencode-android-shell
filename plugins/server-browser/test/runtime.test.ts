import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import fs from "node:fs/promises"
import os from "node:os"
import { randomUUID } from "node:crypto"
import { chromium } from "playwright"
import { BrowserRuntime } from "../src/runtime.js"
import { normalizeURL } from "../src/page.js"

test("server Chromium: real input, snapshots, session isolation and client reconnect", async t => {
  const server = http.createServer((request, response) => {
    response.setHeader("content-type", "text/html")
    response.end(`<title>Server browser fixture</title><label>Name<input aria-label="Name"></label><button onclick="document.querySelector('output').textContent=++window.clicks">Count</button><output>0</output><script>window.clicks=0;setInterval(()=>document.body.dataset.progress=String(Number(document.body.dataset.progress||0)+1),100)</script>`)
  })
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve))
  t.after(() => new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())))
  const runtime = new BrowserRuntime(() => chromium.launch({ executablePath: process.env.SERVER_BROWSER_CHROMIUM }), os.tmpdir(), () => {})
  t.after(() => runtime.dispose())
  const url = `http://127.0.0.1:${(server.address() as { port: number }).port}`
  const run = (session: string, action: Parameters<BrowserRuntime["command"]>[2], requestID = randomUUID()) => runtime.command(session, requestID, action)
  const tab = await run("session-a", { type: "tabs.open", url }) as { id: string }
  const snapshot = await run("session-a", { type: "snapshot", tabID: tab.id }) as { content: string }
  const ref = (name: string) => snapshot.content.split("\n").find(line => line.startsWith(`- ${name === "Name" ? "textbox" : "button"} "${name}"`))!.match(/ref=(e\d+)/)![1]
  await run("session-a", { type: "fill", tabID: tab.id, ref: ref("Name"), text: "中文 from server" })
  const requestID = randomUUID()
  await run("session-a", { type: "click", tabID: tab.id, ref: ref("Count") }, requestID)
  await run("session-a", { type: "click", tabID: tab.id, ref: ref("Count") }, requestID)
  assert.deepEqual((await run("session-a", { type: "evaluate", tabID: tab.id, script: "({value:document.querySelector('input').value, clicks:window.clicks})" }) as { value: unknown }).value, { value: "中文 from server", clicks: 1 })
  await assert.rejects(run("session-a", { type: "reload", tabID: tab.id }, requestID), /reused/)
  const frame = await runtime.frame("session-a", tab.id)
  assert.equal(Buffer.from(frame.image!, "base64").subarray(0, 2).toString("hex"), "ffd8")
  await run("session-a", { type: "evaluate", tabID: tab.id, script: "document.cookie='fixture=session-a';document.body.dataset.progress" })
  const before = Number((await run("session-a", { type: "evaluate", tabID: tab.id, script: "document.body.dataset.progress" }) as { value: unknown }).value)
  // No client lease exists: the browser keeps executing with no connected UI.
  await new Promise(resolve => setTimeout(resolve, 400))
  const restored = await runtime.state("session-a")
  assert.equal(restored.tabs[0].id, tab.id)
  const after = Number((await run("session-a", { type: "evaluate", tabID: tab.id, script: "document.body.dataset.progress" }) as { value: unknown }).value)
  assert.ok(after > before)
  const other = await run("session-b", { type: "tabs.open", url }) as { id: string }
  assert.equal((await run("session-b", { type: "evaluate", tabID: other.id, script: "document.cookie" }) as { value: unknown }).value, "")
  await assert.rejects(runtime.frame("session-b", tab.id), /another session/)
  await run("session-a", { type: "snapshot", tabID: tab.id })
  await assert.rejects(run("session-a", { type: "click", tabID: tab.id, ref: ref("Count") }), /expired/)
  const capture = await run("session-a", { type: "screenshot", tabID: tab.id }) as { files: { path: string }[] }
  assert.ok((await fs.stat(capture.files[0].path)).size > 0)
  const otherCapture = await run("session-b", { type: "screenshot", tabID: other.id }) as { files: { path: string }[] }
  await run("session-b", { type: "tabs.close", tabID: other.id })
  await assert.rejects(fs.stat(otherCapture.files[0].path), /ENOENT/)
  await run("session-b", { type: "tabs.open", url })
  const evaluating = run("session-a", { type: "evaluate", tabID: tab.id, script: "alert('fixture'); 'accepted'" })
  for (let attempts = 0; !(await runtime.state("session-a")).tabs[0].dialog; attempts++) {
    assert.ok(attempts < 100, "Dialog should be observable while evaluation waits")
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  await run("session-a", { type: "dialog", tabID: tab.id, action: "accept" })
  assert.equal((await evaluating as { value: unknown }).value, "accepted")
  await runtime.remove("session-a")
  await assert.rejects(fs.stat(capture.files[0].path), /ENOENT/)
  await assert.rejects(run("session-a", { type: "tabs.open" }), /deleted or moved/)
  assert.deepEqual((await runtime.state("session-a")).tabs, [])
  assert.equal((await runtime.state("session-b")).tabs.length, 1)
})

test("invalid URL schemes and credentials are rejected explicitly", () => {
  assert.equal(normalizeURL("localhost:4096"), "http://localhost:4096/")
  assert.equal(normalizeURL("example.com"), "https://example.com/")
  for (const value of ["file:///etc/passwd", "javascript:alert(1)", "/tmp/page.html", "C:\\page.html", "https://user:password@example.com"]) assert.throws(() => normalizeURL(value))
})
