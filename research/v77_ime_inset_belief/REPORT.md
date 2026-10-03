# v77 P0 — `wm size`/`wm density` 覆盖后 reset：会话页丢工具栏/tab 条、composer 悬浮

复现入口：`research/v73_verify/5554/60_reset.png`（reset 后一切正常）→
`61_session_tab.png`（同一显示尺寸、键盘起来后布局崩）→ `64_open.png` / `71_open2.png`
（force-stop 重启 + 重开会话，仍崩）。四台模拟器本轮都被 v76 复验占用，**未上机**；
下列结论全部由这些截图逐像素量出（`1344x2992 @420dpi` = `512x1140dp`，2.625 px/dp）。

## 现象与量到的数字

`61_session_tab.png`：

| 量 | 值 |
|---|---|
| 窗口 | 2992 px = 1140 dp |
| 平台上报 `ime.bottom` | 2195 px = **836 dp** |
| 屏幕上那块键盘 | **720x766 px 浮窗**，左上角，顶边 y≈797，**不贴窗口底边** |
| 会话 body | `窗口 − 顶条(101.7dp) − ime` = **209~226 dp** |
| chrome 上限 | `chromeMaxHeightForComposer(body, 96dp)` ≈ **32~58 dp < 84 dp**（标题行 48 + 视图 tab 条 36） |
| composer | pane < 224 dp ⇒ compact(96dp)，锚在 body 底边 = 上报的键盘顶 |

于是：**标题行被裁成一条、视图 tab 条整条不在带内**（「丢工具栏/tab 条」）；
composer 缩成 96 dp 并停在屏幕 1/4 处（「composer 悬浮」）；键盘本身是 720 宽的浮窗
（「IME 渲染成小浮框」）；窗口下方 73% 是「为键盘预留」的空白带。

链条上没有一处是 app 自己缓存的旧值：`ime` inset 是平台每次交付的读数，`maxHeight`
来自当帧约束。崩的不是缓存，而是 **app 无条件相信平台给的键盘高度**。

## 根因

`wm size`/`wm density` 覆盖 → Gboard 按覆盖后的窗口（720x1280@420）排版成 720 宽的
docked 键盘；`wm size reset` 后**显示长回 1344x2992 而 IME 窗口没有重新排版**（仍是
720x766 的浮窗，位置停在 y≈797）。平台对「未被 resize 的窗口」按
`inset = 窗口底 − IME 窗顶` 上报 IME inset ⇒ 2195 px 的读数，而真实键盘只占 766 px。
这个读数属于**平台/IME 的窗口状态**（不是 app 状态），所以：

- force-stop 重启后 app 重新拉一次同样的读数 ⇒ 同样的崩坏（「重启后仍崩坏」）；
- IME 自己重新排版时读数才恢复（「直到某个后来事件才恢复」）。

app 这一侧的缺陷：`SessionScreen.bodyContent` 把 `WindowInsets.ime` 原样吃进
`windowInsetsPadding`，于是 836 dp 的「键盘」把 1140 dp 的窗口压成 209 dp 的 body，
v51–v74 的预算规则（chrome 先让、composer 降级）就照章执行 -> 看上去像布局坏了。
同类判断本仓已有先例：`pickerCardMax` 的「an inset that leaves the picker less than a
usable viewport cannot be telling the truth about a keyboard」。会话面板此前没有这层。

## 修复

### 1. 键 claims 有界（`SessionPaneLayout.believedImeBottomPx`）

```
believed = ime                                        // 无键盘 / 盒子没有余量
         = min(ime, 盒子 − essential + floor)          // 有余量而 claims 让面板掉到 essential 形以下
```

- `essential` = `PANE_ESSENTIAL_HEIGHT` = 标题行 48 + 视图 tab 条 36 + 时间线地板 72 +
  composer 116 + composer region padding 12 = **284 dp**（在 284 dp 上
  `chromeMaxHeightForComposer` 给 104 dp ≥ 84 dp，两条 chrome 行都放得下）。
- 只在「盒子付得起 essential 的两倍」时才怀疑（`PANE_ESSENTIAL_SPARE_ROOM = 2`）：
  也就是键盘要在一个已经两倍宽裕的窗口里声称吃掉一半以上、还让面板掉到 essential 形以下。
  这条件排除了所有短窗与 v51–v74 量过的横屏/桌面盒子（308/487 dp 窗口、414 dp 横屏
  盒子、696 dp 桌面盒子），它们一律原样相信键盘（v57 那个 308 dp 窗口的键盘占 82%）。
- 夹取只**降**不升：结果 ≤ 上报值，永远不会把 composer 推到真实键盘下面更深处。

