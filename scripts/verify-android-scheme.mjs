import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { startBackend, startUiServer, nativeBridgeSource, ocHostLoaderSource, seedScript, base64url } from "./verify-android-terminal-release.mjs"

/**
 * 系统深浅色跟随的回归验证。
 *
 * 背景（真机 bug）：系统从浅色切到深色时，页面不跟着换肤。
 * 上游 UI 判断"跟随系统"只走 `matchMedia("(prefers-color-scheme: dark)")`
 * （`packages/ui/src/theme/context.tsx` 的 `getSystemMode()` + `onMount` 的 change 监听），
 * 而 Android WebView 不保证该媒体查询随 `uiMode` 翻转更新；宿主其实每帧都在
 * insets payload 里带着真值 `scheme`。
 *
 * 本脚本钉住一半：
 *   A. 桥本身 —— `oc-host.js` 注入后，`(prefers-color-scheme: dark)` 的
 *      `matches` / `change` 必须跟随 `applyInsets` 的 `scheme`，且不影响其它媒体查询。
 *   B. 真实 SPA —— 打包的上游 UI 在 `colorScheme=system` 下，宿主推新 `scheme`
 *      后必须真的换肤（`data-color-scheme` 与页面底色）。
 */

const root = fileURLToPath(new URL("../", import.meta.url))
const hostSource = fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")
const output = path.join(root, "research/tmp/verify-android-scheme", new Date().toISOString().replace(/[:.]/g, "-"))
fs.mkdirSync(output, { recursive: true })
const report = { scope: "packaged SPA + injected oc-host.js; emulated native bridge and host insets, not an Android device", checks: [], output }

let backend, uiServer, browser
const errors = []

/** 与 Kotlin `InsetsHost.publish` 同形的 payload（只关心 scheme 的两条链路在这里）。 */
const insets = scheme => JSON.stringify({
  safeTop: 0,
  safeBottom: 0,
  safeLeft: 0,
  safeRight: 0,
  imeBottom: 0,
  imeHeight: 0,
  imeVisible: false,
  windowHeight: 1000,
  imeMode: "vars",
  dark: scheme === "dark",
  scheme,
})

async function check(name, run) {
  try {
    const detail = await run()
    report.checks.push({ name, status: "pass", detail })
    console.log("PASS", name, JSON.stringify(detail))
  } catch (error) {
    report.checks.push({ name, status: "fail", error: error.stack })
    console.error("FAIL", name, error.message)
  }
}

