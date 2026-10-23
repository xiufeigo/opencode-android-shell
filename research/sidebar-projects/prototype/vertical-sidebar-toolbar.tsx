import { Icon } from "@opencode/ui/icon"
import { IconButton } from "@opencode/ui/icon-button"
import { Tooltip } from "@opencode/ui/tooltip"
import { useCommand } from "@/shell/commands/command"
import { useLanguage } from "@/runtime/i18n/language"
import { usePlatform } from "@/runtime/platform/platform"
import { useLayout } from "@/shell/state/layout"
import { WindowsAppMenu } from "./windows-menu"

/**
 * Small top-left control row for the vertical-tabs sidebar, matching the familiar
 * (menu / collapse sidebar / back / forward) cluster. The same component renders
 * over the main pane while the sidebar is collapsed, so those controls do not
 * disappear with it.
 */
export function VerticalSidebarToolbar(props: { class?: string }) {
  const command = useCommand()
  const platform = usePlatform()
  const language = useLanguage()
  const layout = useLayout()
  const collapsed = () => layout.verticalTabs.collapsed()
  const label = () => language.t(collapsed() ? "sidebar.expand" : "sidebar.collapse")
  const disabled = (id: string) => !!command.options.find((option) => option.id === id)?.disabled

  command.register("vertical-sidebar-toolbar", () => [
    {
      id: "sidebar.collapse",
      title: language.t(layout.verticalTabs.collapsed() ? "sidebar.expand" : "sidebar.collapse"),
      category: language.t("command.category.view"),
      onSelect: () => layout.verticalTabs.toggleCollapsed(),
    },
  ])

  return (
    <div
      data-slot="vertical-sidebar-toolbar"
      class={`flex h-7 shrink-0 items-center gap-0.5 ${props.class ?? ""}`}
    >
      <WindowsAppMenu
        command={command}
        platform={platform}
        menuPlatform={platform.platform === "android" ? "android" : "windows"}
        actionEnabled={platform.platform === "android" ? platform.androidMenuActionEnabled : undefined}
      />
      <Tooltip placement="bottom" value={label()}>
        <IconButton
          data-action="vertical-sidebar-collapse"
          variant="ghost-muted"
          size="large"
          icon={<Icon name="layout-left" />}
          aria-label={label()}
          aria-expanded={!collapsed()}
          onClick={() => layout.verticalTabs.toggleCollapsed()}
        />
      </Tooltip>
      <Tooltip placement="bottom" value={language.t("common.goBack")}>
        <IconButton
          data-action="vertical-sidebar-back"
          variant="ghost-muted"
          size="large"
          icon={<Icon name="arrow-left" />}
          aria-label={language.t("common.goBack")}
          disabled={disabled("common.goBack")}
          onClick={() => void command.trigger("common.goBack")}
        />
      </Tooltip>
      <Tooltip placement="bottom" value={language.t("common.goForward")}>
        <IconButton
          data-action="vertical-sidebar-forward"
          variant="ghost-muted"
          size="large"
          icon={<Icon name="arrow-right" />}
          aria-label={language.t("common.goForward")}
          disabled={disabled("common.goForward")}
          onClick={() => void command.trigger("common.goForward")}
        />
      </Tooltip>
    </div>
  )
}
