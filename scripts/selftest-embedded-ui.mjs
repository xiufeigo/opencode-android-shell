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
const portraitScreenshotPath =
  process.env.SELFTEST_PORTRAIT_SCREENSHOT || "/opt/cursor/artifacts/selftest-settings-portrait.png"
const portraitScrolledScreenshotPath =
  process.env.SELFTEST_PORTRAIT_SCROLLED_SCREENSHOT ||
  "/opt/cursor/artifacts/selftest-settings-portrait-scrolled.png"

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

const SETTINGS_LAYOUT_JS = `(() => {
  const tabs = document.querySelector('[data-component="tabs-v2"][data-variant="settings"]');
  const list = document.querySelector('[data-slot="tabs-v2-list"]');
  const contents = Array.from(document.querySelectorAll('[data-slot="tabs-v2-content"]'));
  const content = contents.find((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return s.display !== 'none' && r.width > 0 && r.height > 0;
  }) || contents[0];
  const select = document.querySelector('[data-component="select-v2-root"]');
  if (!tabs || !list) return { found: false, viewport: { w: innerWidth, h: innerHeight } };
  const tr = tabs.getBoundingClientRect();
  const lr = list.getBoundingClientRect();
  const cr = content ? content.getBoundingClientRect() : { width: 0, height: 0, top: 0, left: 0 };
  const dir = getComputedStyle(tabs).flexDirection;
  const stacked = dir === 'column' || lr.width >= tr.width * 0.85 || cr.top >= lr.bottom - 4;
  const selectWidth = select ? select.getBoundingClientRect().width : 0;
  return {
    found: true,
    viewport: { w: innerWidth, h: innerHeight },
    flexDirection: dir,
    tabs: { w: Math.round(tr.width), h: Math.round(tr.height) },
    list: { w: Math.round(lr.width), h: Math.round(lr.height), top: Math.round(lr.top) },
    content: { w: Math.round(cr.width), h: Math.round(cr.height), top: Math.round(cr.top), left: Math.round(cr.left) },
    stacked,
    selectWidth: Math.round(selectWidth),
    contentReadable: cr.width >= 280,
    hasGeneral: /通用|General/.test(document.body.innerText || ''),
  };
})()`

const SETTINGS_ACCORDION_JS = `(() => {
  const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return s.display !== 'none' && r.width > 0 && r.height > 0;
  });
  const panel = panels[0];
  const tabHeader = panel ? panel.querySelector('.settings-v2-tab-header') : null;
  const tabHeaderStyle = tabHeader ? getComputedStyle(tabHeader) : null;
  const sections = panel ? Array.from(panel.querySelectorAll('details.settings-v2-section')) : [];
  const summaries = sections.map((el) => {
    const sum = el.querySelector('summary.settings-v2-section-title');
    return {
      title: (sum && sum.textContent || '').trim().slice(0, 40),
      open: el.open,
      sticky: sum ? getComputedStyle(sum).position : null,
    };
  });
  const tabTitle = tabHeader ? (tabHeader.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 40) : null;
  return {
    panelFound: !!panel,
    tabHeaderDisplay: tabHeaderStyle ? tabHeaderStyle.display : null,
    tabHeaderPosition: tabHeaderStyle ? tabHeaderStyle.position : null,
    tabHeaderHidden: !tabHeader || (tabHeaderStyle && tabHeaderStyle.display === 'none'),
    tabTitle,
    sectionCount: sections.length,
    summaries,
    firstOpen: sections[0] ? sections[0].open : null,
  };
})()`

