import { expect, test } from "bun:test"
import { createInputQueue } from "./input"
import type { Action } from "./rpc"

const pointer = (tabID: string, x: number): Action => ({ type: "pointer", tabID, phase: "move", x, y: 0 })

test("sends one action at a time, in order", async () => {
  const sent: Action[] = []
  const queue = createInputQueue(async action => { sent.push(action) })
  const first = queue(pointer("a", 1))
  const second = queue({ type: "insert", tabID: "a", text: "hi" })
  await Promise.all([first, second])
  expect(sent.map(action => action.type)).toEqual(["pointer", "insert"])
})

test("coalesces motion that has not been sent on the same tab", async () => {
  const sent: Action[] = []
  let release: (() => void) | undefined
  const queue = createInputQueue(action => {
    sent.push(action)
    return new Promise<void>(resolve => {
      if (sent.length === 1) release = resolve
      else resolve()
    })
  })
  const first = queue(pointer("a", 1))
  const second = queue(pointer("a", 2))
  const third = queue(pointer("a", 3))
  expect(sent).toHaveLength(1)
  release?.()
  await Promise.all([first, second, third])
  expect(sent).toHaveLength(2)
  expect(sent[1]).toMatchObject({ type: "pointer", x: 3 })
})

test("coalesces scroll on the same tab by summing offsets", async () => {
  const sent: Action[] = []
  let release: (() => void) | undefined
  const queue = createInputQueue(action => {
    sent.push(action)
    return new Promise<void>(resolve => {
      if (sent.length === 1) release = resolve
      else resolve()
    })
  })
  void queue({ type: "scroll", tabID: "a", deltaY: 10 })
  void queue({ type: "scroll", tabID: "a", deltaY: 20 })
  void queue({ type: "scroll", tabID: "a", deltaY: 30 })
  release?.()
  await Promise.resolve()
  await Promise.resolve()
  expect(sent[1]).toMatchObject({ type: "scroll", deltaY: 50 })
})

test("never coalesces actions of different tabs", async () => {
  const sent: Action[] = []
  let release: (() => void) | undefined
  const queue = createInputQueue(action => {
    sent.push(action)
    return new Promise<void>(resolve => {
      if (sent.length === 1) release = resolve
      else resolve()
    })
  })
  void queue(pointer("a", 1))
  void queue(pointer("b", 2))
  release?.()
  await Promise.resolve()
  await Promise.resolve()
  expect(sent.map(action => "tabID" in action && action.tabID)).toEqual(["a", "b"])
})

test("a failed action rejects its waiters and the queue keeps sending", async () => {
  const queue = createInputQueue(async action => {
    if (action.type === "insert") throw new Error("browser unavailable")
  })
  await expect(queue({ type: "insert", tabID: "a", text: "x" })).rejects.toThrow("browser unavailable")
  await expect(queue(pointer("a", 1))).resolves.toBeUndefined()
})