try {
  const { chromium } = await import(process.env.VERIFY_TERMINAL_PLAYWRIGHT || new URL("../_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs", import.meta.url).href)
  browser = await chromium.launch({ executablePath: process.env.VERIFY_TERMINAL_CHROMIUM || "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe", headless: true })

  // ---- A. 桥本身：不需要后端，只让 oc-host.js 在真实 DOM 上跑起来 ----------
  const bridgeContext = await browser.newContext({ viewport: { width: 420, height: 900 } })
  const bridge = await bridgeContext.newPage()
  bridge.on("pageerror", error => errors.push(error.stack))
  await bridge.addInitScript({ content: nativeBridgeSource() + ";" + ocHostLoaderSource(hostSource) })
  await bridge.goto("data:text/html,<html><body><div id=app></div></body></html>", { waitUntil: "domcontentloaded" })
  await bridge.waitForFunction(() => !!window.__ocAndroid)

  await check("bridge-installs-and-reports-light-query", async () => {
    return bridge.evaluate(() => {
      const mq = window.matchMedia("(prefers-color-scheme: dark)")
      const other = window.matchMedia("(prefers-reduced-motion: reduce)")
      return { media: mq.media, matches: mq.matches, otherMedia: other.media, otherTag: Object.prototype.toString.call(other) }
    })
  })

  await check("bridge-follows-host-scheme-and-fires-change", async () => {
    const seen = await bridge.evaluate(() => {
      const mq = window.matchMedia("(prefers-color-scheme: dark)")
      const events = []
      mq.addEventListener("change", event => events.push(event.matches))
      const apply = scheme => window.__ocAndroid.applyInsets(JSON.stringify({
        safeTop: 0, safeBottom: 0, safeLeft: 0, safeRight: 0, imeBottom: 0, imeHeight: 0,
        imeVisible: false, windowHeight: 1000, imeMode: "vars", dark: scheme === "dark", scheme,
      }))
      apply("light")
      const light = { matches: mq.matches, events: events.length }
      apply("dark")
      const dark = { matches: mq.matches, events: events.length }
      apply("dark")
      const repeated = { matches: mq.matches, events: events.length }
      apply("light")
      const back = { matches: mq.matches, events: events.length }
      return { light, dark, repeated, back, events }
    })
    assert.equal(seen.light.matches, false)
    assert.equal(seen.dark.matches, true, "matches must follow the pushed scheme")
    assert.equal(seen.dark.events, 1, "a flip must fire exactly one change event")
    assert.equal(seen.repeated.events, 1, "re-applying the same scheme must not fire again")
    assert.equal(seen.back.matches, false)
    assert.deepEqual(seen.events, [true, false])
    return seen
  })

  await check("bridge-legacy-addListener-path", async () => {
    return bridge.evaluate(() => {
      const mq = window.matchMedia("(prefers-color-scheme: dark)")
      let fired = 0
      const fn = () => { fired += 1 }
      mq.addListener(fn)
      window.__ocAndroid.applyInsets(JSON.stringify({
        safeTop: 0, safeBottom: 0, safeLeft: 0, safeRight: 0, imeBottom: 0, imeHeight: 0,
        imeVisible: false, windowHeight: 1000, imeMode: "vars", dark: true, scheme: "dark",
      }))
      mq.removeListener(fn)
      window.__ocAndroid.applyInsets(JSON.stringify({
        safeTop: 0, safeBottom: 0, safeLeft: 0, safeRight: 0, imeBottom: 0, imeHeight: 0,
        imeVisible: false, windowHeight: 1000, imeMode: "vars", dark: false, scheme: "light",
      }))
      return { fired }
    }).then(detail => {
      assert.equal(detail.fired, 1, "addListener must receive the change, removeListener must stop it")
      return detail
    })
  })

  await check("bridge-leaves-other-media-queries-native", async () => {
    const detail = await bridge.evaluate(() => {
      const other = window.matchMedia("(prefers-reduced-motion: reduce)")
      return { media: other.media, tag: Object.prototype.toString.call(other), sameAsSchemeQuery: other.media === "(prefers-color-scheme: dark)" }
    })
    assert.equal(detail.media, "(prefers-reduced-motion: reduce)")
    assert.equal(detail.tag, "[object MediaQueryList]")
    assert.equal(detail.sameAsSchemeQuery, false)
    return detail
  })

  await check("bridge-honours-native-initial-scheme", async () => {
    const pageWithInitial = await bridgeContext.newPage()
    pageWithInitial.on("pageerror", error => errors.push(error.stack))
    await pageWithInitial.addInitScript({
      content: 'window.__ocHostSchemeInitial="dark";' + nativeBridgeSource() + ";" + ocHostLoaderSource(hostSource),
    })
    await pageWithInitial.goto("data:text/html,<html><body></body></html>", { waitUntil: "domcontentloaded" })
    await pageWithInitial.waitForFunction(() => !!window.__ocAndroid)
    const detail = await pageWithInitial.evaluate(() => {
      const atBoot = window.matchMedia("(prefers-color-scheme: dark)").matches
      window.__ocAndroid.applyInsets(JSON.stringify({
        safeTop: 0, safeBottom: 0, safeLeft: 0, safeRight: 0, imeBottom: 0, imeHeight: 0,
        imeVisible: false, windowHeight: 1000, imeMode: "vars", dark: false, scheme: "light",
      }))
      return { atBoot, afterLightPush: window.matchMedia("(prefers-color-scheme: dark)").matches }
    })
    await pageWithInitial.close()
    assert.equal(detail.atBoot, true, "the native initial value must win over a stale WebView media query")
    assert.equal(detail.afterLightPush, false)
    return detail
  })

  await bridgeContext.close()

  // ---- B. 真实 SPA：system 模式下推 scheme，页面必须换肤 -------------------
  backend = await startBackend()
  backend.workspace = path.join(output, "workspace")
  fs.mkdirSync(backend.workspace, { recursive: true })
  fs.writeFileSync(path.join(backend.workspace, "README.md"), "# verify-android-scheme\n")
  backend.sessionId = "ses_scheme_acceptance"
  uiServer = await startUiServer()
  const ui = `http://127.0.0.1:${uiServer.address().port}`

  const context = await browser.newContext({ viewport: { width: 420, height: 900 }, locale: "en-US" })
  const page = await context.newPage()
  page.on("pageerror", error => errors.push(error.stack))
  const seed = seedScript({ serverOrigin: backend.origin, workspace: backend.workspace, sessionId: backend.sessionId })
    .replace('localStorage.setItem("opencode-color-scheme", "dark")', 'localStorage.setItem("opencode-color-scheme", "system")')
  await page.addInitScript({ content: seed + ";" + nativeBridgeSource() + ";" + ocHostLoaderSource(hostSource) })
  await page.goto(`${ui}/server/${base64url(backend.origin)}/session/${backend.sessionId}`, { waitUntil: "domcontentloaded" })
  await page.locator('[data-slot="session-chat-panel"]').first().waitFor()

  const readTheme = () => page.evaluate(() => ({
    scheme: document.documentElement.dataset.colorScheme,
    storage: localStorage.getItem("opencode-color-scheme"),
    background: document.documentElement.style.backgroundColor,
    media: window.matchMedia("(prefers-color-scheme: dark)").matches,
  }))

  await check("spa-follows-host-scheme-without-reload", async () => {
    const seeded = await readTheme()
    assert.equal(seeded.storage, "system", "the app must be in system color-scheme mode for this check")
    await page.evaluate(payload => window.__ocAndroid.applyInsets(payload), insets("light"))
    await page.waitForFunction(() => document.documentElement.dataset.colorScheme === "light")
    const light = await readTheme()
    await page.evaluate(payload => window.__ocAndroid.applyInsets(payload), insets("dark"))
    await page.waitForFunction(() => document.documentElement.dataset.colorScheme === "dark")
    const dark = await readTheme()
    await page.evaluate(payload => window.__ocAndroid.applyInsets(payload), insets("light"))
    await page.waitForFunction(() => document.documentElement.dataset.colorScheme === "light")
    const back = await readTheme()
    assert.equal(dark.media, true, "the media query the app listens to must report dark")
    assert.equal(back.media, false)
    assert.notEqual(dark.background, light.background, "the page background must change with the scheme")
    return { seeded, light, dark, back }
  })

  await check("no-page-errors", async () => {
    assert.deepEqual(errors, [])
    assert.equal(await page.evaluate(() => window.__ocHostError ?? null), null)
    return { errors }
  })

  await page.screenshot({ path: path.join(output, "scheme-phone.png"), fullPage: true })
  await context.close()
} catch (error) {
  report.checks.push({ name: "scheme-runner", status: "fail", error: error.stack })
  console.error(error.stack)
} finally {
  if (browser) {
    try { await browser.close() } catch (error) { console.error("close-browser", error.message) }
  }
  if (backend) for (const socket of backend.sockets.values()) socket.socket.destroy()
  for (const server of [backend?.server, uiServer].filter(Boolean)) {
    try {
      server.closeAllConnections()
      await new Promise(resolve => server.close(resolve))
    } catch (error) { console.error("close-server", error.message) }
  }
  report.totals = {
    pass: report.checks.filter(check => check.status === "pass").length,
    fail: report.checks.filter(check => check.status === "fail").length,
  }
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify(report, null, 2))
  console.log("REPORT", output, JSON.stringify(report.totals))
  process.exitCode = report.totals.fail ? 1 : 0
}
