import { describe, expect, test, mock } from "bun:test"
import { createRoot } from "solid-js"
import { createStore, produce } from "solid-js/store"
import { Schema } from "effect"
import { createTerminalModel } from "./model"
import { persistStore } from "../../../app/src/runtime/persistence/persist"
import type { Storage, Sessions, ServerRef, MountedSession } from "../sdk"

function fixture(raw = new Map<string, string>()) {
  const create = mock(async () => ({ data: { id: `pty-${create.mock.calls.length}`, title: "Terminal" } }))
  const remove = mock(async () => ({}))
  const server = { id: "server-a", data: { on: () => () => {} }, client: { pty: { create, remove, update: async () => ({}) } } } as unknown as ServerRef
  const session = (id: string, directory = "/project") => ({ id, server, directory }) as MountedSession
  const storage = {
    memory: (_key: string, options: { initial: object }) => [options.initial, () => {}],
    store(key: string, options: any) {
      const name = `${options.scope.server}\0${options.scope.directory}\0${key}`
      const [store, setStore] = createStore(structuredClone(options.initial))
      const persisted = persistStore({ store, setStore, name,
        storage: { getItem: (key: string) => raw.get(key) ?? null, setItem: (key: string, value: string) => { raw.set(key, value) }, removeItem: (key: string) => { raw.delete(key) } },
        serialize: (value) => JSON.stringify(Schema.encodeSync(options.schema)(value)),
        deserialize: (value) => Schema.decodeUnknownSync(options.schema)(JSON.parse(value)),
      })
      return { value: store, ready: () => true, update: (fn: any) => persisted.setStore(produce(fn)), set: (value: any) => persisted.setStore(value) }
    },
    remove: (_key: string, options: any) => { raw.delete(`${options.scope.server}\0${options.scope.directory}\0terminals`) },
  } as unknown as Storage
  const sessions = { list: () => [session("a"), session("b")] } as unknown as Sessions
  const open = () => createRoot((dispose) => ({ model: createTerminalModel({ storage, sessions }), dispose }))
  return { raw, create, remove, session, open }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

describe("Android per-session terminal ownership", () => {
  test("same directory keeps independent tabs and selection through a cold model", async () => {
    const f = fixture()
    const first = f.open()
    const a = first.model.load(f.session("a"))
    const b = first.model.load(f.session("b"))
    a.new()
    await settle()
    const firstId = a.active()
    a.new()
    await settle()
    const secondId = a.active()
    a.open(firstId!)
    b.new()
    await settle()
    const bId = b.active()
    expect(bId).not.toBe(firstId)
    expect(a.all().map((pty) => pty.id)).toEqual([firstId, secondId])
    expect(b.all().map((pty) => pty.id)).toEqual([bId])
    first.model.dispose()
    first.dispose()

    const cold = f.open()
    const restoredA = cold.model.load(f.session("a"))
    expect(restoredA.all().map((pty) => pty.id)).toEqual([firstId, secondId])
    expect(restoredA.active()).toBe(firstId)
    expect(cold.model.load(f.session("b")).active()).toBe(bId)
    expect(f.create).toHaveBeenCalledTimes(3)
    expect(f.remove).not.toHaveBeenCalled()
    cold.model.dispose()
    cold.dispose()
  })

  test("existing shared PTYs migrate once and remain owned after reopen", () => {
    const f = fixture(new Map([["server-a\0/project\0terminals", JSON.stringify({ active: "old-pty", all: [{ id: "old-pty", title: "Running job", titleNumber: 1, cursor: 123, buffer: "progress" }] })]]))
    const first = f.open()
    expect(first.model.load(f.session("a")).active()).toBe("old-pty")
    expect(first.model.load(f.session("b")).all()).toEqual([])
    first.model.dispose()
    first.dispose()
    const cold = f.open()
    expect(cold.model.load(f.session("b")).all()).toEqual([])
    expect(cold.model.load(f.session("a")).all()[0].buffer).toBe("progress")
    expect(f.create).not.toHaveBeenCalled()
    expect(f.remove).not.toHaveBeenCalled()
    cold.model.dispose()
    cold.dispose()
  })

  test("workspace cache eviction and removal do not kill the remote command", async () => {
    const f = fixture()
    const first = f.open()
    const a = first.model.load(f.session("a"))
    a.new()
    await settle()
    const id = a.active()
    for (let i = 0; i < 25; i++) first.model.load(f.session(`other-${i}`, `/other-${i}`))
    expect(first.model.load(f.session("a")).active()).toBe(id)
    expect(f.create).toHaveBeenCalledTimes(1)
    first.model.remove({ server: "server-a", directory: "/project" })
    expect(first.model.load(f.session("a")).all()).toEqual([])
    expect(first.model.load(f.session("a")).ready()).toBe(true)
    expect(f.remove).not.toHaveBeenCalled()
    first.model.dispose()
    first.dispose()
  })

  test("closing a terminal deletes only that PTY and keeps the other session", async () => {
    const f = fixture()
    const first = f.open()
    const a = first.model.load(f.session("a"))
    const b = first.model.load(f.session("b"))
    a.new()
    b.new()
    await settle()
    const bId = b.active()
    await a.close(a.active()!)
    expect(a.all()).toEqual([])
    expect(b.active()).toBe(bId)
    expect(f.remove).toHaveBeenCalledTimes(1)
    expect(f.remove.mock.calls[0][0].ptyID).not.toBe(bId)
    first.model.dispose()
    first.dispose()
  })
})
