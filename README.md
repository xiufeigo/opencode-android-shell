# OpenCode (Android)

Android wrapper that **embeds** the official OpenCode V2 frontend (`@opencode-ai/app`, SolidJS + Vite) and talks to backends you add in the official **Settings → Server** UI — the same flow as OpenCode Desktop.

Installed name: **OpenCode**  
Package id: `com.xiufeigo.opencodeshell` (unchanged)  
Min SDK: 26 (Android 8.0)

Pinned frontend: see [`frontend/OPENCODE_APP_VERSION`](frontend/OPENCODE_APP_VERSION) (currently **v1.18.31**).  
Launcher icon: official OpenCode mark — see [`frontend/branding/SOURCE.md`](frontend/branding/SOURCE.md).
If a previous build left a white square on Xiaomi/HyperOS, **uninstall + reinstall** (the launcher caches icons by package).

## Architecture

## Architecture

```
┌─────────────────────────────────────────────┐
│  Android WebView                            │
│  origin: https://appassets.androidplatform.net │
│  assets: official packages/app dist         │
│  Cold start: official home UI               │
│  Servers: Settings → Server (in-app)        │
└───────────────────┬─────────────────────────┘
                    │ fetch / CORS
                    ▼
┌─────────────────────────────────────────────┐
│  PC: opencode serve (or opencode web)       │
│  http://192.168.x.x:4096                    │
│  --cors https://appassets.androidplatform.net │
└─────────────────────────────────────────────┘
```

- Launch goes **directly** into the official home (sidebar: 项目 / 添加项目 / 设置 / 帮助). There is no native “Connect server” screen.
- With zero servers, the official empty state is shown. Add a backend via **设置 → 服务器** (same list + 「添加服务器」 dialog as Desktop).
- The WebView asset origin is **not** treated as an HTTP server (see `frontend/patches/android-webview-no-origin-server.patch`).
- With zero servers, server-scoped providers use an **offline SDK stub** (never fetches `http://127.0.0.1:0`) so Layout/ServerSDK can init (`frontend/patches/android-webview-disconnected-sdk.patch`).
- Touch: native overlays do not sit above the WebView; zoom-control overlays are disabled; Kobalte tooltips are skipped on coarse pointers (`frontend/patches/android-webview-touchable-home.patch`).
- Safe area: `MainActivity` pads the **root** with `statusBars | navigationBars | displayCutout` (WebView view-padding is ignored on HyperOS). App chrome stays below the status bar.
- The agent **does not** run inside the APK.

## Run the backend (PC)

Prefer headless API + CORS (UI is already in the APK):

```bash
opencode serve --hostname 0.0.0.0 --port 4096 --cors https://appassets.androidplatform.net
```

`opencode web` also works if you pass the same `--hostname` / `--cors`. Binding `127.0.0.1` is not reachable from the tablet.

Optional Basic Auth (enter username/password in **Settings → Server** when adding the server):

```bash
OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=secret \
  opencode serve --hostname 0.0.0.0 --port 4096 --cors https://appassets.androidplatform.net
```

Persist CORS in `opencode.json` if you prefer:

```json
{
  "server": {
    "port": 4096,
    "hostname": "0.0.0.0",
    "cors": ["https://appassets.androidplatform.net"]
  }
}
```

Phone/tablet and PC must share Wi-Fi. Allow TCP **4096** on the PC firewall.

**CORS is required.** The embedded UI is a different origin from `http://192.168.x.x:4096`. Without `--cors https://appassets.androidplatform.net`, the WebView will not be allowed to call the API.

## Point the app at the backend

1. Install **OpenCode** and open it. You should see the official home immediately.
2. Open **设置 → 服务器** (Settings → Server).
3. Tap **添加服务器** and enter a URL such as `http://192.168.1.10:4096` or `https://backend.opencode.example`. Optional Basic Auth is on that same form.
4. The server list, default server, and health indicator match OpenCode Desktop.

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

The page is Chromium WebView, not OpenCode Desktop / Electron.

| Input | What to expect |
| --- | --- |
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
- **Icon still white after upgrade:** uninstall the app, then install the new APK. HyperOS keeps the previous adaptive icon until the package is removed. `versionCode` 6+ ships full-bleed dark `#131010` PNGs (window ~70% of the mask).
- **Title bar under the status bar:** `versionCode` 6+ pads the activity root with system-bar insets. If an older build is still installed, upgrade to 1.3.1+.

## Security

- Server URLs and Basic Auth live in the official WebView `localStorage` server list (same place Desktop’s web entry uses).
- Cleartext HTTP is enabled for LAN (`network_security_config.xml`) and mixed content is allowed because the UI origin is HTTPS while the API is often `http://192.168.x.x`.
- Do not commit keystores or real passwords. Prefer a trusted LAN or HTTPS + Basic Auth.

## License

MIT. Vendored OpenCode frontend remains under OpenCode’s own license (MIT in `@opencode-ai/app`).
