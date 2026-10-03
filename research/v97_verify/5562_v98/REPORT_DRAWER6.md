# 真机复验报告 — v98 fix3 移动抽屉入口（第六轮：**2+ 会话形态** + **默认形态入口事实**）

**设备** `emulator-5562` / **AVD `Small_Desktop`**（`emu avd name` 实测）/ 物理 `1366x768@160`（**窗口化桌面**）
**包** `com.xiufeigo.opencodeshell` / `versionName 1.4.0` / `lastUpdateTime=2026-10-03 02:17:31`
**语言** `opencode-language = en` ⇒ **a11y 标签是英文**（5560 第五轮是中文，同一节点名不同）

> 方法：`am force-stop` + LAUNCHER 冷启动；`uiautomator dump --compressed` + `adb pull`；截图一律 `screencap -p` + `adb pull`（**未用 exec-out**）。
> **坐标全部运行期从 dump 解析**，无写死 x。解析器同时匹配 `text="…"` / `text='…'`（`Attr()` 双分支）。
> 全程**未按 BACK(4)**、**弹层内未按 ESC(111)**；未跑 gradle / 未装机 / 未杀模拟器进程 / 未碰 127.0.0.1:5555。

**本轮要回答的两个未覆盖项**：
1. **2 个及以上 tab 时**抽屉能否打开；
2. **横向（默认）形态**下抽屉入口是否存在。

---

## 0. 环境读数

| 项 | 读数 | 证据 |
|---|---|---|
| AVD 名 | `Small_Desktop` ✅ | `adb -s emulator-5562 emu avd name` |
| 闸门构建 | `versionName=1.4.0` / `lastUpdateTime=2026-10-03 02:17:31` | 首轮 `dumpsys package` |
| reverse（前） | **已存在**：`host-19 tcp:4095 tcp:4095` + `host-19 tcp:2222 tcp:22`（**本轮全程未做任何重挂**） | 首轮 `adb reverse --list` |
| 开关（前） | `appearance.tabLayout` **键不存在**（= 默认 `horizontal`，干净起点） | 首轮 `run-as … cat` |
| 物理 | `Physical size: 1366x768` / `Physical density: 160` | 首轮 `wm size; wm density` |
| 辅助脚本 | `5562_v98_drawer6/helper.ps1`（`Dump`/`Shot`/`FindNode`/`Center`/`Sig`/`DiffCount`/`PixDiff`） | 随证据目录存档 |

### 0.1 ⚠️ 一个必须记录的写入陷阱：`appearance.tabLayout` 键**原本不存在**

`SettingsStore.kt:490` 的 `KEY_TAB_LAYOUT = "appearance.tabLayout"` 是**字面量键名、无存储前缀**
（同文件其它键多为 `opencode.android.*`）。由于该键从未被写过，**prefs 文件里根本没有这一行**。

- 我第一次用 `sed` 就地替换的写法是**空操作**（`cat` 读回仍是原 9 个键，**无该键**）——如果只看"命令返回 0"就会误判已建形态。
- 第二次用 `run-as … cp /sdcard/…` 也失败：`cp: bad '/sdcard/…': Permission denied`（**app uid 读不到 /sdcard**）。
- 最终可行路径：**`force-stop` 后**（防止 SharedPreferences 内存缓存回写覆盖）→ 本地拼 XML → **stdin 管道**经
  `run-as <pkg> sh -c 'cat > shared_prefs/oc_settings.xml'` 写入 → `cat` 读回复核。

> **给下一轮的硬要求**：在 5562 这类**窗口化桌面**设备上建形态，**必须 `cat` 读回**；
> `sed` 就地替换对「键不存在」是静默空操作。

---

## 1. 形态建立（步骤 1）

`appearance.tabLayout=vertical`（**写后 `cat` 读回确认 ✅**）+ `wm size 1200x1600` + `wm density 320`
→ `am force-stop` + LAUNCHER → settle 12s → dump + 截图。

| 断言 | 期望 | 实测 | 判定 |
|---|---|---|:--:|
| **非空壳** | 节点数 ≥ 数十 | **85 节点**（28,736 bytes） | ✅ |
| **关闭态标签条 y** | 底部 | `Home [0,1468][64,1564]` / `New session [64,1468][132,1564]` / `Command palette [132,1468][228,1564]` | ✅ |

