import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { startBackend, startUiServer, nativeBridgeSource, ocHostLoaderSource, seedScript, base64url } from "./verify-android-terminal-release.mjs"

const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(root, "research/tmp/verify-android-navigation", new Date().toISOString().replace(/[:.]/g, "-"))
fs.mkdirSync(output, { recursive: true })
const { chromium } = await import(process.env.VERIFY_TERMINAL_PLAYWRIGHT || new URL("../_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs", import.meta.url).href)
const profiles = [{ name: "phone", width: 411, height: 914 }, { name: "tablet", width: 1280, height: 900 }]
const report = { scope: "complete packaged Android SPA with emulated native bridge; not Android device validation", checks: [], output }
const visible = (locator) => locator.filter({ visible: true })
const button = (page, name) => visible(page.getByRole("button", { name, exact: true })).first()
const record = (device, name, detail) => { report.checks.push({ device, name, status: "pass", detail }); console.log("PASS", device, name, JSON.stringify(detail)) }

async function registry(page) {
  return page.evaluate(() => JSON.parse(localStorage.getItem("opencode.global.dat:server")))
}

async function openSettings(page) {
  await page.keyboard.press("Control+,")
  await visible(page.locator(".settings")).waitFor()
  const back = button(page, "Back to settings")
  if (await back.isVisible()) await back.click()
  await button(page, "Back to app").waitFor()
}

async function selectSettingsEntry(page, phone, name) {
  if (phone) {
    await page.locator(".settings-mobile-menu-trigger").click()
    await page.getByRole("menuitemradio", { name, exact: true }).click()
  } else {
    await page.getByRole("tab", { name, exact: true }).click()
  }
}

async function addServer(page, url, name) {
  await button(page, "Add server").click()
  const dialog = page.getByRole("dialog")
  await dialog.getByRole("button", { name: "Use password", exact: true }).click()
  await dialog.locator("#dialog-server-url").fill(url)
  await dialog.locator("#dialog-server-name").fill(name)
  await dialog.getByRole("button", { name: "Add server", exact: true }).click()
  await dialog.waitFor({ state: "hidden" })
  await page.waitForFunction(url => {
    const store = JSON.parse(localStorage.getItem("opencode.global.dat:server"))
    return store?.list?.some(server => server.http.url === url)
  }, url)
  // Wait for the actual destination, rather than clicking the outgoing root
  // action during its transition and accidentally bypassing the regression.
  await button(page, "Back to settings").waitFor()
  await page.locator(".settings-tab-title").filter({ hasText: name }).waitFor()
}

