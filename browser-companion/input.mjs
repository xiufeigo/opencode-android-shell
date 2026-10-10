const MAX_TEXT = 1_048_576
const TOUCH_TYPES = { start: "touchStart", move: "touchMove", end: "touchEnd", cancel: "touchCancel" }
const MOUSE_TYPES = { mouseDown: "mousePressed", mouseUp: "mouseReleased", mouseMove: "mouseMoved", mouseWheel: "mouseWheel" }
const touchEnabled = new WeakSet()

/** Translate real touch streams, retaining Chromium's tap, scroll, drag and pinch semantics. */
export async function dispatchInput(contents, event, keyboard) {
  if (!event || typeof event !== "object") throw new Error("Invalid browser input")
  const send = (method, params) => contents.debugger.sendCommand(method, params)
  if (event.type === "text") {
    if (typeof event.text !== "string" || event.text.length > MAX_TEXT) throw new Error("Invalid browser text")
    await send("Input.insertText", { text: event.text })
    return
  }
  if (event.type === "keyDown" || event.type === "keyUp") {
    if (typeof event.key !== "string" || typeof event.code !== "string" || !Number.isInteger(event.keyCode) || !Number.isInteger(event.modifiers ?? 0)) throw new Error("Invalid browser key")
    await send("Input.dispatchKeyEvent", { type: event.type, key: event.key, code: event.code, windowsVirtualKeyCode: event.keyCode, modifiers: event.modifiers ?? 0 })
    return
  }
  if (event.type === "touch") {
    const type = TOUCH_TYPES[event.phase]
    if (!type || !Array.isArray(event.points) || event.points.length > 10) throw new Error("Invalid browser touch")
    const points = event.points.map(point => {
      if (!Number.isInteger(point.id) || ![point.x, point.y].every(Number.isFinite)) throw new Error("Invalid browser touch point")
      return { id: point.id, x: point.x, y: point.y, radiusX: 1, radiusY: 1, force: 1 }
    })
    if ((type === "touchEnd" || type === "touchCancel") !== (points.length === 0)) throw new Error("Invalid browser touch phase")
    if (type === "touchStart") {
      contents.focus()
      if (!touchEnabled.has(contents)) {
        await contents.setVisualZoomLevelLimits(1, 5)
        await send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 10 })
        touchEnabled.add(contents)
      }
    }
    await send("Input.dispatchTouchEvent", { type, touchPoints: points })
  } else {
    const type = MOUSE_TYPES[event.type]
    if (!type || ![event.x, event.y].every(Number.isFinite) || !["none", "left", "right", "middle"].includes(event.button ?? "none")) throw new Error("Invalid browser pointer")
    if (event.type === "mouseDown") contents.focus()
    const params = { type, x: event.x, y: event.y, button: event.button ?? "none", clickCount: event.clickCount ?? 0 }
    if (type === "mouseWheel") {
      if (![event.deltaX ?? 0, event.deltaY ?? 0].every(Number.isFinite)) throw new Error("Invalid browser scroll")
      Object.assign(params, { deltaX: -(event.deltaX ?? 0), deltaY: -(event.deltaY ?? 0) })
    }
    await send("Input.dispatchMouseEvent", params)
  }
  if (event.type === "mouseUp" || (event.type === "touch" && event.phase === "end")) {
    const result = await send("Runtime.evaluate", { expression: "!!(document.activeElement?.isContentEditable || /^(INPUT|TEXTAREA)$/.test(document.activeElement?.tagName ?? ''))", returnByValue: true })
    if (result.result.value) keyboard()
  }
}