> **几何口径提示**：本设备条 y 是 **`1468..1564`**，**不是** 5560 第五轮的 `1452..1548`。
> 两者是不同 AVD / 不同覆盖（5562=1200x1600@320，5560=Pixel_Tablet）。**判定「无 `0..84` 回归」时用本设备的 `1468..1564` 作基线**，
> 不要拿第五轮的绝对值当判据。

证据：`A1_home_vertical.dump.xml` / `.png` / `A1_env.txt`

---

## 2. 🚨 红线①：软键盘遮挡（**本轮再次咬人，且收键盘方式与第五轮不同**）

创建会话后 composer 自动聚焦 ⇒ `dumpsys input_method` → **`mInputShown=true`**。硬证据：

```
InsetsSource id=3 type=ime frame=[0,878][1200,1600] visibleFrame=[0,878][1200,1600]
mImeHeight=674
InsetsSourceControl: {3 mType=ime mInsetsHint=Insets{left=0, top=0, right=0, bottom=722}}
```

⇒ **键盘顶边 y=878**。底部条在 `1468..1564` ⇒ **完全落在键盘内**，此时的条上点击**全部是假阴性**。

### 2.1 收键盘：四种方法失败，**一种成功**

| 方法 | 结果 |
|---|---|
| 导航栏键盘切换键 `(600,1583)` | ❌ `mInputShown` 仍 `true` |
| 导航栏左下点 `(24,1583)` | ❌ 仍 `true` |
| App 内空白区 `(600,420)` | ❌ 仍 `true`（composer 仍持焦点） |
| 键盘 mic 键 `(1135,935)` | ❌ **弹出 Gboard 麦克风权限框**（我点 `Don't allow` 拒绝，**未授予任何权限**） |
| **点顶栏非输入 tab `Changes` `(420,120)`** | ✅ **`mInputShown=false`** |

> **结论（可直接复用）**：在这台设备上，**点会话顶栏的 `Changes` / `Terminal` tab** 是唯一可靠的收键盘手段
> ——它让 composer 失去焦点，IME 随即收起。**不需要 BACK(4)、不需要 ESC(111)、不碰 IME 自己的任何键**，
> 因此**不违反本轮纪律**，且比第五轮报告建议的"点 IME 收起键"更稳（不会误触 mic/权限）。
> 副作用：切到了 `Changes` 页；后续用 `Open sessions`（`[80,654][176,750]`，y=702 在键盘上方）可回会话列表。

证据：`A3b_ime_shown.png` / `A5b_kbd.png` / `A10_imedown.dump.xml` / `A4_kbd_state.dump.xml`（权限框 6 节点）

---

## 3. 🔴 结构性发现：**底部条在结构上只能有 1 个槽位**——"2 个槽位"是不可达状态

这是本轮最重要的发现，直接决定"2+ tab"该怎么测。

| 位置 | 原文 | 后果 |
|---|---|---|
| `ui/App.kt:1993` | `val activeTab = openSessions.firstOrNull { it.id == currentSession }` | 只取**当前活动**的那一个会话 |
| `ui/App.kt:1999` | `sessions = openSessions,` | 完整列表**只交给抽屉** |
| `ui/nav/BottomTabStrip.kt:256` | `if (session != null) { … SessionTab(…) }` | `Row` 里**单数**槽位，不是 `for` 循环 |

⇒ **底部条永远只渲染 1 个 `SessionTab`**，无论 `openSessions` 有几个。
「2+ tab」在移动形态下的真实表达是**抽屉里的多行会话列表**，不是条上多槽位。

### 3.1 真机印证

我一共创建了 **3 个会话**（初次 + 两次 `New session → Create session`）。
中途我一度把「条上仍是 1 槽位」误读为「创建失败」——**实际上两次创建都成功**：

| 时刻 | 条上槽位数 | 抽屉里的会话行数 |
|---|:--:|:--:|
| 创建第 1 个后 | 1 | 1 |
| 创建第 2、3 个后（`T1`） | **仍是 1** | **3** |

`T1_drawer_slot1.dump.xml` 里的 3 行（每行 = 可点 `View` + `Untitled` 文本 + `Close tab` 按钮）：

