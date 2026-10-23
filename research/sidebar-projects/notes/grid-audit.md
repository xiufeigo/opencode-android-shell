# Panel-grid prototype audit (vertical-tabs independent panel frames)

Read-only review of the prototype in the disposable checkout:

`C:\sensorsdata\main\program\opencode-android-shell\_scratch_root\desktop-parity\checkout\packages\app`

Reviewed snapshot (2026-10-22 ~18:56 +08:00; the `panel-grid*` files were still being edited at 18:51–18:54 while this review started, so the hashes below are the authoritative target):

| file | sha256 (first 16) | mtime |
| --- | --- | --- |
| `src/session/panel-grid-state.ts` | `8890FFDE1EB562ED` | 18:51:55 |
| `src/session/panel-grid.tsx` | `153B80CEB0508761` | 18:54:13 |
| `src/shell/titlebar/vertical-session-toolbar.tsx` | `AAE1945EC8E37034` | 18:51:30 |
| `src/session/screen.tsx` | `C261E6538DAEEE10` | 18:31:03 |
| `src/shell/titlebar/titlebar.tsx` | `A0FA45AB97CC9282` | 18:30:08 |
| `src/runtime/i18n/en.ts` (new keys) | `7AEFDA5A6D688FBD` | 18:51:14 |

If any of these files changed after this timestamp, re-check the findings against the new content — several earlier findings (missing empty-state copy, missing `edge="end"`, provider availability filtering, raw signals instead of a store) were already fixed by that 18:51–18:54 edit round.

Method: full read of the five files plus the host internals they depend on (`runtime/extension/panels.tsx`, `side-region.tsx`, `render.tsx`, `host.tsx`, `shell/state/layout.tsx`, `session/session-layout.ts`, `session/screen-layout.ts`, `session-panel-width.ts`, `shell/tabs/tabs.tsx`, `shell/state/session-tabs.ts`, `ui/resize-handle.*`, `gui-extensions/src/sdk/*`, `gui-extensions` panel renderers for terminal/review/browser/file, `terminal/panel.tsx`, `terminal/model.ts`), plus the preview harness (`research/sidebar-projects/tools/preview.mjs`) to know what the screenshot can and cannot show.

Typecheck was run and **passes** on this snapshot:

```
cd packages/app
bunx tsc -p tsconfig.android.json --noEmit --tsBuildInfoFile %TEMP%\grid-audit-ts.tsbuildinfo
# 0 errors; 3239 files / 765,372 lines checked (TS 7.0.2, the repo's `tsc`)
```

(The `-p --noEmit` form was used instead of `bun run typecheck tsconfig.android.json` only to keep the emit/tsbuildinfo out of `packages/`; type diagnostics are the same. No build or preview was run, per instructions.) `oxlint` could not be run in this scratch checkout because `script/oxlint/anti-slop/*` is not present here; the lint items below are rule-level observations, not observed failures.

---

## Summary

