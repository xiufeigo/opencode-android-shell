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

const hostAssemblyPath = "frontend/host-runtime/assembly.json"
const hostAssembly = JSON.parse(read(hostAssemblyPath))
if (!Array.isArray(hostAssembly.copies) || !Array.isArray(hostAssembly.hostPieces) || !hostAssembly.hostPieces.length) {
  failures.push(`${hostAssemblyPath}: copies and hostPieces must be non-empty arrays`)
}
for (const [key, list] of [["copies", hostAssembly.copies || []], ["hostPieces", hostAssembly.hostPieces || []]]) {
  if (new Set(list).size !== list.length) failures.push(`${hostAssemblyPath}: ${key} contains duplicate entries`)
  for (const file of list) {
    if (!fs.existsSync(`frontend/host-runtime/${file}`)) failures.push(`${hostAssemblyPath}: ${key} entry is missing: ${file}`)
  }
}
const copies = Array.isArray(hostAssembly.copies) ? hostAssembly.copies : []
const hostPieces = Array.isArray(hostAssembly.hostPieces) ? hostAssembly.hostPieces : []
const hostBuild = read("frontend/host-runtime/build.mjs")
if (!hostBuild.includes("assembly.json")) failures.push("frontend/host-runtime/build.mjs must read assembly order from assembly.json")
if (hostBuild.includes("facade-bootstrap.js") || hostBuild.includes("terminal-settings.js")) failures.push("frontend/host-runtime/build.mjs duplicates host piece ordering instead of reading assembly.json")

if (fs.existsSync("frontend/host-runtime/oc-host.js")) failures.push("frontend/host-runtime/oc-host.js must not reappear; edit the named runtime pieces instead")
const host = hostPieces.map(file => read(`frontend/host-runtime/${file}`)).join("")
for (const needle of ["__ocAndroidShell", "__ocAndroidEscapePassThrough", "oc:ready"]) {
  if (host.includes(needle)) failures.push(`dead page-surface contract reintroduced without a consumer: ${needle}`)
}

for (const needle of [
  "installTerminalFontScale",
  "installTerminalTouchScroll",
  "ocTerminalRenderScale",
  "ocRawMeasureText",
  "ocScaleFontPx",
  "OC-TERM-TOUCH",
  "proto.measureText = function",
]) requireAbsent(host, needle, "oc-host terminal boundary")


