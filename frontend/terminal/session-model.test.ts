import { describe, expect, test } from "bun:test"
import { createStore } from "solid-js/store"
import type { PersistentPtyInfo } from "@opencode/client"
import type { Layout, ServerRef, SessionRef, Storage } from "@opencode/plugin/gui"
import { createTerminalModel } from "./model"

// The Android terminal model mirrors the server's persistent PTYs: the server owns
// the process and its output, so a session's terminals are keyed by the session,
// not by the directory the chat runs in. Two chats in one project must therefore
// keep their own terminals, and a cold client must find the running ones again.

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const info = (id: string, title: string, sessionID: string): PersistentPtyInfo => ({
  id,
  title,
  userTitle: null,
  command: "/bin/zsh",
  args: [],
  cwd: "/repo",
  status: "running",
  pid: 1,
  sessionID,
  foregroundProcess: null,
  size: { cols: 80, rows: 24 },
  output: { head: 0, tail: 0 },
})

// A server with persistent terminals: its own terminal list per session, and an event stream the test drives.
function fakeServer() {
  const terminals = new Map<string, PersistentPtyInfo[]>()
  const handlers = new Map<string, ((event: { data: { sessionID: string; terminal?: PersistentPtyInfo } }) => void)[]>()
  const calls = { list: 0, create: [] as unknown[], remove: [] as string[], update: [] as unknown[] }
  let created = 0

  const server = {
    id: "http://server",
    data: {
      on: (type: string, handler: (event: { data: { sessionID: string; terminal?: PersistentPtyInfo } }) => void) => {
        handlers.set(type, [...(handlers.get(type) ?? []), handler])

        return () =>
          handlers.set(
            type,
            (handlers.get(type) ?? []).filter((item) => item !== handler),
          )
      },
      session: { get: (id: string) => ({ id, location: { directory: "/repo" } }) },
    },
    client: {
      server: { info: async () => ({ capabilities: { persistentPty: true } }) },
      experimental: {
        persistentPty: {
          list: async (input: { sessionID: string }) => {
            calls.list += 1

            return [...(terminals.get(input.sessionID) ?? [])]
          },
          create: async (input: { sessionID: string; title: string; cwd?: string }) => {
            calls.create.push(input)
            created += 1
            const next = info(`pty_new${created}`, input.title, input.sessionID)

            terminals.set(input.sessionID, [...(terminals.get(input.sessionID) ?? []), next])

            return next
          },
          remove: async (input: { ptyID: string }) => {
            calls.remove.push(input.ptyID)
            terminals.forEach((list, sessionID) =>
              terminals.set(
                sessionID,
                list.filter((item) => item.id !== input.ptyID),
              ),
            )
          },
          update: async (input: { ptyID: string; title?: string | null }) => {
            calls.update.push(input)
            const found = [...terminals.values()].flat().find((item) => item.id === input.ptyID)!

            return { ...found, title: input.title ?? found.title, userTitle: input.title ?? null }
          },
        },
      },
    },
  }

  return {
    // SAFETY: the model reads only `id`, `data` (on, session.get) and the client calls stubbed here.
    // oxlint-disable-next-line anti-slop/no-chained-type-assertions -- see SAFETY above
    ref: server as unknown as ServerRef,
    terminals,
    calls,
    emit(type: string, sessionID: string, terminal?: PersistentPtyInfo) {
      handlers.get(type)?.forEach((handler) => handler({ data: { sessionID, terminal } }))
    },
  }
}

