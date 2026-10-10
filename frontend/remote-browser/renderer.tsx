import { lazy, Suspense } from "solid-js"
import { Command, MenuItem, Panel, Style, type PanelTab, type Setup } from "../sdk"
import definition from "./index"
import { createBrowserModel } from "./model"
import styles from "./styles.css?inline"

const setup: Setup<typeof definition> = ctx => {
  const model = createBrowserModel(ctx)
  const open = () => { const session = ctx.sessions.current(); if (session) void model.open(session).catch(() => undefined) }
  ctx.add(Style, styles)
  ctx.add(Command, () => ({ id: "open", title: ctx.t("open"), group: ctx.t("title"), bind: "mod+shift+b", enabled: !!ctx.sessions.current(), run: open }))
  ctx.add(MenuItem, (): MenuItem | undefined => ctx.sessions.current() ? { menu: "session.panel", id: "open", title: ctx.t("title"), icon: "globe", keybind: "server-browser.open", order: 20, run: open } : undefined)
  const tabs = new Map<string, PanelTab>()
  const PanelView = lazy(() => import("./panel"))
  ctx.add(Panel, {
    id: "main", region: "side",
    mobile: { get title() { return ctx.t("title") }, order: 25, kind: "menu", icon: "globe" },
    list(input) {
      const state = model.get(input.session).state
      const result = state.tabs.map(tab => {
        const key = `${input.session.key}:${tab.id}`
        const previous = tabs.get(key)
        if (previous) return previous
        const value: PanelTab = { id: tab.id, group: "browser", get title() { const current = model.get(input.session).state.tabs.find(value => value.id === tab.id); return current?.url === "about:blank" ? ctx.t("new") : current?.title || current?.url || ctx.t("new") } }
        tabs.set(key, value)
        return value
      })
      const key = `${input.session.key}:main`
      let main = tabs.get(key)
      if (!main) {
        main = { id: "main", group: "browser", get title() { return ctx.t("title") }, get hidden() { return model.get(input.session).state.tabs.length > 0 } }
        tabs.set(key, main)
      }
      return [...result, main]
    },
    render: props => <Suspense><PanelView model={model} session={props.session} tab={props.tab} /></Suspense>,
    close: input => { if (model.get(input.session).state.tabs.some(tab => tab.id === input.tab.id)) void model.execute(input.session, { type: "tabs.close", tabID: input.tab.id }).catch(() => undefined) },
    focus: input => { if (input.tab.id !== "main" && model.get(input.session).state.focusedTabID !== input.tab.id) void model.execute(input.session, { type: "tabs.focus", tabID: input.tab.id }).catch(() => undefined) },
  })
}
export default setup
