import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { chromium } from "../plugins/server-browser/node_modules/playwright/index.mjs"
import { startBackend, startUiServer, nativeBridgeSource, ocHostLoaderSource, seedScript, base64url } from "./verify-android-terminal-release.mjs"

const root = fileURLToPath(new URL("../", import.meta.url))
const output = path.join(root, "research/tmp/verify-provider-icons", new Date().toISOString().replace(/[:.]/g, "-"))
fs.mkdirSync(output, { recursive: true })
const cases = [
  ["minimax-cn", "MiniMax (minimax.cn)", "minimax-cn"],
  ["minimax-cn-coding-plan", "MiniMax Token Plan (minimax.cn)", "minimax-cn"],
  ["moonshotai", "Moonshot AI", "moonshotai"],
  ["kimi-code-plan-cn", "Kimi For Coding (kimi.com)", "kimi-for-coding"],
  ["xiaomi", "Xiaomi", "xiaomi"],
  ["xiaomi-token-plan-cn", "Xiaomi Token Plan (China)", "xiaomi"],
  ["alibaba-token-plan-cn", "Alibaba Token Plan (China)", "alibaba-cn"],
  ["stepfun-ai-step-plan", "StepFun Step Plan (Global)", "stepfun"],
  ["tencent-token-plan", "Tencent Token Plan", "tencent"],
  ["volcengine-coding-plan", "Volcengine Ark Coding Plan", "volcengine"],
  ["llmgateway", "LLM Gateway", "llmgateway"],
]
const backend = await startBackend()
backend.workspace = "C:/workspace/provider-icons"
backend.sessionId = "ses_provider_icons"
const ui = await startUiServer()
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, locale: "en-US" })
const errors = []
page.on("pageerror", error => errors.push(error.stack))

try {
  const location = { directory: backend.workspace }
  const providers = cases.map(([id, name]) => ({ id, name, activation: "enabled", package: "@ai-sdk/openai-compatible" }))
  const models = cases.map(([providerID], index) => ({
    id: `model-${index}`, modelID: `model-${index}`, providerID, name: `Model ${index}`,
    capabilities: { tools: true, input: ["text"], output: ["text"] }, variants: [], time: { released: 1 },
    cost: [], status: "active", enabled: true, limit: { context: 128000, output: 8192 },
  }))
  await page.route("**/api/**", async route => {
    const pathname = new URL(route.request().url()).pathname
    if (route.request().method() !== "GET" || (pathname !== "/api/provider" && pathname !== "/api/model")) return route.continue()
    await route.fulfill({ json: { location, data: pathname === "/api/provider" ? providers : models },
      headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "*" } })
  })
  await page.addInitScript({ content: seedScript({ serverOrigin: backend.origin, workspace: backend.workspace, sessionId: backend.sessionId }) + ";" + nativeBridgeSource() + ";" + ocHostLoaderSource(fs.readFileSync(path.join(root, "webapp/src/main/assets/www/oc-host.js"), "utf8")) })
  await page.goto(`http://127.0.0.1:${ui.address().port}/server/${base64url(backend.origin)}/session/${backend.sessionId}`)
  await page.getByRole("button", { name: "OpenCode menu", exact: true }).click()
  await page.getByRole("menuitem", { name: "File", exact: true }).hover()
  await page.getByRole("menuitem", { name: /^Settings/ }).click()
  await page.locator(".settings").waitFor()
  await page.getByRole("tab", { name: "Models", exact: true }).click()
  for (const [, name, expected] of cases) {
    const group = page.locator('[data-component="settings-models-provider"]').filter({ has: page.getByText(name, { exact: true }) })
    await group.waitFor()
    const icon = group.locator('[data-component="provider-icon"] use')
    await icon.waitFor()
    assert.match(await icon.getAttribute("href"), new RegExp(`#${expected}$`), name)
    await page.waitForFunction(element => element.getBBox().width > 0 && element.getBBox().height > 0, await icon.elementHandle())
    console.log("PASS", name, "→", expected)
  }
  assert.deepEqual(errors, [])
  assert.equal(await page.getByText("Request failed", { exact: true }).count(), 0)
  await page.screenshot({ path: path.join(output, "provider-icons.png"), fullPage: true })
  console.log("REPORT", output)
} catch (error) {
  await page.screenshot({ path: path.join(output, "failure.png"), fullPage: true })
  console.error("Provider icon verification failed:", error.message)
  console.error("Page errors:", JSON.stringify(errors))
  console.error("Backend requests:", JSON.stringify(backend.hits))
  throw error
} finally {
  await browser.close()
  for (const connection of backend.sockets.values()) connection.socket.destroy()
  for (const server of [backend.server, ui]) {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
  }
}