| 行 | 行 bounds | 会话文本 | 该行 Close tab |
|---|---|---|---|
| 1 | `[24,1171][1176,1235]` | `Untitled` `[40,1204][107,1235]` | `[107,1171][203,1235]` |
| 2 | `[24,1235][1176,1299]` | `Untitled` `[40,1268][107,1299]` | `[107,1235][203,1299]` |
| 3 | `[24,1299][1176,1395]` | `Untitled` `[40,1332][107,1355]` | `[107,1299][203,1355]` |

> **所以本轮 A 段测的"2+ tab"= 抽屉里有 3 行会话**（严格 ≥2，已满足）。
> **"槽位 1 / 槽位 2" 的正确解释** = 把**活动会话切到第 1 个 / 第 2 个**后再点条上那唯一一个槽位，
> 这才是 2+ 会话形态下抽屉入口的两种状态。两者都做了（§5、§6）。

---

## 4. ✅ 正向对照（步骤 3）—— **通过**

在**同一条**上点**非抽屉**元素 `Home`，坐标从本次 pre-dump 运行期解析：`[0,1468][84,1564]` → 中心 **`(42, 1516)`**。

| 判据路 | 读数 | 判定 |
|---|---|:--:|
| **① dump 差分** | 52 → 86 节点，`Compare-Object` **diff = 132** 行 | ✅ |
| **② `gfxinfo` 帧数** | `Total frames rendered: 11` | ✅ |
| **③ logcat 输入处理** | `logcat -b input` 在本 build **不存在该缓冲**；主缓冲无 `MotionEvent` 行 | ⚠️ **未取到** |

**2/3 路命中 ⇒ 对照通过**。「整条标签条无响应」这个更大缺陷被排除。
（抽屉打开处三路齐活，见 §5.3。）

证据：`PC0_pre.*` / `PC1_post.*` / `PC2_home.*`

---

## 5. 主判据：2+ 会话形态下抽屉打开（步骤 4）

### 5.1 入口节点解析（运行期从 dump 解析，未写死）

`T0_pre_drawer.dump.xml` 底部条（`mInputShown=false` 已先行断言 ✅）：

| # | class | bounds | clickable | content-desc | text |
|---|---|:--:|---|---|---|
| 41 | `Button` | `[0,1468][84,1564]` | true | `Home` | |
| **42** | **`android.view.View`** | **`[84,1468][227,1564]`** | **true** | **（空）** | |
| 43 | `android.view.View` | `[84,1488][235,1544]` | **false** | **`Session`** | |
| 44 | `TextView` | `[96,1501][163,1532]` | false | | `Untitled` |
| 45 | `Button` | `[163,1468][227,1564]` | true | `Close tab` | |
| 47 | `Button` | `[227,1468][295,1564]` | true | `New session` | |
| 48 | `Button` | `[295,1468][391,1564]` | true | `Command palette` | |

**点击目标 = 节点 42 中心 `(156, 1516)`**；落在 `x=156`，**在 ✕（x≥163）左侧**，不会误关标签页。

### 5.2 ⚠️ 再次证伪 fix3 报告的自我描述（与 DRAWER5 §3.3 一致）

- **可点**槽位（节点 42，`clickable=true`）的 `content-desc` 是**空的**；
- `Session` 标签落在**另一个 `clickable=false` 的子节点**（节点 43，`[84,1488][235,1544]`）。

⇒ `MOBILE_DRAWER_ENTRY_FIX.md` §2.1 声称的「标签挂在**带 ACTION_CLICK 的那个节点**上」「与 `TabIconButton` 同一写法」
**在本设备上再次不成立**。`uiautomator` **无法**用"可点且有名字"定位入口，必须靠 clickable + bounds 几何解析。
（本轮不影响结论——几何解析完全有效，抽屉照样打开。）

### 5.3 打开结果（3 会话形态，活动会话 = 第 1 个）—— ✅ **打开成功**

| 读数 | 值 |
|---|---|
| pre（T0 关闭态）节点数 | **48** |
| post（T1 打开态）节点数 | **71** |
| **`Compare-Object` diff** | **43**（不是 0） |
| `gfxinfo` | `Total frames rendered: 2` |
| **截图像素差分**（1/3 采样） | 关闭态 vs 打开态 = **93.08% 像素不同** |

