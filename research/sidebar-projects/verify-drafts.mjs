import assert from "node:assert/strict"
import { mkdir } from "node:fs/promises"
import { chromium } from "file:///C:/sensorsdata/main/program/opencode-android-shell/_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs"

const shots = "C:/sensorsdata/main/program/opencode-android-shell/research/sidebar-projects/shots/drafts-v2"
await mkdir(shots, { recursive: true })

const browser = await chromium.launch({
  executablePath: "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe",
  headless: true,
})
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  locale: "zh-CN",
  timezoneId: "Asia/Shanghai",
})
await context.addInitScript((layout) => {
  // Seed only once: the horizontal check rewrites this key and reloads.
  if (!localStorage.getItem("settings.v3")) {
    localStorage.setItem("settings.v3", JSON.stringify({ appearance: { fontSize: 14, tabLayout: layout } }))
  }
}, "vertical")

const page = await context.newPage()
const errors = []
page.on("console", (message) => {
  if (message.type() === "error") errors.push(message.text())
})
page.on("pageerror", (error) => errors.push(error.message))

let stepIndex = 0
const step = async (name, fn) => {
  const index = String(++stepIndex).padStart(2, "0")
  try {
    const value = await fn()
    console.log(`[ok ${index}] ${name}`, value === undefined ? "" : JSON.stringify(value))
    return value
  } catch (error) {
    await page.screenshot({ path: `${shots}/FAILURE-${index}.png` }).catch(() => {})
    console.error(`[FAIL ${index}] ${name}:`, error)
    throw error
  }
}

const draftRows = () =>
  page.evaluate(() =>
    [...document.querySelectorAll('[data-slot="draft-row"]')].map((row) => ({
      draft: row.getAttribute("data-draft"),
      active: row.hasAttribute("data-active"),
      text: (row.textContent ?? "").trim(),
      inProjectGroup: !!row.closest('[data-slot="project-group"]'),
    })),
  )

const draftListInfo = () =>
  page.evaluate(() => {
    const list = document.querySelector('[data-slot="draft-list"]')
    const sidebar = document.querySelector('[data-slot="vertical-tabs-sidebar"]')
    const heading = [...document.querySelectorAll("div")].find((node) => node.textContent === "项目")
    return {
      present: !!list,
      inProjectGroup: !!list?.closest('[data-slot="project-group"]'),
      draftRowCount: document.querySelectorAll('[data-slot="draft-row"]').length,
      projectGroupCount: document.querySelectorAll('[data-slot="project-group"]').length,
      listBeforeProjects: !!(list && heading && list.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING),
      sidebarVisible: !!sidebar && getComputedStyle(sidebar).display !== "none",
    }
  })

const activeDraftId = async () => {
  const rows = await draftRows()
  return rows.find((row) => row.active)?.draft
}

const waitForCount = async (count) => {
  await page.waitForFunction(
    (expected) => document.querySelectorAll('[data-slot="draft-row"]').length === expected,
    count,
    { timeout: 20_000 },
  )
}

const clickNewSession = async () => {
  await page.locator('[data-action="vertical-tabs-new-session"]').click()
}

