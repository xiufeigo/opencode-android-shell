import type { Platform } from "./platform"
import { createNativeRpc } from "./android-rpc.mjs"
import { openAttachmentPickerDialog } from "./android-files.mjs"
import { createAndroidMenu } from "./android-menu"
import { createSystemFonts } from "./android-system-fonts.mjs"

type NativeChannel = {
  postMessage(message: string): void
  onmessage: ((event: MessageEvent<string>) => void) | null
}

declare global {
  interface Window {
    __ocPlatform?: NativeChannel
  }
}

export function createAndroidPlatform(web: Extract<Platform, { platform: "web" }>): Platform {
  const native = window.__ocPlatform
  if (!native) throw new Error("Android platform message channel is unavailable")
  const events = new EventTarget()
  native.onmessage = event => events.dispatchEvent(new MessageEvent("message", { data: event.data }))
  const request = createNativeRpc(native, events)
  const fonts = createSystemFonts(() => request("listSystemFonts"), document.fonts, FontFace, indexedDB)
  return {
    ...web,
    platform: "android",
    ...createAndroidMenu(request),
    openAttachmentPickerDialog,
    systemFonts: fonts.list,
    loadSystemFont: fonts.load,
    importSystemFont: fonts.import,
    openExternal(url) {
      void request("openExternal", { url }).catch(error => console.error("openExternal", error))
    },
    async openBrowser(url) {
      return request<boolean>("openExternal", { url })
    },
    async saveFile(opts, content) {
      return request<boolean>("saveFile", { name: opts.defaultPath?.split(/[\\/]/).pop() ?? "export.md", content })
    },
    async writeClipboardText(text) {
      await request("writeClipboardText", { text })
    },
    async readClipboardImage() {
      const image = await request<{ base64: string; mime: string; name: string } | null>("readClipboardImage")
      if (!image) return null
      const bytes = Uint8Array.from(atob(image.base64), c => c.charCodeAt(0))
      return new File([bytes], image.name, { type: image.mime })
    },
  }
}
