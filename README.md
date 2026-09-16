# OpenCode (Android)

Android wrapper that **embeds** the official OpenCode V2 frontend (`@opencode-ai/app`, SolidJS + Vite) and talks to backends you add in the official **Settings → Server** UI — the same flow as OpenCode Desktop.

Installed name: **OpenCode**  
Package id: `com.xiufeigo.opencodeshell` (unchanged)  
Min SDK: 26 (Android 8.0)

Pinned frontend: see [`frontend/OPENCODE_APP_VERSION`](frontend/OPENCODE_APP_VERSION) (currently **v1.18.31**).  
Launcher icon: official OpenCode mark — see [`frontend/branding/SOURCE.md`](frontend/branding/SOURCE.md).
If a previous build left a white square on Xiaomi/HyperOS, **uninstall + reinstall** (the launcher caches icons by package).

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  Android WebView                                         │
│  origin: https://appassets.androidplatform.net           │
│  static assets: WebViewAssetLoader → @opencode-ai/app    │
│  Cold start: official home UI                            │
│  Servers: Settings → Server (stores the REAL backend URL)│
└────────────────────────┬─────────────────────────────────┘
                         │ fetch / SSE / WebSocket
                         │ dedicated: http://127.0.0.1:<per-backend-port>/api|/global|/event|/file|/find|/path|/pty|/mcp|...
                         │ mux fallback: /opencode-proxy/<token>/<encoded-real-url>/...
                         │ PTY: browser WebSocket (or native OkHttp polyfill) → gateway Upgrade tunnel
                         ▼
┌──────────────────────────────────────────────────────────┐
│  In-app LocalGateway (loopback, OkHttp)                  │
│  origin-only port per backend (v1 `new URL('/api', base)`│
│  keeps the proxy) + path-encoded mux; strips Origin;     │
│  forwards method/path/query/body/auth **with no path     │
│  allowlist**; streams SSE; tunnels WebSocket Upgrade     │
│  for remote PTY `/pty/{id}/connect`                      │
└────────────────────────┬─────────────────────────────────┘
                         │ HTTPS (Basic Auth, redirects)
                         ▼
┌──────────────────────────────────────────────────────────┐
│  Your OpenCode server                                    │
│  e.g. https://backend.opencode.xiufeigo.xyz              │
│  CORS for the WebView origin is NOT required             │
└──────────────────────────────────────────────────────────┘
```

- Launch goes **directly** into the official home (sidebar: 项目 / 添加项目 / 设置 / 帮助). There is no native “Connect server” screen.
- With zero servers, the official empty state is shown. Add a backend via **设置 → 服务器** (same list + 「添加服务器」 dialog as Desktop). Enter the **real** backend URL (and optional Basic Auth). The shell keeps that URL in the official server list and rewrites only the SDK `baseUrl` onto the loopback reverse-proxy.
- **Proxy mode is the default.** The remote server does **not** need `--cors https://appassets.androidplatform.net`. OkHttp performs the real HTTPS; the WebView never relies on `Access-Control-Allow-Origin` from the backend.
- The WebView asset origin is **not** treated as an HTTP server (see `frontend/patches/android-webview-no-origin-server.patch`).
- With zero servers, server-scoped providers use an **offline SDK stub** (never fetches `http://127.0.0.1:0`) so Layout/ServerSDK can init (`frontend/patches/android-webview-disconnected-sdk.patch`).
- Touch: native overlays do not sit above the WebView; zoom-control overlays are disabled; Kobalte tooltips are skipped on coarse pointers (`frontend/patches/android-webview-touchable-home.patch`).
- Safe area: `MainActivity` pads the **root** with `statusBars | navigationBars | displayCutout` (WebView view-padding is ignored on HyperOS). App chrome stays below the status bar.
- Keyboard / IME: edge-to-edge makes `adjustResize` a no-op, so the root also pads `max(navigationBars, ime)`. Focused fields `scrollIntoView` when the keyboard opens.
- **Android Back** is forwarded into the page first (close Settings / dialogs / popovers, then in-app back). The Activity finishes only at true home with nothing to dismiss. Physical **ESC is not a shell shortcut** — it goes to the WebView (Vim / xterm / OpenCode keybinds) and never exits the app.
- Routing: Android uses the same **MemoryRouter** pattern as OpenCode Desktop so `.../opencode-app/index.html` is not treated as a Solid route (that paints titlebar-only / white home).
- Portrait Settings: below 840px the dialog stacks to a single column; category title is not sticky. Each General section is a collapsible `<details>` panel whose own title sticks while that block is in view (`frontend/patches/android-webview-portrait-settings.patch`, `frontend/patches/android-webview-settings-accordion.patch`).
- The agent **does not** run inside the APK.