try {
  await page.goto("http://127.0.0.1:4444/", { waitUntil: "domcontentloaded" })

  await step("connect to the live server", async () => {
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
    await page.locator('[data-slot="vertical-tabs-sidebar"]').waitFor({ state: "visible", timeout: 60_000 })
  })

  await step("(a) root opens a new-session draft, not Home", async () => {
    await page.locator('[data-slot="draft-list"]').waitFor({ state: "visible", timeout: 60_000 })
    await page.locator('[data-component="composer-editor"]').waitFor({ state: "visible", timeout: 60_000 })
    const url = page.url()
    assert.match(url, /\/new-session\?draftId=/)
    assert.equal(await page.locator('[data-slot="home-panel"]').count(), 0)
    const rows = await draftRows()
    assert.equal(rows.length, 1)
    assert.equal(rows[0].active, true)
    assert.equal(rows[0].text, "新建会话")
    await page.screenshot({ path: `${shots}/01-root-opens-new-draft.png` })
    return { url, rows }
  })

  const firstDraftId = await activeDraftId()

  await step("(b) typing creates/keeps a draft row titled with the draft text", async () => {
    const editor = page.locator('[data-component="composer-editor"]')
    await editor.click()
    await page.keyboard.type("验证草稿标题 one")
    await page.waitForFunction(
      (draftId) => {
        const row = document.querySelector(`[data-slot="draft-row"][data-draft="${draftId}"]`)
        return !!row && (row.textContent ?? "").includes("验证草稿标题 one")
      },
      firstDraftId,
      { timeout: 20_000 },
    )
    const rows = await draftRows()
    assert.equal(rows.length, 1)
    assert.equal(rows[0].draft, firstDraftId)
    assert.match(rows[0].text, /验证草稿标题 one/)
    await page.screenshot({ path: `${shots}/02-draft-titled-from-typed-text.png` })
    return rows
  })

  await step("(c) the draft section is outside every project group", async () => {
    const info = await draftListInfo()
    assert.equal(info.present, true)
    assert.equal(info.inProjectGroup, false)
    assert.equal(info.projectGroupCount > 0, true)
    assert.equal(info.listBeforeProjects, true)
    for (const row of await draftRows()) assert.equal(row.inProjectGroup, false)
    return info
  })

  await step("(e) 新建会话 creates a second draft repeatedly", async () => {
    await clickNewSession()
    await waitForCount(2)
    await page.waitForFunction(
      (previous) => {
        const active = document.querySelector('[data-slot="draft-row"][data-active]')
        return !!active && active.getAttribute("data-draft") !== previous
      },
      firstDraftId,
      { timeout: 20_000 },
    )
    const rows = await draftRows()
    assert.equal(rows.length, 2)
    const active = rows.find((row) => row.active)
    assert.ok(active, "the new draft should be active")
    assert.notEqual(active.draft, firstDraftId)
    assert.equal(active.text, "新建会话")
    assert.equal(rows.some((row) => row.inProjectGroup), false)
    await page.screenshot({ path: `${shots}/03-second-draft-created.png` })
    return rows
  })

  const secondDraftId = await activeDraftId()

  await step("(d) closing the active draft row removes it and returns to the remaining draft", async () => {
    await page.locator(`[data-slot="draft-row"][data-draft="${secondDraftId}"] [data-action="draft-close"]`).click()
    await waitForCount(1)
    const rows = await draftRows()
    assert.equal(rows.length, 1)
    assert.equal(rows[0].draft, firstDraftId)
    assert.equal(rows[0].active, true)
    assert.match(rows[0].text, /验证草稿标题 one/)
    assert.match(page.url(), /\/new-session\?draftId=/)
    await page.screenshot({ path: `${shots}/04-draft-row-closed.png` })
    return { url: page.url(), rows }
  })

  await step("brand wordmark opens a new draft instead of Home", async () => {
    const activeBefore = await activeDraftId()
    await page.locator('[data-action="vertical-tabs-brand"]').click()
    await waitForCount(2)
    await page.waitForFunction(
      (previous) => {
        const active = document.querySelector('[data-slot="draft-row"][data-active]')
        return !!active && active.getAttribute("data-draft") !== previous
      },
      activeBefore,
      { timeout: 20_000 },
    )
    assert.equal(await page.locator('[data-slot="home-panel"]').count(), 0)
    assert.match(page.url(), /\/new-session\?draftId=/)
    const rows = await draftRows()
    assert.equal(rows.length, 2)
    assert.equal(rows.find((row) => row.active)?.text, "新建会话")
    await page.screenshot({ path: `${shots}/05-brand-opens-new-draft.png` })
    return rows
  })

  await step("re-entering / reuses a draft instead of creating duplicate empty drafts", async () => {
    const before = await draftRows()
    const firstIds = before.map((row) => row.draft).sort()
    await page.goto("http://127.0.0.1:4444/", { waitUntil: "domcontentloaded" })
    await page.locator('[data-slot="vertical-tabs-sidebar"]').waitFor({ state: "visible", timeout: 60_000 })
    await page.locator('[data-slot="draft-list"]').waitFor({ state: "visible", timeout: 60_000 })
    await page.waitForTimeout(1_500)
    assert.equal(await page.locator('[data-slot="home-panel"]').count(), 0)
    assert.match(page.url(), /\/new-session\?draftId=/)
    const after = await draftRows()
    const afterIds = after.map((row) => row.draft).sort()
    assert.equal(after.length, before.length)
    assert.deepEqual(afterIds, firstIds)
    await page.screenshot({ path: `${shots}/06-root-reuses-existing-draft.png` })
    return { before: firstIds, after: afterIds }
  })

  await step("horizontal layout still renders Home at /", async () => {
    await page.evaluate(() => {
      localStorage.setItem("settings.v3", JSON.stringify({ appearance: { fontSize: 14, tabLayout: "horizontal" } }))
    })
    await page.goto("http://127.0.0.1:4444/", { waitUntil: "domcontentloaded" })
    await page.locator('[data-slot="home-panel"]').waitFor({ state: "visible", timeout: 60_000 })
    assert.equal(await page.locator('[data-slot="vertical-tabs-sidebar"]').count(), 0)
    assert.equal(await page.locator('[data-slot="draft-list"]').count(), 0)
    await page.screenshot({ path: `${shots}/07-horizontal-home-unchanged.png` })
    return { url: page.url() }
  })

  console.log("\nALL CHECKS PASSED")
  console.log("console errors (may include the pre-auth health probe):", errors)
} finally {
  await browser.close()
}
