#!/usr/bin/env node
/**
 * Verify the APK's actual Android WebView and system IME, without replacing
 * its native bridge, assets, activity or HTTP host. Owns only a mock PTY backend.
 * Usage: node scripts/verify-android-terminal-device.mjs [--inspect] [--label=name]
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { startBackend, seedScript, base64url } from './verify-android-terminal.mjs'

const exec = promisify(execFile)
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const ADB = process.env.ADB || 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
const SERIAL = process.env.ANDROID_SERIAL || 'emulator-5636'
const CDP = process.env.DEVICE_CDP || 'http://127.0.0.1:9226'
const HOST = process.env.DEVICE_HOST || 'http://127.0.0.1:4173'
const PLAYWRIGHT = 'file:///C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs'
const INSPECT = process.argv.includes('--inspect')
const WARM_NATIVE_FOCUS = process.argv.includes('--warm-native-focus')
const VERIFY_NATIVE_INPUT = process.argv.includes('--verify-native-input')
const LABEL = (process.argv.find(v => v.startsWith('--label='))?.slice(8) || 'device').replace(/[^a-zA-Z0-9_-]/g, '_')
const STAMP = new Date().toISOString().replace(/[:.]/g, '-')
const OUT = path.join(ROOT, 'research/tmp/verify-android-terminal-device', `${STAMP}-${LABEL}`)
fs.mkdirSync(OUT, { recursive: true })
const report = {
  status: 'running', started: new Date().toISOString(), environment: 'Android emulator: actual APK WebView, native InsetsHost, Android system IME, adb touch input',
  scope: 'No fake page, no bridge replacement, no UI asset substitution, no app restart or installation, no product source edits.',
  serial: SERIAL, cdp: CDP, nativeHost: HOST, label: LABEL, output: OUT, checks: [], samples: [], artifacts: [], errors: [],
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const writeReport = () => fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))
const save = (name, text) => { fs.writeFileSync(path.join(OUT, name), text); report.artifacts.push(name) }
const check = (name, ok, detail) => {
  report.checks.push({ name, status: ok ? 'pass' : 'fail', detail, at: new Date().toISOString() })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`)
  writeReport()
}
async function adb(args) {
  const result = await exec(ADB, ['-s', SERIAL, ...args], { windowsHide: true, timeout: 20000, maxBuffer: 8 * 1024 * 1024 })
  return result.stdout
}
async function waitFor(fn, timeout = 20000, interval = 250) {
  const deadline = Date.now() + timeout
  let value
  do {
    value = await fn()
    if (value) return value
    await sleep(interval)
  } while (Date.now() < deadline)
  throw new Error(`Condition did not settle within ${timeout}ms`)
}

let browser, page, cdp, backend, reversePort
let screenshotIndex = 0
async function screenshot(name) {
  const basename = `${String(++screenshotIndex).padStart(2, '0')}-${name}.png`
  const remote = `/sdcard/Download/oc-device-terminal-${LABEL}-${screenshotIndex}.png`
  await adb(['shell', 'screencap', '-p', remote])
  await adb(['pull', remote, path.join(OUT, basename)])
  report.artifacts.push(basename)
  writeReport()
  return basename
}

async function probe() {
  return page.evaluate(() => {
    const rect = el => {
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x, y: r.y, width: r.width, height: r.height, bottom: r.bottom, right: r.right }
    }
    const candidates = [...document.querySelectorAll('[data-oc-terminal-engine], [data-component="terminal"], .oc-terminal-frame')]
    const container = candidates.find(el => el._ocTerminal && el.isConnected && el.getBoundingClientRect().width > 0)
    const t = container?._ocTerminal
    const frame = t?.mobileFrame || container?.querySelector('.oc-terminal-frame')
    const style = container ? getComputedStyle(container) : null
    const active = t?.buffer?.active
    const raw = document.documentElement.style
    let hostInfo, bridgeMode
    try { hostInfo = window.__ocAndroidNative?.hostInfo() } catch (e) { hostInfo = String(e) }
    try { bridgeMode = window.__ocAndroidNative?.getImeMode() } catch (e) { bridgeMode = String(e) }
    const cursorRow = active?.getLine(active.baseY + active.cursorY)?.translateToString(true)
    return {
      at: Date.now(), href: location.href, title: document.title, userAgent: navigator.userAgent,
      nativeBridgePresent: !!window.__ocAndroidNative, hostInfo, bridgeMode,
      fakeBridgeMarker: !!window.__ocNativeCalls, engine: window.__ocTerminalEngine,
      viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio, scrollY, scrollX },
      visualViewport: visualViewport ? { width: visualViewport.width, height: visualViewport.height, offsetTop: visualViewport.offsetTop, pageTop: visualViewport.pageTop, scale: visualViewport.scale } : null,
      screen: { width: screen.width, height: screen.height, orientation: screen.orientation?.type },
      ime: { visible: raw.getPropertyValue('--oc-ime-visible'), bottom: raw.getPropertyValue('--oc-ime-bottom'), safeBottom: raw.getPropertyValue('--oc-safe-bottom'), windowHeight: raw.getPropertyValue('--oc-window-height'), mode: document.documentElement.getAttribute('data-oc-ime-mode') },
      terminal: t ? {
        connected: !!t.element?.isConnected, rows: t.rows, cols: t.cols,
        viewportY: active?.viewportY, baseY: active?.baseY, length: active?.length, type: active?.type,
        cursorX: active?.cursorX, cursorY: active?.cursorY, cursorRow,
        container: rect(container), frame: rect(frame), element: rect(t.element),
        padding: { top: parseFloat(style.paddingTop) || 0, bottom: parseFloat(style.paddingBottom) || 0, left: parseFloat(style.paddingLeft) || 0, right: parseFloat(style.paddingRight) || 0 },
        opacity: style.opacity, inputFocused: document.activeElement === t.textarea,
        textarea: rect(t.textarea), textareaStyle: t.textarea ? { visibility: getComputedStyle(t.textarea).visibility, display: getComputedStyle(t.textarea).display } : null,
        documentFocused: document.hasFocus(),
        firstVisibleRow: active?.getLine(active.viewportY)?.translateToString(true),
        lastVisibleRow: active?.getLine(active.viewportY + t.rows - 1)?.translateToString(true),
        activeTag: document.activeElement?.tagName, activeInside: !!document.activeElement && container.contains(document.activeElement),
        cellHeight: typeof t.cellHeight === 'function' ? t.cellHeight() : null,
        renderedCellHeight: t.element?.querySelector('.xterm-rows > div')?.getBoundingClientRect().height || null,
        font: { size: t.options.fontSize, baseSize: t.baseFontSize, family: t.options.fontFamily, lineHeight: t.options.lineHeight },
      } : null,
      mobileTabs: [...document.querySelectorAll('[data-slot="session-mobile-view-navigation"] [data-value], [role="tab"]')].map(el => ({ value: el.getAttribute('data-value'), text: el.textContent, slot: el.getAttribute('data-slot'), selected: el.getAttribute('aria-selected'), rect: rect(el), html: el.outerHTML.slice(0, 1400) })),
      bodyText: (document.body.innerText || '').slice(0, 2000),
    }
  })
}
async function sample(name, native = true) {
  const p = await probe()
  let input = null
  if (native) {
    const raw = await adb(['shell', 'dumpsys', 'input_method'])
    save(`${name}-input-method.txt`, raw)
    const markers = raw.split(/\r?\n/).filter(line => /mInputShown|mIsInputViewShown|mShowRequested|mCurMethodId|mCurId|mImeWindowVis/.test(line))
    const inputShown = /\bmInputShown\s*=\s*true/.test(raw)
    const inputHidden = /\bmInputShown\s*=\s*false/.test(raw)
    input = { inputShown: inputShown ? true : inputHidden ? false : null, markers }
  }
  const value = { name, page: p, inputMethod: input }
  report.samples.push(value)
  writeReport()
  return value
}
function aligned(p) {
  const t = p.terminal
  return !!t && Math.abs(t.container.bottom - t.padding.bottom - t.frame.bottom) <= 2
}
// Does the live row grid still fill the restored viewport? `cellHeight()` is
// screen.height/rows — a value derived from the row count — so it cannot show
// that the webfont settled. Measure the real row box instead: a live
// `.xterm-rows > div` is a sibling of the font metrics xterm caches, so if the
// rows fit the padded container and one more row would not, the grid is the
// right size for the rendered font. Row count may legitimately change when the
// font changes; capacity must not.
function gridCapacity(p) {
  const t = p?.terminal
  const rows = t?.rows
  const h = t?.renderedCellHeight
  const availableHeight = t?.container && t?.padding ? t.container.height - t.padding.top - t.padding.bottom : null
  const fits = Number.isFinite(rows) && rows > 0 && Number.isFinite(h) && h > 0 && Number.isFinite(availableHeight)
    && rows * h <= availableHeight + 1
    && availableHeight < (rows + 1) * h + 1
  return {
    fits, rows, renderedCellHeight: h ?? null, availableHeight: Number.isFinite(availableHeight) ? availableHeight : null,
    gridHeight: Number.isFinite(rows) && Number.isFinite(h) ? rows * h : null,
    nextRowHeight: Number.isFinite(rows) && Number.isFinite(h) ? (rows + 1) * h : null,
    font: t?.font ?? null,
    derivedCellHeight: t?.cellHeight ?? null,
  }
}
function gridFits(p) { return gridCapacity(p).fits }
function imeShown(p) { return p.ime.visible.trim() === '1' && parseFloat(p.ime.bottom) > 0 }
function imeHidden(p) { return p.ime.visible.trim() === '0' && !(parseFloat(p.ime.bottom) > 0) }

async function deviceSpecs() {
  const specs = {}
  for (const [name, args] of Object.entries({
    size: ['shell', 'wm', 'size'], density: ['shell', 'wm', 'density'],
    api: ['shell', 'getprop', 'ro.build.version.sdk'], android: ['shell', 'getprop', 'ro.build.version.release'],
    model: ['shell', 'getprop', 'ro.product.model'], webview: ['shell', 'dumpsys', 'webviewupdate'],
    keyboard: ['shell', 'settings', 'get', 'secure', 'default_input_method'],
    showImeWithHardKeyboard: ['shell', 'settings', 'get', 'secure', 'show_ime_with_hard_keyboard'],
    package: ['shell', 'dumpsys', 'package', 'com.xiufeigo.opencodeshell'],
    window: ['shell', 'dumpsys', 'window', 'windows'],
  })) {
    const text = await adb(args)
    save(`environment-${name}.txt`, text)
    specs[name] = ['package', 'window', 'webview'].includes(name) ? text.split(/\r?\n/).filter(v => /versionName=|versionCode=|WebView|mCurrentFocus|mFocusedApp|Current WebView|Valid package/.test(v)) : text.trim()
  }
  report.device = specs
  const size = /(?:Override|Physical) size:\s*(\d+)x(\d+)/g
  specs.sizeParsed = [...specs.size.matchAll(size)].map(m => ({ width: +m[1], height: +m[2] })).at(-1)
  report.device.initialPage = await probe()
  writeReport()
}

async function screenMapping() {
  const raw = await adb(['shell', 'dumpsys', 'window', 'windows'])
  const frameMatches = [...raw.matchAll(/mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]/g)]
  // Bounds are also exposed in the DevTools target description; use the actual
  // embedded WebView location rather than assuming it starts at screen y=0.
  const targets = await fetch(`${CDP}/json`).then(r => r.json())
  const target = targets.find(t => t.url?.startsWith(HOST)) || targets.find(t => t.type === 'page')
  let description = {}
  try { description = JSON.parse(target?.description || '{}') } catch {}
  const p = await probe()
  const dimensions = report.device.sizeParsed
  const mapped = {
    x: Number(description.screenX || 0), y: Number(description.screenY || 0),
    width: Number(description.width) || Math.round(p.viewport.width * p.viewport.dpr),
    height: Number(description.height) || Math.round(p.viewport.height * p.viewport.dpr),
    scale: Number(description.width) ? description.width / p.viewport.width : p.viewport.dpr,
    physicalWidth: dimensions.width, physicalHeight: dimensions.height, description, windowFrameCount: frameMatches.length,
  }
  return mapped
}
async function tapCss(x, y) {
  const m = await screenMapping()
  const sx = Math.round(m.x + x * m.scale)
  const sy = Math.round(m.y + y * m.scale)
  report.samples.push({ name: 'adb-tap', css: { x, y }, screen: { x: sx, y: sy }, mapping: m })
  await adb(['shell', 'input', 'tap', String(sx), String(sy)])
}
async function dragCss(from, to, duration = 650) {
  const m = await screenMapping()
  const coords = [from.x, from.y, to.x, to.y].map((v, i) => Math.round((i % 2 ? m.y : m.x) + v * m.scale))
  report.samples.push({ name: 'adb-swipe', css: { from, to, duration }, screen: coords, mapping: m })
  await adb(['shell', 'input', 'swipe', ...coords.map(String), String(duration)])
  await sleep(1000)
}
async function keyToggle() {
  await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: '`', code: 'Backquote', windowsVirtualKeyCode: 192, nativeVirtualKeyCode: 192, modifiers: 2 })
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: '`', code: 'Backquote', windowsVirtualKeyCode: 192, nativeVirtualKeyCode: 192, modifiers: 2 })
}
async function liveSocket() {
  const entries = [...backend.sockets.entries()]
  const live = entries.filter(([, socket]) => socket.socket && !socket.socket.destroyed)
  return live.at(-1) || entries.at(-1)
}
async function sendOutput(text) {
  const [id, socket] = await waitFor(liveSocket)
  socket.send(text)
  report.ptyId = id
  await sleep(700)
}
async function blurTerminal() {
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('[data-oc-terminal-engine]')) el._ocTerminal?.blur()
    document.activeElement?.blur?.()
  })
  try {
    await waitFor(async () => { const p = await probe(); return imeHidden(p) ? p : null }, 3000)
    return
  } catch {}
  const p = await probe()
  const native = await adb(['shell', 'dumpsys', 'input_method'])
  if (imeShown(p) && /\bmInputShown\s*=\s*true/.test(native)) {
    await adb(['shell', 'input', 'keyevent', '4'])
  }
  await waitFor(async () => { const current = await probe(); return imeHidden(current) ? current : null }, 12000)
}

class DevicePage {
  constructor(socket) {
    this.socket = socket
    this.pending = new Map()
    this.events = new Map()
    this.nextId = 0
    this.socket.addEventListener('message', event => {
      const message = JSON.parse(event.data)
      if (message.id) {
        const pending = this.pending.get(message.id)
        if (!pending) return
        clearTimeout(pending.timer)
        this.pending.delete(message.id)
        if (message.error) pending.reject(new Error(`${pending.method}: ${message.error.message}`))
        else pending.resolve(message.result)
        return
      }
      for (const handler of this.events.get(message.method) || []) handler(message.params)
    })
    this.socket.addEventListener('close', () => {
      for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(new Error('Actual WebView CDP socket closed')) }
      this.pending.clear()
    })
  }
  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 20000)
      this.pending.set(id, { resolve, reject, timer, method })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }
  on(name, handler) {
    const method = name === 'pageerror' ? 'Runtime.exceptionThrown' : name === 'console' ? 'Runtime.consoleAPICalled' : name
    const fn = name === 'pageerror' ? params => handler(new Error(JSON.stringify(params.exceptionDetails))) : name === 'console' ? params => handler({ type: () => params.type, text: () => (params.args || []).map(v => v.value ?? v.description ?? '').join(' ') }) : handler
    const handlers = this.events.get(method) || []
    handlers.push(fn)
    this.events.set(method, handlers)
  }
  async evaluate(expression) {
    const value = typeof expression === 'function' ? `(${expression.toString()})()` : expression
    const result = await this.send('Runtime.evaluate', { expression: value, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
    return result.result?.value
  }
  async goto(url) {
    const result = await this.send('Page.navigate', { url })
    if (result.errorText) throw new Error(result.errorText)
    await waitFor(async () => {
      try { return await this.evaluate(`location.href === ${JSON.stringify(url)} && document.readyState !== 'loading'`) }
      catch { return false }
    }, 30000)
  }
  locator() { return { innerText: () => this.evaluate(() => document.body.innerText) } }
  async detach() { this.socket.close() }
}

async function connectPage() {
  const { chromium } = await import(PLAYWRIGHT)
  try {
    browser = await chromium.connectOverCDP(CDP, { timeout: 15000 })
    page = browser.contexts().flatMap(context => context.pages()).find(p => p.url().startsWith(HOST))
    if (!page) throw new Error(`Existing APK page at ${HOST} was not found`)
    cdp = await page.context().newCDPSession(page)
    report.connection = 'Playwright chromium.connectOverCDP'
    return
  } catch (error) {
    report.playwrightConnectionError = String(error)
    if (!/Browser context management is not supported|setDownloadBehavior/.test(String(error))) throw error
  }
  const targets = await fetch(`${CDP}/json`).then(r => r.json())
  const target = targets.find(v => v.type === 'page' && v.url?.startsWith(HOST))
  if (!target) throw new Error(`No actual WebView page target at ${HOST}`)
  report.pageTarget = target
  const socket = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
  page = new DevicePage(socket)
  cdp = page
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  report.connection = 'Direct CDP WebSocket to existing APK page; Playwright browser-management unsupported in Android WebView'
}

async function main() {
  await connectPage()
  page.on('pageerror', error => { report.errors.push({ type: 'pageerror', text: String(error) }); writeReport() })
  page.on('console', message => { if (message.type() === 'error') report.errors.push({ type: 'console', text: message.text() }) })
  await deviceSpecs()
  const initial = report.device.initialPage
  check('real-native-bridge', initial.nativeBridgePresent && !initial.fakeBridgeMarker && !String(initial.hostInfo).includes('Chromium-Emulated'), { hostInfo: initial.hostInfo, mode: initial.bridgeMode, fakeBridgeMarker: initial.fakeBridgeMarker, userAgent: initial.userAgent })
  if (INSPECT) { await screenshot('inspect'); return }

  backend = await startBackend()
  backend.sessionId = 'ses_device_terminal'
  backend.workspace = path.join(OUT, 'workspace').replace(/\\/g, '/')
  fs.mkdirSync(backend.workspace, { recursive: true })
  reversePort = new URL(backend.origin).port
  await adb(['reverse', `tcp:${reversePort}`, `tcp:${reversePort}`])
  report.backendOrigin = backend.origin
  report.workspace = backend.workspace
  const previousServerKeys = await page.evaluate(() => ({
    server: localStorage.getItem('opencode.global.dat:server'), serverV3: localStorage.getItem('opencode.global.dat:server.v3'),
  }))
  save('previous-server-selection.json', JSON.stringify(previousServerKeys, null, 2))
  await page.evaluate(seedScript({ serverOrigin: backend.origin, workspace: backend.workspace, sessionId: '' }))
  const route = `${HOST}/server/${base64url(backend.origin)}/session/${backend.sessionId}`
  report.route = route
  await page.goto(route, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await waitFor(async () => {
    const text = await page.locator('body').innerText()
    return text.trim().length > 20
  }, 30000)
  await sleep(2500)

  // Enter through the SPA's real mobile terminal tab; use its own command only when that tab is absent.
  const tab = await page.evaluate(() => {
    const el = [...document.querySelectorAll('[role="tab"][data-value="terminal"], [role="tab"][data-value^="terminal:"]')].find(node => {
      const r = node.getBoundingClientRect()
      return r.width > 0 && r.height > 0 && r.bottom > 0 && r.y < innerHeight
    })
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, selected: el.getAttribute('aria-selected') === 'true', value: el.getAttribute('data-value') }
  })
  report.terminalEntry = tab ? { method: 'actual-adb-mobile-tab', tab } : { method: 'production-terminal-command-via-cdp-keyboard' }
  if (tab && !tab.selected) { await tapCss(tab.x, tab.y); await sleep(500) }
  if (!tab) await keyToggle()
  let mounted
  try { mounted = await waitFor(async () => { const p = await probe(); return p.terminal?.connected && p.terminal.container.height > 0 ? p : null }, 25000) }
  catch (error) {
    await sample('terminal-mount-failed')
    await screenshot('terminal-mount-failed')
    throw error
  }
  check('production-terminal-mounted', mounted.href === route && mounted.nativeBridgePresent && mounted.terminal.connected, { route, actualRoute: mounted.href, terminal: mounted.terminal, engine: mounted.engine })
  if (mounted.href !== route) throw new Error(`Verifier route changed: expected ${route}, got ${mounted.href}`)
  report.loadedTerminalAssets = await page.evaluate(async () => {
    const urls = [...new Set(performance.getEntriesByType('resource').map(e => e.name).filter(url => /ghostty-web-|oc-terminal/.test(url)))]
    return Promise.all(urls.map(async url => {
      const response = await fetch(url)
      const bytes = await response.arrayBuffer()
      const sha256 = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(v => v.toString(16).padStart(2, '0')).join('')
      return { url, status: response.status, length: bytes.byteLength, sha256 }
    }))
  })
  writeReport()
  await sendOutput('\x1b[2J\x1b[HDEVICE-SHORT-OUTPUT\r\n$ ')
  await blurTerminal()
  await sleep(1000)
  const short = (await sample('short-output')).page
  await screenshot('short-output-keyboard-hidden')
  check('short-output-bottom-anchor', aligned(short) && short.terminal.frame.height < short.terminal.container.height / 2 && short.terminal.cursorRow.includes('$'), { terminal: short.terminal, viewport: short.viewport, ime: short.ime })
  check('keyboard-initially-hidden', imeHidden(short), short.ime)

  if (WARM_NATIVE_FOCUS) {
    const activateTab = async value => {
      const point = await page.evaluate(`(() => {
        const selectors = '[role="tab"][data-value="' + ${JSON.stringify(value)} + '"], [role="tab"][data-value^="' + ${JSON.stringify(value)} + ':"]'
        const el = [...document.querySelectorAll(selectors)].find(node => { const r = node.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.bottom > 0 && r.y < innerHeight })
        if (!el) return null
        const r = el.getBoundingClientRect()
        return { x: r.x + r.width / 2, y: r.y + r.height / 2 }
      })()`)
      if (!point) throw new Error(`Visible ${value} tab not found`)
      await tapCss(point.x, point.y)
      await sleep(500)
    }
    await activateTab('session')
    const editor = await waitFor(() => page.evaluate(() => {
      const el = document.querySelector('[data-component="composer-editor"]')
      if (!el) return null
      const r = el.getBoundingClientRect()
      if (!r.width || !r.height || !el.isContentEditable) return null
      return { x: r.x + r.width / 2, y: r.y + Math.min(20, r.height / 2), rect: { x: r.x, y: r.y, width: r.width, height: r.height } }
    }), 12000)
    await tapCss(editor.x, editor.y)
    try { await waitFor(async () => { const p = await probe(); return imeShown(p) ? p : null }, 12000) }
    catch (error) { report.errors.push({ type: 'native-composer-focus-timeout', text: String(error) }) }
    report.nativeFocusPreparation = { editor, sample: await sample('native-composer-focus'), note: 'Real ADB taps through the production Session tab and composer; no DOM focus or bridge call.' }
    await screenshot('native-composer-focus')
    const preparation = report.nativeFocusPreparation.sample
    if (imeShown(preparation.page) && preparation.inputMethod.inputShown === true) {
      await adb(['shell', 'input', 'keyevent', '4'])
      await waitFor(async () => { const p = await probe(); return imeHidden(p) ? p : null }, 12000)
    }
    await activateTab('terminal')
    await waitFor(async () => { const p = await probe(); return p.terminal ? p : null })
    await sleep(750)
    writeReport()
  }

  for (let cycle = 1; cycle <= 3; cycle++) {
    await blurTerminal()
    const baseline = (await sample(`cycle-${cycle}-before`)).page
    const frame = baseline.terminal.frame
    const container = baseline.terminal.container
    const tapX = container.x + container.width / 2
    const tapY = Math.min(baseline.viewport.height - 15, frame.bottom - Math.max(5, Math.min(frame.height / 2, 12)))
    await tapCss(tapX, tapY)
    let opened
    try { opened = await waitFor(async () => { const p = await probe(); return imeShown(p) && p.terminal?.inputFocused && p.viewport.height < baseline.viewport.height - 100 ? p : null }, 12000) }
    catch (error) { report.errors.push({ type: 'keyboard-open-timeout', cycle, text: String(error) }); opened = await probe() }
    await sleep(1000)
    const openSample = await sample(`cycle-${cycle}-open`)
    opened = openSample.page
    await screenshot(`cycle-${cycle}-keyboard-open`)
    const keyboardOpened = imeShown(opened) && !!opened.terminal?.inputFocused && openSample.inputMethod.inputShown === true && opened.viewport.height < baseline.viewport.height - 100
    check(`keyboard-${cycle}-opened`, keyboardOpened, { baselineHeight: baseline.viewport.height, viewport: opened.viewport, ime: opened.ime, native: openSample.inputMethod, terminal: opened.terminal })
    if (!keyboardOpened) {
      report.skipped ||= []
      report.skipped.push({ cycle, checks: ['terminal-above-ime', 'height-restored', 'short-output-bottom'], reason: 'Actual Android IME did not become visible; BACK would navigate instead of dismissing it.' })
      writeReport()
      continue
    }
    check(`keyboard-${cycle}-terminal-above-ime`, aligned(opened) && opened.terminal.frame.bottom <= opened.viewport.height + 2, { frame: opened.terminal.frame, container: opened.terminal.container, padding: opened.terminal.padding, viewportHeight: opened.viewport.height })
    if (VERIFY_NATIVE_INPUT && cycle === 1) {
      const supported = report.device.sizeParsed.width === 1080 && report.device.sizeParsed.height === 2400 && report.device.keyboard.startsWith('com.google.android.inputmethod.latin/')
      check('native-input-keyboard-layout-supported', supported, { screen: report.device.sizeParsed, keyboard: report.device.keyboard, note: 'Physical Gboard q/w/e/space coordinates verified against the native 1080x2400 screenshot.' })
      if (supported) {
        const [id, socket] = await waitFor(liveSocket)
        const mark = socket.received.length
        await page.evaluate(() => {
          const el = document.activeElement
          if (el?.tagName !== 'TEXTAREA') throw new Error('Terminal textarea is not focused')
          el._ocDeviceInputEvents = []
          for (const type of ['keydown', 'beforeinput', 'input', 'compositionstart', 'compositionupdate', 'compositionend']) {
            el.addEventListener(type, event => el._ocDeviceInputEvents.push({ type: event.type, trusted: event.isTrusted, key: event.key, data: event.data, inputType: event.inputType, value: el.value, at: Date.now() }))
          }
        })
        const touches = [{ key: 'q', x: 58, y: 1716 }, { key: 'w', x: 165, y: 1716 }, { key: 'e', x: 273, y: 1716 }, { key: 'space', x: 536, y: 2180 }]
        for (const touch of touches) {
          await adb(['shell', 'input', 'tap', String(touch.x), String(touch.y)])
          await sleep(350)
        }
        await sleep(1000)
        const events = await page.evaluate(() => document.activeElement?._ocDeviceInputEvents || [])
        const received = socket.received.slice(mark)
        report.nativeInput = { ptyId: id, expected: 'qwe ', received, text: received.join(''), touches, events, sample: await sample('native-input') }
        await screenshot('native-input')
        check('native-gboard-input-reaches-pty', received.join('') === 'qwe ', report.nativeInput)
        check('native-input-events-trusted', events.some(event => ['input', 'beforeinput', 'compositionend'].includes(event.type) && event.trusted) && events.every(event => event.trusted), events)
      }
    }
    // Android BACK dismisses the actual soft keyboard. Do not synthesize insets.
    await adb(['shell', 'input', 'keyevent', '4'])
    try { await waitFor(async () => { const p = await probe(); return imeHidden(p) && Math.abs(p.viewport.height - baseline.viewport.height) <= 2 ? p : null }, 12000) }
    catch (error) { report.errors.push({ type: 'keyboard-hide-timeout', cycle, text: String(error) }) }
    await sleep(1000)
    const closedSample = await sample(`cycle-${cycle}-closed`)
    const closed = closedSample.page
    await screenshot(`cycle-${cycle}-keyboard-closed`)
    const closedCapacity = gridCapacity(closed)
    check(`keyboard-${cycle}-height-restored`, imeHidden(closed) && closedSample.inputMethod.inputShown === false && Math.abs(closed.viewport.height - baseline.viewport.height) <= 2 && Math.abs(closed.terminal.container.height - baseline.terminal.container.height) <= 2 && gridFits(closed) && aligned(closed), { baseline: { viewport: baseline.viewport, terminal: baseline.terminal }, closed: { viewport: closed.viewport, terminal: closed.terminal, ime: closed.ime }, native: closedSample.inputMethod, gridFits: closedCapacity.fits, availableHeight: closedCapacity.availableHeight, renderedCellHeight: closedCapacity.renderedCellHeight, gridHeight: closedCapacity.gridHeight, nextRowHeight: closedCapacity.nextRowHeight, font: closedCapacity.font, derivedCellHeight: closedCapacity.derivedCellHeight, rowsBaseline: baseline.terminal.rows, rowsClosed: closed.terminal.rows, note: 'rows may change when the webfont settles; capacity under the rendered row height must hold.' })
    check(`keyboard-${cycle}-short-output-bottom`, aligned(closed) && closed.terminal.frame.height < closed.terminal.container.height / 2, { terminal: closed.terminal })
  }

  await blurTerminal()
  const historyText = '\x1b[2J\x1b[H' + Array.from({ length: 350 }, (_, i) => `[DEVICE-${String(i + 1).padStart(3, '0')}] line ${i + 1} ${'.'.repeat(i % 12 + 3)}`).join('\r\n') + '\r\n$ '
  await sendOutput(historyText)
  const seeded = (await sample('history-seeded')).page
  await screenshot('history-bottom')
  check('history-buffer-created', seeded.terminal.length >= 351 && seeded.terminal.baseY > 100, { terminal: seeded.terminal })
  const r = seeded.terminal.container
  const x = r.x + r.width / 2
  const top = Math.max(30, r.y + r.height * 0.25)
  const bottom = Math.min(seeded.viewport.height - 30, r.y + r.height * 0.75)
  await dragCss({ x, y: top }, { x, y: bottom })
  const olderSample = await sample('history-drag-older')
  const older = olderSample.page
  await screenshot('history-drag-older')
  check('touch-drag-older-history', older.terminal.viewportY < seeded.terminal.viewportY, { before: seeded.terminal.viewportY, after: older.terminal.viewportY, baseY: older.terminal.baseY, native: olderSample.inputMethod, ime: older.ime })
  check('older-drag-does-not-open-ime', imeHidden(older) && olderSample.inputMethod.inputShown === false && !older.terminal.inputFocused, { inputFocused: older.terminal.inputFocused, ime: older.ime, native: olderSample.inputMethod })
  await dragCss({ x, y: bottom }, { x, y: top })
  const newerSample = await sample('history-drag-newer')
  const newer = newerSample.page
  await screenshot('history-drag-newer')
  check('touch-drag-newer-history', newer.terminal.viewportY > older.terminal.viewportY, { before: older.terminal.viewportY, after: newer.terminal.viewportY, baseY: newer.terminal.baseY })
  check('newer-drag-does-not-open-ime', imeHidden(newer) && newerSample.inputMethod.inputShown === false && !newer.terminal.inputFocused, { inputFocused: newer.terminal.inputFocused, ime: newer.ime, native: newerSample.inputMethod })
  check('history-drag-height-stable', Math.abs(newer.viewport.height - seeded.viewport.height) <= 2 && Math.abs(newer.terminal.container.height - seeded.terminal.container.height) <= 2, { before: seeded.viewport, after: newer.viewport })
  report.backendHits = backend.hits
  report.ptyInput = [...backend.sockets.values()].map(socket => ({ id: socket.id, received: socket.received }))
}

async function cleanup() {
  if (backend) {
    report.backendHits = backend.hits
    report.ptyInput = [...backend.sockets.values()].map(socket => ({ id: socket.id, received: socket.received }))
    for (const socket of backend.sockets.values()) { try { socket.socket?.destroy() } catch {} }
    for (const sse of backend.sse) { try { sse.end() } catch {} }
    backend.server.closeAllConnections?.()
    await new Promise(resolve => backend.server.close(resolve))
    report.backendClosed = true
  }
  if (reversePort) {
    try { await adb(['reverse', '--remove', `tcp:${reversePort}`]); report.backendReverseRemoved = true }
    catch (error) { report.errors.push({ type: 'cleanup-reverse', text: String(error) }) }
  }
  try { await cdp?.detach() } catch {}
  // Do not browser.close(): this is an externally owned Android WebView.
  report.finished = new Date().toISOString()
  const totals = { pass: report.checks.filter(c => c.status === 'pass').length, fail: report.checks.filter(c => c.status === 'fail').length }
  report.totals = totals
  report.status = report.fatal ? 'error' : totals.fail ? 'fail' : 'pass'
  writeReport()
  console.log(`REPORT ${path.join(OUT, 'report.json')} ${JSON.stringify(totals)}`)
  process.exitCode = report.status === 'pass' ? 0 : 1
}

try { await main() }
catch (error) {
  report.fatal = String(error?.stack || error)
  console.error(report.fatal)
  try { if (page) { await sample('fatal'); await screenshot('fatal') } } catch {}
}
finally { await cleanup() }
// Disconnect the Node inspector transport without closing the APK or activity.
setTimeout(() => process.exit(process.exitCode || 0), 50)
