#!/usr/bin/env bun
/**
 * Headless Chrome self-test for the embedded OpenCode assets.
 *
 * Serves files at the same pathname WebViewAssetLoader uses:
 *   /assets/opencode-app/index.html  (hostname appassets.androidplatform.net)
 *
 * Official Desktop boots with MemoryRouter at "/". A Browser Router against the
 * asset file path paints Titlebar (grid-plus / +) and never mounts NewHome.
 */
import { spawn } from "node:child_process"
import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)))
const assets = path.join(root, "app/src/main/assets/opencode-app")
const HOST = "appassets.androidplatform.net"
const ANDROID_MOUNT = "/assets/opencode-app"
const reportPath = process.env.SELFTEST_REPORT || path.join(root, "selftest-embedded-ui.json")
const screenshotPath =
  process.env.SELFTEST_SCREENSHOT || "/opt/cursor/artifacts/selftest-home-android-path.png"

function contentType(file) {
  if (file.endsWith(".html")) return "text/html; charset=utf-8"
  if (file.endsWith(".js")) return "application/javascript; charset=utf-8"
  if (file.endsWith(".css")) return "text/css; charset=utf-8"
  if (file.endsWith(".svg")) return "image/svg+xml"
  if (file.endsWith(".png")) return "image/png"
  if (file.endsWith(".woff2")) return "font/woff2"
  if (file.endsWith(".json") || file.endsWith(".webmanifest")) return "application/json"
  return "application/octet-stream"
}

function startServer() {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url || "/", `http://${HOST}`)
      let rel = decodeURIComponent(url.pathname)
      if (rel.startsWith(ANDROID_MOUNT)) rel = rel.slice(ANDROID_MOUNT.length) || "/"
      if (rel === "/") rel = "/index.html"
      const file = path.join(assets, rel)
      if (!file.startsWith(assets) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
        res.writeHead(404)
        res.end("not found")
        return
      }
      res.writeHead(200, { "content-type": contentType(file) })
      fs.createReadStream(file).pipe(res)
    })
    server.listen(0, "127.0.0.1", () => resolve(server))
    server.on("error", reject)
  })
}

async function waitFor(url, timeoutMs = 15_000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url)
      if (res.ok) return await res.json()
    } catch {}
    await Bun.sleep(200)
  }
  throw new Error(`timeout waiting for ${url}`)
}

async function cdp(ws, method, params = {}, sessionId) {
  const id = Math.floor(Math.random() * 1e9)
  const payload = sessionId ? { id, method, params, sessionId } : { id, method, params }
  ws.send(JSON.stringify(payload))
  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.removeEventListener("message", onMsg)
      reject(new Error(`timeout ${method}`))
    }, 20_000)
    const onMsg = (ev) => {
      const data = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data)
      let msg
      try {
        msg = JSON.parse(data)
      } catch {
        return
      }
      if (msg.id !== id) return
      clearTimeout(timer)
      ws.removeEventListener("message", onMsg)
      if (msg.error) reject(new Error(`${method}: ${JSON.stringify(msg.error)}`))
      else resolve(msg.result)
    }
    ws.addEventListener("message", onMsg)
  })
}

async function evaluate(ws, sessionId, expression) {
  const result = await cdp(
    ws,
    "Runtime.evaluate",
    { expression, awaitPromise: true, returnByValue: true },
    sessionId,
  )
  return result?.result?.value
}

const TAP_JS = `(() => {
  const visible = (el) => {
    if (!el) return false;
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.pointerEvents === 'none') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  const labelOf = (el) =>
    ((el.getAttribute('data-action') || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.textContent || '')).trim();
  const nodes = Array.from(document.querySelectorAll('button, [role="button"], [data-action], a'));
  const visibleButtons = nodes.filter(visible);
  const settings = nodes.find((el) => {
    const label = labelOf(el).toLowerCase();
    return visible(el) && (el.getAttribute('data-action') === 'home-open-settings' || label.includes('settings') || label.includes('设置'));
  });
  const add = nodes.find((el) => {
    const label = labelOf(el).toLowerCase();
    return visible(el) && (el.getAttribute('data-action') === 'home-add-project' || el.getAttribute('data-action') === 'home-add-project-row' || label.includes('add project') || label.includes('添加项目'));
  });
  const home = nodes.find((el) => {
    const label = labelOf(el).toLowerCase();
    return visible(el) && (label.includes('home') || label.includes('主页') || el.getAttribute('aria-label')?.toLowerCase() === 'home');
  });
  const fired = [];
  const click = (el, name) => {
    if (!el) return false;
    el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
    el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    fired.push(name);
    return true;
  };
  const homeOk = click(home, 'home');
  const settingsOk = click(settings, 'settings');
  const addOk = click(add, 'add-project');
  const dialog = document.querySelector('[role="dialog"], [data-component*="dialog"], [data-component*="settings"]');
  const bodyText = (document.body.innerText || '');
  const homeCopy = /Projects|项目|Nothing here yet|Get started|还没有|Create a session|创建会话|Settings|设置/.test(bodyText);
  return {
    hostname: location.hostname,
    pathname: location.pathname,
    title: document.title,
    hasRoot: !!document.getElementById('root'),
    rootChildCount: document.getElementById('root')?.childElementCount ?? 0,
    androidShell: !!window.__ocAndroidShell,
    settingsFound: !!settings,
    settingsLabel: settings ? labelOf(settings).slice(0, 80) : null,
    addFound: !!add,
    addLabel: add ? labelOf(add).slice(0, 80) : null,
    homeFound: !!home,
    visibleButtonCount: visibleButtons.length,
    clicked: fired,
    settingsClicked: settingsOk,
    addClicked: addOk,
    homeClicked: homeOk,
    dialogAfterClick: !!dialog,
    pointerEvents: getComputedStyle(document.documentElement).pointerEvents,
    bodyText: bodyText.slice(0, 400),
    homeCopy,
    hasScopeError: /Cannot read properties of undefined/.test(bodyText) || /reading 'scope'/.test(bodyText),
    viewportFitCover: /viewport-fit\\s*=\\s*cover/.test(document.querySelector('meta[name="viewport"]')?.content || ''),
    titlebarOnly: visibleButtons.length > 0 && visibleButtons.length <= 2 && !homeCopy,
  };
})()`

