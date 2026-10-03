# v73 P2 复现记录 — 终端输入路由 + `/btw` 桌面点 composer（5556）

跑在 emulator-5556（Small_Desktop，1366×768 / density 160），用 v73 那一版已装 APK
（`app/build/outputs/apk/debug/app-debug.apk`，2026-09-30 01:02）复现 `v73_verify/5556` 的两条 P2。
会话只用自己的 `E2ECHK_xiufeigo` 前缀；没有发过任何 prompt（`/btw` 的 `hi` 只停在卡片输入框里，
未提交；composer 草稿清空）。**5556 在 02:10 之后从 `adb devices` 消失（不是本轮的 kill），
所以「带问题的高卡」那一步没量到**，见下面的边界说明。

## 1. 终端输入被劫持到 composer（确认）

步骤：Home → 打开自己的会话 → 点会话头右缘的 `Open terminal`（uiautomator:
`[1320,192][1366,240]`，content-desc `Open terminal`）→ 终端面板出现（`Terminal 1`，
提示符 `[xiufeigo@HomeMINISFORUM ~]$` + 块光标）。

- `03_term.png`：面板正常，**但 composer 里也有光标**（`|Ask anything…`）——焦点还在 composer。
- `dumpsys input_method`（面板打开、未点终端）：`mServedView=androidx.compose.ui.platform.AndroidComposeView`
  ——键盘连接属 composer，终端没有任何输入目标。
- 点终端面板表面（400,550）后：`mServedView=com.termux.view.TerminalView{… 0,0-1366,220}`，
  `adb shell input text "echo OK"` → 终端回显 `echo`（`04_typed_after_tap.png`）。
  **终端这条输入通道本身是好的**，缺的是「开面板时没人向终端要素键盘」。
- 反方向（`06_reverse.png` / `09_dock_closed.png`）：终端拿过键盘后，点 composer 的文本行
  （400,353，别点 y=390 那条空带，那里不是输入框命中区）→ `mServedView` 回到
  `AndroidComposeView`，随后 `input text "PQ"` 落在 composer 草稿里（关掉 dock 后可见 `PQ`）。
  所以这次修只需要**把键盘交给终端**，不需要动「交还」那一半。

上游依据（`_upstream/`）：`session/commands/use-session-commands.tsx:198,366`（面板打开即
`requestFocus`）、`session/terminal/tab.tsx:58`（点 chip 即 requestFocus）、
`session/terminal/context.tsx:110-124,249`（`requestFocus`/`focusRequested`/`consumeFocus`，
`terminal.new()` 先挂 pending 请求）、`session/terminal/panel.tsx:356-361`（`autoFocus` →
`onAutoFocus` → `consumeFocus`）、`session/terminal/terminal.tsx:466-474`（没请求时
`restoreFocus`：挂载不抢焦点）。本次改动就是这套语义的 Android 端口。

## 2. `/btw` 卡片桌面形态点 composer 不关（几何确认）

步骤：跟着 1 的会话，开键盘（`settings put secure show_ime_with_hard_keyboard 1`，
**这是我改的 5556 设置，原本是 null**）→ 点会话头 `Ask a side question`（1178,134）→
卡片出现（`11_btw_card_up.png`）。

PIL 逐像素量的（原图坐标，`10_cleared.png` 无卡 / `11_btw_card_up.png` 有卡）：

| 盒子 | x | y |
|---|---|---|
| `/btw` 卡片自身（`dialogCardBounds` 上报的就是它） | 443…922 | 124…259 |
| 会话 composer 条 | 12…1353 | 230…334 |

两者**重叠 y 230…259 × x 443…922**（卡片下沿就在 composer 文本行 253…258 上方 2 像素；
卡片带问题时下沿继续往下长，重叠更大）。scrim 的判定是纯几何（`scrimTapDismisses`），
所以落在这一带、又没被卡片自己控件吃掉的那一击被判「在卡内」→ 不关（`v73` 的
「获焦但卡不关」）。同一次复现里点 composer 左侧（200,258，卡片 x 范围之外）是
**一击关卡 + composer 拿到光标**（`12_tap_composer_left_closes.png`），这就是 v73 手机
（5558，卡在 y 624…1059、composer 在 y 1100+，两块不重叠）能 PASS 而桌面 FAIL 的差别。

本次修法：composer 的盒子（composer 自己上报，卸载时清空）作为 scrim 的
**backdrop override** —— 落在 composer 盒内的一击就算在卡片自己盒内也判为 backdrop。
卡片自己的控件（输入框、发送键）在手势竞争里先消费，所以短窗上卡片输入框照样能用。

### 边界（没量到的部分）

- 5556 在测「带问题的高卡」之前掉线，所以卡片的**位置只有空态实测**（124…259）。
  高卡的重叠只会更大，override 判据用的是「composer 盒 vs 卡片盒」，与卡片高度无关。
- 点击落点若正好压在卡片自己的输入框文本行上，那一击由卡片输入框消费（卡片获焦），
  这是卡片自己的控件，不是 composer 的一击——按上游「点卡外=只关卡」语义保留。
- 5556 的 `show_ime_with_hard_keyboard` 现在被置为 1（v73 那一轮测试员也是开着键盘测的；
  HANDOFF 里 5554 的键盘同样是常开）。若要回到原样：`settings delete secure show_ime_with_hard_keyboard`。
