import assert from "node:assert/strict"
import test from "node:test"
import fs from "node:fs"
import { createResizePointerHandler } from "./resize-pointer.mjs"
import { patchResizeHandle, patchResizeCss } from "./resize-patch.mjs"

function fixture(props = {}, rtl = false) {
  const element = new EventTarget()
  const view = new EventTarget()
  view.getComputedStyle = () => ({ direction: rtl ? "rtl" : "ltr" })
  const style = { userSelect: "text", overflow: "clip" }
  element.ownerDocument = { body: { style }, defaultView: view }
  const captured = new Set()
  element.setPointerCapture = id => captured.add(id)
  element.hasPointerCapture = id => captured.has(id)
  element.releasePointerCapture = id => captured.delete(id)
  const sizes = []
  const collapsed = []
  let collapses = 0
  let cleanup
  const local = { direction: "horizontal", size: 300, min: 100, max: 500, onResize: size => sizes.push(size), onCollapse: () => collapses++, onCollapseChange: value => collapsed.push(value), ...props }
  element.addEventListener("pointerdown", createResizePointerHandler(local, callback => { cleanup = callback }))
  const send = (type, props = {}) => {
    const event = new Event(type, { cancelable: true })
    Object.assign(event, { pointerId: 1, isPrimary: true, pointerType: "touch", button: 0, detail: 0, clientX: 100, clientY: 100, ...props })
    element.dispatchEvent(event)
  }
  return { element, view, style, captured, sizes, collapsed, send, cleanup: () => cleanup(), collapses: () => collapses }
}

test("horizontal, RTL and vertical edges retain upstream direction and size limits", () => {
  for (const [direction, edge, rtl, expected] of [["horizontal", "end", false, 340], ["horizontal", "start", false, 260], ["horizontal", "end", true, 260], ["horizontal", "start", true, 340], ["vertical", "start", false, 260], ["vertical", "end", false, 340]]) {
    const f = fixture({ direction, edge }, rtl)
    f.send("pointerdown")
    f.send("pointermove", { clientX: 140, clientY: 140 })
    assert.equal(f.sizes.at(-1), expected)
    f.send("pointermove", { clientX: 10000, clientY: 10000 })
    assert.ok([100, 500].includes(f.sizes.at(-1)))
    f.send("pointerup")
  }
})

test("secondary pointers cannot replace, move or release the active drag", () => {
  const f = fixture()
  f.send("pointerdown")
  f.send("pointerdown", { pointerId: 2, isPrimary: false, clientX: 400 })
  f.send("pointermove", { pointerId: 2, isPrimary: false, clientX: 450 })
  f.send("pointerup", { pointerId: 2, isPrimary: false })
  assert.deepEqual(f.sizes, [])
  assert.deepEqual([...f.captured], [1])
  f.send("pointermove", { clientX: 150 })
  assert.deepEqual(f.sizes, [350])
  f.send("pointerup")
  assert.deepEqual(f.style, { userSelect: "text", overflow: "clip" })
  assert.equal(f.captured.size, 0)
})

for (const ending of ["pointercancel", "lostpointercapture", "blur", "unmount"]) test(ending + " cleans up without committing a pending collapse", () => {
  const f = fixture({ direction: "vertical", collapseThreshold: 50 })
  f.send("pointerdown")
  f.send("pointermove", { clientY: 400 })
  assert.deepEqual(f.collapsed, [true])
  if (ending === "blur") f.view.dispatchEvent(new Event("blur"))
  else if (ending === "unmount") f.cleanup()
  else f.send(ending)
  assert.equal(f.collapses(), 0)
  assert.deepEqual(f.collapsed, [true, false])
  assert.deepEqual(f.style, { userSelect: "text", overflow: "clip" })
  assert.equal(f.captured.size, 0)
  f.send("pointermove", { clientY: 300 })
  f.send("pointerup")
  assert.deepEqual(f.sizes, [100])
})

test("pointerup commits collapse once; right buttons and repeated mouse clicks do not resize", () => {
  const f = fixture({ direction: "vertical", collapseThreshold: 50 })
  f.send("pointerdown", { pointerType: "mouse", button: 2 })
  f.send("pointerdown", { pointerType: "mouse", detail: 2 })
  assert.equal(f.captured.size, 0)
  f.send("pointerdown")
  f.send("pointermove", { clientY: 400 })
  f.send("pointerup")
  f.send("lostpointercapture")
  assert.equal(f.collapses(), 1)
})

test("generated resize assets are idempotent; incomplete and changed upstream anchors fail", () => {
  const source = fs.readFileSync(new URL("../../webapp/src/main/assets/www/ui/oa/index-D2r3WEwY.js", import.meta.url), "utf8")
  const css = fs.readFileSync(new URL("../../webapp/src/main/assets/www/ui/oa/index-nVyEgSVI.css", import.meta.url), "utf8")
  assert.equal(patchResizeHandle(source), source)
  assert.equal(patchResizeCss(css), css)
  assert.throws(() => patchResizeHandle(source.replace("r=ocResizePointerHandler(t,D)", "r=missingHandler(t)")), /Incomplete/)
  assert.throws(() => patchResizeHandle("function ak(e){changed()}var ok=0"), /upstream implementation changed/)
  assert.throws(() => patchResizeCss("changed"), /anchor changed/)
})