async function main() {
  if (!fs.existsSync(path.join(assets, "index.html"))) {
    throw new Error(`missing embedded assets at ${assets}`)
  }
  const server = await startServer()
  const port = server.address().port
  const pageUrl = `http://${HOST}:${port}${ANDROID_MOUNT}/index.html`
  const debugPort = 9222
  const chrome = spawn(
    "google-chrome",
    [
      "--headless=new",
      "--disable-gpu",
      "--no-sandbox",
      "--disable-dev-shm-usage",
      `--remote-debugging-port=${debugPort}`,
      `--host-resolver-rules=MAP ${HOST} 127.0.0.1`,
      "--user-data-dir=/tmp/oc-selftest-chrome",
      "--window-size=1280,800",
      "about:blank",
    ],
    { stdio: "ignore" },
  )
  try {
    const version = await waitFor(`http://127.0.0.1:${debugPort}/json/version`)
    const ws = new WebSocket(version.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      ws.addEventListener("open", resolve)
      ws.addEventListener("error", reject)
    })
    const { targetId } = await cdp(ws, "Target.createTarget", { url: "about:blank" })
    const { sessionId } = await cdp(ws, "Target.attachToTarget", { targetId, flatten: true })
    await cdp(ws, "Page.enable", {}, sessionId)
    await cdp(ws, "Runtime.enable", {}, sessionId)
    await cdp(
      ws,
      "Emulation.setDeviceMetricsOverride",
      { width: 1280, height: 800, deviceScaleFactor: 2, mobile: true },
      sessionId,
    )
    await cdp(ws, "Emulation.setTouchEmulationEnabled", { enabled: true }, sessionId)
    await cdp(
      ws,
      "Emulation.setEmulatedMedia",
      { features: [{ name: "pointer", value: "coarse" }, { name: "hover", value: "none" }] },
      sessionId,
    )
    const exceptions = []
    ws.addEventListener("message", (ev) => {
      const data = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data)
      let msg
      try {
        msg = JSON.parse(data)
      } catch {
        return
      }
      if (msg.method === "Runtime.exceptionThrown" && msg.sessionId === sessionId) {
        const desc = msg.params?.exceptionDetails?.exception?.description || msg.params?.exceptionDetails?.text || ""
        exceptions.push(desc)
      }
    })
    await cdp(ws, "Page.navigate", { url: pageUrl }, sessionId)
    await Bun.sleep(8_000)
    const probe = await evaluate(ws, sessionId, TAP_JS)
    try {
      const shot = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shot?.data) {
        fs.mkdirSync(path.dirname(screenshotPath), { recursive: true })
        fs.writeFileSync(screenshotPath, Buffer.from(shot.data, "base64"))
      }
    } catch {}
    const scopeCrash =
      exceptions.some((e) => /reading 'scope'/.test(e)) || !!probe?.hasScopeError
    const whiteOnly = !probe?.homeCopy || probe?.titlebarOnly || (probe?.visibleButtonCount ?? 0) < 3
    const ok =
      probe &&
      probe.hostname === HOST &&
      probe.pathname === `${ANDROID_MOUNT}/index.html` &&
      probe.settingsFound &&
      probe.settingsClicked &&
      probe.homeCopy &&
      !whiteOnly &&
      (probe.dialogAfterClick || probe.settingsClicked) &&
      !scopeCrash &&
      probe.viewportFitCover === true
    const report = {
      ok,
      pageUrl,
      probe,
      clicked: probe?.clicked ?? [],
      responded: !!(probe?.settingsClicked && (probe?.dialogAfterClick || probe.settingsFound)),
      exceptions,
      scopeCrash,
      whiteOnly,
      screenshotPath: fs.existsSync(screenshotPath) ? screenshotPath : null,
    }
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2))
    console.log(JSON.stringify(report, null, 2))
    if (!ok) {
      throw new Error(
        scopeCrash
          ? "self-test failed: LayoutProvider TypeError reading scope"
          : whiteOnly
            ? "self-test failed: titlebar-only / white home (asset-path router mismatch)"
            : "self-test failed: Settings control was not found or did not handle the tap",
      )
    }
    ws.close()
    chrome.kill("SIGKILL")
    server.close()
    process.exit(0)
  } finally {
    chrome.kill("SIGKILL")
    server.close()
  }
}

await main()
