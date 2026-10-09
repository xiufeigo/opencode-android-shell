import test from "node:test"
import assert from "node:assert/strict"
import { createNativeRpc } from "./rpc.mjs"

test("correlates concurrent native replies and preserves picker cancellation", async () => {
  const events = new EventTarget()
  const sent = []
  const request = createNativeRpc({ postMessage: message => sent.push(JSON.parse(message)) }, events)
  const save = request("saveFile", { content: "中文\n" })
  const copy = request("writeClipboardText", { text: "code" })
  const reply = (id, value) => events.dispatchEvent(new MessageEvent("message", { data: JSON.stringify({ id, value }) }))
  reply(sent[1].id, true)
  reply(sent[0].id, false)
  assert.equal(await copy, true)
  assert.equal(await save, false)
  assert.equal(sent[0].payload.content, "中文\n")
})

test("rejects native operation errors", async () => {
  const events = new EventTarget()
  let sent
  const request = createNativeRpc({ postMessage: message => { sent = JSON.parse(message) } }, events)
  const result = request("saveFile")
  events.dispatchEvent(new MessageEvent("message", { data: JSON.stringify({ id: sent.id, error: "Disk is full" }) }))
  await assert.rejects(result, /Disk is full/)
})

test("rejects a failed transport instead of leaving a pending request", async () => {
  const request = createNativeRpc({ postMessage() { throw new Error("WebView destroyed") } }, new EventTarget())
  await assert.rejects(request("saveFile"), /WebView destroyed/)
})
