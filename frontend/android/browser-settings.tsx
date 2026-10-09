import { Show } from "solid-js"
import { Button } from "@opencode/ui/button"
import { SettingsRow } from "@/settings/row"
import { usePlatform } from "@/runtime/platform/platform"
import { useLanguage } from "@/runtime/i18n/language"

export function AndroidBrowserSettings() {
  const platform = usePlatform()
  const language = useLanguage()
  const bridge = platform.extensions
  const chinese = () => language.locale().startsWith("zh")
  return <Show when={bridge?.browserConnection}>
    <SettingsRow title={chinese() ? "内置浏览器" : "Built-in browser"}
      description={bridge?.browserConnection?.status()}>
      <Button data-action="settings-android-browser" onClick={() => void bridge?.browserConnection?.configure()}>
        {chinese() ? "连接浏览器服务" : "Connect browser service"}
      </Button>
    </SettingsRow>
  </Show>
}