function setup(server: ReturnType<typeof fakeServer>) {
  const stored = new Map<string, string[]>()
  const opened: { key: string; session: string }[] = []
  const removed: { key: string; options: Parameters<Storage["remove"]>[1] }[] = []

  const storage = {
    memory<T extends object>(_key: string, options: { initial: T }) {
      const [value, set] = createStore(options.initial)

      return [value, (mutate: (draft: T) => void) => set(mutate)] as const
    },
    store<T extends object>(_key: string, options: { initial: T }) {
      const [value, set] = createStore({ value: options.initial })

      return {
        get value() {
          return value.value
        },
        ready: () => true,
        set: (next: T) => set("value", next),
        update: () => undefined,
      }
    },
    remove: (key: string, options: Parameters<Storage["remove"]>[1]): void => removed.push({ key, options }),
  }

  const layout: Pick<Layout, "open" | "stored"> = {
    stored: (session) => stored.get(session.key) ?? [],
    open(key, session) {
      opened.push({ key, session: session.key })
      stored.set(session.key, [...(stored.get(session.key) ?? []), key.slice("terminal:".length)])
    },
  }

  const model = createTerminalModel({
    // SAFETY: the model opens only `memory`, `store` and `remove`, which these stand-ins implement.
    // oxlint-disable-next-line anti-slop/no-chained-type-assertions -- see SAFETY above
    storage: storage as unknown as Storage,
    layout,
    toast: { show: () => ({ dismiss: () => undefined }) },
    t: (key) => key,
    tab: (id) => `terminal:${id}`,
  })

  // Two chats in the same project directory: only the session id tells them apart.
  const session = (id: string): SessionRef => ({
    key: `${server.ref.id}\n${id}`,
    id,
    tab: `tab_${id}`,
    server: server.ref,
    pending: false,
    location: { directory: "/repo" },
  })

  return { model, session, stored, opened, removed }
}

describe("Android per-session terminal ownership", () => {
  test("two chats in one directory keep their own terminals", async () => {
    const server = fakeServer()
    server.terminals.set("ses_a", [info("pty_a", "Terminal a", "ses_a")])
    server.terminals.set("ses_b", [info("pty_b", "Terminal b", "ses_b")])
    const { model, session } = setup(server)

    const a = model.load(session("ses_a"))
    const b = model.load(session("ses_b"))

    await flush()

    expect(a.all().map((pty) => pty.id)).toEqual(["pty_a"])
    expect(b.all().map((pty) => pty.id)).toEqual(["pty_b"])
    expect(a.active()).toBe("pty_a")
    expect(b.active()).toBe("pty_b")

    model.dispose()
  })

  test("a cold client finds the running terminals again", async () => {
    const server = fakeServer()
    server.terminals.set("ses_a", [info("pty_a", "Terminal a", "ses_a")])
    const { model, session } = setup(server)

    model.load(session("ses_a"))
    await flush()

    model.dispose()

    // The server owns the process, so a new client lists the same terminal.
    const cold = setup(server)
    const restored = cold.model.load(cold.session("ses_a"))

    await flush()

    expect(restored.all().map((pty) => pty.id)).toEqual(["pty_a"])
    expect(restored.active()).toBe("pty_a")

    cold.model.dispose()
  })

  test("closing a terminal in one chat leaves the other chat alone", async () => {
    const server = fakeServer()
    server.terminals.set("ses_a", [info("pty_a", "Terminal a", "ses_a")])
    server.terminals.set("ses_b", [info("pty_b", "Terminal b", "ses_b")])
    const { model, session } = setup(server)

    const a = model.load(session("ses_a"))
    const b = model.load(session("ses_b"))

    await flush()

    await a.close("pty_a")
    await flush()

    expect(a.all()).toEqual([])
    expect(b.all().map((pty) => pty.id)).toEqual(["pty_b"])
    expect(server.calls.remove).toEqual(["pty_a"])

    model.dispose()
  })

  test("the legacy local terminal store is dropped", async () => {
    const server = fakeServer()
    const { model, session, removed } = setup(server)

    model.load(session("ses_a"))
    await flush()

    model.remove({ server: server.ref.id, directory: "/repo" })

    // The old client-side store held the terminals; the server does now.
    expect(removed.map((entry) => entry.key)).toContain("terminals")

    model.dispose()
  })
})