for (const file of copies) {
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



{
  const uiDir = "webapp/src/main/assets/www/ui"
  const manifestPath = `${uiDir}/opencode-build.json`
  if (!fs.existsSync(manifestPath)) {
    failures.push(`${manifestPath}: generated upstream UI build manifest is missing`)
  } else {
    const manifest = JSON.parse(read(manifestPath))
    for (const key of ["repository", "commit", "version", "bun"]) {
      if (manifest[key] !== upstream[key]) {
        failures.push(`${manifestPath}: ${key}=${manifest[key]} does not match frontend/android/upstream.json ${upstream[key]}`)
      }
    }
    if (manifest.platform !== "android") failures.push(`${manifestPath}: platform must be android`)
  }
  if (fs.existsSync(`${uiDir}/_assets`)) {
    failures.push(`${uiDir}/_assets must not exist in packaged Android assets; vite logical URLs are mapped to ui/oa`)
  }
  if (!fs.existsSync(`${uiDir}/oa`)) {
    failures.push(`${uiDir}/oa: packaged Android chunk directory is missing`)
  }
  const uiIndexPath = `${uiDir}/index.html`
  if (!fs.existsSync(uiIndexPath)) {
    failures.push(`${uiIndexPath}: generated upstream UI entry is missing`)
  } else {
    const uiIndex = read(uiIndexPath)
    if (uiIndex.includes("/oa/")) failures.push(`${uiIndexPath}: generated HTML must keep logical /_assets URLs; LocalUiOriginServer owns the disk mapping`)
    const refs = [...uiIndex.matchAll(/(?:src|href)="\/_assets\/([^"]+)"/g)].map(match => match[1])
    if (!refs.length) failures.push(`${uiIndexPath}: no logical /_assets bundle references found`)
    for (const ref of new Set(refs)) {
      if (!fs.existsSync(`${uiDir}/oa/${ref}`)) failures.push(`${uiIndexPath}: referenced bundle chunk is missing from ui/oa: ${ref}`)
    }
  }
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


{
  const contractPath = "docs/architecture/host-bridge-contract.json"
  const contract = JSON.parse(read(contractPath))
  if (contract.version !== 1 || !Array.isArray(contract.contracts)) {
    failures.push(`${contractPath}: unsupported contract inventory shape`)
  } else {
    const ids = new Set()
    for (const entry of contract.contracts) {
      if (!entry.id || ids.has(entry.id)) failures.push(`${contractPath}: duplicate or missing contract id ${entry.id}`)
      ids.add(entry.id)
      if (!entry.value || !entry.owner) {
        failures.push(`${contractPath}: ${entry.id} lacks value or owner`)
        continue
      }
      const checkFile = (path, token, role) => {
        if (!fs.existsSync(path)) {
          failures.push(`${contractPath}: ${entry.id} ${role} file is missing: ${path}`)
          return
        }
        if (!read(path).includes(token)) {
          failures.push(`${contractPath}: ${entry.id} ${role} ${path} does not contain required token ${token}`)
        }
      }
      checkFile(entry.owner, entry.ownerToken || entry.value, "owner")
      for (const participant of entry.participants || []) {
        checkFile(participant.path, participant.token || entry.value, "participant")
      }
    }
  }
}


{
  const releaseRunner = read("scripts/verify-android-terminal-release.mjs")
  for (const needle of ["function nativeBridgeSource", "function seedScript", "function ocHostLoaderSource", "const base64url", "function startUiServer", "function listen(server)", "function closeServer(server)", "function startBackend", "class MockPtySocket", "function wsAccept"]) {
    if (releaseRunner.includes(needle)) failures.push(`verify-android-terminal-release owns shared pure helper that belongs to a shared Android verify module: ${needle}`)
  }
  if (!releaseRunner.includes("resolveTerminalModule()")) failures.push("verify-android-terminal-release must consume the shared terminal module resolution")
  if (releaseRunner.includes("process.env.VERIFY_TERMINAL_MODULE")) failures.push("verify-android-terminal-release duplicates VERIFY_TERMINAL_MODULE resolution instead of verify-android-ui-server")
  const uiServer = read("scripts/verify-android-ui-server.mjs")
  if (!uiServer.includes("function resolveTerminalModule")) failures.push("verify-android-ui-server must own terminal module override resolution")
  if (uiServer.includes("Ghostty replacement slot")) failures.push("verify-android-ui-server contains stale Ghostty terminology")
  for (const name of fs.readdirSync("scripts")) {
    if (!name.startsWith("verify-android-") || !name.endsWith(".mjs")) continue
    const path = `scripts/${name}`
    if (read(path).includes("Ghostty replacement")) failures.push(`${path}: stale Ghostty replacement terminology; xterm is the single terminal implementation`)
  }
  for (const name of fs.readdirSync("scripts")) {
    if (!name.endsWith(".mjs") || ["verify-android-terminal-release.mjs", "verify-android-common.mjs"].includes(name)) continue
    const path = `scripts/${name}`
    const source = read(path)
    if (/from ["']\.\/verify-android-terminal-release\.mjs["']/.test(source) && /(nativeBridgeSource|ocHostLoaderSource|seedScript|base64url|startUiServer|startBackend)/.test(source.split("\n").find(line => line.includes("verify-android-terminal-release.mjs")) || "")) {
      failures.push(`${path}: imports shared verification infrastructure from the terminal release runner instead of its canonical module`)
    }
  }
}


{
  const testSourcePath = "webapp/src/test/java/com/xiufeigo/opencodeshell/web/testutil/TestSource.kt"
  if (!fs.existsSync(testSourcePath)) failures.push(`${testSourcePath}: canonical test source reader is missing`)
  const testRoot = "webapp/src/test/java/com/xiufeigo/opencodeshell/web"
  const visit = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const path = `${dir}/${entry.name}`
      if (entry.isDirectory()) visit(path)
      else if (entry.name.endsWith(".kt")) {
        const source = read(path)
        if (/private fun (src|read|moduleFile|sourceOf|asset)\(/.test(source)) failures.push(`${path}: reintroduces a private source-file reader instead of testutil.TestSource`)
        if (/File\(["']src\/main\/[^"']+["']\)\.readText\(/.test(source)) failures.push(`${path}: direct File(...).readText source read bypasses testutil.TestSource`)
      }
    }
  }
  visit(testRoot)
}


{
  const mainHostDir = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/host"
  if (!fs.existsSync(`${mainHostDir}/ImeResize.kt`)) failures.push(`${mainHostDir}/ImeResize.kt: production IME resize policy owner is missing`)
  for (const forbidden of ["ImeOcclusion.kt", "PhoneFormDefect.kt"]) {
    if (fs.existsSync(`${mainHostDir}/${forbidden}`)) failures.push(`${mainHostDir}/${forbidden}: test-only geometry must not live in production source`)
  }
  for (const name of fs.readdirSync(mainHostDir)) {
    if (!name.endsWith(".kt")) continue
    const path = `${mainHostDir}/${name}`
    const source = read(path)
    for (const needle of ["object ImeOcclusion", "object PhoneFormDefect", "object PhoneFormGeometry", "object ImeOcclusionGeometry"]) {
      if (source.includes(needle)) failures.push(`${path}: test oracle geometry leaked into production source: ${needle}`)
    }
  }
}


{
  const insetsHost = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt")
  const themeHost = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/SystemUiThemeHost.kt")
  if (insetsHost.includes("fun applyBarsTheme") || insetsHost.includes("WindowInsetsControllerCompat")) {
    failures.push("InsetsHost owns system-bar theme application again; keep that boundary in SystemUiThemeHost")
  }
  for (const needle of ["WindowInsetsControllerCompat(window, window.decorView)", "isAppearanceLightStatusBars", "isAppearanceLightNavigationBars", "window.navigationBarColor = Color.TRANSPARENT"]) {
    if (!themeHost.includes(needle)) failures.push(`SystemUiThemeHost no longer owns system-bar theme boundary: missing ${needle}`)
  }
}


{
  const modePath = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/ImeMode.kt"
  const resizeHostPath = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/ImeViewportResizeHost.kt"
  if (!fs.existsSync(modePath)) failures.push(`${modePath}: shared IME mode contract owner is missing`)
  if (!fs.existsSync(resizeHostPath)) failures.push(`${resizeHostPath}: viewport resize state-machine owner is missing`)
  const insetsHost = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt")
  if (insetsHost.includes("enum class ImeMode")) failures.push("InsetsHost owns the shared ImeMode contract again; keep it in ImeMode.kt")
  for (const needle of ["committedPadPx", "pendingPadPx", "commitResizeRunnable", "fun applyResize", "fun commitResize", "fun resizeTarget"]) {
    if (insetsHost.includes(needle)) failures.push(`InsetsHost owns viewport resize mechanics again: ${needle}`)
  }
  if (!insetsHost.includes("viewportResize.apply(imeMode, imeBottom, navBottom)")) failures.push("InsetsHost must delegate normalized insets to ImeViewportResizeHost")
  if (fs.existsSync(resizeHostPath)) {
    const resizeHost = read(resizeHostPath)
    for (const needle of ["fun apply(mode: ImeMode, imeBottom: Int, navBottom: Int)", "postDelayed(commitResizeRunnable, ImeResize.COMMIT_DELAY_MS)", "resizeTarget().setBackgroundColor(color)"]) {
      if (!resizeHost.includes(needle)) failures.push(`ImeViewportResizeHost lost required resize ownership boundary: ${needle}`)
    }
  }
}

if (!read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/InsetsHost.kt").includes("var imeMode: ImeMode = ImeMode.DEFAULT")) failures.push("InsetsHost must use ImeMode.DEFAULT as the single default IME mode")
if (read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt").includes("else \"vars\"")) failures.push("WebHostActivity duplicates a divergent IME mode fallback instead of ImeMode.DEFAULT")


{
  const serverPath = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiOriginServer.kt"
  const policyPath = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LocalUiRoutePolicy.kt"
  if (!fs.existsSync(policyPath)) failures.push(`${policyPath}: local UI route policy owner is missing`)
  const server = read(serverPath)
  for (const needle of ["fun normalize(", "fun refuseApiPath(", "fun toDiskPath(", "URL_BUNDLE_PREFIX =", "BUNDLE_DIR ="]) {
    if (server.includes(needle)) failures.push(`${serverPath}: route/path policy leaked back into the socket server: ${needle}`)
  }
  for (const needle of ["LocalUiRoutePolicy.refuseApiPath(path)", "LocalUiRoutePolicy.normalize(path)", "LocalUiRoutePolicy.toDiskPath(normalized)"]) {
    if (!server.includes(needle)) failures.push(`${serverPath}: must delegate to LocalUiRoutePolicy: missing ${needle}`)
  }
  if (server.includes("readAsset(LocalUiRoutePolicy.normalize(path))")) failures.push(`${serverPath}: double-normalizes request paths; readAsset owns the single normalization boundary`)
  if (fs.existsSync(policyPath)) {
    const policy = read(policyPath)
    for (const needle of ['const val URL_BUNDLE_PREFIX = "/_assets/"', 'const val BUNDLE_DIR = "oa"', "fun refuseApiPath", "fun normalize", "fun toDiskPath"]) {
      if (!policy.includes(needle)) failures.push(`${policyPath}: missing route policy contract: ${needle}`)
    }
  }
}


{
  const powerPolicy = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/PowerPolicy.kt")
  for (const needle of ["StreamingAction", "streamingAction", "BACKGROUND_STREAM_REFUSE_STATUS", "allowStreaming", "hasKeepScreenOn", "fun applyKeepScreenOn("]) {
    if (powerPolicy.includes(needle)) failures.push(`PowerPolicy reintroduced the dead loopback-proxy streaming policy: ${needle}`)
  }
}


{
  const lifecyclePath = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/BackgroundLifecycleHost.kt"
  const keepPath = "webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/KeepScreenOnHost.kt"
  if (!fs.existsSync(keepPath)) failures.push(`${keepPath}: keep-screen-on effect owner is missing`)
  const lifecycle = read(lifecyclePath)
  for (const needle of ["fun applyKeepScreenOn(", "private val keepScreenOnBackstop", "fun clearKeepScreenOnOnBackground(", "WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON"]) {
    if (lifecycle.includes(needle)) failures.push(`${lifecyclePath}: keep-screen-on effect machinery leaked back into lifecycle orchestration: ${needle}`)
  }
  for (const needle of ["keepScreenOn.applyState()", "keepScreenOn.onBackground()", "keepScreenOn.onForeground()", "keepScreenOn.onWebViewDisposed()", "keepScreenOn.onDestroy()"]) {
    if (!lifecycle.includes(needle)) failures.push(`${lifecyclePath}: must delegate keep-screen-on lifecycle boundary to KeepScreenOnHost: missing ${needle}`)
  }
  if (fs.existsSync(keepPath)) {
    const keep = read(keepPath)
    for (const needle of ["PowerPolicy.shouldKeepScreenOn", "postDelayed(keepScreenOnBackstop", "removeCallbacks(keepScreenOnBackstop)", "clearFlags(PowerPolicy.FLAG_KEEP_SCREEN_ON_BIT)", ".addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"]) {
      if (!keep.includes(needle)) failures.push(`${keepPath}: keep-screen-on owner lost required boundary behavior: ${needle}`)
    }
    if (keep.includes("AppCompatActivity")) failures.push(`${keepPath}: must depend on Window/runOnUiThread capabilities rather than the whole Activity`)
    for (const needle of ["private val window: Window", "private val runOnUiThread: (Runnable) -> Unit"]) {
      if (!keep.includes(needle)) failures.push(`${keepPath}: missing narrowed Android boundary dependency: ${needle}`)
    }
  }
}


{
  for (const tree of ["src/main/java", "src/main/res", "src/main/assets/www"]) {
    if (!gradle.includes(`"${tree}"`)) failures.push(`webapp/build.gradle.kts: test contract input tree is missing: ${tree}`)
  }
  for (const file of ["src/main/AndroidManifest.xml", "build.gradle.kts", "../frontend/terminal/terminal.js", "../frontend/android/upstream.patch"]) {
    if (!gradle.includes(`"${file}"`)) failures.push(`webapp/build.gradle.kts: test contract input file is missing: ${file}`)
  }
  if (/"src\/main\/(?:java|res|assets\/www)\/[^"]+"/.test(gradle)) {
    failures.push("webapp/build.gradle.kts: per-file src/main test inputs duplicate the canonical tree inputs")
  }

  const testRoot = "webapp/src/test/java"
  const testFiles = []
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const path = `${dir}/${entry.name}`
      if (entry.isDirectory()) walk(path)
      else if (entry.isFile() && entry.name.endsWith(".kt")) testFiles.push(path)
    }
  }
  walk(testRoot)
  const coveredByTree = (rel) =>
    (rel.startsWith("src/main/java/") && gradle.includes('"src/main/java"')) ||
    (rel.startsWith("src/main/res/") && gradle.includes('"src/main/res"')) ||
    (rel.startsWith("src/main/assets/www/") && gradle.includes('"src/main/assets/www"'))
  for (const file of testFiles) {
    const source = read(file)
    for (const match of source.matchAll(/TestSource\.read\("([^"]+)"\)/g)) {
      const rel = match[1]
      if (!coveredByTree(rel) && !gradle.includes(`"${rel}"`)) {
        failures.push(`${file}: TestSource.read contract input is not declared for test invalidation: ${rel}`)
      }
    }
  }
}


