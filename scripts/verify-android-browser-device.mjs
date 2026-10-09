import fs from "node:fs/promises"
import http from "node:http"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"
import assert from "node:assert/strict"
import { DevicePage } from "./android-webview-cdp.mjs"

// Use an isolated, English-language test APK session already paired with a real
// OpenCode server and companion on this computer. No native credentials are read.
const serial = process.env.ANDROID_SERIAL
if (!serial) throw new Error("Set ANDROID_SERIAL to your isolated Android test device")
const adb = process.env.ADB || "adb"
const cdp = process.env.DEVICE_CDP || "http://127.0.0.1:9226"
const output = fileURLToPath(new URL(`../research/tmp/verify-android-browser/${new Date().toISOString().replace(/[:.]/g, "-")}-device`, import.meta.url))
await fs.mkdir(output, { recursive: true })
const checks = []
const proof = []
const errors = []
const native = args => execFileSync(adb, ["-s", serial, "shell", ...args], { stdio: "pipe" })
const fixture = http.createServer((req, res) => {
  if (req.url.startsWith("/proof")) { proof.push(req.url); res.end("ok"); return }
  res.setHeader("content-type", "text/html; charset=utf-8")
  res.end(`<!doctype html><title>Android live browser</title><style>body{margin:16px;font:18px sans-serif}input,button{position:absolute;left:16px;width:180px;height:40px;margin:0}input{top:100px}button{top:160px}</style>
    <h2>Android browser</h2><input aria-label="Text" onfocus="fetch('/proof?focus=1')" oninput="fetch('/proof?text='+encodeURIComponent(this.value))">
    <button onclick="fetch('/proof?click=1')">Tap test</button><div style="height:1800px">Scroll test</div>
    <script>fetch('/proof?ready=1');onscroll=()=>fetch('/proof?scroll='+scrollY)</script>`)
})
await new Promise((resolve, reject) => { fixture.once("error", reject); fixture.listen(0, "127.0.0.1", resolve) })
const address = `http://127.0.0.1:${fixture.address().port}`
native(["input", "keyevent", "224"])
const target = (await fetch(`${cdp}/json`).then(response => response.json())).find(target => target.url.startsWith("http://127.0.0.1:4173"))
if (!target) { fixture.close(); throw new Error("Packaged APK WebView is not attached to CDP") }
const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }) })
const wait = async condition => {
  const deadline = Date.now() + 30_000
  let wakeAt = 0
  while (Date.now() < deadline) {
    // Emulator screen timeout suspends WebView rendering during long waits.
    if (Date.now() >= wakeAt) { native(["input", "keyevent", "224"]); wakeAt = Date.now() + 5_000 }
    if (await condition()) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error("Android browser test timed out")
}
const page = new DevicePage(socket, wait)
await page.send("Runtime.enable")
page.on("pageerror", error => errors.push(error.message))
const pass = name => { checks.push(name); console.log(`PASS ${name}`) }
async function selectButton(text) {
  await wait(() => page.evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find(button => button.textContent === ${JSON.stringify(text)});
    if (!button) return false;
    const rect = button.getBoundingClientRect();
    return rect.height > 0 && rect.top >= 0 && rect.bottom <= innerHeight;
  })()`))
  await page.evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find(button => button.textContent === ${JSON.stringify(text)} && button.getBoundingClientRect().height > 0);
    if (!button) throw new Error('Missing button');
    button.click();
  })()`)
}
async function openBrowser() {
  if (await page.evaluate(() => !!document.querySelector('[data-slot="browser-address-input"]'))) return
  if (!await page.evaluate(() => [...document.querySelectorAll("button")].some(button => {
    const rect = button.getBoundingClientRect()
    return button.textContent === "Browser" && rect.height > 0 && rect.top >= 0 && rect.bottom <= innerHeight
  }))) await selectButton("More...")
  await wait(() => page.evaluate(() => [...document.querySelectorAll("button")].some(button => button.textContent === "Browser" && button.getBoundingClientRect().height > 0)))
  await selectButton("Browser")
  await wait(() => page.evaluate(() => !!document.querySelector('[data-slot="browser-address-input"]')))
}
try {
  await openBrowser()
  await wait(() => page.evaluate(() => document.querySelector('[data-slot="browser-address-input"]')?.disabled === false))
  const addressPoint = await page.evaluate(() => {
    const rect = document.querySelector('[data-slot="browser-address-input"]').getBoundingClientRect()
    return [Math.round((rect.x + rect.width / 2) * devicePixelRatio), Math.round((rect.y + rect.height / 2) * devicePixelRatio)]
  })
  native(["input", "tap", ...addressPoint.map(String)])
  await wait(() => page.evaluate(() => document.activeElement?.getAttribute("data-slot") === "browser-address-input"))
  await page.evaluate(() => document.querySelector('[data-slot="browser-address-input"]').select())
  await page.send("Input.insertText", { text: address })
  native(["input", "keyevent", "66"])
  await wait(() => proof.includes("/proof?ready=1"))
  await wait(() => page.evaluate(() => innerHeight > 800 && document.activeElement?.getAttribute("data-slot") !== "browser-address-input"))
  await wait(() => page.evaluate(() => !!document.querySelector("[data-android-browser-surface] img")?.naturalWidth))
  pass("actual Android browser frame")
  const bounds = () => page.evaluate(() => {
    const rect = document.querySelector("[data-android-browser-surface]").getBoundingClientRect()
    return { x: rect.x, y: rect.y, width: rect.width, height: rect.height, dpr: devicePixelRatio }
  })
  let rect = await bounds()
  assert.ok(rect.width > 300 && rect.height > 300)
  const point = (x, y) => [String(Math.round((rect.x + x) * rect.dpr)), String(Math.round((rect.y + y) * rect.dpr))]
  native(["input", "tap", ...point(80, 168)])
  await wait(() => proof.some(value => value.includes("click=1")))
  pass("ADB click reaches webpage")
  native(["input", "tap", ...point(80, 114)])
  await wait(() => proof.includes("/proof?focus=1"))
  await wait(() => page.evaluate(() => document.activeElement?.getAttribute("aria-label") === "Browser keyboard"))
  await page.evaluate(() => {
    const field = document.activeElement
    field.value = "你好 Android"
    field.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: field.value }))
  })
  await wait(() => proof.some(value => decodeURIComponent(value).includes("你好 Android")))
  pass("Android IME textarea sends Unicode")
  native(["input", "keyevent", "4"])
  await wait(() => page.evaluate(() => {
    const root = document.querySelector("[data-android-browser-surface]")
    const image = root?.querySelector("img")
    const rect = root?.getBoundingClientRect()
    return innerHeight > 800 && image?.naturalHeight && Math.abs(image.naturalWidth / image.naturalHeight - rect.width / rect.height) < 0.005
  }))
  rect = await bounds()
  native(["input", "swipe", ...point(200, 550), ...point(200, 250), "500"])
  await wait(() => proof.some(value => value.startsWith("/proof?scroll=") && Number(value.split("=")[1]) > 100))
  pass("ADB swipe scrolls webpage")
  native(["input", "swipe", ...point(200, 250), ...point(200, 600), "500"])
  await wait(() => proof.includes("/proof?scroll=0"))
  const screenshot = await page.send("Page.captureScreenshot", { format: "png" })
  await fs.writeFile(path.join(output, "browser.png"), Buffer.from(screenshot.data, "base64"))
  native(["input", "keyevent", "224"])
  const previousDocument = await page.evaluate(() => performance.timeOrigin)
  await page.send("Page.reload")
  await wait(() => page.evaluate(`performance.timeOrigin !== ${previousDocument} && document.readyState === 'complete'`))
  await wait(() => page.evaluate(() => [...document.querySelectorAll("button")].some(button => button.textContent === "More...")))
  await openBrowser()
  await wait(() => page.evaluate(() => !!document.querySelector("[data-android-browser-surface] img")?.naturalWidth))
  assert.equal(await page.evaluate(() => document.querySelector('[data-slot="browser-address-input"]').value), `${address}/`)
  pass("reload reconnects and restores browser tab")
  assert.deepEqual(errors, [])
  pass("no Android page errors")
} finally {
  await page.detach()
  fixture.close()
  await fs.writeFile(path.join(output, "report.json"), JSON.stringify({ scope: "actual Android WebView + ADB webpage input + real OpenCode browser companion; menu setup uses DOM clicks, Unicode insertion uses CDP InputEvent", serial, pass: checks.length, checks, errors }, null, 2))
}
console.log(`REPORT ${path.join(output, "report.json")}`)
