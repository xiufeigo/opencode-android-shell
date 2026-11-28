#!/usr/bin/env node
import fs from "node:fs"

const failures = []
const read = (path) => fs.readFileSync(path, "utf8")
const requireAbsent = (text, needle, label) => {
  if (text.includes(needle)) failures.push(`${label}: forbidden legacy implementation is present: ${needle}`)
}
const requirePresent = (text, needle, label) => {
  if (!text.includes(needle)) failures.push(`${label}: required canonical implementation is missing: ${needle}`)
}

const hostPieces = ["android-facade.js", "overlay-focus.js", "terminal-settings.js"]
if (fs.existsSync("frontend/host-runtime/oc-host.js")) failures.push("frontend/host-runtime/oc-host.js must not reappear; edit the named runtime pieces instead")
const host = hostPieces.map(file => read(`frontend/host-runtime/${file}`)).join("")
for (const needle of [
  "installTerminalFontScale",
  "installTerminalTouchScroll",
  "ocTerminalRenderScale",
  "ocRawMeasureText",
  "ocScaleFontPx",
  "OC-TERM-TOUCH",
  "proto.measureText = function",
]) requireAbsent(host, needle, "oc-host terminal boundary")


for (const file of ["index.html", "oc-perf.js", "oc-preflight.js"]) {
  const sourcePath = `frontend/host-runtime/${file}`
  const outputPath = `webapp/src/main/assets/www/${file}`
  if (!fs.existsSync(outputPath)) {
    failures.push(`${outputPath}: generated host-runtime artifact is missing`)
    continue
  }
  if (!fs.readFileSync(sourcePath).equals(fs.readFileSync(outputPath))) {
    failures.push(`${outputPath}: generated artifact differs from authoritative source ${sourcePath}; run node frontend/host-runtime/build.mjs`)
  }
}

{
  const outputPath = "webapp/src/main/assets/www/oc-host.js"
  if (!fs.existsSync(outputPath)) {
    failures.push(`${outputPath}: generated host facade artifact is missing`)
  } else if (!Buffer.from(host, "utf8").equals(fs.readFileSync(outputPath))) {
    failures.push(`${outputPath}: generated artifact differs from frontend/host-runtime pieces; run node frontend/host-runtime/build.mjs`)
  }
}

{
  const sourcePath = "plugins/server-browser/src/rpc.ts"
  const outputPath = "frontend/remote-browser/rpc.ts"
  if (!fs.existsSync(outputPath)) {
    failures.push(`${outputPath}: generated RPC client contract is missing; run node frontend/remote-browser/sync-rpc.mjs`)
  } else if (!fs.readFileSync(sourcePath).equals(fs.readFileSync(outputPath))) {
    failures.push(`${outputPath}: generated RPC client contract differs from ${sourcePath}; run node frontend/remote-browser/sync-rpc.mjs`)
  }
}

const terminal = read("frontend/terminal/terminal.js")
for (const needle of [
  "oc.settings.terminalFontScale",
  "pointerdown",
  "pointermove",
  "fling(velocity)",
  "class FitAddon",
]) requirePresent(terminal, needle, "xterm terminal adapter")

for (const path of [
  "webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalFontScaleTest.kt",
  "webapp/src/test/java/com/xiufeigo/opencodeshell/web/TerminalTouchScrollTest.kt",
]) {
  if (fs.existsSync(path)) failures.push(`obsolete Ghostty fallback test still exists: ${path}`)
}


for (const name of fs.readdirSync("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host")) {
  if (!name.endsWith(".kt")) continue
  const path = `webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/${name}`
  const source = read(path)
  if (source.includes("import com.xiufeigo.opencodeshell.web.WebHostActivity")) {
    failures.push(`${path}: host capability module imports the entry Activity`)
  }
}


const upstream = JSON.parse(read("frontend/android/upstream.json"))
const gradle = read("webapp/build.gradle.kts")
const readme = read("README.md")
const versionCode = /versionCode\s*=\s*(\d+)/.exec(gradle)?.[1]
const versionName = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1]
const hasStandaloneFact = (text, value) => {
  let at = 0
  while ((at = text.indexOf(value, at)) !== -1) {
    const before = text[at - 1] || ""
    const after = text[at + value.length] || ""
    if (!/[A-Za-z0-9]/.test(before) && !/[A-Za-z0-9]/.test(after)) return true
    at += value.length
  }
  return false
}
for (const value of [upstream.version, upstream.commit, upstream.bun, versionCode, versionName].filter(Boolean)) {
  if (hasStandaloneFact(readme, value)) failures.push(`README.md duplicates mutable version fact from its authority: ${value}`)
}
if (/当前 UI 基准为官方 v|Bun 必须为 \d/.test(read("frontend/android/README.md"))) {
  failures.push("frontend/android/README.md duplicates the current upstream/Bun authority")
}