async function runProfile(browser, profile, ui, servers) {
  const context = await browser.newContext({ viewport: { width: profile.width, height: profile.height }, locale: "en-US", isMobile: profile.name === "phone", hasTouch: true })
  const page = await context.newPage()
  const errors = []
  page.on("pageerror", error => errors.push(error.stack))
  await page.addInitScript({ content: nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
  try {
    await page.goto(ui, { waitUntil: "domcontentloaded" })
    await button(page, "Use password").click()
    await page.locator("#server-connect-url").fill(servers[0].origin)
    await button(page, "Connect").click()
    await page.locator('[data-component="connect-server"]').waitFor({ state: "hidden" })
    if (profile.name === "phone") await page.locator('[data-component="home-projects-dropdown"]').waitFor()
    else await button(page, "Settings").waitFor()
    await openSettings(page)
    if (profile.name === "phone") await page.locator(".settings-mobile-menu-trigger").click()
    const rootEntry = profile.name === "phone" ? page.getByRole("menuitemradio", { name: "Server", exact: true }) : page.getByRole("tab", { name: "Server", exact: true })
    await rootEntry.waitFor({ timeout: 5000 })
    record(profile.name, "first-add-keeps-server-settings", { registry: await registry(page) })
    await rootEntry.click()
    await addServer(page, servers[1].origin, "Server B")
    assert.equal(await button(page, "Add server").evaluate(node => getComputedStyle(node).opacity), "1")
    // Continue on the page opened after saving B; reopening root settings hid
    // the missing add action when the server inventory changes to multiple.
    for (const [index, name] of [[2, "Server C"], [3, "Server D"], [4, "Server E"]]) {
      await addServer(page, servers[index].origin, name)
      await button(page, "Add server").waitFor()
    }
    await openSettings(page)
    await button(page, "Add server").waitFor()
    const stored = await registry(page)
    assert.equal(stored.list.length, 5)
    assert.deepEqual(stored.list.map(server => server.http.url).sort(), servers.map(server => server.origin).sort())
    record(profile.name, "five-servers-added-without-reopening-settings", stored)
    await page.reload({ waitUntil: "domcontentloaded" })
    await openSettings(page)
    await button(page, "Add server").waitFor()
    assert.equal((await registry(page)).list.length, 5)
    record(profile.name, "five-servers-persist-after-reload", await registry(page))
    await selectSettingsEntry(page, profile.name === "phone", "Server B")
    const row = page.locator(".settings-servers-row").filter({ hasText: "Server B" })
    await row.getByRole("button", { name: "More options", exact: true }).click()
    await page.getByRole("menuitem", { name: "Edit", exact: true }).click()
    const dialog = page.getByRole("dialog")
    await dialog.locator("input").nth(1).fill("Server B edited")
    await dialog.getByRole("button", { name: "Save", exact: true }).click()
    await dialog.waitFor({ state: "hidden" })
    await page.waitForFunction(url => JSON.parse(localStorage.getItem("opencode.global.dat:server")).list.some(server => server.http.url === url && server.displayName === "Server B edited"), servers[1].origin)
    record(profile.name, "edit-second-server", await registry(page))
    await button(page, "Back to settings").click()
    await button(page, "Add server").waitFor()
    await button(page, "Back to app").click()
    for (const name of [new URL(servers[0].origin).host, "Server C", "Server D", "Server E", "Server B edited"]) {
      if (profile.name === "phone") await page.locator('[data-component="home-projects-dropdown"]').click()
      const homeRow = visible(page.locator("[data-home-row]")).filter({ hasText: name })
      await homeRow.getByRole("button").first().click()
      if (profile.name === "phone") await page.locator('[data-component="home-projects-dropdown"]').click()
      await homeRow.locator(":scope[data-selected]").waitFor()
      record(profile.name, "switch-server", { selected: name })
      if (profile.name === "phone") await page.keyboard.press("Escape")
    }
    await page.waitForFunction(url => JSON.parse(localStorage.getItem("opencode.global.dat:layout"))?.home?.selection?.server === url, servers[1].origin)
    await page.reload({ waitUntil: "domcontentloaded" })
    if (profile.name === "phone") await page.locator('[data-component="home-projects-dropdown"]').click()
    await visible(page.locator("[data-home-row]")).filter({ hasText: "Server B edited" }).locator(":scope[data-selected]").waitFor()
    if (profile.name === "phone") await page.keyboard.press("Escape")
    record(profile.name, "selected-server-persists-after-reload", { selected: "Server B edited" })
    await openSettings(page)
    await button(page, "Add server").waitFor()
    assert.equal((await registry(page)).list.find(server => server.http.url === servers[1].origin).displayName, "Server B edited")
    record(profile.name, "edited-servers-persist-after-reload", await registry(page))
    await selectSettingsEntry(page, profile.name === "phone", "Server C")
    await page.locator(".settings-servers-row").getByRole("button", { name: "More options", exact: true }).click()
    await page.getByRole("menuitem", { name: "Remove", exact: true }).click()
    await page.waitForFunction(url => !JSON.parse(localStorage.getItem("opencode.global.dat:server")).list.some(server => server.http.url === url), servers[2].origin)
    await button(page, "Add server").waitFor()
    assert.equal((await registry(page)).list.length, 4)
    await page.reload({ waitUntil: "domcontentloaded" })
    assert.equal((await registry(page)).list.length, 4)
    await openSettings(page)
    await button(page, "Add server").waitFor()
    record(profile.name, "remove-server-and-persist-four-remaining", await registry(page))
    for (const [name, index] of [["Server E", 4], ["Server D", 3], ["Server B edited", 1]]) {
      await selectSettingsEntry(page, profile.name === "phone", name)
      await page.locator(".settings-servers-row").filter({ hasText: name }).getByRole("button", { name: "More options", exact: true }).click()
      await page.getByRole("menuitem", { name: "Remove", exact: true }).click()
      await page.waitForFunction(url => !JSON.parse(localStorage.getItem("opencode.global.dat:server")).list.some(server => server.http.url === url), servers[index].origin)
      await button(page, "Back to app").waitFor()
    }
    assert.equal((await registry(page)).list.length, 1)
    await selectSettingsEntry(page, profile.name === "phone", "Server")
    await addServer(page, servers[1].origin, "Server B restored")
    await addServer(page, servers[2].origin, "Server C restored")
    await button(page, "Add server").waitFor()
    assert.equal((await registry(page)).list.length, 3)
    record(profile.name, "shrink-to-one-and-expand-to-three-without-losing-add", await registry(page))
    assert.equal(await page.evaluate(() => window.__ocHostError ?? null), null)
    assert.deepEqual(errors, [])
    await page.screenshot({ path: path.join(output, profile.name + "-servers.png"), fullPage: true })
  } catch (error) {
    await page.screenshot({ path: path.join(output, profile.name + "-failure.png"), fullPage: true })
    const state = await page.evaluate(() => ({ text: document.body.innerText, registry: localStorage.getItem("opencode.global.dat:server"), marked: [...document.querySelectorAll("[data-oc-phantom]")].map(node => ({ text: node.textContent, html: node.outerHTML.slice(0, 1500) })), hostError: window.__ocHostError }))
    report.checks.push({ device: profile.name, status: "fail", error: error.stack, state, pageErrors: errors, hits: servers.map(server => server.hits) })
    console.error("FAIL", profile.name, error.message)
  } finally { await context.close() }
}

async function runOfficialPlus(browser, ui, server) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: "en-US" })
  const page = await context.newPage()
  const errors = []
  page.on("pageerror", error => errors.push(error.stack))
  server.sessionId = "ses_navigation_plus"
  await page.addInitScript({ content: seedScript({ serverOrigin: server.origin, workspace: server.workspace, sessionId: server.sessionId }) + ";" + nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
  try {
    await page.goto(`${ui}/server/${base64url(server.origin)}/session/${server.sessionId}`, { waitUntil: "domcontentloaded" })
    const review = page.locator("#review-panel")
    await button(page, "Toggle review").waitFor()
    if (await button(page, "Toggle review").getAttribute("aria-expanded") !== "true") await button(page, "Toggle review").click()
    await visible(review.getByRole("button", { name: "Add tab", exact: true })).click()
    await page.getByRole("menuitem", { name: /^Browser\b/ }).waitFor()
    await page.getByRole("menuitem", { name: /^Open file\b/ }).click()
    await review.getByRole("tab", { name: "Open file", exact: true }).waitFor()
    await visible(review.locator('input[type="search"][aria-label="Filter files"]')).waitFor()
    assert.equal(await page.evaluate(() => typeof window.__ocPlusChooser), "undefined")
    assert.equal(await page.evaluate(() => window.__ocHostError ?? null), null)
    assert.equal(await page.getByRole("menuitem", { name: "Open terminal", exact: true }).count(), 0)
    assert.deepEqual(errors, [])
    record("tablet", "official-review-plus-menu-opens-workspace-files", { customChooser: false })
    await visible(review.getByRole("button", { name: "Add tab", exact: true })).click()
    await page.getByRole("menuitem", { name: /^Browser\b/ }).click()
    await page.getByText(/This server has no built-in browser gateway/).first().waitFor()
    assert.equal(await page.evaluate(() => window.__ocNativeCalls.some(call => call.name === "configureBrowser")), false)
    assert.equal(await page.locator('[data-android-browser-surface]').count(), 0)
    record("tablet", "browser-without-gateway-reports-real-error-without-pairing-or-fake-pane", { nativePairing: false })
    await page.screenshot({ path: path.join(output, "tablet-official-plus.png"), fullPage: true })
  } catch (error) {
    await page.screenshot({ path: path.join(output, "tablet-plus-failure.png"), fullPage: true })
    report.checks.push({ device: "tablet", name: "official-review-plus", status: "fail", error: error.stack, text: await page.locator("body").innerText(), buttons: await page.locator("#review-panel button").evaluateAll(nodes => nodes.map(node => ({ label: node.getAttribute("aria-label"), title: node.getAttribute("title"), html: node.outerHTML.slice(0,500) }))), pageErrors: errors })
    console.error("FAIL", "tablet official plus", error.message)
  } finally { await context.close() }
}

async function runAppMenu(browser, profile, ui, server) {
  const context = await browser.newContext({ viewport: { width: profile.width, height: profile.height }, locale: "en-US", isMobile: profile.name === "phone", hasTouch: true })
  const page = await context.newPage()
  const errors = []
  page.on("pageerror", error => errors.push(error.stack))
  await page.addInitScript({ content: seedScript({ serverOrigin: server.origin, workspace: server.workspace, sessionId: server.sessionId }) + ";" + nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
  const item = name => visible(page.getByRole("menuitem").filter({ has: page.getByText(name, { exact: true }) })).first()
  const open = async group => {
    await button(page, "OpenCode menu").click()
    await item(group).click()
  }
  const select = async (group, name) => { await open(group); await item(name).click() }
  try {
    await page.goto(`${ui}/server/${base64url(server.origin)}/session/${server.sessionId}`, { waitUntil: "domcontentloaded" })
    await button(page, "OpenCode menu").click()
    for (const name of ["File", "Edit", "View", "Go", "Window", "Help"]) await item(name).waitFor()
    await page.screenshot({ path: path.join(output, profile.name + "-application-menu.png") })
    await item("File").click()
    assert.equal(await item("New Window").getAttribute("aria-disabled"), "true")
    await item("Settings").click()
    await button(page, "Back to app").waitFor()
    record(profile.name, "official-application-menu-six-groups-and-settings-command", { unsupportedNewWindowDisabled: true })
    await button(page, "Back to app").click()
    await select("View", "Zoom In")
    await page.waitForFunction(() => document.documentElement.style.zoom === "1.2")
    const unsupported = await page.evaluate(() => {
      const event = new KeyboardEvent("keydown", { key: "N", code: "KeyN", ctrlKey: true, shiftKey: true, bubbles: true, cancelable: true })
      document.dispatchEvent(event)
      return event.defaultPrevented
    })
    assert.equal(unsupported, false)
    await select("View", "Actual Size")
    await page.waitForFunction(() => document.documentElement.style.zoom === "1")
    await select("View", "Toggle Full Screen")
    await page.waitForFunction(() => window.__ocNativeCalls.some(call => call.name === "menuAction" && call.args.action === "view.toggleFullscreen"))
    record(profile.name, "application-view-actions-zoom-reset-and-native-fullscreen-request", { zoom: 1 })
    // Use a real editor field so the menu must restore focus and selection.
    const editor = visible(page.locator('[contenteditable="true"]')).first()
    await editor.fill("menu editing proof")
    await editor.press("Control+a")
    await select("Edit", "Copy")
    await page.waitForFunction(() => window.__ocNativeCalls.some(call => call.name === "writeClipboardText" && call.args.text === "menu editing proof"))
    await select("Edit", "Delete")
    await page.waitForFunction(() => [...document.querySelectorAll('[contenteditable="true"]')].some(node => node.getBoundingClientRect().height && node.textContent === ""))
    record(profile.name, "application-edit-menu-preserves-editor-selection", { copied: "menu editing proof", deleted: true })
    await select("Help", "OpenCode Documentation")
    await page.waitForFunction(() => window.__ocNativeCalls.some(call => call.name === "openExternal" && call.args.url === "https://opencode.ai/docs"))
    assert.deepEqual(errors, [])
    record(profile.name, "application-help-opens-official-documentation", { pageErrors: 0 })
  } catch (error) {
    await page.screenshot({ path: path.join(output, profile.name + "-menu-failure.png"), fullPage: true })
    report.checks.push({ device: profile.name, name: "application-menu", status: "fail", error: error.stack, text: await page.locator("body").innerText(), pageErrors: errors })
    console.error("FAIL", profile.name, "application menu", error.message)
  } finally { await context.close() }
}

async function runImport(browser, profile, ui, server) {
  const context = await browser.newContext({ viewport: { width: profile.width, height: profile.height }, locale: "en-US", isMobile: profile.name === "phone", hasTouch: true })
  const page = await context.newPage()
  const errors = []
  page.on("pageerror", error => errors.push(error.stack))
  server.sessionId = "ses_navigation_import"
  await page.addInitScript({ content: seedScript({ serverOrigin: server.origin, workspace: server.workspace, sessionId: server.sessionId }) + ";" + nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
  const importFile = async content => {
    if (profile.name === "phone" && !await visible(page.locator('[data-action="home-project-menu"]')).first().isVisible())
      await page.locator('[data-component="home-projects-dropdown"]').click()
    await visible(page.locator('[data-action="home-project-menu"]')).first().click()
    const chooser = page.waitForEvent("filechooser")
    await page.getByRole("menuitem", { name: "Import session", exact: true }).click()
    await (await chooser).setFiles({ name: "session.json", mimeType: "application/json", buffer: Buffer.from(content) })
  }
  try {
    await page.goto(ui, { waitUntil: "domcontentloaded" })
    const before = server.sessionImports.length
    await importFile('{"invalid":true}')
    await page.getByText("Request failed", { exact: true }).waitFor()
    assert.equal(server.sessionImports.length, before)
    assert.equal(await page.locator('input[type="file"]').count(), 0)
    record(profile.name, "invalid-session-file-fails-before-api", { imports: 0 })
    const info = await fetch(`${server.origin}/api/session/${server.sessionId}`).then(response => response.json()).then(value => value.data)
    const data = { info, messages: [] }
    await importFile(JSON.stringify(data) + " ".repeat(2 * 1024 * 1024))
    await page.waitForURL(url => url.pathname.endsWith(`/session/${server.sessionId}`))
    assert.equal(server.sessionImports.length, before + 1)
    assert.deepEqual(server.sessionImports.at(-1), { ...data, location: { directory: server.workspace } })
    assert.deepEqual(errors, [])
    record(profile.name, "official-import-reads-large-file-and-opens-remote-session", { bytes: 2 * 1024 * 1024, directory: server.workspace })
  } catch (error) {
    await page.screenshot({ path: path.join(output, profile.name + "-import-failure.png"), fullPage: true })
    report.checks.push({ device: profile.name, name: "import", status: "fail", error: error.stack, text: await page.locator("body").innerText(), pageErrors: errors })
    console.error("FAIL", profile.name, "import", error.message)
  } finally { await context.close() }
}

async function runUpgrade(browser, ui, servers) {
  const context = await browser.newContext({ viewport: { width: 411, height: 914 }, locale: "en-US", isMobile: true, hasTouch: true })
  const page = await context.newPage()
  await page.addInitScript({ content: nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
  const saved = {
    list: servers.map((server, index) => ({ type: "http", http: { url: server.origin }, displayName: `Saved ${index + 1}` })),
    hidden: {}, projects: {}, lastProject: {}, recentlyClosed: {},
  }
  try {
    await page.goto(ui, { waitUntil: "domcontentloaded" })
    await button(page, "Use password").waitFor()
    await page.evaluate(({ saved, ui }) => {
      const previous = { ...saved, list: [{ type: "http", http: { url: ui } }, ...saved.list], hidden: { [ui]: true } }
      localStorage.setItem("opencode.global.dat:server", JSON.stringify(previous))
      localStorage.setItem("opencode.settings.dat:defaultServerUrl", ui)
    }, { saved, ui })
    await page.reload({ waitUntil: "domcontentloaded" })
    await page.locator('[data-component="home-projects-dropdown"]').waitFor()
    assert.deepEqual(await registry(page), saved)
    assert.equal(await page.evaluate(() => localStorage.getItem("opencode.settings.dat:defaultServerUrl")), null)
    await openSettings(page)
    await button(page, "Add server").waitFor()
    await page.locator(".settings-mobile-menu-trigger").click()
    for (const name of servers.map((_, index) => `Saved ${index + 1}`)) await page.getByRole("menuitemradio", { name, exact: true }).waitFor()
    record("phone", "upgrade-keeps-three-real-servers-and-settings", await registry(page))
    await page.screenshot({ path: path.join(output, "phone-upgrade.png"), fullPage: true })
  } catch (error) {
    report.checks.push({ device: "phone", name: "upgrade", status: "fail", error: error.stack, registry: await registry(page) })
    console.error("FAIL", "upgrade", error.message)
  } finally { await context.close() }
}

const uiServer = await startUiServer()
const ui = `http://127.0.0.1:${uiServer.address().port}`
const servers = await Promise.all(Array.from({ length: 5 }, () => startBackend()))
servers.forEach((server, i) => { server.workspace = "C:\\workspace\\navigation-" + i })
const browser = await chromium.launch({ executablePath: process.env.VERIFY_TERMINAL_CHROMIUM || "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe", headless: true })
try {
  if (process.env.VERIFY_NAVIGATION_FILTER !== "plus") for (const profile of profiles) await runProfile(browser, profile, ui, servers)
  if (process.env.VERIFY_NAVIGATION_FILTER !== "plus") for (const profile of profiles) await runImport(browser, profile, ui, servers[0])
  await runOfficialPlus(browser, ui, servers[0])
  for (const profile of profiles) await runAppMenu(browser, profile, ui, servers[0])
  if (process.env.VERIFY_NAVIGATION_FILTER !== "plus") await runUpgrade(browser, ui, servers.slice(0, 3))
}
finally {
  await browser.close()
  for (const server of [...servers.map(state => state.server), uiServer]) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)) }
  report.totals = { pass: report.checks.filter(check => check.status === "pass").length, fail: report.checks.filter(check => check.status === "fail").length }
  fs.writeFileSync(path.join(output, "report.json"), JSON.stringify(report, null, 2))
  console.log("REPORT", output, JSON.stringify(report.totals))
  process.exitCode = report.totals.fail ? 1 : 0
}
