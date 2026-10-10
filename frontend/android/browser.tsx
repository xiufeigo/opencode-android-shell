import { createEffect, createSignal, on, onCleanup, onMount, Show } from "solid-js"
import type { Bridge, BridgeMessage } from "@opencode/gui-extensions/sdk/bridge"
import type { EmbedProps } from "@opencode/gui-extensions/sdk"

import { createBrowserTransport } from "./android-browser-transport.mjs"
import { showToast } from "@/shell/notifications/toast"

type NativeRequest = <T = unknown>(method: string, payload?: Record<string, unknown>) => Promise<T>
const IPC = "browser.pane"

export function createAndroidBrowser(native: NativeRequest): Bridge {
  const [status, setStatus] = createSignal("Browser uses the selected OpenCode server")
  const listeners = new Set<(value: BridgeMessage) => void>()
  const surfaces = new Map<string, (bytes: Uint8Array | string) => void>()
  const keyboards = new Map<string, () => void>()
  const transport = createBrowserTransport({ native, status: setStatus,
    publish: value => listeners.forEach(listener => listener(value)),
    surface: (id, bytes) => surfaces.get(id)?.(bytes), keyboard: id => keyboards.get(id)?.(),
  })
  const forward = (id: string, method: string, input: unknown) => void transport.surface(id, method, input).catch(error => { setStatus(error.message); console.error(error) })
  window.addEventListener("pagehide", () => transport.stop())
  window.addEventListener("pageshow", () => transport.resume())

  function Surface(props: EmbedProps) {
    const [image, setImage] = createSignal<string>()
    const [error, setError] = createSignal<string>()
    const [active, setActive] = createSignal(document.visibilityState === "visible")
    let root!: HTMLDivElement
    let keyboard!: HTMLTextAreaElement
    let imageURL: string | undefined
    const touches = new Map<number, { x: number; y: number }>()
    let movement: number | undefined
    const visible = () => props.visible && !props.frozen && active()
    const input = (event: Record<string, unknown>) => { if (visible() && props.id) forward(props.id, "input", { id: props.id, event }) }
    const layout = () => {
      if (!props.id) return
      const rect = root.getBoundingClientRect()
      forward(props.id, "embed", { id: props.id, layout: { visible: visible(), bounds: { x: 0, y: 0, width: Math.max(1, rect.width), height: Math.max(1, rect.height) } } })
    }
    const point = (event: PointerEvent) => { const rect = root.getBoundingClientRect(); return { x: Math.round(event.clientX - rect.left), y: Math.round(event.clientY - rect.top) } }
    const mouseButton = (event: PointerEvent) => ["left", "middle", "right"][event.button] ?? "none"
    const touch = (phase: string) => input({ type: "touch", phase, points: [...touches].map(([id, value]) => ({ id, ...value })) })
    const flushTouch = () => { if (movement !== undefined) { cancelAnimationFrame(movement); movement = undefined; touch("move") } }
    const cancelTouches = () => {
      if (!touches.size) return
      if (movement !== undefined) { cancelAnimationFrame(movement); movement = undefined }
      touches.clear()
      if (props.id) forward(props.id, "input", { id: props.id, event: { type: "touch", phase: "cancel", points: [] } })
    }
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
        forward(id, "embed", { id })
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
    createEffect(() => { if (!visible()) cancelTouches(); props.id; if (root) layout() })
    onCleanup(() => { cancelTouches(); if (movement !== undefined) cancelAnimationFrame(movement); if (imageURL) URL.revokeObjectURL(imageURL) })
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
        if (event.pointerType === "mouse") { input({ type: "mouseDown", ...point(event), button: mouseButton(event), clickCount: 1 }); return }
        touches.set(event.pointerId, point(event))
        flushTouch()
        touch("start")
      }}
      onPointerMove={event => {
        const p = point(event)
        if (event.pointerType === "mouse") { input({ type: "mouseMove", ...p, button: event.buttons & 1 ? "left" : event.buttons & 2 ? "right" : event.buttons & 4 ? "middle" : "none" }); return }
        if (!touches.has(event.pointerId)) return
        touches.set(event.pointerId, p)
        if (movement === undefined) movement = requestAnimationFrame(() => { movement = undefined; touch("move") })
      }}
      onPointerUp={event => {
        if (event.pointerType === "mouse") { input({ type: "mouseUp", ...point(event), button: mouseButton(event), clickCount: 1 }); return }
        if (!touches.has(event.pointerId)) return
        flushTouch()
        touches.delete(event.pointerId)
        touch(touches.size ? "move" : "end")
      }}
      onPointerCancel={cancelTouches}
      onContextMenu={event => event.preventDefault()}
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
  return {
    packaged: true,
    view: Surface,
    browserConnection: { status, configure: transport.configureLegacy, connect: transport.connect,
      report: message => showToast({ title: "Browser", description: message }) },
    call: transport.call,
    async subscribe(ipc) { return { available: ipc === IPC } },
    on(listener) { listeners.add(listener); return () => listeners.delete(listener) },
    configure: transport.configure,
    embed(id, layout) { forward(id, "embed", { id, layout }) },
    async capture(id) { const bytes = await transport.surface<string | null>(id, "capture", id); return bytes ? Uint8Array.from(atob(bytes), c => c.charCodeAt(0)) : undefined },
    runMenubarItem() { throw new Error("Native menubar is unavailable on Android") },
    manager: { initial: Promise.resolve([]), list: async () => [{ id: "browser", name: "Browser", version: "2.0.26", builtin: true, enabled: true }],
      enable: unsupported, disable: unsupported, reload: unsupported, install: unsupported, remove: unsupported, source: unsupported,
      asset() { throw new Error("External extension assets are unavailable") } },
  }
}