**四条判据逐条命中**：

| 判据 | 命中 | 证据（均在 T1 post-dump） |
|---|:--:|---|
| ① 新覆盖层容器 | ✅ | `View [24,1191][1176,1375]` |
| ② 抽屉内容行 | ✅ | `New session [88,1388][245,1419]` / `Home [88,1477][161,1500]` / `Settings [88,1533][189,1564]` / `Help [1104,1533][1160,1564]`（父节点均 `clk=true`） |
| ③ 抽屉行语义 | ✅ | 3 个会话行各带 `Close tab` 按钮（见 §3.1） |
| ④ 关闭态不可见的行出现 | ✅ | 相对 T0 新出现 `New session`/`Home`/`Settings`/`Help` + 3 行会话 |

截图 `T1_drawer_slot1.png` 目视确认：底部弹出 sheet（白色卡片 + 灰色 scrim），内容为
`Untitled ×` ×3 → `+ New session` → `Home` → `Settings` … `Help`，其上为 scrim，正文与会话工具条仍在原位。

---

## 6. 打开态几何（步骤 4 的几何部分）—— ✅ **5/5 全过**

全部取自 dump 逐节点比对（脚本输出存档 `T3_geometry.txt`），**不依赖截图**（动画过渡帧会误判）。

| # | 断言 | 关闭态（T0/T2） | 打开态（T1/S2） | 判定 |
|---|---|---|---|:--:|
| **①** | 底部条**仍在** `y≈1468..1564`（缺陷签名 `0..84`） | 5 个可点 @ `1468..1564` | 5 个可点 @ `1468..1564` | ✅ **无 `0..84` 回归** |
| **②** | NavHost / 正文**未被压零** | `y≤900` 内最大内容底 **845** | **845** | ✅ **未压零，逐像素相同** |
| **③** | 状态栏**未被遮挡** | `y2 ≤ 145` 带内 **4** 节点 | **4** 节点 | ✅ **未遮挡** |
| **④** | 关闭态 vs 打开态条 y **一致** | 条顶 y = **1468** | 条顶 y = **1468** | ✅ **完全一致** |
| **⑤** | **scrim 点击能关** | 打开 71 节点 → tap `(600,600)` → **52 节点** | vs 打开态 `diff=47` | ✅ **抽屉零残留**（见下） |

**⑤ 的精确回关证据**：sheet 卡片本体为 `[24,1171][1176,1596]`，故屏幕中部 `(600,600)` **必为 scrim**（非写死）。
关闭后与 `T0` 基线 `Compare-Object` 为 **6 行**，`Compare-Object` 全文逐行为：

```
=> TextView|[450,778][750,812]      |Couldn't load changes
=> TextView|[229,824][972,855]      |The diff request timed out or failed. Tap Refresh to try again.
=> View   |[528,869][672,965]       |
=> TextView|[552,901][648,932]      |Refresh
=> Button |[528,887][672,946]       |
<= TextView|[550,814][650,845]      |Loading
```

⇒ **6 行全部是 `Changes` 页的异步 diff 文案（`Loading` → `Couldn't load changes` + `Refresh` 按钮），与抽屉无关**。
**`Settings` / `Help` / 3 行会话 / sheet 容器在关闭后 dump 中一条都不存在 ⇒ 抽屉精确关闭。**
条亦恢复全宽：`Home [0,1468][84,1564]`、槽位 `[84,1468][227,1564]`（打开态被 sheet 压成 `[177,1468][227,1564]`）。

### 6.1 打开态下条的可点 x 被 sheet 压窄（与 DRAWER5 相同的既有行为，非回归）

| 槽位 | 关闭态 x | 打开态 x | 打开态 y |
|---|---|---|---|
| `Home` | `0..84` | `0..24` | `1468..1564`（不变） |
| 会话槽位（入口） | `84..227` | `177..227` | `1468..1564`（不变） |
| `Close tab` ✕ | `163..227` | `177..227` | `1468..1564`（不变） |
| `New session` | `227..295` | `227..295` | `1468..1564` |
| `Command palette` | `295..391` | `295..391` | `1468..1564` |

