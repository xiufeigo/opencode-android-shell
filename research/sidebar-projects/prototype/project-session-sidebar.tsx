import type { SessionInfo } from "@opencode/client/promise"
import { skipToken, useQuery } from "@tanstack/solid-query"
import { createMemo, For, Show, startTransition } from "solid-js"
import { createStore } from "solid-js/store"
import { Icon } from "@opencode/ui/icon"
import { IconButton } from "@opencode/ui/icon-button"
import {
  displayName,
  getProjectAvatarSource,
  getProjectAvatarVariant,
  ProjectAvatar,
} from "@opencode/ui/project-avatar"
import { Spinner } from "@opencode/ui/spinner"
import { Tooltip } from "@opencode/ui/tooltip"
import {
  HOME_SESSION_LIMIT,
  loadHomeSessionIndex,
  mergeHomeSessionIndex,
  retainHomeSessions,
} from "@/home/sessions/index"
import { homeProjectForSession } from "@/home/sessions/records"
import { useLanguage } from "@/runtime/i18n/language"
import { ServerConnection, useServers } from "@/runtime/server/registry"
import { useServerCtx } from "@/runtime/server/runtime"
import { useCommand } from "@/shell/commands/command"
import { compareSessionTime } from "@/shell/layout/helpers"
import { SessionTabAvatar } from "@/shell/layout/session-tab-avatar"
import { useLayout, type LocalProject } from "@/shell/state/layout"
import { findSessionTab, tabKey, useTabs, type DraftTab, type SessionTab } from "@/shell/tabs/tabs"
import { sessionLabel } from "@/session/title"
import { pathKey } from "@/workspaces/path-key"

/**
 * Vertical-tabs sidebar: a project tree with the project's sessions instead of a
 * flat list of open tabs. Projects come from the same per-server registry the
 * home page renders; sessions come from the same index the home page loads, so
 * both views agree and no protocol change is required.
 */
export function ProjectSessionSidebar() {
  const language = useLanguage()
  const servers = useServers()
  return (
    <div data-slot="project-sidebar" class="flex min-h-0 flex-1 flex-col">
      <div class="flex h-6 shrink-0 items-center ps-1.5 text-[11px] leading-4 text-v2-text-text-faint [font-weight:530]">
        {language.t("home.projects")}
      </div>
      <div
        data-slot="project-sidebar-scroll"
        class="flex min-h-0 flex-1 flex-col overflow-y-auto overflow-x-hidden pb-2"
      >
        <For each={servers.visible}>
          {(server) => <ServerProjectTree server={server} multiServer={servers.visible.length > 1} />}
        </For>
      </div>
    </div>
  )
}

type ProjectEntry = {
  project: LocalProject
  sessions: SessionInfo[]
  drafts: DraftTab[]
}

