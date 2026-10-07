# OpenCode (Android)

Native **Jetpack Compose** reimplementation of the official OpenCode web frontend
(`@opencode/app`, `v2` branch of `github.com/anomalyco/opencode`), aiming for
**1:1 UI / feature / interaction parity**. Baseline: **2.0.11**
(see `_upstream/packages/app/package.json`).

Installed name: **OpenCode**  
Package id: `com.xiufeigo.opencodeshell` (unchanged)  
Min SDK: 26 (Android 8.0) · target/compile SDK: 35

> History note: before 1.4.0 this app embedded the official frontend in a
> WebView; that shell is retired. The launcher is now the native Compose
> `OcActivity`; the old `MainActivity` WebView stays only as a non-launcher
> fallback until the WebView is fully removed (with it, `LocalGateway`,
> `frontend/patches`, and the vendored bundle under
> `app/src/main/assets/opencode-app/`). The retired shell's own documentation
> lives in [`README-webview-legacy.md`](README-webview-legacy.md).

## Features

- **Sessions**: list with Today/Yesterday/Older groups + search, new session,
  rename, delete, fork, revert, session tabs, back-stack restores last page.
- **Streaming chat**: SSE-driven timeline, paced reveal, stop button,
  tool cards (icon-tinted, no side bar), grouped context tools, error cards,
  true hunk diffs.
- **Markdown**: Markwon rendering with tables (`TableLayout`), Prism4j syntax
  highlighting in the `OcTheme` dual-scheme palette, code-block chrome.
- **Docks**: permission + question docks (v1), **v2 form dock** (all six field
  types, `when` linkage, external-link pages).
- **Composer**: inbox **queue + steer** (`delivery`), structured **@mentions**
  (file/agent/skill/command), attachments, model/agent pickers, shell `!` and
  command `/` prefixes.
- **Command palette** (Ctrl+K style entry points), **Settings** (General /
  Shortcuts / Servers / Providers / Models), **terminal** (vendored Termux VT
  kernel driving the remote PTY protocol — font/colors/grid parity, no
  Ghostty-only features).
- **i18n**: English + 简体中文 + 繁體中文 (re-collected from v2,
  `research/_i18n_v2/`). **Theme**: dark + light (`OcTheme`, OC-2 tokens).

## Server support: dual protocol (v1 serve vs v2 serve)

Each server is **hard either/or** — one generation drives the store. The
generation is probed on connect and persisted per server key
(`generation:<url>`); unknown results keep existing behavior and re-probe next
time. Normative table: `V2Capability.COVERAGE` in code; human mirror:
[`research/v2_coverage.md`](research/v2_coverage.md); transport notes:
[`research/v2_audit/wave1-notes.md`](research/v2_audit/wave1-notes.md).

| | v1 serve (1.x, e.g. 1.18.31) | v2 serve (2.x, e.g. 2.0.11) |
|---|---|---|
| Probe | `GET /global/health` → JSON with `version` | `GET /api/info` → JSON `version` starting `"2."` (+ `GET /api/location` confirms directory) |
| Auth | none required | Basic auth supported |
| Transport | `V1Transport` over `OpenCodeClient` | `V2Transport` over `V2Api` + `V2EventAdapter` + `V2SeqTracker` |
| Streams | v1 `/event` SSE | `GET /api/event` + per-session experimental log (`?after=&follow=true`) |
| Only here | question dock, share/unshare/archive, todo, legacy `prompt_async`/shell/command bodies, abort | inbox queue/steer, form dock (replaces questions), fork `{before}`, `DELETE …/revert`, `POST …/interrupt` flag |

Probe order is always `/api/info` first, then `/global/health` — a v1 server
answers the info probe with SPA HTML (never selected as v2) and vice versa.

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
./gradlew assembleDebug
```

Windows: `gradlew.bat assembleDebug`.

`JAVA_HOME` note: use Android Studio's JBR (e.g. `/c/Program Files/Android/Android
Studio/jbr`) — a newer system JDK can crash the Kotlin daemon
(see `research/v2_audit/PROGRESS.md`, environment note).

## Point the app at the backend

1. Start a server on a machine the phone can reach:
   ```bash
   opencode serve --hostname 0.0.0.0 --port 4096        # v1
   OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=secret \
     opencode serve --hostname 0.0.0.0 --port 4095      # v2, Basic auth
   ```
2. In the app, open server settings and add the server URL plus the Basic-auth
   username/password when the server requires it.
3. Emulator reaching a host-loopback server: forward the port, then add
   `http://127.0.0.1:<port>` in the app:
   ```bash
   adb reverse tcp:4095 tcp:4095   # and/or: adb reverse tcp:4096 tcp:4096
   ```

## Testing

```bash
./gradlew :app:testDebugUnitTest      # legacy native shell
./gradlew :webapp:testDebugUnitTest   # web shell (what CI runs)
```

