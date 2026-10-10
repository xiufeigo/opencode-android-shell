import { randomUUID, createHash } from "node:crypto"
import fs from "node:fs/promises"
import path from "node:path"
import type { CDPSession, Dialog, Locator, Page, Request } from "playwright"
import type { Action, Tab } from "./rpc.js"

const MAX_ENTRIES = 500
const MAX_BODY = 20_000
const MAX_FILES = 64
const MAX_IMAGE_BYTES = 5 * 1024 * 1024
const MAX_CAPTURE_PIXELS = 16_000_000
type Artifact = { id: string; name: string; path: string; mime: string; bytes: number; state: "pending" | "completed" | "failed"; error?: string }
type Traffic = { id: string; url: string; method: string; resourceType: string; timestampMs: number; status?: number; failure?: string; request: Request }

export function normalizeURL(value: string) {
  const source = value.trim() || "about:blank"
  if (/^(?:[\\/.]|[a-z]:[\\/])/i.test(source)) throw new Error("Use an HTTP/HTTPS URL; filesystem paths are not browser URLs.")
  const local = /^(localhost|127(?:\.\d{1,3}){3}|\[::1\])(?::\d+)?(?:[/?#]|$)/i.test(source)
  const url = new URL(source === "about:blank" || /^[a-z][a-z\d+.-]*:/i.test(source) && !local ? source : `${local ? "http" : "https"}://${source}`)
  if (url.href !== "about:blank" && !["http:", "https:"].includes(url.protocol) || url.username || url.password) {
    throw new Error("Use HTTP/HTTPS or about:blank without embedded credentials. localhost refers to the server.")
  }
  return url.href
}

export class BrowserPage {
  readonly id = `tab_${randomUUID()}`
  readonly files = new Map<string, Artifact>()
  private refs = new Map<string, number>()
  private refSequence = 0
  private console: { level: string; text: string; timestampMs: number }[] = []
  private traffic = new Map<Request, Traffic>()
  private dialog?: Dialog
  private loading = false
  private title = ""
  private history = { back: false, forward: false }
  private dropped = { console: 0, network: 0 }
  private tasks = new Set<Promise<void>>()
  private cleanup?: Promise<void>

  constructor(readonly page: Page, readonly cdp: CDPSession, private artifacts: string, private changed: () => void) {
    this.artifacts = path.join(artifacts, this.id)
    page.setDefaultTimeout(10_000)
    page.setDefaultNavigationTimeout(30_000)
    page.on("framenavigated", frame => {
      if (frame !== page.mainFrame()) return
      this.refs.clear()
      this.traffic.clear()
      this.console = []
      this.dropped = { console: 0, network: 0 }
      this.changed()
    })
    page.on("domcontentloaded", () => { this.loading = false; this.changed() })
    page.on("dialog", dialog => { this.dialog = dialog; this.changed() })
    page.on("console", message => this.log(message.type(), message.text()))
    page.on("pageerror", error => this.log("error", error.message))
    page.on("request", request => {
      this.traffic.set(request, { id: randomUUID(), url: request.url(), method: request.method(), resourceType: request.resourceType(), timestampMs: Date.now(), request })
      if (this.traffic.size > MAX_ENTRIES) { this.traffic.delete(this.traffic.keys().next().value!); this.dropped.network++ }
    })
    page.on("response", response => { const value = this.traffic.get(response.request()); if (value) value.status = response.status() })
    page.on("requestfailed", request => { const value = this.traffic.get(request); if (value) value.failure = request.failure()?.errorText })
    page.on("download", download => {
      const name = path.basename(download.suggestedFilename())
      const file: Artifact = { id: randomUUID(), name, path: path.join(this.artifacts, `${randomUUID()}-${name}`), mime: "application/octet-stream", bytes: 0, state: "pending" }
      const task = (async () => {
        await this.retain(file)
        await fs.mkdir(this.artifacts, { recursive: true })
        await download.saveAs(file.path)
        file.bytes = (await fs.stat(file.path)).size
        file.state = "completed"
      })().catch(async error => { await download.cancel(); file.state = "failed"; file.error = String(error); console.error("[server-browser] download failed", error) }).finally(() => { this.tasks.delete(task); this.changed() })
      this.tasks.add(task)
    })
  }

  private async retain(file: Artifact) {
    if (this.files.size >= MAX_FILES) {
      const oldest = [...this.files.values()].find(value => value.state !== "pending")
      if (!oldest) throw new Error(`This tab already has ${MAX_FILES} active downloads.`)
      this.files.delete(oldest.id)
      await fs.rm(oldest.path, { force: true })
    }
    this.files.set(file.id, file)
  }

  async settled() { await Promise.all([...this.tasks]) }

  dispose() {
    this.cleanup ??= (async () => {
      await this.settled()
      const relative = path.relative(path.resolve(this.artifacts, ".."), path.resolve(this.artifacts))
      if (relative !== this.id) throw new Error("Browser tab cleanup escaped its owned artifact directory.")
      await fs.rm(this.artifacts, { recursive: true, force: true })
      this.files.clear()
    })()
    return this.cleanup
  }

  private log(level: string, text: string) {
    this.console.push({ level, text: text.slice(0, 100_000), timestampMs: Date.now() })
    if (this.console.length > MAX_ENTRIES) { this.console.shift(); this.dropped.console++ }
  }

  async info(): Promise<Tab> {
    const history = await this.cdp.send("Page.getNavigationHistory")
    this.history = { back: history.currentIndex > 0, forward: history.currentIndex < history.entries.length - 1 }
    if (!this.dialog) this.title = (await this.page.title()).slice(0, 16_384)
    const size = this.page.viewportSize()!
    const dialog = this.dialog ? { type: this.dialog.type(), message: this.dialog.message(), defaultValue: this.dialog.defaultValue() } : null
    return { id: this.id, url: this.page.url(), title: this.title, loading: this.loading, canGoBack: this.history.back, canGoForward: this.history.forward, ...size, dialog }
  }

  async frame(previous?: string) {
    const capture = await this.cdp.send("Page.captureScreenshot", { format: "jpeg", quality: 65, captureBeyondViewport: false })
    const bytes = Buffer.from(capture.data, "base64")
    const hash = createHash("sha256").update(bytes).digest("hex")
    return { hash, image: previous === hash ? null : bytes.toString("base64"), tab: await this.info() }
  }

  private async element(ref: string): Promise<Locator> {
    const backendNodeId = this.refs.get(ref.replace(/^@/, ""))
    if (!backendNodeId) throw new Error("Element ref expired or belongs to another tab. Take a fresh snapshot before acting.")
    const { object } = await this.cdp.send("DOM.resolveNode", { backendNodeId })
    if (!object.objectId) throw new Error("Element is no longer attached. Take a fresh snapshot.")
    try {
      const result = await this.cdp.send("Runtime.callFunctionOn", {
        objectId: object.objectId,
        functionDeclaration: "function(ref) { if (!this.isConnected) throw new Error('Element detached'); if (this.nodeType !== 1) throw new Error('Ref names a text node; select an element ref'); this.setAttribute('data-opencode-server-ref', ref); }",
        arguments: [{ value: ref }],
      })
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text)
    } finally { await this.cdp.send("Runtime.releaseObject", { objectId: object.objectId }) }
    return this.page.locator(`[data-opencode-server-ref="${ref}"]`)
  }

  private async snapshot(query?: string) {
    this.refs.clear()
    const { nodes } = await this.cdp.send("Accessibility.getFullAXTree")
    const lines: string[] = []
    for (const node of nodes) {
      if (node.ignored) continue
      const role = String(node.role?.value ?? "")
      const name = String(node.name?.value ?? "")
      const value = node.value?.value
      const ref = node.backendDOMNodeId ? `e${++this.refSequence}` : undefined
      if (ref) this.refs.set(ref, node.backendDOMNodeId!)
      const line = `- ${role} ${JSON.stringify(name)}${value === undefined ? "" : ` value=${JSON.stringify(value)}`}${ref ? ` [ref=${ref}]` : ""}`
      if (!query || line.toLowerCase().includes(query.toLowerCase())) lines.push(line)
    }
    const content = lines.join("\n")
    return { content: content.slice(0, 100_000), truncated: content.length > 100_000 }
  }

  private async navigate(run: () => Promise<unknown>) {
    this.loading = true
    this.changed()
    try { await run() } finally { this.loading = false; this.changed() }
    return this.info()
  }

  async execute(action: Exclude<Action, { type: "tabs.list" | "tabs.open" | "tabs.focus" | "tabs.close" }>, directory: string): Promise<unknown> {
    switch (action.type) {
      case "navigate": return this.navigate(() => this.page.goto(normalizeURL(action.url), { waitUntil: "domcontentloaded" }))
      case "back": return this.navigate(() => this.page.goBack({ waitUntil: "domcontentloaded" }))
      case "forward": return this.navigate(() => this.page.goForward({ waitUntil: "domcontentloaded" }))
      case "reload": return this.navigate(() => this.page.reload({ waitUntil: "domcontentloaded" }))
      case "stop": await this.cdp.send("Page.stopLoading"); this.loading = false; break
      case "snapshot": return { tab: await this.info(), ...await this.snapshot() }
      case "find": return { tab: await this.info(), ...await this.snapshot(action.text) }
      case "evaluate": return { tab: await this.info(), value: await this.page.evaluate(action.script) ?? null }
      case "click": await (await this.element(action.ref)).click({ button: action.button, clickCount: action.count }); break
      case "hover": await (await this.element(action.ref)).hover(); break
      case "drag": await (await this.element(action.from)).dragTo(await this.element(action.to)); break
      case "fill": await (await this.element(action.ref)).fill(action.text); break
      case "select": await (await this.element(action.ref)).selectOption([...action.values]); break
      case "check": await (await this.element(action.ref)).setChecked(action.checked); break
      case "press": await this.page.keyboard.press(action.key); break
      case "insert": await this.page.keyboard.insertText(action.text); break
      case "scroll": await this.page.mouse.wheel(action.deltaX ?? 0, action.deltaY); break
      case "pointer": {
        await this.page.mouse.move(action.x, action.y)
        if (action.phase === "down") await this.page.mouse.down({ button: action.button ?? "left" })
        if (action.phase === "up") await this.page.mouse.up({ button: action.button ?? "left" })
        break
      }
      case "viewport": await this.page.setViewportSize({ width: action.width, height: action.height }); break
      case "wait": {
        const timeout = action.timeoutMs ?? 10_000
        if (action.condition === "load") await this.page.waitForLoadState("load", { timeout })
        else {
          if (!action.text) throw new Error("Text is required for a text wait.")
          await this.page.waitForFunction(({ text, gone }) => document.body.innerText.includes(text) !== gone, { text: action.text, gone: action.condition === "textGone" }, { timeout })
        }
        break
      }
      case "screenshot": {
        const size = action.fullPage ? await this.page.evaluate(() => ({ width: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight })) : this.page.viewportSize()!
        if (size.width * size.height > MAX_CAPTURE_PIXELS) throw new Error("Capture exceeds 16 million pixels. Capture the viewport or an element instead.")
        await fs.mkdir(this.artifacts, { recursive: true })
        const id = randomUUID()
        const filename = path.join(this.artifacts, `${id}.png`)
        const bytes = action.ref
          ? await (await this.element(action.ref)).screenshot({ path: filename })
          : await this.page.screenshot({ path: filename, fullPage: action.fullPage })
        if (bytes.length > MAX_IMAGE_BYTES) { await fs.rm(filename); throw new Error("Screenshot exceeds the 5 MiB image attachment limit.") }
        const file: Artifact = { id, name: "screenshot.png", path: filename, mime: "image/png", bytes: bytes.length, state: "completed" }
        await this.retain(file)
        return { tab: await this.info(), files: [file] }
      }
      case "dialog": {
        const dialog = this.dialog
        const value = dialog ? { type: dialog.type(), message: dialog.message(), defaultValue: dialog.defaultValue() } : null
        if (dialog && action.action !== "get") {
          if (action.action === "accept") await dialog.accept(action.promptText)
          else await dialog.dismiss()
          this.dialog = undefined
        }
        return { dialog: value }
      }
      case "files.upload": {
        const root = await fs.realpath(directory)
        const files = await Promise.all(action.paths.map(async value => {
          const filename = await fs.realpath(path.resolve(directory, value))
          const relative = path.relative(root, filename)
          if (relative.startsWith(`..${path.sep}`) || relative === ".." || path.isAbsolute(relative)) throw new Error("Browser uploads must be files within this session's workspace, including symlink targets.")
          return filename
        }))
        await (await this.element(action.ref)).setInputFiles(files)
        break
      }
      case "files.list": return { files: [...this.files.values()] }
      case "files.get": {
        const file = this.files.get(action.fileID)
        if (!file || file.state !== "completed") throw new Error(file?.error ?? "File is not a completed artifact owned by this tab.")
        return { files: [file] }
      }
      case "console": return { messages: this.console.slice(-(action.limit ?? 100)), dropped: this.dropped.console }
      case "network.list": return {
        requests: [...this.traffic.values()].filter(value => !action.urlContains || value.url.includes(action.urlContains)).slice(-(action.limit ?? 100)).map(({ request, ...value }) => value),
        dropped: this.dropped.network,
      }
      case "network.get": {
        const capture = [...this.traffic.values()].find(value => value.id === action.requestID)
        if (!capture) throw new Error("Request expired or belongs to another tab.")
        const { request, ...value } = capture
        const response = await request.response()
        const type = response?.headers()["content-type"] ?? ""
        const length = Number(response?.headers()["content-length"] ?? NaN)
        const responseBody = !action.includeBody ? undefined : !response ? { unavailable: "Request has no response" }
          : !/text|json|javascript|xml|svg/.test(type) ? { unavailable: "Binary response" }
          : !Number.isFinite(length) || length > MAX_BODY || response.headers()["content-encoding"] ? { unavailable: "Body size is unknown, compressed, or exceeds 20000 bytes; not materialized." }
          : (await response.text()).slice(0, MAX_BODY)
        return { request: value, requestHeaders: await request.allHeaders(), responseHeaders: response ? await response.allHeaders() : {}, requestBody: action.includeBody ? request.postData()?.slice(0, MAX_BODY) ?? null : null, responseBody: responseBody ?? null }
      }
    }
    return this.info()
  }
}
