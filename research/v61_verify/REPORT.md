# v61 · 手机形态下 ESC 关不掉弹层（2026-09-29）

## 现象（v60 报）
- Home 长按**会话行**菜单：ESC 连按 3 次不关。
- Home **项目行**菜单：ESC 2 次不关。
- **命令面板**：ESC 不关（它没用我们的 `ocOverlayEscape`）。
- 对照：模型选择器弹层 ESC 一次即关；5556（Small_Desktop，freeform 窗口）全 PASS。

## 根因（真机 logcat 指认的事件路径）

### 一、行菜单：ESC 压根没到弹层，`ocOverlayEscape` 挂错了地方

诊断 build 在 `ocOverlayEscape` 挂载/收键处打 `OcEscapeDiag`，5558 上：

```
# 菜单打开着，input keyevent 111
OcEscapeDiag: dialogHost key=Escape type=KeyDown stack=0 ime=true
              view=AndroidComposeView winFocus=true
# 一条 keyListener / preview 日志都没有
```

**根因 1：`DropdownMenu` 的 `modifier` 不在 `Popup` 里。**
M3 把它挂在宿主树上一个零尺寸 `Box` 上（弹层内容是另一次组合），于是
`LocalView.current` 拿到的是**宿主的 `AndroidComposeView`**，不是 `PopupLayout`
——挂载日志里 `ocOverlayEscape … view=AndroidComposeView`（且打了 6 次，六个会话行各一次）。
弹层自己的 `Popup` 窗口从来没拿到焦点（`winFocus` 一直在宿主上），所以 `Popup`
内部既收不到 `OnKeyListener` 也走不到 `onPreviewKeyEvent`。

**根因 2：一个 View 只能挂一个 `OnKeyListener`。**
六个行菜单的 `DisposableEffect` 全往宿主 `AndroidComposeView` 的同一个槽位上装，
谁 `onDispose` 谁 `setOnKeyListener(null)`，活着的兄弟的监听被顺手摘掉 —— ESC 到达
时那个槽位是空的，于是既没人关菜单，事件又照常往下掉（`dialogHost` 那条 `onKeyEvent`
因为 `stack=0` 返回 false）。

> v59 那轮补的 `PopupProperties(focusable = true)` + `Modifier.ocOverlayEscape`
> 因此在手机上一颗没生效：它改的是弹层窗口的属性和宿主树上一个用不上的节点。
> 桌面（5556）能过是因为那边 popover 走的是另一条已经能接住 ESC 的通道（模型弹层
> 那种 modifier 真在 `Popup` 里的 `Popup`），行菜单在桌面上同样只能靠外点关。

### 二、命令面板：ESC 与软键盘的死循环

`DialogHost` 的 ESC 规则是「**IME 在 → 只收键盘**」（v50 的原意：别在用户正打字
时把对话框关掉）。手机上命令面板的搜索框 autofocus，软键盘随之而来；收掉之后下一个
`input keyevent 111` 打在仍有焦点的文本框上，Android 又把键盘拉回来 —— ESC 于是永远
在「收键盘 / 键盘又回来了」之间打转。真机连按 3 次才关（`v61_diag/5558/12_pal_esc1..3`
记录了改前那三次：第 1 次 collapse、第 2 次 insets 还没落定又 collapse、第 3 次才关）。
桌面上键盘从来没起来过，所以一次就关 —— 设备形态分叉的另一半。

## 修复

1. **`OcEscapeRouter`**（`ui/components/OcPopoverDismiss.kt`）：窗口级 ESC 路由。
   监听按**引用计数**在一个 View 上只装一个，各弹层只往路由里注册自己的关闭动作，
   ESC 来了交给**最后注册**的那一个（最上面那层）。`Modifier.ocOverlayEscape` 新增
   `open` 参数，只有弹层真的开着才注册（`DropdownMenu` 那个宿主 `Box` 在菜单关着时
   也在组合里，不门控还会把焦点从 composer 抢走）。
   接线：`HomeSessionsList` / `HomeProjectsList` / `NewSessionScreen` 的三个
   `DropdownMenu` 全部改成 `ocOverlayEscape(open = …)`。
2. **命令面板的收键盘记忆**（`ui/overlay/DialogLayerPolicy.kt` + `DialogHost.kt`）：
   `DialogEscape.escapeDismissesLayer(imeVisible, collapsedByEscape)` 多一个参数，
   这一层已经被 ESC 收过一次键盘之后，下一次 ESC 一律关闭。预览那一遍
   （`collapseImeOnEscape`）同步放行，否则事件到不了宿主那条关闭的 handler。
   桌面键盘从来没起来过 → 行为不变。

未动：152ms 退出穿透窗、外点记录逻辑、`ocPopoverDismiss` 的外点补发语义、
`ocModalEscape`（自绘 scrim 的确认框）、模型弹层的一次即关。

## 验证

真机 5558（Pixel_9a，手机全屏）：

| 用例 | 结果 | 截图 |
| --- | --- | --- |
| 命令面板 ESC | 第 1 次收键盘、第 **2 次关闭** | `5558/03_palette_open.png` → `04_palette_esc1.png` → `05_palette_esc2.png` |
| 会话行菜单 ESC | **1 次关闭** | `5558/07_rowmenu_open.png` → `08_rowmenu_esc1.png` |
| 项目行菜单 ESC | **1 次关闭** | `5558/09_projmenu_open.png` → `10_projmenu_esc1.png` |
| 模型选择器 ESC（红线） | **1 次关闭**，无回归 | `5558/14_modelpicker.png` → `15_modelpicker_esc1.png` |
| composer 上 ESC 收键盘 | 布局回到无键盘档，无锚点漂移 | `5558/12_session_nokb.png` |

真机 5556（Small_Desktop，freeform 窗口，无回归）：

| 用例 | 结果 | 截图 |
| --- | --- | --- |
| 项目行菜单 ESC | **1 次关闭** | `5556_projmenu_open.png` → `5556_projmenu_esc1.png` |
| 命令面板 ESC | 收键盘 + **第 2 次关闭**（这条窗口开了 Gboard，与手机同形） | `5556_palette_open.png` → `_esc1` → `_esc2` |

JVM：`EscapeOnPhoneV61Test`（新，7 例）+ `ThreeTailsV59Test` / `OcPopoverDismissTest`
断言更新到真实接法（`ocOverlayEscape(open = …)` / `OcEscapeRouter`）。
`DropdownMenuDarkSurfaceTest` 的 `menuCall` 改成按括号配平切参数表 —— 尾随 lambda
（`ocOverlayEscape(open = menuOpen) { … }`）在参数表里带了一个 `) {`，原来的
`substringBefore(") {")` 会把调用拦腰截断。

## 残留 / 备注

- `ocPopoverDismiss` 的四个调用点（ModelChip / AgentChip / Composer / SessionHeader ⋮）
  都是裸 `Popup { OcEnterOnMount(modifier = …) }`，modifier 确实在 `Popup` 里、只在
  打开时组合，所以 `open` 默认 `true` 对它们是对的；它们现在也一并挂上路由，多一条
  通道不改变既有行为。
- v60 报「palette Esc 不关」实测是**要按到第 3 次**；现在是 2 次。有 Gboard 的桌面窗口
  同形（第一次收键盘、第二次关闭），无 Gboard 时仍然一次。