function ServerProjectTree(props: { server: ServerConnection.Any; multiServer: boolean }) {
  const language = useLanguage()
  const tabs = useTabs()
  const command = useCommand()
  const layout = useLayout()
  const ctx = useServerCtx(() => props.server)
  const [expanded, setExpanded] = createStore<Record<string, boolean>>({})
  const serverKey = () => ServerConnection.key(props.server)
  const active = createMemo(() => {
    const route = layout.route()
    return route.type === "session" ? { server: route.server, sessionId: route.sessionId } : undefined
  })

  // Same query key and loader as the home page: both views share one cached
  // index, and the session store covers live updates and pending removals.
  const sessionLoad = useQuery(() => {
    const current = ctx()
    return {
      queryKey: ["home-sessions", props.server] as const,
      enabled: !!current && current.sdk.connection.status() === "connected",
      queryFn: current
        ? ({ signal }: { signal: AbortSignal }) =>
            loadHomeSessionIndex((input, options) => current.sdk.api.session.list(input, options), signal)
        : skipToken,
      retry: false,
      staleTime: 30_000,
      refetchOnMount: true,
    }
  })

  const projects = createMemo(() => ctx()?.projects.list() ?? [])
  const sessions = createMemo(() => {
    const current = ctx()
    if (!current) return []
    const fetched = sessionLoad.isPending ? [] : (sessionLoad.data ?? [])
    return retainHomeSessions(
      mergeHomeSessionIndex(fetched, current.data.session.list()),
      HOME_SESSION_LIMIT,
      Date.now(),
    )
  })

  const groups = createMemo(() => {
    const list = projects()
    const entries = new Map<string, ProjectEntry>()
    for (const project of list) entries.set(pathKey(project.worktree), { project, sessions: [], drafts: [] })
    for (const session of sessions()) {
      const project = homeProjectForSession(session, list)
      const entry = project ? entries.get(pathKey(project.worktree)) : undefined
      if (entry) entry.sessions.push(session)
    }
    for (const tab of tabs.store) {
      if (tab.type !== "draft" || tab.server !== serverKey()) continue
      const project = list.find((item) => pathKey(item.worktree) === pathKey(tab.directory))
      const entry = project ? entries.get(pathKey(project.worktree)) : undefined
      if (entry) entry.drafts.push(tab)
    }
    for (const entry of entries.values()) entry.sessions.sort(compareSessionTime)
    return [...entries.values()]
  })

  const groupKey = (entry: ProjectEntry) => pathKey(entry.project.worktree)
  const isExpanded = (entry: ProjectEntry) => {
    const value = expanded[groupKey(entry)]
    if (value !== undefined) return value
    const route = active()
    if (
      route?.server === serverKey() &&
      (entry.sessions.some((session) => session.id === route.sessionId) ||
        entry.drafts.some((tab) => tab.draftID === route.sessionId))
    )
      return true
    return (
      entry.drafts.length > 0 ||
      entry.sessions.some((session) => !!findSessionTab(tabs.store, serverKey(), session.id))
    )
  }
  const toggle = (entry: ProjectEntry) => setExpanded(groupKey(entry), !isExpanded(entry))

  const closeTab = (tab: SessionTab | DraftTab) => {
    const index = tabs.store.findIndex((item) => tabKey(item) === tabKey(tab))
    if (index !== -1) tabs.closeTab(index)
  }

  const openSession = (session: SessionInfo) => {
    const current = ctx()
    const existing = findSessionTab(tabs.store, serverKey(), session.id)
    if (existing) {
      tabs.select(existing)
      return
    }
    void startTransition(() => {
      const tab = tabs.addSessionTab({ server: serverKey(), sessionId: session.id })
      tabs.select(tab)
      current?.data.session.remember(session)
      current?.projects.open(session.location.directory)
      current?.projects.touch(session.location.directory)
    })
  }

  const newSession = (entry: ProjectEntry) => {
    const current = ctx()
    current?.projects.open(entry.project.worktree)
    current?.projects.touch(entry.project.worktree)
    void tabs.newDraft({ server: serverKey(), directory: entry.project.worktree })
  }

  const visibleTabs = createMemo(() => {
    const ordered: SessionTab[] = []
    for (const entry of groups()) {
      for (const session of entry.sessions) {
        const tab = findSessionTab(tabs.store, serverKey(), session.id)
        if (tab?.type === "session") ordered.push(tab)
      }
    }
    return ordered
  })

  const cycle = (offset: -1 | 1) => {
    const list = visibleTabs()
    if (list.length === 0) return
    const route = active()
    const current = route ? findSessionTab(tabs.store, route.server, route.sessionId) : undefined
    const index = current ? list.findIndex((tab) => tabKey(tab) === tabKey(current)) : -1
    tabs.select(list[(index + offset + list.length) % list.length]!)
  }

  command.register("project-sidebar-tab-cycle", () => [
    {
      id: "tab.prev",
      category: "tab",
      title: "",
      keybind: `mod+option+ArrowLeft,ctrl+shift+tab`,
      hidden: true,
      onSelect: () => cycle(-1),
    },
    {
      id: "tab.next",
      category: "tab",
      title: "",
      keybind: `mod+option+ArrowRight,ctrl+tab`,
      hidden: true,
      onSelect: () => cycle(1),
    },
  ])
  command.register("project-sidebar-tab-numbers", () =>
    visibleTabs()
      .slice(0, 9)
      .map((tab, index) => ({
        id: `tab.${index + 1}`,
        category: "tab",
        title: "",
        keybind: `mod+${index + 1}`,
        hidden: true,
        onSelect: () => tabs.select(tab),
      })),
  )

  return (
    <div class="flex min-w-0 flex-col" data-server={serverKey()}>
      <Show when={props.multiServer}>
        <div class="flex h-6 shrink-0 items-center gap-2 ps-1.5 pe-2 pt-1 text-[11px] leading-4 text-v2-text-text-faint">
          <span class="min-w-0 truncate [font-weight:530]">
            {props.server.displayName ?? new URL(props.server.http.url).host}
          </span>
        </div>
      </Show>
      <For each={groups()}>
        {(entry) => {
          const count = () => entry.sessions.length + entry.drafts.length
          return (
            <div class="flex min-w-0 flex-col" data-slot="project-group">
              <div
                data-slot="project-row"
                data-project={entry.project.worktree}
                data-expanded={isExpanded(entry) ? "" : undefined}
                class="group/project relative flex h-7 min-w-0 shrink-0 items-center rounded-[6px]"
              >
                <button
                  type="button"
                  aria-expanded={isExpanded(entry)}
                  class={`
                    flex h-7 min-w-0 flex-1 items-center gap-1.5 rounded-[6px] ps-1 pe-8 text-start
                    text-[13px] leading-4 text-v2-text-text-muted transition-[background-color,color] duration-[120ms] ease-in-out
                    hover:bg-v2-overlay-simple-overlay-hover hover:text-v2-text-text-base
                    focus-visible:bg-v2-overlay-simple-overlay-hover focus-visible:text-v2-text-text-base focus-visible:outline-none
                  `}
                  onClick={() => toggle(entry)}
                >
                  <Icon
                    name="chevron-down"
                    size="small"
                    class="shrink-0 text-v2-icon-icon-muted transition-transform duration-150 ease-in-out"
                    style={{ transform: isExpanded(entry) ? "rotate(0deg)" : "rotate(-90deg)" }}
                  />
                  <ProjectAvatar
                    fallback={displayName(entry.project)}
                    src={getProjectAvatarSource(entry.project.id, entry.project.icon)}
                    variant={getProjectAvatarVariant(entry.project.icon?.color)}
                  />
                  <span data-slot="project-title" class="min-w-0 flex-1 truncate [font-weight:530]" dir="auto">
                    {displayName(entry.project)}
                  </span>
                  <Show when={count() > 0}>
                    <span class="shrink-0 pe-0.5 text-[11px] tabular-nums text-v2-text-text-faint">{count()}</span>
                  </Show>
                </button>
                <div class="absolute end-0.5 top-0 flex h-7 items-center">
                  <Tooltip class="flex shrink-0 items-center" placement="right" value={language.t("command.session.new")}>
                    <IconButton
                      data-action="project-sidebar-new-session"
                      variant="ghost-muted"
                      size="small"
                      class="hover-reveal group-hover/project:opacity-100 focus-within:opacity-100"
                      icon={<Icon name="edit" />}
                      aria-label={language.t("command.session.new")}
                      onClick={(event) => {
                        event.preventDefault()
                        event.stopPropagation()
                        newSession(entry)
                      }}
                    />
                  </Tooltip>
                </div>
              </div>
              <Show when={isExpanded(entry)}>
                <div class="flex min-w-0 flex-col gap-px pb-1" data-slot="project-sessions">
                  <For each={entry.drafts}>
                    {(tab) => (
                      <SessionRow
                        project={entry.project}
                        server={props.server}
                        directory={tab.directory}
                        sessionId={undefined}
                        title={language.t("command.session.new")}
                        active={active()?.sessionId === tab.draftID}
                        open
                        onOpen={() => tabs.select(tab)}
                        onClose={() => closeTab(tab)}
                      />
                    )}
                  </For>
                  <For each={entry.sessions}>
                    {(session) => {
                      const tab = createMemo(() => findSessionTab(tabs.store, serverKey(), session.id))
                      return (
                        <SessionRow
                          project={entry.project}
                          server={props.server}
                          directory={session.location.directory}
                          sessionId={session.id}
                          title={sessionLabel(session)}
                          active={active()?.sessionId === session.id && active()?.server === serverKey()}
                          open={!!tab()}
                          onOpen={() => openSession(session)}
                          onClose={() => {
                            const value = tab()
                            if (value?.type === "session") closeTab(value)
                          }}
                        />
                      )
                    }}
                  </For>
                </div>
              </Show>
            </div>
          )
        }}
      </For>
      <Show when={sessionLoad.isPending && groups().length === 0}>
        <div class="flex h-7 items-center justify-center">
          <Spinner class="size-3.5" />
        </div>
      </Show>
      <Show when={!sessionLoad.isPending && groups().length === 0}>
        <div class="px-1.5 py-1 text-[11px] leading-4 text-v2-text-text-faint">
          {language.t("sidebar.empty.title")}
        </div>
      </Show>
    </div>
  )
}