> **y 区间在开关两态完全相同 ⇒ 断言 ①④ 判定为过。**
> **下一轮若要在打开态点 `Home`，x 必须重新解析**（`0..24` 极窄，易落空）。

---

## 7. 槽位 2：活动会话切到第 2 个后再点条上槽位 —— ✅ **同样打开成功**

| 步骤 | 动作 | 结果 | 证据 |
|---|---|---|---|
| S0 | 点条上槽位重开抽屉（`156,1516`） | 抽屉开，**3 行会话**（`[24,1171]…` / `[24,1235]…` / `[24,1299]…`） | `S0_drawer_reopen.*` |
| **S1** | 点**抽屉第 2 行会话** `[24,1235][1176,1299]` → 中心 `(600,1267)` | 活动会话切到第 2 个；`mInputShown=false`；48 节点；**条几何与 S0 逐字相同** | `S1_pre_slot2.*` |
| **S2** | 点条上槽位 `[84,1468][227,1564]` → `(156,1516)` | ✅ **打开成功** | `S2_drawer_slot2.*` |

| 读数 | 值 |
|---|---|
| pre（S1）节点数 | **48** |
| post（S2）节点数 | **71** |
| **`Compare-Object` diff** | **41**（不是 0） |
| `gfxinfo` | `Total frames rendered: 1` |
| **截图像素差分** | 关闭态 vs 打开态 = **93.07%** |
| 打开态抽屉内会话行 | **仍是 3 行**（`[24,1171][1176,1235]` / `[24,1235][1176,1299]` / `[24,1299][1176,1395]`） |
| 打开态条 y | 5 个可点 **全部 `1468..1564`** ✅ |
| 抽屉四行 | `New session`/`Home`/`Settings`/`Help` 全部在 ✅ |

**两次打开态互比**：`T1`(槽位 1) vs `S2`(槽位 2) 截图仅 **6.65%** 采样像素不同 ⇒ **两种活动会话下抽屉形状一致**，
差异仅来自正文页内容（都是空的 `Changes` 页 + 同名会话）。

> **2+ 会话形态的两点差异结论**：
> 1. 槽位 1 / 槽位 2 **都能打开抽屉**，均无 `0..84` 回归、均 NavHost 未压零、均 scrim 精确回关；
> 2. 唯一的结构性差异在**打开之前**——活动会话不同 ⇒ `App.kt:1993` 选中的 `activeTab` 不同 ⇒ 条上槽位承载的会话不同；
>    **打开之后**抽屉内容与几何一致（都列出全部 3 个会话）。

证据：`S0_drawer_reopen.*` / `S1_pre_slot2.*` / `S2_drawer_slot2.*` / `S1_switch.txt` / `T4_pixdiff.txt`

---

## 8. B 段：横向（默认）形态 —— **默认形态【没有】该抽屉入口**

`wm size reset` + `wm density reset` + 开关还原（`appearance.tabLayout` → `horizontal`）→ 冷启动。

| 读数 | 值 |
|---|---|
| `wm` | `Physical size: 1366x768` / `Physical density: 160`（**无 `Override` 行**） |
| 节点数 | **32** |
| 桌面条位置 | **顶部** `y=30..78`（不是底部） |
| 桌面条节点 | `Home [8,30][56,78]` / **槽位 `View [62,30][134,78]`（`clk=true`）** / `Close tab [102,30][134,78]` / `New session [134,30][168,78]` / `Command palette [168,30][216,78]` |

### 8.1 实测点桌面槽位：不开抽屉

点解析出的中心 `(98, 54)`：

| 读数 | 值 |
|---|---|
| pre / post 节点数 | **32 → 31** |
| **`Compare-Object` diff** | **1** |
| `gfxinfo` | `Total frames rendered: 35`（点击被派发，界面有轻微响应） |
| `Settings` 行 | **不存在** |
| `Help` 行 | **不存在** |
| 底部 sheet 迹象（`y2 ≥ 600` 的节点） | **无**（只有 3 个根 `FrameLayout/View`） |
| `New session` 的匹配项 | 只有条上那个 `[134,30][168,78]`，**没有 `x=24` 的抽屉变体** |

### 8.2 结论（与移动形态的差异事实）