## Run the backend (PC)

The embedded UI origin stays `https://appassets.androidplatform.net`. API calls from the page go to the in-app reverse-proxy on `127.0.0.1`, so **you do not need to allow that origin on the server**.

```bash
opencode serve --hostname 0.0.0.0 --port 4096
```

`opencode web` also works with `--hostname 0.0.0.0`. Binding `127.0.0.1` is not reachable from the tablet.

Optional Basic Auth (enter the same username/password in **Settings → Server** — the Android shell does not pop a system login dialog):

```bash
OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=secret \
  opencode serve --hostname 0.0.0.0 --port 4096
```

Phone/tablet and PC must share Wi-Fi (or the server must be a public HTTPS URL). Allow TCP **4096** on the PC firewall for LAN.

If you already pass `--cors https://appassets.androidplatform.net`, it is unused by this app (the gateway does not forward `Origin`). CORS remains optional for browser tabs that open the UI from a different origin.

### Reverse proxy (nginx / Caddy)

TLS in front of `opencode serve` (e.g. `https://backend.example.com` → `127.0.0.1:4096`) does **not** need `Access-Control-Allow-Origin` for Android. Forward `Authorization`, HTTP/1.1, and WebSockets; for SSE avoid buffering the event stream:

```nginx
proxy_http_version 1.1;
proxy_set_header Authorization $http_authorization;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
proxy_buffering off;
proxy_read_timeout 24h;
```

Health paths match official OpenCode clients: `GET /api/health`, then `GET /global/health`.

## Point the app at the backend

1. Install **OpenCode** and open it. You should see the official home immediately.
2. Open **设置 → 服务器** (Settings → Server).
3. Tap **添加服务器** and enter a **real** URL such as `http://192.168.1.10:4096` or `https://backend.opencode.xiufeigo.xyz`. Optional Basic Auth is on that same form. Do not paste the loopback `opencode-proxy` URL.
4. The add-server dialog notes that the shell reverse-proxies natively. If connect fails, the error distinguishes authentication vs generic network when possible. `adb logcat -s OpenCodeShell` prints gateway/TLS/health details.
5. After add, the new server is **applied immediately** (active connection + launch default). The card shows **当前** (Current) and **默认** (Default).
6. To switch later: **tap the server name/row** (勾选/应用) or **⋮ → 设为默认**. Both call `setActive` and persist the default. Providers, Models, and new sessions use that server. The green health dot alone does **not** mean “selected”.

Desktop’s Settings v2 list is management-only (no row click; `设为默认` is launch-default only). Switching the live SDK there happens from the session status popover / home sidebar. Android stays on Settings after add and has no home status popover, so this shell adds the tap-to-apply path.

## Operation-logic review (1.3.14)

Compared to official OpenCode web against local `opencode serve` with a real workspace directory. Remote **file tree** and **PTY** are in scope (the same HTTP/SSE/WebSocket APIs the official `@opencode-ai/app` uses). Out of scope only: Desktop/Electron OS integrations the web UI never offers (Android-local document picker, device-host PTY).

| Issue | Outcome |
| --- | --- |
| Settings v2 row is not selectable; after add you stay on the list with no 勾选 | **Fixed (1.3.13).** Row tap → `setActive` + `setDefault`. **当前** / **默认** tags. |
| `设为默认` only wrote `defaultServerUrl`, did not activate | **Fixed (1.3.13).** Menu item also `setActive`. |
| Providers / Models empty after native proxy | **Fixed (1.3.12).** Dedicated origin-only port + `keepProxyPrefix`. |
| Remote file tree / open / find / diff after apply marked N/A | **Fixed.** Gateway has no path allowlist (`/path`, `/file`, `/file/content`, `/find`, `/project`, `/vcs`, `/session/{id}/diff`). Add project uses official **server** directory dialog (`platform === "web"`). |
| Remote PTY / terminal WebSocket | **Fixed.** HTTP `POST /pty` plus WebSocket Upgrade `/pty/{id}/connect` (and `/api/pty/...`). HTTPS WebView origin uses a native OkHttp WebSocket polyfill so mixed-content `ws://127.0.0.1` cannot silently no-op. |
| MCP / permissions / search / command palette | **Fixed / tested.** HTTP `/mcp` `/api/mcp` `/permission` `/api/permission/request` `/find` `/api/fs/find`; E2E also dispatches the official web keybinds (`mod+k` palette, `mod+p` open file, `mod+;` MCP, `ctrl+\`` terminal, `mod+\\` file tree) and toggles Settings → Advanced → file tree / search. |
| Desktop session status popover | **N/A.** Web UI on Android applies the server from Settings → Server. |
| Android-device folder picker / device PTY | **Out of scope.** The web UI never offers those. |

