import { expect, test } from "bun:test"
import { createRoot } from "solid-js"
import type { Context, MountedSession } from "@opencode/plugin/gui"
import type { Action, State, Tab } from "./rpc"
import { createBrowserModel } from "./model"

type Call = { method: "open" | "close" | "state" | "stored"; key: string; options?: unknown }
type Client = {
  state(): Promise<State>
  command(input: { action: Action }): Promise<unknown>
  events: { on(): () => void }
}

const tab = (id: string, url = "about:blank"): Tab => ({
  id, url, title: "", loading: false, canGoBack: false, canGoForward: false, width: 1280, height: 800, dialog: null,
})

function session(client: Client, key = "server\nsession") {
  return {
    key, id: "session", directory: "/workspace", server: { connected: true, client: { rpc: () => client } },
  } as unknown as MountedSession
}

function context(calls: Call[], narrow = false) {
  return {
    id: "server-browser",
    signal: new AbortController().signal,
    sessions: { current: () => undefined, list: () => [] },
    layout: {
      narrow: () => narrow,
      stored: () => ["stale"],
      state: () => "closed",
      open: (key: string, _session: unknown, options?: unknown) => calls.push({ method: "open", key, options }),
      close: (key: string) => calls.push({ method: "close", key }),
    },
  } as unknown as Context
}

function model(client: Client, calls: Call[], narrow = false) {
  return createRoot(() => createBrowserModel(context(calls, narrow)))
}

test("refresh applies server tabs and reconciles the stored strip", async () => {
  const calls: Call[] = []
  const client: Client = { state: async () => ({ version: 1, tabs: [tab("a", "https://example.com")], focusedTabID: "a" }), command: async () => undefined, events: { on: () => () => {} } }
  const instance = model(client, calls)
  const mounted = session(client)
  const state = await instance.refresh(mounted)
  expect(state.tabs.map(item => item.id)).toEqual(["a"])
  expect(instance.get(mounted).state.tabs[0]?.url).toBe("https://example.com")
  expect(calls).toEqual([
    { method: "close", key: "server-browser:stale", options: undefined },
    { method: "open", key: "server-browser:a", options: { tab: "append", background: true } },
  ])
})

test("a command refreshes the state it changes", async () => {
  const calls: Call[] = []
  let tabs = [tab("a")]
  const client: Client = {
    state: async () => ({ version: 1, tabs, focusedTabID: tabs.at(-1)?.id ?? null }),
    command: async input => { if (input.action.type === "tabs.open") tabs = [...tabs, tab("b")]; return { id: "b" } }, events: { on: () => () => {} },
  }
  const instance = model(client, calls)
  const mounted = session(client)
  await instance.execute(mounted, { type: "tabs.open" })
  expect(instance.get(mounted).state.tabs.map(item => item.id)).toEqual(["a", "b"])
})

test("open shows the panel before the server answers, then selects the new tab", async () => {
  for (const narrow of [true, false]) {
    const calls: Call[] = []
    const client: Client = {
      state: async () => ({ version: 1, tabs: [tab("a")], focusedTabID: "a" }),
      command: async () => ({ id: "a" }), events: { on: () => () => {} },
    }
    const instance = model(client, calls, narrow)
    await instance.open(session(client))
    const opened = calls.filter(call => call.method === "open")
    expect(opened[0]?.key).toBe("server-browser:main")
    expect(opened.at(-1)).toEqual({ method: "open", key: "server-browser:a", options: { tab: "select" } })
  }
})

test("open opens the panel even when the server has no browser plugin", async () => {
  const calls: Call[] = []
  const client: Client = {
    state: async () => { throw { type: "rpc.not_found", message: "RPC method not found" } },
    command: async () => { throw { type: "rpc.not_found", message: "RPC method not found" } },
    events: { on: () => () => {} },
  }
  const instance = model(client, calls)
  const mounted = session(client)
  await instance.open(mounted).catch(() => undefined)
  expect(calls.filter(call => call.method === "open").map(call => call.key)).toEqual(["server-browser:main"])
  expect(instance.get(mounted).error).toBe("RPC method not found")
})

test("an RPC failure is kept for the panel", async () => {
  const calls: Call[] = []
  const client: Client = {
    state: async () => ({ version: 1, tabs: [], focusedTabID: null }),
    command: async () => { throw { type: "browser", message: "Chromium is not installed" } },
    events: { on: () => () => {} },
  }
  const instance = model(client, calls)
  const mounted = session(client)
  await instance.execute(mounted, { type: "tabs.open" }).catch(() => undefined)
  expect(instance.get(mounted).error).toBe("Chromium is not installed")
})