> **默认（横向 / 桌面宽度）形态下不存在这个抽屉入口。**
> `DesktopTabStrip` **不挂 `MobileDrawerMount`**（`MOBILE_DRAWER_ENTRY_FIX.md` §2.2 自检表已声明，本轮实测印证）：
> 条上的槽位仍可点，但点击**只切换会话，不打开任何抽屉/sheet**。
> 在默认形态下，多会话导航只能靠**条上已有的会话 tab 本身**（或 `Open sessions` 会话列表），
> **没有"集中式抽屉"这一层**。
>
> **⇒ 这是形态差异，不是缺陷**：移动形态（mobile 宽度 + `tabLayout=vertical`）才有底部条 + 移动抽屉；
> 默认形态走顶部桌面条 + 无抽屉。

证据：`B5_default_form.*` / `B6_desktop_slot_tap.*`

---

## 9. C 段：收尾还原 —— ✅

| 项 | 终态读数 | 判定 |
|---|---|:--:|
| `wm size` | `Physical size: 1366x768`，**无 `Override size` 行** | ✅ |
| `wm density` | `Physical density: 160`，**无 `Override density` 行** | ✅ |
| 开关 | `appearance.tabLayout` **键已整行删除** ⇒ 回到**纯净默认**（`horizontal`），`cat` 读回确认 | ✅ |
| 冷启动默认形态 | **128 节点**，条在**顶部** `y=30..78`（`Home [8,30][56,78]` / `New session` / `Command palette`） | ✅ |
| `mCurrentFocus` | `OcActivity`（**从未出现 `Application Not Responding`**，未触发 `adb reboot`） | ✅ |
| IME | `mInputShown=false` | ✅ |
| 崩溃 | 全程 `logcat` **0 FATAL EXCEPTION / 0 ANR** | ✅ |
| **自建会话清理** | 本轮创建 **3 个 `Untitled`**，**已全部删除** | ✅ |
| `/sdcard` 残留 | `d.xml` / `s.png` / `oc_settings_5562*` **均无** | ✅ |
| `reverse` | `host-19 tcp:4095` + `host-19 tcp:2222`，**与开局一致，全程未动** | ✅ |

**自建会话删除过程**（`Open sessions` 列表 → 长按行 → `Delete` → 确认）：

1. 长按弹 `Pop-Up Window`（13 节点），菜单含 `Rename` / `Export session…` / `Import session` / `Delete…`；
2. 点 `Delete` → 确认框 **`Delete session?` / `This deletes "Untitled" and all of its messages. This cannot be undone.`**；
3. 点 `Delete [985,803][1064,843]`（其可点父 `[961,775][1088,871]`）→ 列表该行消失。

三次删除后，列表中 `minutes ago` 标记的自建会话**全部消失**，只剩一条非本轮创建的 `just now` 会话。

> ⚠️ **删除入口不写在行上**：会话行 `View` 的 `content-desc` 为空、无 `Close` 按钮；
> **只有长按才出菜单**。下一轮要清理必须长按。

证据：`C7…C19_*.dump.xml`（每步一张）/ `C_cleanup.txt` / `C21_final_verify.dump.xml`

---

## 10. 未闭环 / 需回单