const activitySource = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")

for (const needle of ["pendingCameraPermission", "cameraPermissionInFlight"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns camera permission state that belongs to CameraPermissionHost: ${needle}`)
}


if (activitySource.includes("onBackPressedDispatcher.addCallback")) {
  failures.push("WebHostActivity owns Back callback plumbing that belongs to BackNavigationHost")
}


if (activitySource.includes("private fun sameOrigin")) {
  failures.push("WebHostActivity owns origin comparison logic that belongs to WebOrigins")
}


for (const needle of ["watchdogArmed", "firstContentSeen", "loadIssuedAtMs", "WebViewCompat.getCurrentWebViewPackage"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns startup recovery state that belongs to StartupRecoveryHost: ${needle}`)
}


for (const needle of ["backgroundKeepAlive", "keepScreenOnBackstop", "backgroundRoundRelease", "clearKeepScreenOnOnBackground", "dispatchPageLifecycleEvent", "PowerPolicy.shouldKeepScreenOn", "FLAG_KEEP_SCREEN_ON"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns background lifecycle/power state that belongs to BackgroundLifecycleHost: ${needle}`)
}

for (const needle of ["WebViewPreflight.preflight(", "H.launch.enter", "J.loadUrlIssued", "restored.startsWith(uiOrigin)"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns startup navigation policy that belongs to StartupNavigationHost: ${needle}`)
}

for (const needle of ["EXTRA_FROM_NOTIFICATION", "opened from notification", "deliverOpenSession(sessionId)"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns notification-click consumption that belongs to NotificationLaunchHost: ${needle}`)
}

if (activitySource.includes("KeyEvent.KEYCODE_ESCAPE")) failures.push("WebHostActivity owns physical ESC event construction that belongs to WebHostEscapePolicy")

for (const needle of ["EVAL_SETTLE_MS", "OcWebHost/Eval"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns debug-eval behavior that belongs to DebugEvalHost: ${needle}`)
}

for (const needle of ["isFocusableInTouchMode", "setOnTouchListener", "viewportHost.addView", "Z.firstDraw"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns viewport/focus assembly that belongs to WebViewViewportFactory: ${needle}`)
}

for (const needle of ["private var pageReportedDark", "private fun applySystemBarTheme", "private var systemDark"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns system UI theme policy that belongs to SystemUiThemeHost: ${needle}`)
}

for (const needle of ["inner class HostWebChromeClient", "override fun onShowFileChooser", "EXTRA_MIME_TYPES"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns WebChromeClient behavior that belongs to HostWebChromeClient: ${needle}`)
}

for (const needle of ["shouldOverrideUrlLoading", "shouldInterceptRequest", "private var assetLoader", "inner open class HostWebViewClient"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns WebViewClient behavior that belongs to HostWebViewClient: ${needle}`)
}

for (const needle of ["private fun configureSettings", "private fun installFacade", "private fun installPerfProbe", "private fun installPreflightProbe"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns WebView bootstrap policy that belongs to WebViewBootstrapHost: ${needle}`)
}

for (const needle of ["lastRenderCrashAtMs", "renderCrashCount", "RENDER_CRASH_RELOAD_WINDOW_MS", "SystemClock.elapsedRealtime"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns renderer crash recovery state that belongs to RenderProcessRecoveryHost: ${needle}`)
}

for (const needle of ["notificationPermissionRequested", "notificationPermissionState()", "new CustomEvent('oc:notifyPerm'"]) {
  if (activitySource.includes(needle)) failures.push(`WebHostActivity owns notification permission state that belongs to NotificationPermissionHost: ${needle}`)
}

if (activitySource.includes("databaseEnabled")) {
  failures.push("WebHostActivity re-enables deprecated Web SQL database support without a caller")
}

if (failures.length) {
  console.error(failures.join("\n"))
  process.exit(1)
}
console.log("architecture checks passed")