{
  const tracker = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/RoundActivityTracker.kt")
  for (const needle of ["RoundStreamSignal", "classifyRoundStream", "onRoundStreamSignal", "containsRoundEndMarker", "containsSubstantivePayload", "isRoundStartRequest", "ROUND_STATUS_MARKER", "SUBSTANTIVE_MARKER"]) {
    if (tracker.includes(needle)) failures.push(`RoundActivityTracker reintroduced the dead loopback-proxy traffic classifier: ${needle}`)
  }
}


{
  const bridge = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/WebHostBridge.kt")
  const legacy = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/LegacyBridgeChannel.kt")
  const facade = read("frontend/host-runtime/android-facade.js")
  for (const needle of ["fun getSecret(key", "fun setSecret(key"]) {
    if (bridge.includes(needle)) failures.push(`WebHostBridge reintroduced the speculative generic secret API: ${needle}`)
  }
  for (const needle of ['"getSecret" to', '"setSecret" to']) {
    if (legacy.includes(needle)) failures.push(`LegacyBridgeChannel reintroduced generic secret forwarding: ${needle}`)
  }
  for (const needle of ["getSecret: function", "setSecret: function"]) {
    if (facade.includes(needle)) failures.push(`android-facade reintroduced the speculative generic secret API: ${needle}`)
  }
}


