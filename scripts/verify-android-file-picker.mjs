import assert from "node:assert/strict"
import fs from "node:fs"

const { chromium } = await import(process.env.VERIFY_TERMINAL_PLAYWRIGHT || new URL("../_upstream/node_modules/.bun/playwright@1.59.1/node_modules/playwright/index.mjs", import.meta.url).href)
const source = fs.readFileSync(new URL("../frontend/android/files.mjs", import.meta.url), "utf8")
const moduleUrl = `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
const browser = await chromium.launch({ executablePath: process.env.VERIFY_TERMINAL_CHROMIUM || "C:/Users/xiufe/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe", headless: true })
const page = await browser.newPage()
try {
  await page.setContent("<!doctype html><title>Android file picker contract</title>")
  await page.evaluate(async url => { window.picker = (await import(url)).openAttachmentPickerDialog }, moduleUrl)
  const begin = async (options, failName) => {
    const chooser = page.waitForEvent("filechooser")
    await page.evaluate(({ options, failName }) => {
      window.reads = []
      window.result = "pending"
      window.picker(options, async file => {
        const text = await file.text()
        window.reads.push({ name: file.name, size: file.size, length: text.length })
        if (file.name === failName) throw new Error("Invalid import payload")
      }).then(() => { window.result = "done" }, error => { window.result = error.message })
    }, { options, failName })
    return chooser
  }
  let chooser = await begin({ multiple: true, accept: ["application/json"], extensions: ["json"] })
  assert.equal(await chooser.isMultiple(), true)
  assert.equal(await page.locator('input[type="file"]').getAttribute("accept"), "application/json,.json")
  await chooser.setFiles([
    { name: "large.json", mimeType: "application/json", buffer: Buffer.from(" ".repeat(2 * 1024 * 1024)) },
    { name: "second.json", mimeType: "application/json", buffer: Buffer.from("{}") },
  ])
  await page.waitForFunction(() => window.result === "done")
  assert.deepEqual(await page.evaluate(() => window.reads.map(file => [file.name, file.length])), [["large.json", 2 * 1024 * 1024], ["second.json", 2]])
  assert.equal(await page.locator('input[type="file"]').count(), 0)
  console.log("PASS sequential file reads preserve files larger than 1 MiB")

  chooser = await begin({ multiple: true }, "invalid.json")
  await chooser.setFiles(["first.json", "invalid.json", "third.json"].map(name => ({ name, mimeType: "application/json", buffer: Buffer.from("{}") })))
  await page.waitForFunction(() => window.result === "Invalid import payload")
  assert.deepEqual(await page.evaluate(() => window.reads.map(file => file.name)), ["first.json", "invalid.json"])
  assert.equal(await page.locator('input[type="file"]').count(), 0)
  console.log("PASS callback failure rejects and stops remaining files")

  await begin({})
  await page.locator('input[type="file"]').dispatchEvent("cancel")
  await page.waitForFunction(() => window.result === "done")
  assert.deepEqual(await page.evaluate(() => window.reads), [])
  assert.equal(await page.locator('input[type="file"]').count(), 0)
  console.log("PASS picker cancellation resolves without importing")

  chooser = await begin({})
  assert.equal(await chooser.isMultiple(), false)
  await chooser.setFiles({ name: "retry.json", mimeType: "application/json", buffer: Buffer.from("{}") })
  await page.waitForFunction(() => window.result === "done")
  assert.equal(await page.locator('input[type="file"]').count(), 0)
  console.log("PASS picker opens again after cancellation")
} finally { await browser.close() }