JVM unit suite (native shell ≈1,250 tests; the web shell adds ≈246 — see
`research/v2_audit/wave2.4a-notes.md` / `wave2.4b-notes.md`).

Device verification is not claimed here: Wave 4 E2E is still in flight — see
`research/wave4_*_report.md` as the acceptance evidence location.

## CI: what the GitHub Release contains

`.github/workflows/build.yml` builds the **web shell only** (`:webapp`; the
legacy native `:app` is not built in CI because dozens of its tests depend on
gitignored device-capture fixtures under `research/v97_verify/**`). It runs the
web-shell unit tests, assembles **one** APK and publishes exactly that file as
both the Actions artifact and the GitHub Release asset:

| | |
|---|---|
| Steps | `:webapp:testDebugUnitTest` → `:webapp:assembleRelease` |
| Release asset | `opencode-2.0.2.apk` (built from `webapp/build/outputs/apk/release/`) |
| Package id | `com.xiufeigo.opencodeshell` — **every** variant carries this one id (no `.debug` suffix anywhere); guard step: `aapt2 dump badging` must report exactly this id, else CI fails |
| Version | `versionCode 29` / `versionName 2.0.2` (`webapp/build.gradle.kts`) |
| Signature | the shared keystore from CI secrets (`760dc05f…`) — the same key every previous GitHub release used, so this APK **updates an installed GitHub build in place** |

`debug` and `releaseR8` are **local-only** variants: three APKs sharing one
applicationId + versionCode can never coexist on a device, so shipping them all
only made the download page ambiguous. A `debug` APK is produced only when the
build itself is debug (`./gradlew :webapp:assembleDebug`), never by CI.

> ⚠️ Locally built APKs are signed with this machine's `~/.android/debug.keystore`
> (`220657df…`), which is *not* the CI key (`760dc05f…`): installing a local
> debug build over a CI-signed install fails with
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. That is a keystore mismatch, not a
> package-name problem — point `DEBUG_KEYSTORE_PATH` at a copy of the CI
> keystore if you need the two to be interchangeable.

## Repo layout

- `app/` — the native app (Kotlin + Compose). Top level: `ChatViewModel`,
  `ChatStore`, `OpenCodeClient`, `EventStream`, `ServerConfigStore`,
  `BasicAuth`, `OcActivity` (launcher) / `MainActivity` (legacy fallback).
  `app/.../ui/` holds `theme/`, `components/`, `session/`, `home/`, `nav/`,
  `settings/`, `palette/`, `terminal/`, `markdown/`, `overlay/`, `servers/`;
  `app/.../v2/` holds the dual-protocol layer (`SessionTransport`,
  `V1Transport`, `V2Transport`, `V2Api`, `V2Capability`, `V2EventAdapter`,
  `V2SeqTracker`). Termux VT sources are vendored under
  `app/src/thirdparty/termux/` (`:terminal-view`, `:terminal-emulator`).
- `_upstream/` — **read-only** sparse checkout of `anomalyco/opencode` (`v2`
  branch) used as the UI/behavior reference. Do not edit; its own `AGENTS.md`
  applies inside.
- `research/` — audits and verification docs: `research/v2_audit/` (waves
  A0–A5 + `wave1-notes.md`, `wave2.*-notes.md`, `PROGRESS.md`),
  `research/v2_coverage.md`, `research/wave4_*.md` + `research/wave4_shots/`.
- `scripts/` — still relevant: `generate-launcher-icons.py`,
  `check-apk-launcher-icon.py`. WebView-era (`sync-opencode-app.sh`,
  `extract-i18n.mjs`, `selftest-embedded-ui.mjs`) no longer drives the build.
- `frontend/` — `OPENCODE_APP_VERSION` (legacy bundle pin), `branding/`
  (icon provenance), `patches/` (legacy WebView patches).

## Launcher icon / branding

Official OpenCode mark (favicon-v3) drawn full-bleed opaque on `#131010`;
**legacy-only** bitmaps (no adaptive XML) because HyperOS composed the adaptive
layers into a white plate with a tiny glyph. Source and regeneration — see
[`frontend/branding/SOURCE.md`](frontend/branding/SOURCE.md):

```bash
python3 scripts/generate-launcher-icons.py
python3 scripts/check-apk-launcher-icon.py app/build/outputs/apk/debug/app-debug.apk
```

If a previous build left a white square on Xiaomi/HyperOS, **uninstall +
reinstall** (the launcher caches icons by package).

## Security

- Server URLs and Basic Auth live in the app's `ServerConfigStore`, never in a
  WebView.
- HTTPS backends use the **system** certificate store plus **user**-installed
  CAs. Cleartext HTTP stays enabled for LAN (`network_security_config.xml`).
- Do not commit keystores or real passwords. Prefer a trusted LAN or HTTPS +
  Basic Auth.

## License

MIT. The `_upstream/` reference copy of OpenCode remains under OpenCode's own
license.
