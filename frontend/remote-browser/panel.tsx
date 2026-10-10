import { createEffect, createMemo, createSignal, For, onCleanup, Show, untrack } from "solid-js"
import { Button } from "@opencode/ui/button"
import { Icon } from "@opencode/ui/icon"
import { IconButton } from "@opencode/ui/icon-button"
import { Menu } from "@opencode/ui/menu"
import { useExtension, usePanel, type MountedSession, type PanelTab } from "../sdk"
import type definition from "./index"
import type { Model } from "./model"
import type { Action, Tab } from "./rpc"

export default function BrowserPanel(props: { model: Model; session: MountedSession; tab: PanelTab }) {
  const ctx = useExtension<typeof definition>()
  const panel = usePanel()
  const record = () => props.model.get(props.session)
  const selected = () => record().state.tabs.find(tab => tab.id === (props.tab.id === "main" ? record().state.focusedTabID : props.tab.id))
  const [draft, setDraft] = createSignal<{ source: string; text: string }>()
  const [frameRevision, setFrameRevision] = createSignal(0)
  const [image, setImage] = createSignal<string>()
  const [live, setLive] = createSignal<Tab>()
  const [prompt, setPrompt] = createSignal("")
  const [files, setFiles] = createSignal<{ name: string; path: string; state: string }[]>([])
  let addressField!: HTMLInputElement
  let keyboard!: HTMLTextAreaElement
  let surface!: HTMLDivElement
  let img!: HTMLImageElement
  let composing = false
  let pointer: { id: number; x: number; y: number; moved: boolean; touch: boolean } | undefined
  const current = () => live()?.id === selected()?.id ? live() : selected()
  const run = (action: Action) => {
    const session = props.session
    return props.model.execute(session, action)
  }
  const perform = (action: Action) => { void run(action).catch(() => undefined) } // model retains the visible error.
  const open = () => { void props.model.open(props.session).catch(() => undefined) }
  const target = (type: "back" | "forward" | "reload" | "stop") => { const tab = selected(); if (tab) perform({ type, tabID: tab.id }) }
  const blank = () => !selected() || selected()?.url === "about:blank"
  const pageURL = createMemo(() => blank() ? "" : selected()?.url ?? "")
  const addressSource = createMemo(() => `${props.session.key}\n${selected()?.id ?? props.tab.id}\n${pageURL()}`)
  const address = () => draft()?.source === addressSource() ? draft()!.text : pageURL()
  const frameTarget = createMemo(() => { const tab = selected(); return tab ? `${tab.id}\n${tab.url}` : "" })

  createEffect(() => {
    frameRevision()
    frameTarget()
    const tab = untrack(selected)
    const session = props.session
    const visible = panel.visible()
    setImage(undefined)
    setLive(undefined)
    setFiles([])
    if (!visible || !tab || tab.url === "about:blank") return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    let hash: string | undefined
    const poll = async () => {
      if (controller.signal.aborted) return
      if (document.visibilityState === "hidden") { timer = setTimeout(() => void poll(), 1000); return }
      try {
        const frame = await props.model.client(session).frame({ sessionID: session.id, tabID: tab.id, previous: hash }, { ...props.model.options(session), signal: controller.signal })
        if (controller.signal.aborted) return
        hash = frame.hash
        if (frame.image) setImage(`data:image/jpeg;base64,${frame.image}`)
        setLive(frame.tab)
        timer = setTimeout(() => void poll(), 250)
      } catch (error) {
        if (!controller.signal.aborted) props.model.fail(session, error)
      }
    }
    void poll()
    onCleanup(() => { controller.abort(); clearTimeout(timer) })
  })

  const go = async () => {
    const session = props.session
    const url = address().trim()
    try {
      const tab = selected() ?? await props.model.execute(session, { type: "tabs.open" }) as Tab
      await props.model.execute(session, { type: "navigate", tabID: tab.id, url: url.includes(" ") ? `https://www.google.com/search?q=${encodeURIComponent(url)}` : url })
      ctx.layout.open(`${ctx.id}:${tab.id}`, session, { tab: "select" })
      addressField.blur()
    } catch { /* The model displays the RPC error, including missing browser dependencies. */ }
  }
  const point = (event: PointerEvent) => {
    const tab = current()
    const bounds = img.getBoundingClientRect()
    if (!tab || !bounds.width || !bounds.height) return
    return { x: Math.max(0, Math.min(tab.width, (event.clientX - bounds.left) * tab.width / bounds.width)), y: Math.max(0, Math.min(tab.height, (event.clientY - bounds.top) * tab.height / bounds.height)) }
  }
  const pointerEvent = (event: PointerEvent, phase: "down" | "up" | "move") => {
    const tab = selected()
    const position = point(event)
    if (!tab || !position) return
    perform({ type: "pointer", tabID: tab.id, phase, ...position, button: event.button === 2 ? "right" : event.button === 1 ? "middle" : "left" })
  }
  const insert = (text: string) => { const tab = selected(); if (tab && text) perform({ type: "insert", tabID: tab.id, text }) }
  const showFiles = async () => {
    const tab = selected()
    if (!tab) return
    try { setFiles((await run({ type: "files.list", tabID: tab.id }) as { files: { name: string; path: string; state: string }[] }).files) } catch { /* model displays the failure */ }
  }
  const reconnect = async () => {
    try {
      await props.model.refresh(props.session)
      setFrameRevision(value => value + 1)
    } catch { /* refresh retains the visible failure. */ }
  }

  return <aside data-component="server-browser" aria-label={ctx.t("title")}>
    <Show when={panel.placement() === "mobile"}>
      <div class="browser-mobile-tabs" role="tablist">
        <For each={record().state.tabs}>{tab => <div class="flex items-center">
          <button type="button" role="tab" aria-selected={record().state.focusedTabID === tab.id} onClick={() => perform({ type: "tabs.focus", tabID: tab.id })}>
          <Icon name="globe" size="small" /><span class="truncate">{tab.url === "about:blank" ? ctx.t("new") : tab.title || tab.url}</span>
          </button><IconButton icon={<Icon name="close" />} aria-label={ctx.t("close")} onClick={() => perform({ type: "tabs.close", tabID: tab.id })} />
        </div>}</For>
        <IconButton icon={<Icon name="plus" />} aria-label={ctx.t("new")} onClick={open} />
      </div>
    </Show>
    <form class="browser-bar" onSubmit={event => { event.preventDefault(); void go() }}>
      <IconButton variant="ghost-muted" icon={<Icon name="arrow-left" />} aria-label={ctx.t("back")} disabled={!current()?.canGoBack} onClick={() => target("back")} />
      <IconButton variant="ghost-muted" icon={<Icon name="arrow-right" />} aria-label={ctx.t("forward")} disabled={!current()?.canGoForward} onClick={() => target("forward")} />
      <IconButton variant="ghost-muted" icon={<Icon name={current()?.loading ? "close" : "outline-rotate-clockwise"} />} aria-label={ctx.t(current()?.loading ? "stop" : "reload")} disabled={!selected()} onClick={() => target(current()?.loading ? "stop" : "reload")} />
      <input ref={addressField} class="browser-address" aria-label={ctx.t("address")} placeholder={ctx.t("address")} disabled={panel.placement() !== "mobile" && !selected()} value={address()} onInput={event => setDraft({ source: addressSource(), text: event.currentTarget.value })} onFocus={event => event.currentTarget.select()} autocapitalize="off" spellcheck={false} />
      <Menu gutter={4} modal={false} placement="bottom-end">
        <Menu.Trigger as={IconButton} variant="ghost-muted" aria-label={ctx.t("options")} icon={<Icon name="outline-dots" />} />
        <Menu.Portal><Menu.Content>
          <Menu.Item onSelect={() => { if (selected()) void ctx.system.copy(selected()!.url).catch(error => props.model.fail(props.session, error)) }}>{ctx.t("copy")}</Menu.Item>
          <Menu.Item onSelect={() => { if (selected()) ctx.system.openExternal(selected()!.url) }}>{ctx.t("external")}</Menu.Item>
          <Menu.Item onSelect={() => { const tab = selected(); if (tab) perform({ type: "viewport", tabID: tab.id, width: Math.max(240, Math.min(2560, Math.round(surface.clientWidth))), height: Math.max(200, Math.min(1600, Math.round(surface.clientHeight))) }) }}>{ctx.t("fit")}</Menu.Item>
          <Menu.Item onSelect={() => void showFiles()}>{ctx.t("files")}</Menu.Item>
        </Menu.Content></Menu.Portal>
      </Menu>
    </form>
    <Show when={record().error}><div class="browser-error" role="alert">{record().error}<Button size="small" onClick={() => void reconnect()}>{ctx.t("retry")}</Button></div></Show>
    <Show when={blank()} fallback={
      <div ref={surface} class="browser-surface" tabindex="0" onContextMenu={event => event.preventDefault()}>
        <Show when={image()}><img ref={img} src={image()} alt={current()?.title ?? ctx.t("title")} draggable={false}
          onPointerDown={event => {
            event.preventDefault()
            pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, moved: false, touch: event.pointerType === "touch" }
            event.currentTarget.setPointerCapture(event.pointerId)
            if (!pointer.touch) pointerEvent(event, "down")
          }}
          onPointerMove={event => {
            if (!pointer || pointer.id !== event.pointerId) return
            if (!pointer.touch) { pointerEvent(event, "move"); return }
            const dy = pointer.y - event.clientY
            const dx = pointer.x - event.clientX
            if (Math.abs(dx) + Math.abs(dy) < 8 && !pointer.moved) return
            pointer.moved = true
            pointer.x = event.clientX; pointer.y = event.clientY
            const tab = selected()
            if (tab) perform({ type: "scroll", tabID: tab.id, deltaX: dx, deltaY: dy })
          }}
          onPointerUp={event => {
            if (!pointer || pointer.id !== event.pointerId) return
            if (pointer.touch && !pointer.moved) pointerEvent(event, "down")
            if (!pointer.touch || !pointer.moved) { pointerEvent(event, "up"); keyboard.focus({ preventScroll: true }) }
            pointer = undefined
          }}
          onPointerCancel={event => { if (pointer && !pointer.touch) pointerEvent(event, "up"); pointer = undefined }}
          onWheel={event => { event.preventDefault(); const tab = selected(); if (tab) perform({ type: "scroll", tabID: tab.id, deltaX: event.deltaX, deltaY: event.deltaY }) }}
        /></Show>
        <textarea ref={keyboard} class="browser-keyboard" aria-label={ctx.t("keyboard")} autocapitalize="off" autocomplete="off" spellcheck={false}
          onCompositionStart={() => { composing = true }}
          onCompositionEnd={event => { composing = false; insert(event.currentTarget.value); event.currentTarget.value = "" }}
          onInput={event => { if (!composing && !event.isComposing) { insert(event.currentTarget.value); event.currentTarget.value = "" } }}
          onKeyDown={event => {
            if (composing || event.isComposing) return
            if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) return
            if (["Control", "Meta", "Shift", "Alt", "Unidentified", "Process"].includes(event.key)) return
            event.preventDefault()
            const tab = selected()
            const key = [...event.ctrlKey ? ["Control"] : [], ...event.metaKey ? ["Meta"] : [], ...event.altKey ? ["Alt"] : [], ...event.shiftKey ? ["Shift"] : [], event.key].join("+")
            if (tab) perform({ type: "press", tabID: tab.id, key })
          }}
        />
        <Show when={current()?.dialog}>{dialog => <div class="browser-dialog" role="dialog" aria-label={dialog().type}>
          <p>{dialog().message}</p>
          <Show when={dialog().type === "prompt"}><input aria-label="Prompt" value={prompt() || dialog().defaultValue} onInput={event => setPrompt(event.currentTarget.value)} /></Show>
          <div class="flex gap-2"><Button onClick={() => { const tab = selected(); if (tab) perform({ type: "dialog", tabID: tab.id, action: "accept", promptText: prompt() }) }}>{ctx.t("accept")}</Button><Button variant="neutral" onClick={() => { const tab = selected(); if (tab) perform({ type: "dialog", tabID: tab.id, action: "dismiss" }) }}>{ctx.t("dismiss")}</Button></div>
        </div>}</Show>
      </div>
    }>
      <div ref={surface} class="browser-home">
        <Icon name="globe" size="large" /><strong>{record().error ? ctx.t("unavailable") : ctx.t("home")}</strong>
        <p>{record().error ? ctx.t("install") : ctx.t("description")}</p>
      </div>
    </Show>
    <Show when={files().length}><div class="p-2 max-h-40 overflow-auto"><For each={files()}>{file => <button class="block p-1 text-left" disabled={file.state !== "completed"} onClick={() => ctx.links.open({ href: file.path, session: props.session, exact: true })}>{file.name} — {file.state}</button>}</For></div></Show>
  </aside>
}