const SETTINGS_SCROLL_JS = `(() => {
  const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return s.display !== 'none' && r.width > 0 && r.height > 0;
  });
  const panel = panels[0];
  if (!panel) return { scrolled: false };
  const appearance = Array.from(panel.querySelectorAll('summary, h3')).find((el) => /外观|Appearance/.test(el.textContent || ''));
  if (appearance) appearance.scrollIntoView({ block: 'start' });
  else panel.scrollTop = Math.min(panel.scrollHeight, 420);
  const tabHeader = panel.querySelector('.settings-v2-tab-header');
  const tabHeaderStyle = tabHeader ? getComputedStyle(tabHeader) : null;
  const sticky = Array.from(panel.querySelectorAll('summary.settings-v2-section-title, h3.settings-v2-section-title')).filter((el) => {
    const s = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    const pr = panel.getBoundingClientRect();
    return s.position === 'sticky' && r.height > 0 && Math.abs(r.top - pr.top) < 8;
  });
  return {
    scrolled: true,
    scrollTop: Math.round(panel.scrollTop),
    tabHeaderHidden: !tabHeader || (tabHeaderStyle && tabHeaderStyle.display === 'none'),
    stickyTitles: sticky.map((el) => (el.textContent || '').trim().slice(0, 40)),
    lockedGeneral: sticky.some((el) => /^(通用|General)$/.test((el.textContent || '').trim())) && sticky.length === 1 && /^(通用|General)$/.test((sticky[0].textContent || '').trim()) && panel.scrollTop > 80,
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
      "--window-size=800,1280",
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
      { width: 800, height: 1280, deviceScaleFactor: 2, mobile: true },
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
    await Bun.sleep(800)
    const settingsLayout = await evaluate(ws, sessionId, SETTINGS_LAYOUT_JS)
    const settingsAccordion = await evaluate(ws, sessionId, SETTINGS_ACCORDION_JS)
    try {
      const shot = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shot?.data) {
        fs.mkdirSync(path.dirname(screenshotPath), { recursive: true })
        fs.writeFileSync(screenshotPath, Buffer.from(shot.data, "base64"))
        fs.writeFileSync(portraitScreenshotPath, Buffer.from(shot.data, "base64"))
      }
    } catch {}
    if (settingsAccordion?.sectionCount >= 2 && settingsAccordion.summaries?.[1]) {
      const collapseJs = `(() => {
        const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
          const s = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          return s.display !== 'none' && r.width > 0 && r.height > 0;
        });
        const panel = panels[0];
        const details = panel && panel.querySelectorAll('details.settings-v2-section')[1];
        const sum = details && details.querySelector('summary');
        if (sum) sum.click();
        return { collapsed: details ? details.open === false : false, title: sum ? (sum.textContent || '').trim().slice(0, 40) : null };
      })()`
      settingsAccordion.collapseProbe = await evaluate(ws, sessionId, collapseJs)
      await Bun.sleep(250)
      const reopenJs = `(() => {
        const panels = Array.from(document.querySelectorAll('.settings-v2-panel')).filter((el) => {
          const s = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          return s.display !== 'none' && r.width > 0 && r.height > 0;
        });
        const details = panels[0] && panels[0].querySelectorAll('details.settings-v2-section')[1];
        const sum = details && details.querySelector('summary');
        if (sum && details && !details.open) sum.click();
        return { open: details ? details.open : null };
      })()`
      settingsAccordion.reopenProbe = await evaluate(ws, sessionId, reopenJs)
    }
    const settingsScroll = await evaluate(ws, sessionId, SETTINGS_SCROLL_JS)
    await Bun.sleep(300)
    try {
      const shot2 = await cdp(ws, "Page.captureScreenshot", { format: "png" }, sessionId)
      if (shot2?.data) {
        fs.mkdirSync(path.dirname(portraitScrolledScreenshotPath), { recursive: true })
        fs.writeFileSync(portraitScrolledScreenshotPath, Buffer.from(shot2.data, "base64"))
      }
    } catch {}
    const scopeCrash =
      exceptions.some((e) => /reading 'scope'/.test(e)) || !!probe?.hasScopeError
    const whiteOnly = !probe?.homeCopy || probe?.titlebarOnly || (probe?.visibleButtonCount ?? 0) < 3
    const settingsPortraitOk =
      !!settingsLayout?.found &&
      settingsLayout.stacked === true &&
      (settingsLayout.contentReadable === true || settingsLayout.content?.w >= 280)
    const accordionOk =
      !!settingsAccordion?.panelFound &&
      (settingsAccordion.sectionCount ?? 0) >= 2 &&
      settingsAccordion.tabHeaderHidden === true &&
      settingsAccordion.summaries?.every((s) => s.sticky === "sticky")
    const collapseOk = settingsAccordion?.collapseProbe?.collapsed === true
    const scrollOk =
      !!settingsScroll?.scrolled &&
      settingsScroll.tabHeaderHidden === true &&
      settingsScroll.lockedGeneral !== true
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
      probe.viewportFitCover === true &&
      settingsPortraitOk &&
      accordionOk &&
      collapseOk &&
      scrollOk
    const report = {
      ok,
      pageUrl,
      probe,
      settingsLayout,
      settingsAccordion,
      settingsScroll,
      clicked: probe?.clicked ?? [],
      responded: !!(probe?.settingsClicked && (probe?.dialogAfterClick || probe.settingsFound)),
      exceptions,
      scopeCrash,
      whiteOnly,
      settingsPortraitOk,
      accordionOk,
      collapseOk,
      scrollOk,
      screenshotPath: fs.existsSync(screenshotPath) ? screenshotPath : null,
      portraitScreenshotPath: fs.existsSync(portraitScreenshotPath) ? portraitScreenshotPath : null,
      portraitScrolledScreenshotPath: fs.existsSync(portraitScrolledScreenshotPath)
        ? portraitScrolledScreenshotPath
        : null,
    }
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2))
    console.log(JSON.stringify(report, null, 2))
    if (!ok) {
      throw new Error(
        scopeCrash
          ? "self-test failed: LayoutProvider TypeError reading scope"
          : whiteOnly
            ? "self-test failed: titlebar-only / white home (asset-path router mismatch)"
            : !settingsPortraitOk
              ? "self-test failed: Settings is still a squished two-column layout in portrait"
              : !accordionOk
                ? "self-test failed: Settings category title still sticky-locked or sections are not accordions"
                : !collapseOk
                  ? "self-test failed: Settings section did not collapse"
                  : !scrollOk
                    ? "self-test failed: 「通用」 still locked after scroll"
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