{
  const verifyBridge = read("scripts/verify-android-common.mjs")
  for (const needle of ["proxyOrigin", "proxyPrefix"]) {
    if (verifyBridge.includes(needle)) failures.push(`verify-android-common mock bridge is wider than production: ${needle}`)
  }
}


{
  const activity = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/WebHostActivity.kt")
  for (const needle of ["private val systemUiTheme = SystemUiThemeHost(", "private val backgroundLifecycle = BackgroundLifecycleHost("]) {
    if (activity.includes(needle)) failures.push(`WebHostActivity constructs a window-dependent host before Activity attach: ${needle}`)
  }
  for (const needle of ["private lateinit var systemUiTheme: SystemUiThemeHost", "private lateinit var backgroundLifecycle: BackgroundLifecycleHost", "systemUiTheme = SystemUiThemeHost(", "backgroundLifecycle = BackgroundLifecycleHost("]) {
    if (!activity.includes(needle)) failures.push(`WebHostActivity must initialize window-dependent hosts inside onCreate after attach: missing ${needle}`)
  }
  const superAt = activity.indexOf("super.onCreate(savedInstanceState)")
  const systemUiAt = activity.indexOf("systemUiTheme = SystemUiThemeHost(")
  const lifecycleAt = activity.indexOf("backgroundLifecycle = BackgroundLifecycleHost(")
  if (!(superAt >= 0 && systemUiAt > superAt && lifecycleAt > superAt)) failures.push("window-dependent hosts must initialize after super.onCreate, when Activity.window exists")
}


{
  const startupRecovery = read("webapp/src/main/java/com/xiufeigo/opencodeshell/web/host/StartupRecoveryHost.kt")
  if (startupRecovery.includes("private val appContext = context.applicationContext")) failures.push("StartupRecoveryHost resolves applicationContext in its constructor; Activity context is not attached yet")
  if (!startupRecovery.includes("get() = context.applicationContext")) failures.push("StartupRecoveryHost must defer applicationContext resolution until post-attach use")
}

if (activitySource.includes("databaseEnabled")) {
  failures.push("WebHostActivity re-enables deprecated Web SQL database support without a caller")
}

if (failures.length) {
  console.error(failures.join("\n"))
  process.exit(1)
}
console.log("architecture checks passed")
