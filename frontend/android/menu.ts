import { createSignal } from "solid-js"
import type { DesktopMenuAction } from "@/shell/commands/desktop-menu"

type NativeRequest = <T = unknown>(method: string, payload?: Record<string, unknown>) => Promise<T>
const ZOOM_STEP = 0.1
const ZOOM_MIN = 0.5
const ZOOM_MAX = 2
const actions = new Set<DesktopMenuAction>([
  "edit.undo", "edit.redo", "edit.cut", "edit.copy", "edit.paste", "edit.delete", "edit.selectAll",
  "view.reload", "view.resetZoom", "view.zoomIn", "view.zoomOut", "view.toggleFullscreen",
  "window.close", "window.minimize",
])

/** Host actions used by the official application menu; unsupported desktop actions stay disabled. */
export function createAndroidMenu(request: NativeRequest) {
  const [zoom, setZoom] = createSignal(1)
  const edit = (command: string, text?: string) => {
    if (!document.execCommand(command, false, text)) throw new Error(`Cannot execute editing command: ${command}`)
  }
  const captureEditor = () => {
    const field = document.activeElement
    if (!(field instanceof HTMLElement)) throw new Error("No focused editor")
    const input = field instanceof HTMLInputElement || field instanceof HTMLTextAreaElement ? field : undefined
    const start = input?.selectionStart
    const end = input?.selectionEnd
    const content = input ? input.value : field.textContent
    const selection = window.getSelection()
    const range = selection?.rangeCount ? selection.getRangeAt(0).cloneRange() : undefined
    return () => {
      if (!field.isConnected || (input ? input.value : field.textContent) !== content)
        throw new Error("The editor changed while waiting for the clipboard")
      field.focus({ preventScroll: true })
      if (input && start !== null && start !== undefined && end !== null && end !== undefined)
        input.setSelectionRange(start, end)
      if (!input && range?.commonAncestorContainer.isConnected) {
        const current = window.getSelection()
        current?.removeAllRanges()
        current?.addRange(range)
      }
    }
  }
  return {
    androidMenuActionEnabled: (action: DesktopMenuAction) => actions.has(action),
    webviewZoom: zoom,
    async runDesktopMenuAction(action: DesktopMenuAction) {
      if (!actions.has(action)) throw new Error(`Unsupported Android menu action: ${action}`)
      if (action === "view.reload") { location.reload(); return }
      if (action === "view.resetZoom" || action === "view.zoomIn" || action === "view.zoomOut") {
        const next = action === "view.resetZoom" ? 1 : Math.round((zoom() + (action === "view.zoomIn" ? ZOOM_STEP : -ZOOM_STEP)) * 10) / 10
        setZoom(Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, next)))
        document.documentElement.style.zoom = String(zoom())
        return
      }
      if (!action.startsWith("edit.")) { await request("menuAction", { action }); return }
      if (action === "edit.paste") {
        const restore = captureEditor()
        const text = await request<string>("readClipboardText")
        restore()
        edit("insertText", text)
        return
      }
      if (action === "edit.copy" || action === "edit.cut") {
        const restore = captureEditor()
        const focused = document.activeElement
        const text = focused instanceof HTMLInputElement || focused instanceof HTMLTextAreaElement
          ? focused.value.slice(focused.selectionStart ?? 0, focused.selectionEnd ?? 0)
          : window.getSelection()?.toString() ?? ""
        if (!text) throw new Error("Select text before copying or cutting")
        await request("writeClipboardText", { text })
        if (action === "edit.cut") {
          restore()
          edit("delete")
        }
        return
      }
      edit(action.slice("edit.".length))
    },
  }
}
