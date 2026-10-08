import fs from "node:fs/promises"
import { createHash } from "node:crypto"
import { fileURLToPath } from "node:url"
import { createResizePointerHandler } from "./resize-pointer.mjs"

const entry = fileURLToPath(new URL("../../webapp/src/main/assets/www/ui/oa/index-D2r3WEwY.js", import.meta.url))
const stylesheet = fileURLToPath(new URL("../../webapp/src/main/assets/www/ui/oa/index-nVyEgSVI.css", import.meta.url))
const signature = "function ak(e)"
const nextComponent = "var ok="
const originalHash = "b0115d8817f480354440191b042b72f9cadfcf541613d76954afad63fbd9e178"
const prefix = `const ocResizePointerHandler=(${createResizePointerHandler.toString()});\n`
const cssBefore = "[data-component=resize-handle]{z-index:10;position:absolute}"
const cssAfter = "[data-component=resize-handle]{z-index:10;touch-action:none;position:absolute}"
// Enlarge only the invisible touch target; keep the official gap and divider.
const touchTarget = '@media(any-pointer:coarse){[data-component=resize-handle]:before{content:"";position:absolute;inset:0 -8px}[data-component=resize-handle][data-direction=vertical]:before{inset:-8px 0}}'

export function patchResizeHandle(source) {
  const start = source.indexOf(signature)
  const end = source.indexOf(nextComponent, start)
  if (start < 0 || end < 0 || source.split(signature).length !== 2) throw new Error("ResizeHandle component anchor changed")
  const original = source.slice(start, end).trim()
  if (source.includes(prefix)) {
    if (source.split(prefix).length !== 2 || !original.includes("r=ocResizePointerHandler(t,D)") || !original.includes("onPointerDown:r") || original.includes("onMouseDown:r")) throw new Error("Incomplete resize pointer patch")
    return source
  }
  if (createHash("sha256").update(original).digest("hex") !== originalHash) throw new Error("ResizeHandle upstream implementation changed; review its pointer and size contract")
  const handler = original.indexOf(",r=e=>{")
  const render = original.indexOf(";return(()=>")
  const patched = (original.slice(0, handler) + ",r=ocResizePointerHandler(t,D)" + original.slice(render)).replace("onMouseDown:r", "onPointerDown:r")
  return source.slice(0, start) + prefix + patched + "\n" + source.slice(end)
}

export function patchResizeCss(source) {
  if (source.includes(cssAfter) && source.includes(touchTarget) && !source.includes(cssBefore)) return source
  if (source.split(cssBefore).length !== 2 || source.includes(touchTarget)) throw new Error("ResizeHandle stylesheet anchor changed")
  return source.replace(cssBefore, cssAfter) + "\n" + touchTarget + "\n"
}

export async function patchResizeAssets() {
  const [source, css] = await Promise.all([fs.readFile(entry, "utf8"), fs.readFile(stylesheet, "utf8")])
  const patched = patchResizeHandle(source)
  const patchedCss = patchResizeCss(css)
  if (patched !== source) await fs.writeFile(entry, patched)
  if (patchedCss !== css) await fs.writeFile(stylesheet, patchedCss)
}
