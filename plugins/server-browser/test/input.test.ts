import { test } from "node:test"
import assert from "node:assert/strict"
import { createInputQueue } from "../../../frontend/remote-browser/input.ts"

test("client preserves click/key order while coalescing only unsent movement", async () => {
  const sent: { type: string; x?: number; phase?: string }[] = []
  let release!: () => void
  const first = new Promise<void>(resolve => release = resolve)
  const queue = createInputQueue(async action => { sent.push(action); if (sent.length === 1) await first; return sent.length })
  const promises = [queue({ type: "pointer", tabID: "tab", phase: "down", x: 0, y: 0 })]
  for (let x = 1; x <= 100; x++) promises.push(queue({ type: "pointer", tabID: "tab", phase: "move", x, y: 0 }))
  promises.push(queue({ type: "pointer", tabID: "tab", phase: "up", x: 100, y: 0 }))
  promises.push(queue({ type: "insert", tabID: "tab", text: "中文" }))
  release()
  await Promise.all(promises)
  assert.equal(sent.length, 4)
  assert.deepEqual(sent.slice(0, 3).map(value => [value.phase, value.x]), [["down", 0], ["move", 100], ["up", 100]])
  assert.equal(sent[3].type, "insert")
})

test("input failure rejects its caller and does not discard the following input", async () => {
  const queue = createInputQueue(async action => { if (action.type === "reload") throw new Error("visible failure"); return "next" })
  await assert.rejects(queue({ type: "reload", tabID: "tab" }), /visible failure/)
  assert.equal(await queue({ type: "insert", tabID: "tab", text: "next" }), "next")
})
