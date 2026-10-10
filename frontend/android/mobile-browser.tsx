import { createEffect, For, lazy, Show, Suspense } from "solid-js"
import { Button } from "@opencode/ui/button"
import { IconButton } from "@opencode/ui/icon-button"
import { Icon } from "@opencode/ui/icon"
import { useExtension, usePanel, type MountedSession, type SessionScreen } from "../sdk"
import type { Model } from "./model"
const SessionBrowserPane = lazy(() => import("./panel"))

/** The official browser pane inside the host's existing mobile view switcher. */
export function AndroidMobileBrowser(props: { model: Model; session: MountedSession; screen: SessionScreen }) {
  const panel = usePanel()
  const extension = useExtension()
  const opened = new Set<string>()
  const state = () => props.model.inventory(props.session)
  const selected = () => state()?.tabs.find(tab => tab.id === state()?.focusedTabID)
  createEffect(() => {
    if (!panel.visible() || !props.model.available(props.session) || props.model.pending(props.session)) return
    if (state()?.tabs.length || props.model.opening(props.session) || opened.has(props.session.key)) return
    opened.add(props.session.key)
    props.model.open(props.session)
  })
  return <div class="flex flex-col h-full min-h-0">
    <div class="flex items-center gap-1 overflow-x-auto shrink-0 px-2 py-1" role="tablist" aria-label={extension.t("tab.title")}>
      <For each={state()?.tabs}>{tab => <div class="flex items-center shrink-0">
        <Button role="tab" aria-selected={tab.id === selected()?.id}
          onClick={() => props.model.focusTab(props.session, tab.id)}>{!tab.url || tab.url === "about:blank" ? extension.t("tab.new") : tab.title || tab.url}</Button>
        <IconButton icon={<Icon name="close" />} aria-label={`${extension.t("common.closeTab")}: ${tab.title || extension.t("tab.new")}`} onClick={() => props.model.closeTab(props.session, tab.id)} />
      </div>}</For>
      <IconButton icon={<Icon name="plus-small" />} aria-label={extension.t("tab.new")} onClick={() => props.model.open(props.session)} />
    </div>
    <Show when={selected()} fallback={<div role="status" class="p-4">{props.model.error(props.session) || (props.model.available(props.session)
      ? extension.t("home.title")
      : `${extension.t("common.loading")}${extension.t("common.loading.ellipsis")}`)}</div>}>
      {tab => <div class="flex-1 min-h-0"><Suspense><SessionBrowserPane tab={() => ({ id: tab().id, title: tab().title })}
        session={props.session} screen={props.screen} model={props.model} /></Suspense></div>}
    </Show>
  </div>
}
