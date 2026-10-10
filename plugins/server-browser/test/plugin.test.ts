import { test } from "node:test"
import assert from "node:assert/strict"
import os from "node:os"
import plugin from "../src/index.js"
import type { Info } from "@opencode/plugin/tool"

async function setup(automation?: boolean) {
  const tools: Info[] = []
  let state!: (input: { sessionID: string }) => Promise<unknown>
  const context = {
    options: { ...automation === undefined ? {} : { automation }, ...process.env.SERVER_BROWSER_CHROMIUM ? { executablePath: process.env.SERVER_BROWSER_CHROMIUM } : {} },
    location: { directory: os.tmpdir() },
    session: { get: async () => ({ location: { directory: os.tmpdir() } }) },
    rpc: { register: async (_definition: unknown, handlers: { state: typeof state }) => { state = handlers.state; return { events: { emit: async () => {} } } } },
    tool: { transform: async (edit: (editor: { namespace: () => void; add: (tool: Info) => void }) => void) => edit({ namespace: () => {}, add: tool => tools.push(tool) }) },
    event: { subscribe: async function* ({ signal }: { signal: AbortSignal }) { await new Promise(resolve => signal.addEventListener("abort", resolve, { once: true })) } },
  } as unknown as Parameters<typeof plugin.setup>[0]
  const cleanup = await plugin.setup(context)
  return { tools, state: () => state({ sessionID: "fixture-session" }), cleanup }
}

test("model automation requires explicit configuration; manual RPC remains available", async () => {
  const value = await setup()
  try {
    assert.equal(value.tools.length, 0)
    assert.deepEqual(await value.state(), { version: 1, tabs: [], focusedTabID: null })
  } finally { await value.cleanup?.() }
})

test("authorized plugin tools invoke real Chromium and return the shared session tabs", async () => {
  const value = await setup(true)
  try {
    const tool = (name: string) => value.tools.find(tool => `${tool.options?.namespace}.${tool.name}` === name)!
    assert.equal(value.tools.length, 29)
    const context = { sessionID: "fixture-session", signal: new AbortController().signal } as Parameters<Info["execute"]>[1]
    const opened = await tool("server_browser.tabs.open").execute({}, context)
    const tab = opened.output as { id: string }
    assert.match(tab.id, /^tab_/)
    const listed = await tool("server_browser.tabs.list").execute({}, context)
    assert.equal((listed.output as { tabs: { id: string }[] }).tabs[0].id, tab.id)
    await tool("server_browser.tabs.close").execute({ tabID: tab.id }, context)
    assert.deepEqual(await value.state(), { version: 1, tabs: [], focusedTabID: null })
  } finally { await value.cleanup?.() }
})
