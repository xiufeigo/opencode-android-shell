import { lazy, Suspense } from "solid-js"
import { Command, createKeyed, MenuItem, Panel, Style, type PanelTab, type Plugin } from "@opencode/plugin/gui"
import definition from "./index"
import { createBrowserModel } from "./model"
import styles from "./styles.css?inline"

const setup: Plugin.Setup<typeof definition> = ctx => {
  const model = createBrowserModel(ctx)
  const open = (session = ctx.sessions.current()) => { if (session) void model.open(session).catch(() => undefined) }
  ctx.add(Style, styles)
  ctx.add(Command, () => ({
    id: "open",
    title: ctx.t("open"),
    group: ctx.t("title"),
    bind: "mod+shift+b",
    enabled: !!ctx.sessions.current(),
    run: () => open(),
  }))
  ctx.add(MenuItem, (): MenuItem | undefined => ctx.sessions.current()
    ? { menu: "session.panel", id: "open", title: ctx.t("title"), icon: "globe", keybind: `${ctx.id}.open`, order: 20, run: () => open() }
    : undefined)
  // Tab objects per session and browser tab, reused so frames and strip updates never rebuild a trigger.
  const tabs = new Map<string, Map<string, PanelTab>>()
  createKeyed(
    () => ctx.sessions.list().map(session => session.key).join("\u0000"),
    () => {
      const keep = new Set(ctx.sessions.list().map(session => session.key))
      tabs.forEach((_cache, key) => { if (!keep.has(key)) tabs.delete(key) })
    },
  )
  const create = (key: string, id: string): PanelTab => ({
    id,
    group: "browser",
    get title() {
      const tab = model.state(key).tabs.find(value => value.id === id)
      return tab?.url === "about:blank" ? ctx.t("new") : tab?.title || tab?.url || ctx.t("new")
    },
  })
  // The panel's own tab: listed even before the server answers, so opening the browser always shows the panel and its
  // failure (missing plugin, no Chromium, a disconnected server) instead of nothing. Hidden once a registered tab exists.
  const landing = (key: string): PanelTab => ({
    id: "main",
    group: "browser",
    get title() { return ctx.t("title") },
    get hidden() { return model.state(key).tabs.length > 0 },
  })
  const PanelView = lazy(() => import("./panel"))
  ctx.add(Panel, {
    id: "main", region: "side",
    mobile: { get title() { return ctx.t("title") }, order: 25, kind: "menu", icon: "globe" },
    list(input) {
      const state = model.get(input.session).state
      const previous = tabs.get(input.session.key) ?? new Map<string, PanelTab>()
      const next = new Map(state.tabs.map(tab => [tab.id, previous.get(tab.id) ?? create(input.session.key, tab.id)]))
      next.set("main", previous.get("main") ?? landing(input.session.key))
      tabs.set(input.session.key, next)
      return [...next.values()]
    },
    render: props => <Suspense><PanelView model={model} session={props.session} tab={props.tab} /></Suspense>,
    close: input => { if (model.get(input.session).state.tabs.some(tab => tab.id === input.tab.id)) void model.execute(input.session, { type: "tabs.close", tabID: input.tab.id }).catch(() => undefined) },
    focus: input => { if (input.tab.id !== "main" && model.get(input.session).state.focusedTabID !== input.tab.id) void model.execute(input.session, { type: "tabs.focus", tabID: input.tab.id }).catch(() => undefined) },
  })
}
export default setup