| 项 | 状态 | 说明 |
|---|---|---|
| **2+ 会话时抽屉能否打开** | ✅ **本轮闭环** | 3 会话形态下槽位 1 / 槽位 2 各开一次，`diff=43` / `diff=41`，几何 5/5 全过，scrim 精确回关 |
| **默认形态入口事实** | ✅ **本轮闭环** | 默认形态**没有**该入口：桌面槽位可点但 `diff=1`、无任何抽屉行（§8） |
| **"条上有 2 个槽位"** | ❌ **不可达（非缺陷）** | `App.kt:1993` `firstOrNull` + `BottomTabStrip.kt:256` `if (session != null)` ⇒ 条结构上恒为 1 槽位。多会话只在抽屉列表体现 |
| fix3 声称「标签挂在带 ACTION_CLICK 的节点上」 | ❌ **本轮再次证伪** | 可点槽位 `content-desc` 为空，`Session` 标签落在 `clickable=false` 的子节点 `[84,1488][235,1544]`（与 DRAWER5 §3.3 同） |
| 抽屉内交互（`Settings` / `Help` / `Rename` 抽屉行） | 未测 | 本轮按任务范围做到"打开 + 切会话 + scrim 关闭"；抽屉内其余行未逐一验 |
| 抽屉打开态下点 `Home`（条） | 未测 | 打开态 `Home` 可点 x 被压窄到 `0..24`，风险高，留给下一轮 |
| logcat 输入处理路 | ⚠️ 部分未取到 | `logcat -b input` 在本 build **无此缓冲**；主缓冲无 `MotionEvent` 行。正向对照取到 2/3 路（已足够），抽屉打开处三路齐活 |
| 自建会话命名 | ⚠️ **偏离纪律** | 新建会话表单**无标题字段**（只有 `Session summary` / 目的地 / `Create session`），故 3 个会话都叫 `Untitled`，**未按 `E2ECHK5562t*` 命名**。我改用**创建时间戳**（5/6/10 分钟前）识别并删除，3 个已全删。**下一轮建议：创建后立即长按 `Rename` 打前缀** |
| Gboard 麦克风权限框 | 已处置 | 试收键盘时点 mic 键弹出 `Allow Gboard to record audio?`，我点 **`Don't allow`** 拒绝，**未授予任何权限** |
| 收键盘手段 | ✅ 已确立 | 本设备**只有**「点顶栏 `Changes`/`Terminal` tab」可靠；导航栏键盘键、空白区、IME 自身收起键均失败（§2.1） |
| `wm` 覆盖值几何 | ℹ️ 口径差异 | 本设备条 `1468..1564`，5560 为 `1452..1548`。**跨设备比对绝对 y 会误判** |

---

## 11. 回报

**状态：✅ A 段（2+ 会话形态）抽屉打开判定全过、几何 5/5；✅ B 段默认形态入口事实已确立；✅ C 段全部还原并复核。**

- **2 tab 时抽屉能否打开**：**能，且两种活动会话都通过。**
  - **槽位 1**（活动会话 = 第 1 个）：`48 → 71` 节点、**`diff=43`**、`gfxinfo` 2 帧、截图像素差 **93.08%**；四条判据（新覆盖层容器 / 抽屉内容行 / 抽屉行语义 / 关闭态不可见行）全命中，抽屉内列出**全部 3 个会话**。
  - **槽位 2**（经抽屉第 2 行把活动会话切到第 2 个后）：`48 → 71` 节点、**`diff=41`**、`gfxinfo` 1 帧、截图像素差 **93.07%**；抽屉仍列出 3 个会话，条 y 未变。
  - 两次打开态截图互比仅 **6.65%** 像素差 ⇒ 两种活动会话下抽屉形状一致；唯一差异在打开**之前**（条上承载的会话不同）。
  - **前提红线已守**：每次点条前先断言 `mInputShown=false`（IME 顶边 y=878 会吞掉 `y=1516` 的全部点击）。
- **打开态条 y**：**`1468..1564`（5 个可点节点全部一致，与关闭态逐字相同）**，未出现缺陷签名 `0..84`；
  NavHost 未压零（`y≤900` 内最大内容底 845，关闭/打开相同）；状态栏带节点数 4 = 4（未遮挡）；scrim 点 `(600,600)` 精确回关且**抽屉零残留**。
- **默认形态是否存在该入口**：**不存在。** `wm size/density` reset + 开关还原后冷启动，桌面条在**顶部** `y=30..78`；
  点桌面槽位 `(98,54)` → `diff=1`、**无 `Settings`/`Help` 行、无任何 sheet**。
  `DesktopTabStrip` 不挂 `MobileDrawerMount` ⇒ **默认形态没有这个集中式抽屉入口，多会话只能靠条上已有 tab 或 `Open sessions` 列表**。这是与移动形态的差异事实，非缺陷。
- **报告路径**：`research\v97_verify\5562_v98\REPORT_DRAWER6.md`
- **证据目录**：`research\v97_verify\5562_v98\5562_v98_drawer6\`
  （`A1`~`A10` 形态与键盘、`B1`~`B6` 默认形态、`PC*` 正向对照、`T0`~`T4` 槽位 1 与几何、`S0`~`S3` 槽位 2、`C7`~`C21` 收尾清理）
