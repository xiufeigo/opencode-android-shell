// Shared by the vendored ResizeHandle. The upstream component still owns size,
// limits, collapse callbacks and persistence; this owns only the drag lifetime.
export function createResizePointerHandler(local, onCleanup) {
  let finish
  onCleanup(() => finish?.(false))

  return event => {
    if (finish || !event.isPrimary || event.button !== 0 || (event.pointerType === "mouse" && event.detail > 1)) return
    const element = event.currentTarget
    const doc = element.ownerDocument
    const view = doc.defaultView
    const pointer = event.pointerId
    const horizontal = local.direction === "horizontal"
    const edge = local.edge ?? (horizontal ? "end" : "start")
    const rtl = horizontal && view.getComputedStyle(element).direction === "rtl"
    const start = horizontal ? event.clientX : event.clientY
    const startSize = local.size
    const min = local.min
    const max = local.max
    const threshold = local.collapseThreshold ?? 0
    const onResize = local.onResize
    const onCollapse = local.onCollapse
    const onCollapseChange = local.onCollapseChange
    const savedSelect = doc.body.style.userSelect
    const savedOverflow = doc.body.style.overflow
    let collapsed = false

    element.setPointerCapture(pointer)
    event.preventDefault()
    doc.body.style.userSelect = "none"
    doc.body.style.overflow = "hidden"

    const move = event => {
      if (event.pointerId !== pointer) return
      const position = horizontal ? event.clientX : event.clientY
      const reverse = horizontal ? (edge === "start") !== rtl : edge !== "end"
      const current = startSize + (reverse ? start - position : position - start)
      const nextCollapsed = threshold > 0 && current < threshold
      if (nextCollapsed !== collapsed) {
        collapsed = nextCollapsed
        onCollapseChange?.(collapsed)
      }
      onResize(Math.min(max, Math.max(min, current)))
    }
    const end = event => {
      if (event.pointerId === pointer) finish?.(event.type === "pointerup")
    }
    const cancel = () => finish?.(false)
    finish = commit => {
      finish = undefined
      element.removeEventListener("pointermove", move)
      element.removeEventListener("pointerup", end)
      element.removeEventListener("pointercancel", end)
      element.removeEventListener("lostpointercapture", end)
      view.removeEventListener("blur", cancel)
      doc.body.style.userSelect = savedSelect
      doc.body.style.overflow = savedOverflow
      if (element.hasPointerCapture(pointer)) element.releasePointerCapture(pointer)
      if (commit && collapsed) onCollapse?.()
      else onCollapseChange?.(false)
    }
    element.addEventListener("pointermove", move)
    element.addEventListener("pointerup", end)
    element.addEventListener("pointercancel", end)
    element.addEventListener("lostpointercapture", end)
    view.addEventListener("blur", cancel)
  }
}