| # | priority | area | defect |
| --- | --- | --- | --- |
| 1 | **P0** | layout | the row never gives the grid any width; the chat panel stays `100%`/fixed px and the grid is appended after it, so it is laid out past the right edge and clipped by `main { overflow-x: hidden }` |
| 2 | **P1** | Solid | outer keyed `<For each={frames()}>` over freshly-created arrays remounts *every* frame on every toggle/session switch |
| 3 | **P1** | host contract | the dock-region Terminal panel is rendered through `Panel.render` outside `DockRegion`; with `visible: () => true` it auto-spawns a PTY, loses the `#terminal-panel` frame id, and its own close calls `layout.close(DOCK)` while the grid frame stays open |
| 4 | **P1** | regression | existing `review.toggle` (mod+shift+r) and `terminal.toggle` (ctrl+`) still mutate the now-hidden side/dock state, so they do nothing visible in vertical mode; dock/side open state can then be true while their DOM is gone |
| 5 | **P1** | correctness | only `tabs()[0]` is rendered; opening a *second* file/browser tab switches the strip's `active` key but the frame keeps rendering the first tab, so the link/URL click looks dead |
| 6 | **P1** | correctness | `grow={row() === column.length - 1}` makes the only frame in a one-panel column `flex-1` and hides its height handle, so frame height is not resizable in that (common) state and stored heights stop applying |
| 7 | P2 | resize | height handle is clipped in half (and its touch expansion entirely) by the frame's `overflow-hidden`; 4 px grab strip inside the frame |
| 8 | P2 | resize | column/frame max sizes ignore the viewport/column height; no narrow-width fallback → at 768–1000 px the grid leaves the chat below its 450 px minimum, at 1280 px two max columns leave the chat ~0 px |
| 9 | P2 | persistence | grid state bypasses `persisted()`/`Persist.*`: raw `localStorage`, raw key outside `opencode.global.dat:*`, `typeof localStorage` guards, a synchronous full-state serialise on every resize pointermove, no pruning of pathname keys |
| 10 | P2 | keying | `useLocation().pathname` is not the app's session identity (route can address a parent tab via `routeSessionId`; pathname also accumulates); use `session.layout.tabKey()` / `session.identity.sessionKey()` |
| 11 | P2 | resize | column widths are stored by column index; when a column disappears/is filtered the surviving column inherits the wrong stored width |
| 12 | P2 | style/types | `provider() as unknown as { mobile?: ... }` cast is unnecessary (`Panel.mobile` is already typed `MobileView`), plus two non-null assertions in the render |
| 13 | P2 | a11y | toggle buttons expose `aria-expanded` instead of `aria-pressed`, can be `disabled` while showing pressed; frames are unnamed `<section>`s; closing a frame that holds focus drops focus to `<body>` |
| 14 | P2 | regression | `sideVisible()` stays true when the hidden side region is "open", so the timeline still reserves header space for a review toggle that is not rendered in grid mode |
| 15 | P2 | provider pick | `GRID_PANEL_EXTENSIONS` claims "preference order" but `find(.includes)` follows registry order, not the array order |
| 16 | P2 | adjacent | `TitlebarRight` registrations are last-writer-wins and cached timelines stay mounted, so switching back to a cached session can leave another session's 摘要/详情 in the titlebar |
| 17 | P2 | style | hand-formatting (frame children indented 2 spaces too far, 147-char import) and module-level store; see notes |

No i18n defect: all visible copy in the new files goes through `language.t`; `verticalTabs.*` already existed and the new English-only `session.panelGrid.*` keys are present in `runtime/i18n/en.ts:653-663` (correctly untranslated). No `any`, no aliased/star imports, `createStore` used for the grid state, `mergeProps` getters are live in `panelInput`/`tabInput`, no effects/timers were added that need cleanup.

---

## P0 — the grid is laid out off-screen; the row has no width budget

**Where**
- `packages/app/src/session/screen.tsx:401-418` — chat panel keeps `md:flex-none` (`flex: 0 0 auto`) and `style={{ width: screen.panel.width() }}`.
- `packages/app/src/session/screen.tsx:443-451` — `<PanelGrid />` is appended to the same `flex flex-row gap-2` row (`screen.panel.ref`, line 381) with no width allocation.
- `packages/app/src/session/screen-layout.ts:19,29-30,56-62` — `panelWidth()` is `"100%"` when neither the side region nor the side dock is open, otherwise a fixed `resizedWidth()` px. It has no notion of the grid.

**Why it breaks**

`panelGridMode` only changes which children are *mounted*; it never changes the row's sizing contract. With the preview seed (`preview.mjs:388-396` opens neither side nor dock → `tabs.region(..., "side")` is `false`, `screen-layout.ts:19` → `besideOpen()` false → `panelWidth()` `"100%"`):

- chat panel: `flex-none` + `width: 100%` of the row;
- panel grid: `shrink-0` (`panel-grid.tsx:82`) with columns of `340px` each (default), i.e. ~688 px;
- row total = 100% + 8 px gap + 688 px; `main` has `overflow-x-hidden` (`shell/shell.tsx:74-75`, the `relative flex-1 … overflow-x-hidden` element), so everything after the chat panel is clipped.

Result: in the default vertical-tabs session state (and in the default preview state) the “grid-expanded” screenshot will show the chat at full width and **no panel frames at all** (or only a sliver when the side region/dock happens to be open). The toolbar toggles then appear to do nothing, because the frames they mount are outside the viewport. This is the single most important thing to verify with the first build.

**Minimal fix** (in `screen.tsx`; keeps non-grid mode byte-for-byte semantics):

```tsx
// classList, line 402-406
"@container relative z-10 min-w-0 shrink-0 flex flex-col min-h-0 h-full flex-1 transition-[width]": true,
"md:flex-none": !panelGridMode(),

// style, line 418
style={{ width: panelGridMode() ? undefined : screen.panel.width() }}

// handle, line 426
<Show when={!panelGridMode() && screen.panel.resizable()}>
```

With `flex-1` still present at all breakpoints and `md:flex-none` only in classic mode, the chat panel becomes `flex: 1 1 0%` next to the `shrink-0` grid in grid mode and fills the remaining width; `screen.panel.width()` (a percentage or the stored px width) is ignored because `flex-basis: 0%` wins in that mode.

Also pass the mode into the layout so the hidden regions do not keep a stale reservation, e.g. `createSessionScreenLayout(session, { wide, sidebar, grid: panelGridMode })` and use `grid` to force `tabsOpen()`/`dockSideOpen()` false inside that factory (`screen-layout.ts:19-23`); today a side or dock that was open before the switch keeps `screen.panel.width()` fixed and `sideVisible()` true even though their DOM is unmounted (see P2-14). The minimal version above already fixes the visible clipping; the factory change makes the state story consistent.

**Confidence:** high (CSS/flex arithmetic is deterministic; the clip is `main { overflow-x: hidden }`).

---

## P1 — toggling one frame remounts every frame

**Where:** `packages/app/src/session/panel-grid.tsx:74-79` and `:83-101`.

```ts
const frames = createMemo(() =>
  panelGrid.columns(props.sessionKey).map((column) => column.filter(available)).filter((column) => column.length > 0),
)
...
<For each={frames()}>{(column, index) => ( ... <For each={column}> ... )}
```

**Why it breaks**

`createMemo` has reference equality; `columns()` allocates a new outer array and new inner arrays on every run. `<For>` keys its children by item identity, so any run of `frames()` replaces every `column` item and unmounts/remounts every `PanelFrame`, with its `Contribution`/provider render. `frames()` re-runs whenever `panelGrid.open[key]` changes (any toggle), whenever `host.items(Panel)` changes (extension enable/disable/reload), and on session switch.

Concrete consequences: hiding the Browser frame remounts the Terminal (new xterm surface, scrollback/selection reset, PTY re-attach), re-creates the File browser subtree (scroll/filter reset), re-runs the Review model wiring, and can briefly re-render the browser pane. It also defeats the “independent frames” idea — the only state that should change is the toggled frame.

**Minimal fix:** use positional `<Index>` for the column list, which keeps the child keyed by position and passes the item as an accessor; the inner keyed `<For each={column()}>` then reuses panel components by id.

```tsx
// import { Index } from "solid-js"
<Index each={frames()}>
  {(column, index) => (
    <div class="relative flex flex-col gap-2 min-h-0 h-full shrink-0"
         style={{ width: `${panelGrid.width(props.sessionKey, index())}px` }}>
      <For each={column()}>
        {(id, row) => (
          <PanelFrame ... grow={row() === column().length - 1} />
        )}
      </For>
      ...
```

(Alternatively keep `<For>` and memoise with a structural `equals` that reuses unchanged inner arrays, but `<Index>` is the smaller change.)

**Confidence:** high.

---

## P1 — dock-region panel (Terminal) is hosted outside its region and changes behaviour

**Where**
- `packages/app/src/session/panel-grid.tsx:187-210` — every frame renders `provider.render(...)` behind a hand-built `PanelContext.Provider` with `visible: () => true`, `present: () => true`, `placement: () => "side"`.
- `packages/app/src/session/panel-grid-state.ts:76-79` — every session starts with **all four** panels open.
- Host reference implementation: `packages/app/src/runtime/extension/panels.tsx:386-542` (`DockRegion`) — the terminal panel is only ever mounted inside this `aside#terminal-panel`, with `visible: opened` (the per-session dock open state) and `present: opened || present`.
- Extension expectations: `packages/gui-extensions/src/terminal/panel.tsx:44,55-61,64-80` (`frame.visible` drives first-terminal auto-creation and the outside-focus listener), `packages/gui-extensions/src/terminal/model.ts:225-238` (`#terminal-panel` is the “inside” boundary for cancelling focus requests), `packages/gui-extensions/src/terminal/renderer.tsx:27-55` (`toggle`/`close` commands drive `layout.open/close(DOCK)`).

**Why it breaks**

1. **Auto-spawned shell.** `TerminalPanel` runs `createKeyed(opened, …)` with `opened = frame.visible`; in the dock that is `view().dock.opened()`, but the grid hard-codes `visible: () => true`. The default grid state has Terminal open in every session, so merely entering any session in vertical-tabs layout mounts `TerminalPanel` with `visible === true`, which calls `workspace.new()` when the workspace has no terminals (`terminal/panel.tsx:64-80`). That is a new side effect for a user who never asked for a terminal, on desktop and on the Android shell.
2. **Frame identity/cleanup semantics lost.** There is no `#terminal-panel` ancestor in grid mode, so the terminal's own outside-focus logic (`panel.tsx:55-61`, `model.ts:225-238`) treats every focus inside the grid terminal as “outside”. The terminal panel's own close (`renderer.tsx:143` → `layout.close(DOCK, …)`) only flips the hidden dock state: the grid frame stays open, and because the last-terminal watcher (`panel.tsx:83-89`) also calls `close()` on exit, an exited shell leaves a frame that renders an empty terminal tab strip.
3. **Placement lie.** `placement: "side"` is correct for review/file/browser, but a dock panel is being asked to render as if it were a side panel; today Terminal ignores placement, but this is not a contract the SDK supports for dock providers.

**Minimal fix (choose one, in increasing scope):**

- *Small, safe for the prototype:* stop exposing dock-region panels through the generic frame path. Filter frames to `entry()?.value.region === "side"` and keep the existing bottom/side `DockRegion` for Terminal in vertical mode instead of hiding it (i.e. do not hide the dock when the only grid panel it would contribute is Terminal). This keeps terminal semantics exactly as today, at the cost of Terminal not being a grid frame as originally sketched.
- *Keeps the design:* render dock-region providers through the generic dock host. In the frame body render the existing `<DockRegion … fill embedded present contentHeight="100%" reserve={!…} />` instead of `provider.render`, and make the titlebar Terminal toggle drive `screen.dock` open/close (`session.layout.view().dock.open()/close()`) so `visible`, auto-create, `#terminal-panel`, and close all keep working. The frame then only supplies chrome; no extension internals are named.
- *Cleanest long term:* extend the SDK with an explicit grid/frame placement (e.g. `PanelFrame.placement: "grid"` or a `Panel.region: "grid"`) so extensions can opt in and can read the real visibility; that requires the SDK-docs/lint update and belongs in a follow-up, not this prototype.

Either way, change the default open state (next item) so a fresh session does not mount a Terminal frame at all.

**Confidence:** high for the auto-spawn (code path is unambiguous); high for the close/focus mismatch by comparison to `DockRegion`.

---

## P1 — default state opens all four panels

**Where:** `packages/app/src/session/panel-grid-state.ts:76-79`

```ts
open(key: string) {
  return state.open[key] ?? [...GRID_PANELS]
}
```

**Why it breaks:** a brand-new session (or a pathname with no stored entry) renders four frames before the user presses any toggle:
- the titlebar buttons all report `pressed` (`vertical-session-toolbar.tsx:40-52`) even though the user never opened them;
- Review shows “No changes in this session.”, Browser shows its “Open a page” launcher, Files shows the file-browser launcher, and Terminal mounts/spawns a PTY (above);
- combined with P0, the user sees meta-UI claiming four open panels and no panel frame on screen.

If the intent is “Claude-style frames start empty and are opened from the titlebar”, the default must be `[]`. If the intent is a demo screenshot, that is fine only if the default is deliberately documented and the toggles reflect a visibly opened state; the auto-spawned terminal is still a defect.

**Minimal fix:**

```ts
open(key: string) {
  return state.open[key] ?? []
}
```

and, in `toggle`, use the same `[]` default for the updater. (With the current filter, unavailable providers already drop out, so an empty default renders no frames and the toolbar starts unpressed.)

**Confidence:** high (behaviour), medium on intent (prototype comment currently says “Every session starts with all four panels open”).

---

## P1 — existing commands/keybinds become no-ops in grid mode

**Where**
- `packages/app/src/session/commands/use-session-commands.tsx:449-455` — `review.toggle` (mod+shift+r) → `layout.view().side.toggle()`.
- `packages/gui-extensions/src/terminal/renderer.tsx:27-55` — `terminal.toggle` (ctrl+``) → `layout.open/close(DOCK, …)`.
- `packages/app/src/session/screen.tsx:443-451,453,582` — in grid mode the side region *and* both dock variants are unmounted (`Show when={!panelGridMode() && …}`).

**Why it breaks:** the commands still mutate `tabs.region(tab, "side")`/`"dock"`, which now has no DOM to show. In grid mode the palette item, desktop-menu item (`shell/commands/desktop-menu.ts:183-184`), the keybind and the in-session review toggle all run code the user cannot see. Worse, they leave state that becomes visible later: toggle review with mod+shift+r in grid mode, switch to horizontal layout, and the side region appears open although the user “closed” it (or vice versa) — and `screen.side.layout().visible` keeps influencing `screen.panel.width()` and `reserveReviewToggle` while the side region is hidden.

There is currently no keybind/command for the four new grid toggles, so keyboard users lose the review and terminal toggles entirely in the default (vertical) layout.

**Minimal fix:** route the existing commands through the grid when it is active, in one place. Since neither command file can import `PanelGrid` directly without dragging app shell state around, the smallest version is a grid-aware helper in `panel-grid-state.ts` (already imported by the titlebar):

```ts
/** In grid mode the titlebar owns side/dock panels; commands must use this instead of Layout.side/dock. */
export function toggleSidePanel(id: GridPanelID, fallback: () => void) {
  // caller passes key() (= location.pathname)
  ...
}
```

and in `use-session-commands.tsx`/`terminal/renderer.tsx` choose `toggleSidePanel("review", () => view().side.toggle())` / `toggleSidePanel("terminal", () => layout.open/close(DOCK))` when `screen.current()` exists and vertical tabs are active. (A cleaner option is to register a `Command` per grid toggle in one host component that knows both modes, and have the old commands delegate to the grid toggle while `panelGridMode` is on.) Also decide the dock/side state on entering/leaving grid mode: either close both (`setRegion(…, false)`) when `panelGridMode` becomes true, or keep them and accept the invisible-state desync; silently carrying it over is the bug.

**Confidence:** high (both command paths and the hidden `Show`s are explicit).

---

## P1 — the frame ignores the strip's selected tab

**Where:** `packages/app/src/session/panel-grid.tsx:141-145,155-158,187-210`

```ts
return props.tabs().all().filter((key) => key.startsWith(prefix)).map((key) => key.slice(prefix.length))
...
const tab = createMemo(() => tabs()[0])
```

(`Tabs` at line 20 already declares `active()`, but it is unused.)

**Why it breaks:** the side strip stores a *list* and an `active` key (`shell/state/layout.tsx:638-642`, `shell/state/session-tabs.ts:48-86`). Opening a file from a timeline link, or a page in the browser, appends/replaces a stored tab and sets it active, but the grid renders `tabs()[0]` — the first listed tab. Opening a second file while a first is pinned in the Files frame therefore changes `active` and leaves the visible content on the first file: the link appears to do nothing. Same for two Browser pages; the server-browser `list` also always appends its `main` launcher last, so “first” is especially arbitrary there.

The frame also has no way to switch among the listed tabs (the side strip that did it is hidden), so tabs 2..n are unreachable when the panel is in a frame. Closing the frame only hides the panel; the tabs remain in the strip.

**Minimal fix (renders the user's actual selection):**

```ts
const tab = createMemo(() => {
  const extension = entry()?.extension
  const active = props.tabs().active()
  const id = extension && active?.startsWith(`${extension}:`) ? active.slice(extension.length + 1) : undefined
  return tabs().find((item) => item.id === id) ?? tabs()[0]
})
```

Then, for full parity, either add a small tab selector/tab strip to the frame header (using `tabs()` + `props.tabs().setActive`/`select`) or accept the “one frame = selected tab only” scope explicitly. The Files launcher `["open"]` hack (line 152-153) stays as is.

**Confidence:** high on the mechanism; medium on how much tab switching the prototype is meant to expose.

---

## P1 — one-frame columns are not height-resizable

**Where:** `packages/app/src/session/panel-grid.tsx:98,169-170,212-221`

```tsx
grow={row() === column.length - 1}
...
classList={{ "flex-1": props.grow }}
style={props.grow ? undefined : { height: `${panelGrid.height(...)}px` }}
...
<Show when={!props.grow}><ResizeHandle edge="end" direction="vertical" ... /></Show>
```

**Why it breaks:** the last row of *every* column grows, including a column with a single frame. In that state the frame has no explicit height and no height handle, so `panelGrid.setHeight`/`height` are unused and the design rule “each frame height is resizable” is false whenever only one panel is open in a column — which is the common case once the user closes one of a pair (and also after provider filtering collapses a column to one frame).

The same `grow` prop also means the second frame of a pair can never be resized directly; resizing the first shrinks the second, which is acceptable, but the stored height of a frame is silently dropped while it is the growing row.

**Minimal fix** (shown for the existing `<For>` version; with the P1-2 `<Index>` version use `column().length`):

```tsx
grow={column.length > 1 && row() === column.length - 1}
```

and for a one-frame column keep the explicit `height()` + bottom handle (optionally give the column `justify-start`; leftover space below is expected). If the desired visual is “single frame fills the column, no resize”, then drop the “each frame height is resizable” requirement in the spec instead of leaving the state store half-used.

**Confidence:** high.

---

## P2 details

### 7. Height handle is clipped by the frame
`panel-grid.tsx:168` sets `overflow-hidden` on the `<section>`; `panel-grid.tsx:213-220` places the vertical `ResizeHandle` (CSS `position:absolute; height:8px; transform: translateY(50%)` at `inset-block-end:0`, `packages/ui/src/components/resize-handle.css:51-70`) as a child of that section. Half of the 8 px strip is outside the section's padding box and clipped, so the grab area inside the frame is ~4 px, on top of the bottom content. On touch (`@media (any-pointer: coarse)`, same file 73-81) the `::before` expansion is clipped too, making it nearly impossible to grab on the Android target.
**Fix:** render the height handle in the column as a sibling of the frame (mirroring the width handle at `panel-grid.tsx:102-110`), or wrap the frame in a positioned non-clipping div and keep `overflow-hidden` on an inner content div. Confidence high.

### 8. Clamps do not know the container; no narrow fallback
`panel-grid.tsx:102-110` uses `max={COLUMN_WIDTH_MAX}` (640) per column; two columns = 1280 px + gaps + chat, so at 1280 px viewport a user can squeeze the chat to 0 (`min-w-0`, and with P0 fixed `flex-1` shrinks). `panel-grid.tsx:212-221` `max={760}` for height is also unrelated to the row height: on a 770 px window the row is ~700 px, so dragging the first frame past that makes the growing second frame shrink to 0 (`min-h-0`) and can overflow. And `panelGridMode` has no width guard: at 768 px (still `isDesktop`) two default 340 px columns leave ~70 px for chat.
**Fix:** compute per-column `max` from the measured row width/`screen.panel.max()` minus chat minimum and other columns, and frame height `max` from the column's `clientHeight - gaps`; gate grid mode on a minimum usable width (e.g. `min-width: 1024px` media query, or `screen.panel.max() >= SESSION_PANEL_WIDTH_MIN`), falling back to the current dock/side layout below that. Confidence high on the arithmetic, medium on the exact breakpoint the product wants.

### 9. Persistence bypasses the app's storage layer
`panel-grid-state.ts:30,56-71,110-126,68-71`: module-level `createStore`, raw `localStorage.getItem/setItem("opencode.panel-grid.v1")`, `typeof localStorage` runtime checks, schema decode that resets **all** sessions on any unknown value, no pruning (one entry per visited pathname, `schema.ts:33-35` allows arbitrary keys), and `save()` is called from `setWidth`/`setHeight` on every pointermove (`resize-handle` has no end callback), synchronously serialising the whole state at drag rate.
Elsewhere in this app layout state goes through `persisted(Persist.global(...), Schema, …)` (`runtime/extension/panels.tsx:70-87` for the panel sidebar; `runtime/persistence/storage.ts` for namespacing/defaults/migration). The prototype comment already calls this a follow-up, but shipping in this form also means the state does not follow the desktop window storage path used by the rest of the layout, is not reset with settings, and can grow unbounded in a long-lived window.
**Fix:** declare a `Persisted` store (`Persist.global("panel-grid")`, or session scoped if it should be per window) and save coordinates only on drag end (add `onResizeEnd`/`onCollapseChange` support to the vendored `ResizeHandle`, or persist from a pointerup listener in the grid), and key by the session key rather than the full pathname (next item). Confidence high.

### 10. Pathname is not the session identity
`screen.tsx:449` passes `sessionKey={location.pathname}`; `vertical-session-toolbar.tsx:30` computes the same. The rest of the app keys this state by `session.layout.tabKey()`/`session.identity.sessionKey()` (`session/session-layout.ts:50-56`, `shell/state/layout.tsx:627-629`), and a parent session can be routed under a child's `routeSessionId` (see `session/model.ts:38-51`, `tabs.rememberSessionRoute`), so two pathnames can address the same shell tab. Today screen and toolbar agree (both pathname), so the UI is internally consistent, but per-session state diverges from the side strip/dock state and leaks an entry for every routed id. Use `session.layout.tabKey()` (or `session.identity.sessionKey()`) on both sides instead. Confidence high on the mismatch, medium on whether parent/child routes are in scope for this prototype.

### 11. Widths are stored by column index
`panel-grid-state.ts:107-119` stores `widths[key][columnIndex]`; `panel-grid.tsx:87,105,108` reads/writes by the *filtered* column index. If two columns exist with custom widths `[w0, w1]` and the first column's panels are both closed (or filtered out), the single surviving column reads index 0 and inherits `w0` instead of its own `w1`. Because `frames()` also `.filter`s unavailable panels, adding/removing an extension shifts indexes too.
**Fix:** key widths by a stable column identity, e.g. the first panel id in the column (`terminal`, `review`, `browser`, `file` are fixed and unique), or by the sorted member key joined with `+`; keep the index only as a fallback for migration. Confidence high (low user-visible frequency).

### 12. Unnecessary cast + non-null assertions
`panel-grid.tsx:159-161`:

```ts
const meta = createMemo(() => provider() as unknown as { mobile?: { title?: string; icon?: IconName } } | undefined)
```

`Panel` already exposes `mobile?: MobileView` (`gui-extensions/src/sdk/registries.ts:263-267`, `MobileView` at 198-213), so the cast is both unnecessary and unsafe (`no-chained-type-assertions`, `require-safety-comment-for-type-assertion` warn under the repo's oxlint config; the cast hides future SDK changes). Note it also means frame titles are the *mobile* strings, not the panel copy: e.g. Review gets `ctx.plural("mobile.title", 0)` → “Changes”, which happens to match but is a different concept.
**Fix:** `const meta = createMemo(() => provider()?.mobile)`; or, more semantically, `tab()?.title ?? language.t(spec.title)` for the frame header.

`panel-grid.tsx:206-207` uses `entry()!` / `provider()!` inside the `<Show>` children. It is currently safe (the `Show` condition is derived from the same memos), but it is the pattern the style guide bans. Restructure so the value is handed over by `Show`:
```tsx
<Show when={entry()}>
  {(current) => (
    <Contribution extension={current().extension}>
      {() => tab() && current().value.render(tabInput(() => props.view, props.screen, tab))}
    </Contribution>
  )}
</Show>
```
Confidence high (style; no runtime repro).

### 13. Toggle semantics / a11y
`vertical-session-toolbar.tsx:44-55`: a toggle button sets `state="pressed"` (visual) and `aria-expanded={pressed()}`. For a toggle that shows/hides a frame, `aria-pressed` (or `aria-expanded` plus `aria-controls` pointing at the frame) is the correct pair; the current combination exposes state via a property (“expanded”) with no controlled element, and the `Tooltip` wrapper around a `disabled` IconButton can still be announced. Also, because the default state opens all panels (P1), a button for a provider whose frame was filtered can be disabled **and** pressed at the same time.
`panel-grid.tsx:163-170`: the frame is an unnamed `<section>`; the title span is not referenced by `aria-labelledby`, so screen readers get “region” with no name. Add `aria-label={title()}` (or a generated id + `aria-labelledby`). Closing a frame that contains focus (e.g. focus in the file tree, then the titlebar toggle) unmounts the focused element and focus falls to `<body>`; the old dock/side regions explicitly blur on close (`panels.tsx:457-465`, `side-region.tsx:82-90`). Add the same blur/refocus handling in the frame (e.g. remember the toggle button and refocus it on close).
Confidence high for aria semantics, medium for focus handling expectations on touch-first Android.

### 14. `sideVisible()`/`reserveReviewToggle` still reflect the hidden side region
`screen.tsx:120,274`: `sideVisible()` is `isDesktop() && screen.side.layout().visible` and is fed to the timeline as `reserveReviewToggle={!sideVisible()}`. In grid mode `screen.side.layout().visible` can stay true (the stored side state is not cleared), while `SessionReviewToggle` is hidden (`screen.tsx:384-400`). The timeline then reserves header space for a button that is not rendered (and `screen.panel.width()` uses the “side open” branch — P0). Counts as a minor visual regression; fixing P0’s factory change (force side/dock closed in grid mode) removes it.

### 15. Provider “preference order” is not honoured
`panel-grid-state.ts:16-22` documents `GRID_PANEL_EXTENSIONS` as “in preference order”, but `panel-grid.tsx:130-132` and `vertical-session-toolbar.tsx:32-33` use
```ts
host.items(Panel).find((item) => GRID_PANEL_EXTENSIONS[props.id].includes(item.extension))
```
`host.items` is registry order, so if both `server-browser` and the desktop `browser` extension were active (both can contribute the Browser panel; the window built-in list currently registers only `server-browser`, `gui-extensions/src/renderer.ts:30-49`, but the desktop host composes the browser extension in other builds, see `gui-extensions/README.md:689`), the first-registered wins regardless of the declared preference. Fix by walking the preference list:
```ts
const items = host.items(Panel)
const entry = GRID_PANEL_EXTENSIONS[props.id].flatMap((extension) => items.filter((item) => item.extension === extension))[0]
```
Confidence high on the code, low on real-world impact in this build.

### 16. Adjacent (outside the five listed files): the titlebar right slot can show the wrong session
`session/timeline/message-timeline.tsx:606-619` portals `session.header` slots into `<TitlebarRight>` whenever `settings.appearance.tabLayout() === "vertical"`, and `shell/titlebar/right-slot.tsx:26-68` makes the **last registered** `<TitlebarRight>` the one that renders. Cached timelines are kept mounted while hidden (`session/timeline/cache.ts:23-56`; the SDK documents this for `session.header`), and a cached timeline that becomes visible again does **not** re-register (`onMount` ran once). So after A → B → A, B's registration can still be last, and the titlebar shows B's 摘要 chip/详情 popover while A is routed. The new `TitlebarRightMount` at `titlebar.tsx:768` makes this visible in the default (vertical) layout; before this prototype the same slot rendered inline in each timeline's own header.
**Fix:** gate the portal on the timeline's own active flag:
```tsx
<Show when={verticalTitlebar() && props.active !== false}>
  <TitlebarRight> … </TitlebarRight>
</Show>
```
(no fallback for hidden cached timelines), or give `TitlebarRight` an `active` accessor and re-register when it changes. Confidence medium-high (the ordering logic is explicit; the cached-mount behaviour is documented).

### 17. Style/formatting notes
- `panel-grid.tsx:172-221`: the `<section>` children are indented two spaces too far and the `<Show>` at 212 sits at the wrong level; `npm run build` does not care, but the repo's style is consistent formatting and the file will show a large formatting diff on the next `prettier`/format pass.
- `panel-grid.tsx:18`: 147-character import; split it.
- Module-level store (`panel-grid-state.ts:66`) is acceptable for this SPA but not reactive-scope-safe if the screen is ever SSR'd or mounted twice in one document (the state is global for all windows in the same JS realm; the desktop app opens one window, so fine for now).
- `panel-grid.tsx:20`: the local `Tabs` type duplicates the layout accessor type; it already includes `active()` (used by the P1 fix above). Consider exporting/reusing the type from `session/session-layout` instead of a structural copy.
- `titlebar.tsx` removes `hidden={hideVerticalTitlebar()}` (diff against v2.1.2), so the persistent header now also appears on macOS/Linux vertical-tabs desktop windows where it was previously hidden; that is the design (“persistent vertical-tabs titlebar”), but it is a visible layout change outside the panel grid and should be called out in review/screenshots. Verify the macOS traffic-light padding (`minHeight`/`padding-left`, `titlebar.tsx:80-86,173-178`) and the 36 px offset still look right — not testable from this Windows host or the web preview.

---

## What was checked and looks correct

- **Packing/order:** `columns()` (`panel-grid-state.ts:91-106`) packs in the fixed `GRID_PANELS` order, at most 2 per column, deterministic and stable across toggles; the fixed order is documented in the file. The hard rule holds.
- **Toggle order drift:** reopening a panel reinstates it in the fixed order instead of append order, which matches the documented rule.
- **Reactivity of the host registry:** `useExtensionHost().items(Panel)` is backed by a `createMemo` over host entries (`runtime/extension/host.tsx:250-297`), so calling it inside `createMemo`/render is reactive; the `.find` result does update when an extension contributes/withdraws. `available()` used in `frames()` is therefore also reactive.
- **`mergeProps` liveness:** `panelInput`/`tabInput` keep `session`/`tab` as getters and pass `screen` as the constant it is; this matches `panels.tsx:373-384`. `screen` is not re-created per read.
- **State container:** the grid state uses one `createStore` (per `packages/app/AGENTS.md` “createStore over multiple signals”), not a pile of signals.
- **i18n:** no hardcoded visible copy in any of the five files; `common.closeTab` was replaced by `session.panelGrid.close`; empty states are per panel and English-only, which matches the “English source strings only” rule. `session.files.selectToOpen` is no longer used for every panel.
- **Non-vertical modes:** the old side region and both dock variants are hidden only under `!panelGridMode()` (`screen.tsx:453,582`); horizontal/mobile rendering itself is untouched. The in-session review toggle is hidden only for desktop + vertical (`screen.tsx:384-390`), and mobile already never showed it.
- **`ResizeHandle` semantics:** existing callers pass absolute `size`/`min`/`max` and receive clamped values on every move (`ui/resize-handle.tsx`, `android-resize-pointer.mjs:33-44`); the grid uses the same contract and `setWidth`/`setHeight` do not need to double-clamp min (harmless). Column drag direction is right (horizontal default edge `end`), and the new `edge="end"` on the height handle fixes the earlier top-edge/inverted-drag bug — only the clipping (P2-7) remains.
- **`For` vs `Index` inside a column:** the inner `<For each={column}>` is keyed by panel id strings, so panel components are reused while a panel stays in that column; only the outer (P1-2) is wrong.
- **Provider availability filtering** (`panel-grid.tsx:70-79`) correctly drops frames for missing extensions and keeps the toolbar button disabled; this also means a crashed/disabled extension does not leave an empty column.

---

## Verification plan (after fixes)

Commands (as requested):

```powershell
cd C:\sensorsdata\main\program\opencode-android-shell\_scratch_root\desktop-parity\checkout\packages\app
bun run typecheck tsconfig.android.json

$env:VITE_OPENCODE_SERVER_MODE="none"; $env:VITE_OPENCODE_PLATFORM="android"; $env:OPENCODE_CHANNEL="prod"
bun run build --base / --sourcemap false --logLevel error `
  --outDir "C:/sensorsdata/main/program/opencode-android-shell/dist/sidebar-preview/ui-after" --emptyOutDir

cd C:\sensorsdata\main\program\opencode-android-shell
node research\sidebar-projects\tools\preview.mjs --ui dist\sidebar-preview\ui-after `
  --out research\sidebar-projects\shots --name grid-expanded
```

Before reading the screenshot, note what the harness seeds (`research/sidebar-projects/tools/preview.mjs`):
- `settings.v3` has `appearance.tabLayout: "vertical"` (lines 402-409), so the screenshot is in grid mode at 1280×770;
- `layout` seeds `sidebar.opened: false`, `terminal.opened: false`, `review.panelOpened: false`, `fileTree.opened: false` (388-396), so the P0 “no side/dock open → chat width 100 %” case is exactly what the first screenshot exercises;
- `review` currently crashes during startup in this harness (`[extension] review TypeError …` — see task-1 / `notes/review-preview-crash.md`), so the 变更 frame may be absent and the review toggle disabled. That is not a panel-grid failure; check `report.errors` to distinguish it from a filter bug.
- `server-browser`’s `list` ignores the strip and always ends with its `main` launcher (`gui-extensions/src/remote-browser/renderer.tsx:18-35`), and the file panel gets the `"open"` launcher from `panel-grid.tsx:152-153`, so the Browser/Files frames are expected to show launchers, not the generic empty-state copy, even with an empty strip.
- Terminal: `list` always returns its tab and the grid passes `visible: () => true`, so the frame mounts `TerminalPanel`; the mock backend answers the PTY inventory with `{ data: [] }` and a PTY create may fail or return empty. This is exactly P1-3 — the screenshot/console should show whether a shell was requested without the user opening the terminal.

What the screenshot/step run must prove:

1. **Frames actually exist on screen.** With the default seed, `[data-slot="session-panel-grid"]` must be inside the viewport: chat (`#session-chat-panel` or `[data-slot="session-chat-panel"]`) width ≈ viewport − shell insets − grid width − gaps; `document.querySelectorAll('[data-slot="session-panel-frame"]')` ≥ 3 (Terminal, Browser, Files; Review if the task-1 crash is fixed). If only the chat is visible, P0 is still present.
2. **Layout intent:** two columns of at most two frames, fixed order Terminal/Review then Browser/Files; each column ~340 px at default; no frame overflows downward past the row.
3. **Toggle buttons** (`[data-action="vertical-panel-terminal|review|browser|file"]`): clicking one hides only that frame; the remaining frames must not blank/re-mount (watch terminal scrollback survive, file-tree scroll/filter survive, browser pane keep its state). The button’s pressed state tracks the frame. Clicking all off leaves an empty grid area and a stable chat width.
4. **Second tab selection (P1-5):** open two files / two browser pages in one session; the visible frame must follow the selected (active) tab, not stay on the first. Use `--actions` to click two file links from the timeline (or `eval` to open `layout.panel` tabs) and an `eval` assertion on the frame’s `data-panel` + inner text.
5. **Height resize:** after the P1-6 fix, with one panel open, it must have a bottom handle and the stored height must apply. Drag down grows, drag up shrinks, min 140, no negative/0-height second frame; direction must match the edge (P2-7 fix).
6. **Width resize:** dragging column 1 changes only that column; chat shrinks; min 260/max 640 (per updated clamp); last column has no handle; reload (or re-run the harness) restores widths.
7. **Touch target:** with `hasTouch: true`/coarse pointer (the harness sets `hasTouch: false`, so this needs a device/emulator or a devtools emulation) both resize handles must be grabbable inside the frame; specifically confirm `::before` expansion is not clipped (P2-7).
8. **Session switching:** open A, close Terminal, open B, return to A — A’s grid state must persist, B’s must be its own, and no frame from A should remain mounted in B.
9. **tabLayout runtime switch:** from vertical switch to horizontal (settings appearance), verify the side region + dock come back with their previous state, no duplicate terminal (`[data-component="terminal-panel"]` count ≤ 1, no duplicate `#terminal-panel`), and the old review toggle reappears; switch back to vertical and confirm the frames restore. Also verify the old `review.toggle`/`terminal.toggle` commands are consistent with the new toggles (P1-4).
10. **Narrow/mobile fallback:** run at width 390 (and 768/1024) — under 768 the app must be the classic mobile layout, no grid, titlebar toolbar hidden; between 768 and the chosen grid breakpoint the layout must not squeeze the chat below `SESSION_PANEL_WIDTH_MIN` (P2-8).
11. **Focus/keyboard:** tab into a frame, toggle it off from the titlebar, focus must not stay on a detached node; terminal typing works in a frame (no `#terminal-panel` assumption); `Esc`/inert behaviour unchanged in non-grid mode.
12. **Console/network:** no new errors from PanelGrid; no unexpected PTY creation when the user has not opened the Terminal frame (P1-3); `localStorage["opencode.panel-grid.v1"]` exists after a toggle and is not written on every pointermove (P2-9) if that fix is included.

Assets to add once fixes land: `grid-expanded.png` (all four frames), `grid-terminal-closed.png`, `grid-session-b.png`, `grid-horizontal-after-toggle.png`, `grid-narrow-768.png`; plus a `--actions` transcript with the frame count/rect `eval`s above.

**Preview harness limitation for resizing:** `preview.mjs` actions support `click`/`hover`/`move`/`clickAt`/`key`/`eval` only (lines 555-565); there is no pointer-down → move → pointerup drag. Width/height clamping and the pointer capture path therefore need either a new `drag` action (`page.mouse.down` + `move` + `up`) added to the harness or a manual check against the dev server; do not claim resize verification from the current `--actions` set alone.

---

## Suggested fix order

1. **P0-1** (row width) + **P1-2** (`Index`) — makes the feature visible and stable; rebuild and take the first screenshot.
2. **P1-3** (Terminal hosting / default open) + **P1-6** (single-column height) — the two behavioural differences users will notice next.
3. **P1-4** (commands) + **P1-5** (selected tab) — restores parity with the classic layout.
4. **P2-7/8/9/10/11/12/13** as polish; **P2-16** if the summary chip/details move into the titlebar is in scope (it was part of the design intent, so it should be).

Caveat: this checkout is a disposable scratch tree with sibling work in flight (the review crash under `notes/review-preview-crash.md`, and the panel-grid files changed at 18:51–18:54 during this audit). If the files move again, the P0/P1 findings should be re-checked first; the P2 items are largely independent of the edit round.
