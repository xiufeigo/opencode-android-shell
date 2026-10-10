import path from "node:path"
import os from "node:os"
import fs from "node:fs/promises"
import { createHash } from "node:crypto"
import type { Browser, BrowserContext, Page } from "playwright"
import { BrowserPage, normalizeURL } from "./page.js"
import type { Action, State } from "./rpc.js"

type SessionBrowser = {
  context: BrowserContext; tabs: Map<string, BrowserPage>; pages: Set<BrowserPage>; focused: string | null;
  queue: Promise<unknown>;
  requests: Map<string, { fingerprint: string; result: Promise<unknown> }>;
}

export class BrowserRuntime {
  private browser?: Promise<Browser>
  private sessions = new Map<string, Promise<SessionBrowser>>()
  private disposed = false
  private removed = new Set<string>()
  private artifacts(sessionID: string) {
    return path.join(os.tmpdir(), "opencode-server-browser", createHash("sha256").update(this.directory).digest("hex"), createHash("sha256").update(sessionID).digest("hex"))
  }
  private async cleanArtifacts(sessionID: string) {
    const base = path.resolve(os.tmpdir(), "opencode-server-browser")
    const directory = path.resolve(this.artifacts(sessionID))
    const relative = path.relative(base, directory)
    if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) throw new Error("Browser artifact cleanup escaped its owned directory.")
    await fs.rm(directory, { recursive: true, force: true })
  }

  constructor(private launch: () => Promise<Browser>, private directory: string, private changed: (sessionID: string) => void) {}

  private async create(sessionID: string) {
    this.browser ??= this.launch()
    const browser = await this.browser
    if (this.disposed) throw new Error("Browser plugin is shutting down.")
    const context = await browser.newContext({ viewport: { width: 1024, height: 768 }, acceptDownloads: true })
    const state: SessionBrowser = { context, tabs: new Map(), pages: new Set(), focused: null, queue: Promise.resolve(), requests: new Map() }
    context.on("page", page => {
      void this.addPage(sessionID, state, page).catch(error => console.error("[server-browser] failed to register page", error))
    })
    return state
  }

  private async addPage(sessionID: string, state: SessionBrowser, page: Page) {
    // newPage and popup callbacks can both discover the same page.
    const existing = [...state.tabs.values()].find(tab => tab.page === page)
    if (existing) return existing
    const cdp = await state.context.newCDPSession(page)
    const found = [...state.tabs.values()].find(tab => tab.page === page)
    if (found) { await cdp.detach(); return found }
    const artifactDirectory = this.artifacts(sessionID)
    const tab = new BrowserPage(page, cdp, artifactDirectory, () => this.changed(sessionID))
    state.tabs.set(tab.id, tab)
    state.pages.add(tab)
    state.focused = tab.id
    page.on("close", () => {
      state.tabs.delete(tab.id)
      if (state.focused === tab.id) state.focused = [...state.tabs.keys()].at(-1) ?? null
      this.changed(sessionID)
      void tab.dispose().then(() => state.pages.delete(tab), error => console.error("[server-browser] tab artifact cleanup failed", error))
    })
    this.changed(sessionID)
    return tab
  }

  private async session(sessionID: string) {
    if (this.disposed) throw new Error("Browser plugin is shutting down.")
    if (this.removed.has(sessionID)) throw new Error("Browser session was deleted or moved.")
    let value = this.sessions.get(sessionID)
    if (!value) {
      value = this.create(sessionID)
      this.sessions.set(sessionID, value)
    }
    return value
  }

  async state(sessionID: string): Promise<State> {
    const value = this.sessions.get(sessionID)
    if (!value) return { version: 1, tabs: [], focusedTabID: null }
    const state = await value
    return { version: 1, tabs: await Promise.all([...state.tabs.values()].map(tab => tab.info())), focusedTabID: state.focused }
  }

  private async target(sessionID: string, tabID: string) {
    const state = await this.sessions.get(sessionID)
    const tab = state?.tabs.get(tabID)
    if (!tab) throw new Error("Browser tab was closed or belongs to another session. Refresh the tab list.")
    return tab
  }

  async frame(sessionID: string, tabID: string, previous?: string) {
    return (await this.target(sessionID, tabID)).frame(previous)
  }

  async command(sessionID: string, requestID: string, action: Action, signal?: AbortSignal) {
    if (action.type === "tabs.list") return this.state(sessionID)
    // A dialog or stalled navigation must be interruptible while the queued
    // operation is waiting for the page, otherwise the queue deadlocks.
    if (action.type === "dialog" || action.type === "stop") {
      signal?.throwIfAborted()
      return (await this.target(sessionID, action.tabID)).execute(action, this.directory)
    }
    const state = await this.session(sessionID)
    const fingerprint = JSON.stringify(action)
    const existing = state.requests.get(requestID)
    if (existing) {
      if (existing.fingerprint !== fingerprint) throw new Error("Request ID was reused for a different browser action.")
      return existing.result
    }
    // A queue rejection is consumed only by the next queue link. The original
    // promise still rejects to its caller; a failed click is never replayed.
    const result = state.queue.then(async () => {
      signal?.throwIfAborted()
      if (this.disposed) throw new Error("Browser plugin is shutting down.")
      if (action.type === "tabs.open") {
        const url = normalizeURL(action.url ?? "about:blank")
        const tab = await this.addPage(sessionID, state, await state.context.newPage())
        if (url !== "about:blank") await tab.execute({ type: "navigate", tabID: tab.id, url }, this.directory)
        return tab.info()
      }
      const tab = await this.target(sessionID, action.tabID)
      if (action.type === "tabs.close") { await tab.page.close(); await tab.dispose(); return this.state(sessionID) }
      if (action.type === "tabs.focus") { state.focused = tab.id; return tab.info() }
      return tab.execute(action, this.directory)
    }).finally(() => {
      if (["tabs.open", "tabs.close", "tabs.focus", "navigate", "back", "forward", "reload"].includes(action.type)) this.changed(sessionID)
    })
    state.queue = result.then(() => undefined, () => undefined)
    state.requests.set(requestID, { fingerprint, result })
    if (state.requests.size > 256) state.requests.delete(state.requests.keys().next().value!)
    return result
  }

  async remove(sessionID: string) {
    this.removed.add(sessionID)
    const value = this.sessions.get(sessionID)
    if (!value) return
    this.sessions.delete(sessionID)
    const state = await value
    const pages = [...state.pages]
    await state.context.close()
    await Promise.all(pages.map(page => page.dispose()))
    await this.cleanArtifacts(sessionID)
  }

  async dispose() {
    this.disposed = true
    for (const sessionID of [...this.sessions.keys()]) await this.remove(sessionID)
    if (this.browser) await (await this.browser).close()
    this.sessions.clear()
  }
}