### 2. 面板的尺寸输入随 Configuration 走

- `SessionScreen` 的三处测量（composer / viewport / pane）改为**存 px**
  （`composerHeightPx` / `viewportHeightPx` / `paneHeightPx`），dp 在读处用当前
  `LocalDensity` 换算。原写法 `with(density) { it.height.toDp() }` 把「回调建立时的密度」
  写进状态，而回调只在**像素**尺寸变化时触发：密度变了而 px 不变（`wm density` 覆盖、
  显示尺寸设置）时，面板预算里留着上一套 Configuration 的 dp —— v76 的
  `SessionPaneArrangement` 输入（`viewportHeightPx`/`composerTopPx` 的 pane/composer +
  `composerSharesViewport`/`composerCeiling`）吃的就是这个值。现在它的每个输入都跟着配置走。
- `minimalComposer` 的种子 `remember(windowHeightDp)`：窗口高度变了就重新播种（同一次
  组合内仍由 body 立即精化，无闪烁帧）。
- 同款「一次算永久用」缓存一并扫掉：`MessageTimeline` 的 pinned 行高度、`CommandPalette`
  的行宽，都是同一形状，改为 px + 读处换算。

### 3. body 的 inset 结构

`BoxWithConstraints` 现在量的是**把键盘放回去**的面板盒子（只吃 top/horizontal inset），
IME 的那半变成内层 `padding(bottom = max(nav 地板, believed))`——所以面板先知道自己有
多少地，再判断键盘声称要多少。believed 等于原始 inset 时几何与改前逐像素一致。

## 测试

- 新增 `SessionPaneImeBeliefV77Test`（11 例）：纯函数的边界（无键盘、无余量、刚过阈值、
  只降不升、扫描夹取不变式）、61_session_tab 的实测数字（2195 px 的 claims 被夹到
  essential 形；原始 claims 下的 pane 付不起 chrome 带）、以及三个源文件的结构钉子
  （三处 px 测量 + 读处换算、`minimalComposer` 按窗口播种、body 走 `believedImeBottomPx`、
  `SessionPaneArrangement` 的输入是这些派生 dp、时间线/palette 同款缓存已清）。
- 过时钉子更新（保强度）：`SessionPaneArrangementV76Test`（viewport/pane 报告改 px）、
  `SessionPaneShortWindowV51Test`（chrome 上限读 `bodyHeightDp`）、`V54Test` / `V55Test`
  （viewport/pane 的测量形状 + IME 在两种移动形态都消费）、`ShortWindowListV52Test`、
  `ShortWindowTimelinePadV58Test`（pinned 行改 px）、`ComposerShortWindowV74Test`
  （形态输入是 `bodyHeightDp`，并新增「body = 盒子 − 键盘」的钉子）。
- 已过闸门：`compileDebugKotlin` 绿；上面 15 套 + 第二批 23 套精确 `--tests` 全绿
  （含新套件 11 例）。

## 残留（交给主 agent / 下轮真机）

1. **未上机**：本轮四台模拟器都在忙（v76 复验），禁 `assembleDebug`，所以修复只有 JVM
   证据。下轮复验步骤：`wm size 720x1280` + `wm density 420` → 开会话、起键盘 →
   `wm size reset`（density 保留覆盖）→ 起键盘 → 断言视图 tab 条在、composer 完整、
   消息区有地板；再 force-stop 重启复验同一断言。
2. **浮窗键盘本身不是 app 能修的**：IME 窗口的尺寸/位置属另一个进程。修复后 app 侧
   （工具栏/tab 条/composer/消息地板）不再崩，但 composer 会停在「上报的键盘顶」上方
   （本 case 约 66 dp 的可见间隙），浮窗键盘下方那片空白仍在——这是平台态，需要
   `wm size reset` 后主动重启 IME（或系统自己重排）才消。
3. `believedImeBottomPx` 是一条**启发式**：它只在「本就两倍宽裕的盒子」上生效。真被触发
   的场景是键盘在一个 ≥584 dp 高的盒子里声称 >50% 且让面板掉到 284 dp 以下——真机上
   等于「极高大键盘 + 中等高度窗口」，此窗口内不再区分「真键盘」与「僵死 inset」。若下轮
   真机发现合法键盘被误夹（composer 沉到键盘下），把 `PANE_ESSENTIAL_SPARE_ROOM` 提到 3
   或改用「inset / 窗口」比例阈值，判断依据就写在 `believedImeBottomPx` 的 KDoc 里。
4. 未动 `research/HANDOFF.md`（有意偏离表未碰）：本轮结论在本文件与主 agent 汇报里，
   避免与并行的 v76 轮写同一文件撞车。
