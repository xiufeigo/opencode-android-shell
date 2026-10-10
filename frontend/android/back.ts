import { useNavigate } from "@solidjs/router"
import { onCleanup } from "solid-js"
import { usePlatform } from "@/runtime/platform/platform"
import { useLayout } from "@/shell/state/layout"
import { tabHref, useTabs } from "@/shell/tabs/tabs"
import { useSettingsSurface } from "@/settings/surface"

declare global {
  interface Window {
    __ocAndroid?: { setBackHandler(handler: (() => boolean) | null): void }
  }
}

// System Back follows the current application surface. Browser history also
// contains closed tabs, so replaying it would recreate those tabs from the URL.
export function installAndroidBack() {
  if (usePlatform().platform !== "android") return
  const host = window.__ocAndroid
  if (!host) throw new Error("Android back channel is unavailable")
  const navigate = useNavigate()
  const layout = useLayout()
  const tabs = useTabs()
  const settings = useSettingsSurface()

  host.setBackHandler(() => {
    if (layout.mobileSidebar.opened()) {
      layout.mobileSidebar.hide()
      return true
    }
    if (settings.active()) {
      if (settings.search.back()) return true
      if (settings.view().subtab) settings.subtab(undefined)
      else if (settings.view().type !== "root") settings.back()
      else {
        const source = settings.route()
        const tab = tabs.store.find(tab => source.type === "draft"
          ? tab.type === "draft" && tab.draftID === source.draftID
          : source.type === "session" && tab.type === "session" && tab.server === source.server
            && (tab.sessionId === source.sessionId || tab.routeSessionId === source.sessionId))
        navigate(tab ? tabHref(tab) : "/", { replace: true })
      }
      return true
    }
    if (layout.route().type === "home") return false
    navigate("/", { replace: true })
    return true
  })
  onCleanup(() => host.setBackHandler(null))
}
