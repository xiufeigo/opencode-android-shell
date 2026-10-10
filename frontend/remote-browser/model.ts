import { createEffect, onCleanup } from "solid-js"
import { createStore } from "solid-js/store"
import { Schema } from "effect"
import type { Context, MountedSession } from "../sdk"
import { Definition, State, type Action } from "./rpc"
import { createInputQueue } from "./input"

type Record = { state: State; error?: string; pending: boolean }
export type Model = ReturnType<typeof createBrowserModel>
const empty = (): State => ({ version: 1, tabs: [], focusedTabID: null })
export function errorMessage(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) return String(error.message)
  return String(error)
}

export function createBrowserModel(ctx: Context) {
  const [records, setRecords] = createStore<{ [key: string]: Record }>({})
  const requests = new Map<string, Promise<State>>()
  const dirty = new Set<string>()
  const inputs = new Map<string, ReturnType<typeof createInputQueue>>()
  const get = (session: MountedSession): Record => records[session.key] ?? { state: empty(), pending: true }
  const client = (session: MountedSession) => session.server.client.rpc(Definition)
  const options = (session: MountedSession) => ({ location: { directory: session.directory }, signal: ctx.signal })
  const fail = (session: MountedSession, error: unknown) => setRecords(session.key, { ...get(session), pending: false, error: errorMessage(error) })
  const apply = (session: MountedSession, state: State) => {
    setRecords(session.key, { state, pending: false, error: undefined })
    for (const id of ctx.layout.stored(session)) {
      if (id !== "main" && !state.tabs.some(tab => tab.id === id)) ctx.layout.close(`${ctx.id}:${id}`, session)
    }
    for (const tab of state.tabs) {
      if (ctx.layout.state(`${ctx.id}:${tab.id}`, session) === "closed") ctx.layout.open(`${ctx.id}:${tab.id}`, session, { tab: "append", background: true })
    }
  }
  const refresh = (session: MountedSession) => {
    const previous = requests.get(session.key)
    if (previous) { dirty.add(session.key); return previous }
    const request = (async () => {
      let state: State
      do {
        dirty.delete(session.key)
        state = Schema.decodeUnknownSync(State)(await client(session).state({ sessionID: session.id }, options(session)))
        apply(session, state)
      } while (dirty.has(session.key))
      return state
    })().catch(error => { fail(session, error); throw error }).finally(() => requests.delete(session.key))
    requests.set(session.key, request)
    return request
  }
  const send = async (session: MountedSession, action: Action) => {
    try {
      const value = await client(session).command({ sessionID: session.id, requestID: crypto.randomUUID(), action }, options(session))
      if (["tabs.open", "tabs.close", "tabs.focus", "navigate", "back", "forward", "reload", "dialog"].includes(action.type)) await refresh(session)
      return value
    } catch (error) { fail(session, error); throw error }
  }
  const execute = (session: MountedSession, action: Action) => {
    if (action.type === "dialog" || action.type === "stop") return send(session, action)
    let input = inputs.get(session.key)
    if (!input) { input = createInputQueue(action => send(session, action)); inputs.set(session.key, input) }
    return input(action)
  }
  const open = async (session: MountedSession) => {
    ctx.layout.open(`${ctx.id}:main`, session)
    const value = await execute(session, { type: "tabs.open" }) as { id: string }
    ctx.layout.open(`${ctx.id}:${value.id}`, session, { tab: "select" })
  }
  createEffect(() => {
    const session = ctx.sessions.current()
    if (!session || !session.server.connected) return
    void refresh(session).catch(() => undefined) // refresh stores the failure for the panel.
    const unsubscribe = client(session).events.on("changed", event => {
      if (event.data.sessionID === session.id) void refresh(session).catch(() => undefined)
    }, { signal: ctx.signal })
    onCleanup(unsubscribe)
  })
  return { get, fail, refresh, execute, open, client, options, apply }
}
