import test from "node:test"
import assert from "node:assert/strict"
import { dispatchInput } from "./input.mjs"

test("touch streams preserve two fingers, lift, cancel and editor focus", async () => {
  const calls = []
  let focus = 0, keyboard = 0
  const contents = { focus: () => focus++, setVisualZoomLevelLimits: async () => {}, debugger: { sendCommand: async (method, params) => { calls.push({ method, params }); return { result: { value: true } } } } }
  const run = event => dispatchInput(contents, event, () => keyboard++)
  const a = { id: 1, x: 10, y: 20 }, b = { id: 2, x: 100, y: 20 }
  await run({ type: "touch", phase: "start", points: [a] })
  await run({ type: "touch", phase: "start", points: [a, b] })
  await run({ type: "touch", phase: "move", points: [a, { ...b, x: 180 }] })
  await run({ type: "touch", phase: "move", points: [a] })
  await run({ type: "touch", phase: "end", points: [] })
  await run({ type: "touch", phase: "cancel", points: [] })
  assert.equal(focus, 2)
  assert.equal(keyboard, 1)
  assert.deepEqual(calls.filter(call => call.method === "Input.dispatchTouchEvent").map(call => [call.params.type, call.params.touchPoints.length]), [["touchStart", 1], ["touchStart", 2], ["touchMove", 2], ["touchMove", 1], ["touchEnd", 0], ["touchCancel", 0]])
  const before = calls.length
  await assert.rejects(run({ type: "touch", phase: "end", points: [a] }), /phase/)
  await assert.rejects(run({ type: "touch", phase: "move", points: [{ ...a, x: NaN }] }), /point/)
  assert.equal(calls.length, before)
})
