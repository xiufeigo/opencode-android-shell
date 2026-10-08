import { test } from "node:test"
import assert from "node:assert/strict"
import { createTerminalHandoffs } from "./panel-lifecycle.mjs"

test("rapid remount waits for every older snapshot even when the newest cleanup finishes first", async () => {
  const handoffs = createTerminalHandoffs()
  const finishOld = handoffs.begin("same-pty")
  const finishNew = handoffs.begin("same-pty")
  const waiting = handoffs.wait("same-pty")
  let restored = false
  waiting.then(() => { restored = true })
  finishNew()
  await new Promise(setImmediate)
  assert.equal(restored, false)
  assert.equal(handoffs.wait("same-pty"), waiting)
  finishOld()
  await waiting
  assert.equal(restored, true)
  assert.equal(handoffs.wait("same-pty"), undefined)
})

test("completed older cleanup cannot erase a newer pending handoff or block another PTY", async () => {
  const handoffs = createTerminalHandoffs()
  const finishOld = handoffs.begin("first")
  const finishNew = handoffs.begin("first")
  const finishOther = handoffs.begin("other")
  const newer = handoffs.wait("first")
  finishOld()
  finishOther()
  await new Promise(setImmediate)
  assert.equal(handoffs.wait("first"), newer)
  assert.equal(handoffs.wait("other"), undefined)
  finishNew()
  await newer
  assert.equal(handoffs.wait("first"), undefined)
})