function SessionRow(props: {
  project: LocalProject
  server: ServerConnection.Any
  directory: string
  sessionId: string | undefined
  title: string
  active: boolean
  open: boolean
  onOpen: () => void
  onClose: () => void
}) {
  const language = useLanguage()
  return (
    <div
      data-slot="session-row"
      data-session={props.sessionId ?? ""}
      data-active={props.active ? "" : undefined}
      class="group/session relative flex h-7 min-w-0 items-center rounded-[6px]"
      classList={{ "bg-v2-overlay-simple-overlay-pressed": props.active }}
    >
      <button
        type="button"
        class={`
          flex h-7 min-w-0 flex-1 items-center gap-2 rounded-[6px] ps-6 pe-6 text-start text-[13px] leading-4
          text-v2-text-text-muted transition-[background-color,color] duration-[120ms] ease-in-out
          hover:bg-v2-overlay-simple-overlay-hover hover:text-v2-text-text-base
          focus-visible:bg-v2-overlay-simple-overlay-hover focus-visible:text-v2-text-text-base focus-visible:outline-none
        `}
        classList={{ "text-v2-text-text-base": props.active }}
        onClick={() => props.onOpen()}
      >
        <span class="flex size-4 shrink-0 items-center justify-center">
          <Show
            when={props.sessionId}
            fallback={<span class="block size-4 rounded-[3px] border border-v2-border-border-muted" aria-hidden="true" />}
          >
            {(sessionId) => (
              <SessionTabAvatar
                project={props.project}
                directory={props.directory}
                sessionId={sessionId()}
                server={ServerConnection.key(props.server)}
              />
            )}
          </Show>
        </span>
        <span class="min-w-0 flex-1 overflow-hidden text-ellipsis whitespace-nowrap" dir="auto">
          {props.title}
        </span>
      </button>
      <Show when={props.open}>
        <IconButton
          variant="ghost-muted"
          size="small"
          class="hover-reveal absolute end-0.5 top-1/2 -translate-y-1/2 group-hover/session:opacity-100 focus-within:opacity-100"
          onPointerDown={(event) => {
            event.preventDefault()
            event.stopPropagation()
          }}
          onClick={(event) => {
            event.preventDefault()
            event.stopPropagation()
            props.onClose()
          }}
          icon={<Icon name="xmark-small" />}
          aria-label={language.t("common.closeTab")}
        />
      </Show>
    </div>
  )
}
