import assert from "node:assert/strict"
import { mkdir } from "node:fs/promises"
import { chromium } from "file:///C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"

const shots = "C:/sensorsdata/main/program/opencode-android-shell/research/sidebar-projects/shots/drafts-v2"
await mkdir(shots, { recursive: true })

const browser = await chromium.launch({
  executablePath: "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe",
  headless: true,
})
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: "zh-CN" })
await context.addInitScript(() => {
  if (!localStorage.getItem("settings.v3")) {
    localStorage.setItem("settings.v3", JSON.stringify({ appearance: { fontSize: 14, tabLayout: "vertical" } }))
  }
})
const page = await context.newPage()
page.on("pageerror", (error) => console.log("[page error]", error.message))

const rows = () =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-slot="draft-row"]')].map((row) => ({
      draft: row.getAttribute("data-draft"),
      active: row.hasAttribute("data-active"),
      text: (row.textContent ?? "").trim(),
    })),
  )

try {
  await page.goto("http://127.0.0.1:4444/", { waitUntil: "domcontentloaded" })
  const method = page.locator('[data-component="server-connect-method"] button')
  await method.waitFor({ state: "visible", timeout: 30_000 })
  await method.click()
  await page.locator('input[name="server"]').fill("http://127.0.0.1:40199")
  await page.locator('input[name="password"]').fill(process.env.OC_PW)
  await page
    .locator("form")
    .filter({ has: page.locator('input[name="server"]') })
    .locator('button[type="submit"]')
    .click()
  await page.locator('[data-slot="draft-list"]').waitFor({ state: "visible", timeout: 60_000 })
  const first = await rows()
  console.log("initial", first)

  // Close the only draft: vertical mode must stay on a draft screen, so a fresh empty draft should
  // replace the closed one (and the closed ID must not come back).
  await page.locator(`[data-slot="draft-row"][data-draft="${first[0].draft}"] [data-action="draft-close"]`).click()
  await page.waitForTimeout(2_000)
  const afterClose = await rows()
  console.log("after closing the only draft", afterClose, page.url())
  assert.equal(afterClose.length, 1)
  assert.notEqual(afterClose[0].draft, first[0].draft)
  assert.equal(afterClose[0].text, "新建会话")
  assert.match(page.url(), /\/new-session\?draftId=/)
  await page.screenshot({ path: `${shots}/08-closing-only-draft-stays-on-new-draft.png` })

  // Rapid repeated 新建会话 clicks should keep adding drafts, not get swallowed.
  const button = page.locator('[data-action="vertical-tabs-new-session"]')
  await button.click()
  await page.locator('[data-slot="draft-row"]').nth(1).waitFor({ state: "visible", timeout: 20_000 })
  await button.click()
  await page.waitForFunction(() => document.querySelectorAll('[data-slot="draft-row"]').length === 3, undefined, {
    timeout: 20_000,
  })
  console.log("after two more 新建会话 clicks", await rows(), page.url())
  await page.screenshot({ path: `${shots}/09-repeated-new-session-clicks.png` })
  console.log("EDGE CHECKS PASSED")
} finally {
  await browser.close()
}
