import { createEffect, createSignal, on, onCleanup, onMount, Show } from "solid-js"
import type { Bridge, BridgeMessage, BridgeLayout } from "@opencode/gui-extensions/sdk/bridge"
import type { EmbedProps } from "@opencode/gui-extensions/sdk"

type Pairing = { url: string; token: string }
type NativeRequest = <T = unknown>(method: string, payload?: Record<string, unknown>) => Promise<T>
const IPC = "browser.pane"
const PROTOCOL = "opencode-browser-v1"

export function createAndroidBrowser(native: NativeRequest): Bridge {
  const [status, setStatus] = createSignal("Browser service is not configured")
  const listeners = new Set<(value: BridgeMessage) => void>()
  const surfaces = new Map<string, (bytes: Uint8Array | string) => void>()
  const keyboards = new Map<string, () => void>()
  const pending = new Map<string, { resolve(value: unknown): void; reject(error: Error): void; cleanup(): void }>()
  let socket: WebSocket | undefined
  let configured = false
  let opening: Promise<void> | undefined
  let configuration: Promise<boolean> | undefined
  let pairing: Pairing | null = null
  let retry: ReturnType<typeof setTimeout> | undefined
  let retries = 0
  let stopped = false
  let lifecycle = 0
  let endpoints: Parameters<Bridge["configure"]>[0] = []
  let count = 0
  const publish = (value: BridgeMessage) => listeners.forEach(listener => listener(value))
  const fail = (message: string) => {
    configured = false
    setStatus(message)
    publish({ type: "available", ipc: IPC, available: false })
    pending.forEach(request => { request.cleanup(); request.reject(new Error(message)) })
    pending.clear()
    surfaces.forEach(surface => surface(message))
  }
  const reconnect = () => {
    if (stopped || !pairing || retry) return
    retry = setTimeout(() => { retry = undefined; void boot() }, Math.min(30_000, 1_000 * 2 ** Math.min(retries++, 5)))
  }
  function request<T>(method: string, input: unknown, signal?: AbortSignal): Promise<T> {
    if (socket?.readyState !== WebSocket.OPEN) return Promise.reject(new Error("Browser service is disconnected"))
    if (signal?.aborted) return Promise.reject(new Error("Browser request aborted"))
    const id = String(++count)
    return new Promise((resolve, reject) => {
      const cancel = (error: Error) => {
        pending.delete(id); cleanup()
        try { if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ id: `cancel-${id}`, method: "cancel", input: id })) }
        catch (transportError) { console.error("Browser cancellation transport", transportError) }
        reject(error)
      }
      const abort = () => cancel(new Error("Browser request aborted"))
      const timer = setTimeout(() => cancel(new Error(`Browser ${method} timed out; cancellation requested`)), 120_000)
      const cleanup = () => { clearTimeout(timer); signal?.removeEventListener("abort", abort) }
      pending.set(id, { resolve: value => resolve(value as T), reject, cleanup })
      signal?.addEventListener("abort", abort, { once: true })
      try { socket!.send(JSON.stringify({ id, method, input })) }
      catch (error) { pending.delete(id); cleanup(); reject(error) }
    })
  }
  async function connect(config: Pairing | null, token: number) {
    if (stopped || token !== lifecycle) return
    clearTimeout(retry); retry = undefined; pairing = config
    if (socket) { socket.onclose = socket.onerror = socket.onmessage = socket.onopen = null; socket.close(); socket = undefined }
    fail(config ? "Connecting to browser service…" : "Browser service is not configured")
    if (!config) return
    const url = new URL(config.url)
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:"
    url.pathname = `${url.pathname.replace(/\/$/, "")}/bridge`
    const connected = new WebSocket(url, [PROTOCOL, config.token])
    connected.binaryType = "arraybuffer"
    socket = connected
    let ready: () => void = () => {}
    connected.onmessage = event => {
      if (socket !== connected) return
      if (event.data instanceof ArrayBuffer) {
        const bytes = new Uint8Array(event.data)
        const size = new DataView(event.data).getUint16(0)
        const header = JSON.parse(new TextDecoder().decode(bytes.subarray(2, 2 + size))) as { id: string }
        surfaces.get(header.id)?.(bytes.subarray(2 + size))
        return
      }
      const message = JSON.parse(event.data)
      if (message.id && pending.has(message.id)) {
        const call = pending.get(message.id)!
        pending.delete(message.id); call.cleanup()
        if (message.error) call.reject(new Error(message.error))
        else call.resolve(message.value)
      } else if (message.type === "surfaceError") surfaces.get(message.id)?.(message.error)
      else if (message.type === "keyboard") keyboards.get(message.id)?.()
      else if (message.type === "available") { if (message.available) ready() }
      else publish(message)
    }
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => { connected.close(); reject(new Error("Browser connection timed out")) }, 15_000)
      ready = () => { clearTimeout(timer); resolve() }
      connected.onopen = () => {}
      connected.onerror = () => { if (socket !== connected) return; clearTimeout(timer); reject(new Error("Cannot connect to browser service; check its URL and pairing token")) }
      connected.onclose = () => { if (socket !== connected) return; clearTimeout(timer); const error = "Browser service disconnected"; fail(error); reconnect(); reject(new Error(error)) }
    })
    if (stopped || token !== lifecycle || socket !== connected || connected.readyState !== WebSocket.OPEN) return
    await request("configure", endpoints)
    if (stopped || token !== lifecycle || socket !== connected || connected.readyState !== WebSocket.OPEN) return
    configured = true
    retries = 0
    setStatus("Browser service connected")
    publish({ type: "available", ipc: IPC, available: true })
  }
  const boot = () => {
    if (stopped) return Promise.resolve()
    if (configured && socket?.readyState === WebSocket.OPEN) return Promise.resolve()
    if (opening) return opening
    const token = ++lifecycle
    const attempt = native<Pairing | null>("browserConfig").then(config => connect(config, token)).catch(error => {
      if (stopped || token !== lifecycle) return
      fail(error.message); console.error(error); reconnect()
    })
    opening = attempt
    void attempt.finally(() => { if (opening === attempt) opening = undefined })
    return attempt
  }
  const forward = (method: string, input: unknown) => void request(method, input).catch(error => { setStatus(error.message); console.error(error) })
  window.addEventListener("pagehide", () => {
    stopped = true; lifecycle++; opening = undefined; clearTimeout(retry); retry = undefined
    if (socket) { socket.onclose = null; socket.close() }
    fail("Browser page closed")
  })
  window.addEventListener("pageshow", () => { if (!stopped) return; stopped = false; void boot() })

  function Surface(props: EmbedProps) {
    const [image, setImage] = createSignal<string>()
    const [error, setError] = createSignal<string>()
    const [active, setActive] = createSignal(document.visibilityState === "visible")
    let root!: HTMLDivElement
    let keyboard!: HTMLTextAreaElement
    let imageURL: string | undefined
    let pointer: { x: number; y: number; scrolled: boolean } | undefined
    const visible = () => props.visible && !props.frozen && active()
    const input = (event: Record<string, unknown>) => { if (visible() && props.id) forward("input", { id: props.id, event }) }
    const layout = () => {
      if (!props.id) return
      const rect = root.getBoundingClientRect()
      forward("embed", { id: props.id, layout: { visible: visible(), bounds: { x: 0, y: 0, width: Math.max(1, rect.width), height: Math.max(1, rect.height) } } })
    }
    const point = (event: PointerEvent) => { const rect = root.getBoundingClientRect(); return { x: Math.round(event.clientX - rect.left), y: Math.round(event.clientY - rect.top) } }
    createEffect(on(() => props.id, id => {
      setImage(undefined)
      setError(undefined)
      if (!id) return
      const receive = (bytes: Uint8Array | string) => {
        if (typeof bytes === "string") { setImage(undefined); setError(bytes); return }
        const next = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: "image/jpeg" }))
        const old = imageURL
        imageURL = next; setImage(next); setError(undefined)
        if (old) URL.revokeObjectURL(old)
      }
      const focus = () => keyboard.focus({ preventScroll: true })
      surfaces.set(id, receive)
      keyboards.set(id, focus)
      if (root) layout()
      onCleanup(() => {
        // An older pane can finish unmounting after its replacement binds this ID.
        if (surfaces.get(id) !== receive) return
        surfaces.delete(id)
        if (keyboards.get(id) === focus) keyboards.delete(id)
        if (socket?.readyState === WebSocket.OPEN) forward("embed", { id })
      })
    }))
    onMount(() => {
      const visibility = () => setActive(document.visibilityState === "visible")
      document.addEventListener("visibilitychange", visibility)
      const observer = new ResizeObserver(layout)
      observer.observe(root)
      onCleanup(() => { observer.disconnect(); document.removeEventListener("visibilitychange", visibility) })
      layout()
    })
    createEffect(() => { visible(); props.id; if (root) layout() })
    onCleanup(() => { if (imageURL) URL.revokeObjectURL(imageURL) })
    const key = (event: KeyboardEvent, type: "keyDown" | "keyUp") => {
      if (event.isComposing || (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) || event.key === "Unidentified") return
      event.preventDefault()
      input({ type, key: event.key, code: event.code, keyCode: event.keyCode, modifiers: (event.altKey ? 1 : 0) | (event.ctrlKey ? 2 : 0) | (event.metaKey ? 4 : 0) | (event.shiftKey ? 8 : 0) })
    }
    return <div ref={root} class={props.class} data-android-browser-surface={props.id} data-android-browser-visible={visible()}
      style={{ position: "relative", overflow: "hidden", "touch-action": "none", "min-height": "1px", "border-radius": `${props.radius ?? 0}px`, background: props.background }}
      onPointerDown={event => {
        if (!visible()) return
        root.setPointerCapture(event.pointerId)
        pointer = { ...point(event), scrolled: false }
        if (event.pointerType === "mouse") input({ type: "mouseDown", ...point(event), button: event.button === 2 ? "right" : "left", clickCount: 1 })
      }}
      onPointerMove={event => {
        const p = point(event)
        if (event.pointerType === "mouse") { input({ type: "mouseMove", ...p }); return }
        if (!pointer) return
        const dx = pointer.x - p.x, dy = pointer.y - p.y
        if (pointer.scrolled || Math.abs(dx) + Math.abs(dy) > 6) {
          input({ type: "mouseWheel", ...p, deltaX: -dx, deltaY: -dy, canScroll: true })
          pointer = { ...p, scrolled: true }
        }
      }}
      onPointerUp={event => {
        if (!pointer) return
        const p = point(event)
        if (event.pointerType === "mouse" || !pointer.scrolled) {
          if (event.pointerType !== "mouse") input({ type: "mouseDown", ...p, button: "left", clickCount: 1 })
          input({ type: "mouseUp", ...p, button: event.button === 2 ? "right" : "left", clickCount: 1 })
        }
        pointer = undefined
      }}
      onPointerCancel={() => { pointer = undefined }}
      onWheel={event => { event.preventDefault(); input({ type: "mouseWheel", x: event.offsetX, y: event.offsetY, deltaX: -event.deltaX, deltaY: -event.deltaY, canScroll: true }) }}>
      <Show when={props.visible && image()}><img src={image()} alt="" draggable={false} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", "object-fit": "fill", "pointer-events": "none" }} /></Show>
      <textarea ref={keyboard} aria-label="Browser keyboard" autocapitalize="off" autocorrect="off" spellcheck={false}
        style={{ position: "absolute", width: "1px", height: "1px", opacity: 0, top: 0, left: 0 }}
        onKeyDown={event => key(event, "keyDown")} onKeyUp={event => key(event, "keyUp")}
        onInput={event => { if (event.isComposing) return; const text = keyboard.value; keyboard.value = ""; if (text) input({ type: "text", text }) }}
        onCompositionEnd={() => { const text = keyboard.value; keyboard.value = ""; if (text) input({ type: "text", text }) }} />
      <Show when={error()}><div role="alert">{error()}</div></Show>
      {props.children}
    </div>
  }
  const unsupported = async (): Promise<never> => { throw new Error("This companion provides the built-in browser; external GUI extension management is unavailable") }
  const configure = () => {
    if (configuration) return configuration
    const attempt = (async () => {
      try {
        const config = await native<Pairing | null | false>("configureBrowser")
        if (config === false) return false
        const token = ++lifecycle
        const connection = connect(config, token)
        opening = connection
        try { await connection; return configured } finally { if (opening === connection) opening = undefined }
      } catch (error) {
        setStatus(error instanceof Error ? error.message : String(error))
        throw error
      }
    })()
    configuration = attempt
    void attempt.then(() => { if (configuration === attempt) configuration = undefined }, () => { if (configuration === attempt) configuration = undefined })
    return attempt
  }
  void boot()
  return {
    packaged: true,
    view: Surface,
    browserConnection: { status, configure },
    async call(input, signal) {
      if (input.ipc === IPC && input.method === "close" && (stopped || socket?.readyState !== WebSocket.OPEN)) return undefined
      await boot()
      return request("call", input, signal)
    },
    async subscribe(ipc) { await boot(); return { available: ipc === IPC && configured && socket?.readyState === WebSocket.OPEN } },
    on(listener) { listeners.add(listener); return () => listeners.delete(listener) },
    configure(servers) { endpoints = servers; if (socket?.readyState === WebSocket.OPEN) forward("configure", servers) },
    embed(id, layout?: BridgeLayout) { forward("embed", { id, layout }) },
    async capture(id) { const bytes = await request<string | null>("capture", id); return bytes ? Uint8Array.from(atob(bytes), c => c.charCodeAt(0)) : undefined },
    runMenubarItem() { throw new Error("Native menubar is unavailable on Android") },
    manager: { initial: Promise.resolve([]), list: async () => [{ id: "browser", name: "Browser", version: "2.0.26", builtin: true, enabled: true }],
      enable: unsupported, disable: unsupported, reload: unsupported, install: unsupported, remove: unsupported, source: unsupported,
      asset() { throw new Error("External extension assets are unavailable") } },
  }
}
