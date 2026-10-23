import { createServer } from "node:http"
import { readFile } from "node:fs/promises"
import { extname, join, normalize } from "node:path"
import { fileURLToPath } from "node:url"

const ROOT = fileURLToPath(new URL(".", import.meta.url))
const PLAYWRIGHT =
  process.env.PREVIEW_PLAYWRIGHT ||
  "C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"
const CHROMIUM =
  process.env.PREVIEW_CHROMIUM || "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe"

const TYPES = { ".html": "text/html", ".css": "text/css", ".js": "text/javascript", ".png": "image/png" }

const server = createServer(async (request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url ?? "/", "http://127.0.0.1").pathname))
  const file = join(ROOT, path === "/" ? "mockup.html" : path)
  if (!file.startsWith(ROOT)) return response.writeHead(403).end()
  try {
    const body = await readFile(file)
    response.writeHead(200, { "content-type": TYPES[extname(file)] ?? "application/octet-stream" }).end(body)
  } catch {
    response.writeHead(404).end()
  }
})

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve))
const { port } = server.address()

const { chromium } = await import(`file:///${PLAYWRIGHT.replace(/\\/g, "/")}`)
const browser = await chromium.launch({ headless: true, executablePath: CHROMIUM })
const page = await browser.newPage({ viewport: { width: 1500, height: 900 }, deviceScaleFactor: 2 })
await page.goto(`http://127.0.0.1:${port}/mockup.html`, { waitUntil: "networkidle" })
await page.screenshot({ path: join(ROOT, "mockup-expanded.png"), clip: { x: 0, y: 0, width: 1500, height: 900 } })
const sections = await page.locator(".state").all()
for (const [index, section] of sections.entries()) {
  await section.screenshot({ path: join(ROOT, `state-${index + 1}.png`) })
}
await browser.close()
server.close()
console.log("ok", ROOT)
