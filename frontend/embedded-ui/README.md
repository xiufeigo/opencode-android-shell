# Android embedded web platform

The APK's HTTP origin serves static UI assets, never an OpenCode API. The web
entry uses the upstream `VITE_OPENCODE_SERVER_MODE=none` behavior: it does not
register that origin as a builtin server. Server inventory, adding, editing and
switching remain controlled by the upstream registry and settings components.

`patch.mjs` applies this build option to the pinned entry chunk. It fails on
unknown upstream anchors. The existing frontend build invokes it, and CI checks
the generated entry alongside the terminal assets.

Before hydration, `server-state.mjs` removes only legacy asset-origin records,
visibility flags and defaults. It retains real server records and credentials,
projects, names and user visibility preferences. This is an idempotent upgrade
migration; there are no storage observers, forced reloads or DOM hiding rules.
The origin is the Android host's own loopback socket, which serves static assets
and rejects `/api/*`; a backend cannot share that socket. A remote backend using
the same port remains a distinct URL and is retained. Both registry generations
are parsed and validated before either one is updated.

The review-panel `+` belongs entirely to the official UI. The web platform opens
the workspace file browser directly. The official browser menu is conditional
on a platform `browserPane` implementation and its experimental setting; Android
currently uses the web platform and does not supply that desktop capability.

Run `npm test` and `npm run build` in `frontend/terminal`, then run
`node scripts/verify-android-navigation.mjs` from the repository root. The latter
uses the full packaged SPA on phone and tablet viewports, with emulated Android
bridge and mock backends; it is not Android device validation.

`resize-pointer.mjs` replaces the mouse-only drag lifetime of the same pinned
shared `ResizeHandle`. Pointer capture handles mouse, touch and pen, ignores
secondary pointers, and cleans up on release, cancellation, lost capture, window
blur and unmount. Size limits, RTL/edge calculation and official persistence stay
with the component. Cancellation clears collapse feedback without closing a pane.
The stylesheet disables browser panning only on handles and enlarges their
invisible hit target to 24px for coarse pointers. Visual gaps remain unchanged.
`resize-patch.mjs` checks the exact upstream component hash before transforming
it; the frontend build and CI also verify the generated stylesheet.

Run `node scripts/verify-android-resize.mjs` for the full SPA with trusted CDP
touch/mouse input, both axes, multiple pointers, cancellation, pane limits,
collapse/reopen and reload persistence. Reports retain the original failed run.
`node scripts/verify-android-resize-device.mjs` verifies both directions on the
actual debug APK WebView using ADB swipes, then restores the emulator's display
settings. It shares the existing device CDP transport with the terminal runner
and does not replace the native bridge or any served asset. This remains emulator
validation, not a physical tablet test.
