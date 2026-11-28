#!/usr/bin/env node
// Shared pure helpers for packaged Android UI verification runners. Keep this
// module free of runner-specific report state; server lifecycle helpers live
// with the backend/server implementations until those are extracted too.

/** The mock Kotlin bridge: every method oc-host.js reaches for. */
function nativeBridgeSource() {
  return `(() => {
  const calls = []
  window.__ocNativeCalls = calls
  window.__ocPlatform = {
    onmessage: null,
    postMessage(raw) {
      const request = JSON.parse(raw)
      calls.push({ name: request.method, args: request.payload })
      if (["browserConfig", "configureBrowser"].includes(request.method)) throw new Error("Built-in browser platform methods have been removed")
      const value = request.method === "readClipboardImage" ? null : true
      queueMicrotask(() => this.onmessage?.({ data: JSON.stringify({ id: request.id, value }) }))
    },
  }
  const record = (name, args) => { try { calls.push({ name, args }) } catch {} }
  window.__ocAndroidNative = {
    hostInfo: () => JSON.stringify({ model: "Chromium-Emulated-Android", sdk: 34, brand: "verify" }),
    hasNotificationPermission: () => 1,
    requestNotificationPermission: () => record("requestNotificationPermission"),
    postMessage: (t, b, s) => { record("postMessage", [t, b, s]); return 0 },
    pickFile: (a, m) => record("pickFile", [a, m]),
    readPickedFile: () => JSON.stringify({ error: "no file" }),
    saveContent: (n, m, b) => record("saveContent", [n, m, b]),
    clearPickedFile: () => record("clearPickedFile"),
    share: (t, s) => { record("share", [t, s]); return true },
    setKeepScreenOn: (on) => record("setKeepScreenOn", [on]),
    setImeMode: (m) => { record("setImeMode", [m]); window.__ocImeMode = m },
    getImeMode: () => window.__ocImeMode || "resize",
    retry: () => record("retry"),
    reportTheme: (dark) => record("reportTheme", [dark]),
    proxyOrigin: () => "http://127.0.0.1:0",
    proxyPrefix: () => "/opencode-proxy/unused",
  }
})()`
}


/** Seeds the exact localStorage shape the production persistence layer reads. */
function seedScript({ serverOrigin, workspace, sessionId }) {
  const serverKey = serverOrigin
  const state = {
    list: [{ type: "http", http: { url: serverOrigin } }],
    hidden: {},
    projects: { [serverKey]: [{ worktree: workspace, expanded: true }] },
    lastProject: { [serverKey]: workspace },
    recentlyClosed: {},
  }
  return `(() => {
  try {
    const server = ${JSON.stringify(state)}
    localStorage.setItem("opencode.global.dat:server", JSON.stringify(server))
    localStorage.setItem("opencode.global.dat:server.v3", JSON.stringify(server))
    localStorage.setItem("opencode-color-scheme", "dark")
    localStorage.setItem("opencode-theme-id", "oc-2")
    localStorage.setItem("opencode.android.tabPlusCreatesSession", "false")
  } catch {}
})()`
}

const base64url = (v) =>
  Buffer.from(v, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "")

/**
 * The complete oc-host.js, loaded verbatim, at the earliest instant that has a
 * DOM root — still before any page script runs.
 *
 * Why not a plain `addInitScript`: Playwright (and Chromium's
 * `Page.addScriptToEvaluateOnNewDocument`, which is what WebView's
 * `addDocumentStartJavaScript` mirrors) evaluates scripts *before* the parser has
 * created `document.documentElement`. Parts of oc-host.js append styles through
 * `(document.head || document.documentElement)`, so evaluating too early can throw
 * before the rest of the facade installs. The host file is never edited; this
 * loader just hands it a moment where `document.documentElement` exists and no
 * page script has executed yet.
 */
function ocHostLoaderSource(hostSource) {
  return `(() => {
  if (window.__ocHostInstalled) return
  const run = () => {
    if (window.__ocHostInstalled) return true
    const root = document.documentElement
    if (!root) return false
    window.__ocHostInstalled = true
    try {
      ;(0, eval)(window.__ocHostSource)
    } catch (err) {
      window.__ocHostError = String((err && err.stack) || err)
    }
    return true
  }
  window.__ocHostSource = ${JSON.stringify(hostSource)}
  if (run()) return
  const obs = new MutationObserver(() => { if (run()) obs.disconnect() })
  obs.observe(document, { childList: true, subtree: true })
  document.addEventListener("readystatechange", run, { once: true })
  document.addEventListener("DOMContentLoaded", run, { once: true })
})()`
}


export { nativeBridgeSource, ocHostLoaderSource, seedScript, base64url }