No open blockers for web-UI feature parity through the shell.

## Build a debug APK

### Android Studio

1. JDK 17+ and Android SDK 35.
2. **File → Open** this repo root (`settings.gradle.kts`).
3. **Build → Build APK(s)**.
4. Output: `app/build/outputs/apk/debug/app-debug.apk`

### Gradle CLI

```properties
# local.properties
sdk.dir=/path/to/Android/sdk
```

```bash
chmod +x gradlew
./gradlew assembleDebug
```

Windows: `gradlew.bat assembleDebug`.

## Bump the OpenCode frontend

Needs [bun](https://bun.sh) and git.

```bash
chmod +x scripts/sync-opencode-app.sh
OPENCODE_REF=v1.18.31 ./scripts/sync-opencode-app.sh
```

That clones `anomalyco/opencode` at the tag, applies `frontend/patches/*.patch`, runs `vite build --base ./ --sourcemap false` in `packages/app`, copies `dist/` into `app/src/main/assets/opencode-app/`, and updates `frontend/OPENCODE_APP_VERSION`.

Then `./gradlew assembleDebug`. Commit the vendored assets with the pin file.

Do not hand-edit files under `app/src/main/assets/opencode-app/`.

## Keyboard, mouse, right-click (Xiaomi Pad)

The page is Chromium WebView, not OpenCode Desktop / Electron. The shell does **not** implement its own shortcut map; letter keys and Ctrl/Cmd chords go to the Solid app (same keybinds as Desktop).

| Input | What to expect |
| --- | --- |
| Android Back | First forwarded into the page as **Escape** (closes Settings / dialogs / popovers, one layer). Then in-app back/home if present. Then `WebView.goBack()` if the asset history allows it. **Only at true home with nothing to dismiss** does Back finish the Activity. Separate from the physical Esc key. |
| Escape / ESC | **Pass-through to the WebView.** The shell does not hijack editor/terminal keys and has no app-wide ESC shortcut. Focused xterm/Vim get normal Esc (leave insert mode, etc.). OpenCode’s own UI layer may close a dialog if one is open. ESC **never** `finish()`es the Activity. |
| USB / Bluetooth keyboard | Character keys and many Ctrl/Cmd shortcuts reach the Solid app when the WebView has focus. |
| Ctrl+C / Ctrl+V | Usually work. Some OEM shortcuts are swallowed by HyperOS. |
| Function keys, Alt+Tab | Often captured by the system, not the page. |
| Mouse left click / scroll | Works. |
| Mouse right-click | OpenCode’s own `contextmenu` handlers can fire on recent Android System WebView. There is **no** desktop browser chrome menu (Inspect, Open in new tab, etc.). Long-press is still the touch equivalent. |
| Hover tooltips | Depend on the pointer stack; not as complete as Desktop. |

Keep the WebView focused (tap the UI once after attaching a keyboard).

## HyperOS / Xiaomi notes

- **Settings → Apps → OpenCode → Battery** → Unrestricted if the session dies in the background.
- Allow **LAN / local network** if a privacy toggle appears.
- Install unknown sources to sideload the debug APK.
- Autostart is not required; the agent stays on the PC.
- **Icon still white / tiny glyph after upgrade:** uninstall the app, then install the new APK. HyperOS caches the previous adaptive icon until the package is removed. `versionCode` 12+ ships **legacy-only** full-bleed PNGs (no `adaptive-icon` XML).
- **Title bar under the status bar:** `versionCode` 6+ pads the activity root with system-bar insets. If an older build is still installed, upgrade to 1.3.3+.
- **Settings squished in portrait:** `versionCode` 8+ stacks the Settings dialog to a single column below 840px CSS width.
- **Settings「通用」stuck while scrolling:** `versionCode` 9+ hides the sticky category title; each section is a collapsible panel whose own title sticks.
- **Settings accordion stretched / blank scroll in portrait:** `versionCode` 10+ tightens collapsed section gaps and stops the panel from being `height: 100%` of the full dialog under the stacked nav.
- **Empty home still scrolls:** `versionCode` 11+ drops the stacked `min-h: 100cqh` on the sessions column (that plus 项目 and 设置/帮助 overflowed the card) and zeros CSS safe-area padding because the Activity already insets the WebView.
- **「添加服务器」fields hidden behind the keyboard:** `versionCode` 13 / **1.3.8** pads IME insets and scrolls the focused input into view.
- **「无法连接到服务器」while browser/Desktop work:** `versionCode` 14 / **1.3.9** reverse-proxies API/SSE in-app. The backend no longer needs CORS for `https://appassets.androidplatform.net`. Uninstall then reinstall if you are sideloading over an older debug APK.
- **Back from Settings exits to the launcher:** `versionCode` 15 / **1.3.10** forwards Android Back into the page (closes Settings). `versionCode` 16 / **1.3.11** does **not** consume ESC in the shell — Vim/terminal Esc pass through; only Back closes overlays / may exit at root.
- **Providers / Models empty after selecting a server (1.3.9–1.3.11):** official v1 `OpenCode.make` joins with `new URL('/api/...', baseUrl)`, which dropped `/opencode-proxy/<token>/<enc>`. Health still worked because it falls back to the v2 SDK (string concat) and `/global/health`. **1.3.12** binds a dedicated loopback origin per backend so `/api/provider`, `/api/model`, `/api/session`, `/global/*`, `/event`, and `/pty` stay on the proxy; fetch also restores a lost mux prefix. Protocol probes go through the proxy (not the real URL, so CORS is still unused).
- **Added a server but it was not applied (1.3.12):** official Settings v2 has no row-select; `设为默认` is launch-default only. **1.3.13** / `versionCode` 18: tap the card or 设为默认 to make that server the active connection (**当前** + **默认**). Uninstall then reinstall if you are sideloading over an older debug APK.
- **Remote files / remote PTY missing after apply:** **1.3.14** / `versionCode` 19. The gateway reverse-proxies every HTTP path and tunnels WebSocket Upgrade for `/pty/{id}/connect`. HTTPS WebView uses a native OkHttp WebSocket polyfill. E2E against `opencode serve` in a real workspace: **56 PASS / 0 FAIL / 1 N/A** (Desktop-only status popover). Checklist covers sessions, chat prompt, providers/models/config, remote file list/open/find/v2 fs, remote PTY WebSocket, MCP, permissions, VCS, command palette, and SSE. Debug APK sha256 `55657542db5d436bf6460d7015664ae8f1e4ac33e322d714316210834aa28f5d` — https://filebin.net/oc1314-1789555532/OpenCode-1.3.14-debug.zip (expires ~2026-09-23). Uninstall then reinstall if you are sideloading over an older debug APK.

## Security

- Server URLs and Basic Auth live in the official WebView `localStorage` server list (same place Desktop’s web entry uses). The stored URL is the real backend; only runtime fetches use `127.0.0.1/opencode-proxy/...`.
- The loopback gateway binds `127.0.0.1` only. The mux URL still requires a per-process path token; each selected backend also gets a dedicated ephemeral port (still loopback-only) so SDK absolute paths cannot skip the proxy.
- Cleartext HTTP is enabled for LAN (`network_security_config.xml`) and mixed content is allowed because the UI origin is HTTPS while the proxy and many LAN APIs are `http://127.0.0.1` / `http://192.168.x.x`.
- HTTPS backends use the **system** certificate store plus **user**-installed CAs (Settings → Security → Encryption & credentials). Custom private CAs work after the user installs the cert; the WebView does not bypass TLS errors. OkHttp uses the same system/user trust store.
- Do not commit keystores or real passwords. Prefer a trusted LAN or HTTPS + Basic Auth.

## License

MIT. Vendored OpenCode frontend remains under OpenCode’s own license (MIT in `@opencode-ai/app`).
