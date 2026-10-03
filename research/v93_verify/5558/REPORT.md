# V93 真机复验 — emulator-5558 (Pixel_9a)

- 设备：`emulator-5558`，`wm size` = 1080x2424，`wm density` = 420dpi → 竖屏窗口 **411dp** 宽，横屏 2424x1080 → **923dp** 宽。
- 包：`com.xiufeigo.opencodeshell`（`app-debug.apk` 已由上游 `install -r`，本轮未装机、未改代码、未跑 gradle）。
- `adb reverse`：`tcp:4095`、`tcp:4096` 均已就绪（复验前后各确认一次）。
- 后端探活：`GET http://127.0.0.1:4095/api/info`（Basic opencode:opencode）→ **200**，`{"version":"2.0.18","pid":56824,...}`。
- 证据目录：`research/v93_verify/5558/`，全部带 `E2ECHK5558_` 前缀。
- 旋转状态已复原：验完横屏执行 `settings put system user_rotation 0`，复验结束复查为 **0**。

---

## 结论汇总

| # | 复验项 | 结论 | 关键证据 |
|---|--------|------|----------|
| 1 | F11 竖屏堆叠形态 | **PASS** | `E2ECHK5558_s17_row_portrait.png` |
| 2 | F11 横屏宽形态 | **PASS** | `E2ECHK5558_s18_landscape.png` |
| 3 | 删除回归（持久删除） | **PASS** | `E2ECHK5558_s21_deleted.png` / `_s24_cold_servers.png` |
| 4 | WS 回归 | **PASS** | `E2ECHK5558_s25_home_cold.png` / `_s26_session.png` |

---

## 1. F11 竖屏堆叠形态 — PASS

**流程**：冷启 `OcActivity` → Settings →（横向滑动 tab 条 4 次）Servers → Add SSH server → Host `user@192.0.2.1` / Name `V93D` / Authentication=Password（占位口令）→ Add server。

条目添加后立即进入 `Connecting over SSH...`，`192.0.2.1` 不可达，约 20s 后落为
`Connection failed` → `Disconnected. The remote server is left running.`
（证据 `E2ECHK5558_s16_added_portrait.png`、`ui_v93d_row.xml`）。

竖屏实测几何（`uiautomator` bounds，单位 px，截图 `E2ECHK5558_s17_row_portrait.png`）：

| 元素 | bounds | 行 |
|------|--------|-----|
| 卡片 | x 148–929，底边 y=1507 | — |
| 身份行 1「V93D」 | `[201,1215]-[291,1256]` | 身份独占 |
| 身份行 2「Disconnected. The remote server is left running.」 | `[201,1261]-[843,1296]` | 身份独占 |
| 按钮「Reconnect」 | `[323,1339]-[494,1380]` | FlowRow 第 1 行 |
| 按钮「Disconnect」 | `[631,1339]-[811,1380]` | FlowRow 第 1 行 |
| 按钮「⋮」 | `[780,1375]-[906,1501]`，字形 y 1427–1449 | FlowRow 第 2 行 |

逐条对照验收点：

1. **身份列独占一行** ✅ 身份文本占满整行宽度，右侧不再有按钮与它同行；三个控件的 y 起点（1339 / 1375）全部
   低于身份行底（1296），不存在 `Row` 里 `weight(1f)` 被挤压到 0 宽的旧形态，也没有 "Disconnect" 逐字断行。
2. **按钮行换行** ✅ `FlowRow` 生效：`Reconnect + Disconnect` 同行，⋮ 溢出后流到下一行，右对齐
   （`Arrangement.spacedBy(8.dp, Alignment.End)`）。
3. **状态文案折行不溢出** ✅ 状态串在 `[201,1261]-[843,1296]` **单行**完整渲染，右端 843 距卡片右沿 929
   尚有 86px 余量，无折行、无省略号、无裁切。
4. **无按钮被裁切** ✅ `rowscan.ps1` 像素扫描（x 770–940, y 1330–1530, thresh 150）显示 ⋮ 为**三段完整暗带**
   1427–1431 / 1436–1440 / 1445–1449，中心 1438，与节点中心一致；阈值 251 扫描（x 170–930）定位卡片底边
   在 **y=1507**，字形底 1449 距底边 58px，⋮ 未被卡片裁掉。`Reconnect`/`Disconnect` 文本节点 bounds
   完整（41px 高）且可点。

**形态判定**：卡片宽 781px ≈ 297dp < `SshTunnelRowStackedWidth` = 450dp，走 `stacked` 分支，符合
`SshTunnelHost.kt:240-271` 的设计（`BoxWithConstraints` 测自身宽度，非窗口宽度）。

> 备注（设计预期，非缺陷）：⋮ 单独占 FlowRow 第二行是 `FlowRow` 的既定行为
> （`SshTunnelHost.kt:213-215` "flows an overflowing control to the next line instead of squeezing it"），
> 259–297dp 的手机行宽放不下 352dp 的控件定尺，故 ⋮ 必然换行。同理身份列只显示
> `sshName(name, target)` 的 **name**（`SshTunnelHost.kt:448`），`target` 仅在 name 为空时回落显示，
> 故 `user@192.0.2.1` 不出现在行内是预期，不是丢字。

---

## 2. F11 横屏宽形态 — PASS

**流程**：`settings put system accelerometer_rotation 0` + `settings put system user_rotation 1` → 横屏
（`dumpsys window displays`：`cur=2424x1080`，923dp）→ 同一 Servers 面板同一 V93D 条目。

实测几何（`ui_landscape.xml`）：

| 元素 | bounds | 中心 y |
|------|--------|--------|
| 「V93D」 | `[834,553]-[924,594]` | 574 |
| 状态串 | `[834,599]-[1476,634]` | 616 |
| 「Reconnect」 | `[1656,573]-[1827,614]` | 594 |
| 「Disconnect」 | `[1922,573]-[2102,614]` | 594 |
| 「⋮」 | `[2124,530]-[2250,656]` | 593 |

- 卡片 x 781–2268 = 1487px ≈ **566dp ≥ 450dp** → 走 `else` 分支的单行 `Row`。
- 三个控件中心 y = 594 / 594 / 593，与身份列（574 / 616）同处一行并垂直居中：**无堆叠、无换行**。
- 身份列右端 1476，控件左端 1656，中间 180px 间隙，无重叠、无挤压。
- 状态串单行完整（1476 < 卡片右沿 2268），与修复前的桌面一行形态一致。

复原：横屏验证后执行 `settings put system user_rotation 0`，复查 `settings get system user_rotation` = **0**。

---

## 3. 删除回归（持久删除）— PASS

| 步骤 | 观察 | 证据 |
|------|------|------|
| ⋮ → Delete | 弹出「Delete SSH server? / This removes "V93D" from the list. This cannot be undone.」 | `E2ECHK5558_s19_menu.png`、`_s20_confirm.png` |
| 确认 Delete | V93D 卡片与「SSH tunnels」小标题一并消失，仅剩 Default | `E2ECHK5558_s21_deleted.png` |
| 离开面板（About）→ 回 Servers | 仍只有 Default，无 V93D、无 SSH tunnels 小标题 | `_s22_reenter.png` / `ui_reenter.xml` |
| `am force-stop` → 冷启 → Settings → Servers | 仍只有 Default | `E2ECHK5558_s24_cold_servers.png` |

`ui_reenter.xml` 与冷启后的 dump 中均检索不到 `V93D` / `SSH tunnels` 节点，Default 行
（`10.0.2.2:4095` / `v2.0.18 • opencode` / 绿点健康）保持完好。F10 的 EncryptedSharedPreferences
持久化与持久删除在真机上成立。

---

## 4. WS 回归 — PASS

冷启（force-stop 之后）后：

- **Home 会话列表正常加载** ✅ 项目区 4 项（`nonexist_v84` / `nonexist_v82` / `x` /
  `opencode-android-shell`）+ 会话区 8 条（`E2ECHK5554-pane`、`Untitled`、`E2ECHK5558-draft`、
  `E2ECHK5558-ok`、`E2ECHK5558-probe1`、`E2ECHK5558-baddir…`、`E2E check respondi…` 等）全部渲染，
  无空列表、无 loading 卡死。证据 `E2ECHK5558_s25_home_cold.png`。
- **点开会话进入会话页** ✅ 点 `E2ECHK5558-ok` → 会话页加载完成：标题 `E2ECHK5558-ok`、tab 条
  `Session / Changes / Files / Terminal`、composer（`GPT-6.1 Sol`）就位。证据 `E2ECHK5558_s26_session.png`。
- Servers 面板侧同步佐证：Default 行 `v2.0.18 • opencode` + 绿点（`Server healthy` content-desc
  `[201,1749]-[222,1770]`），与 `/api/info` 的 2.0.18 一致。

---

## 复现步骤（关键路径）

```powershell
$adb = 'C:/Users/xiufe/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$adb -s emulator-5558 shell settings put system accelerometer_rotation 0
$adb -s emulator-5558 shell settings put system user_rotation 0
$adb -s emulator-5558 shell am force-stop com.xiufeigo.opencodeshell
$adb -s emulator-5558 shell am start -a android.intent.action.MAIN `
  -c android.intent.category.LAUNCHER -n com.xiufeigo.opencodeshell/.OcActivity
# Settings → 横向滑动 tab 条 4 次 → Servers → Add SSH server
# Host: user@192.0.2.1 / Name: V93D / Authentication: Password(占位) → Add server
# 竖屏取证后：settings put system user_rotation 1 取横屏图，再置回 0
```

探针脚本（拷自 `research/v91_verify/5558/`，serial 已是 `emulator-5558`，仅改输出目录与 dump 名）：
`nav.ps1`（按 label 定位/点击/分段 input text/截图）、`rowscan.ps1`（逐行亮像素扫描，取 ⋮ 字形与卡片底边）、
`textpix.ps1`。

---

## 过程中观察到的次要问题（非本轮 F9/F10/F11 复验项，未判定为 FAIL）

1. **Add SSH server 表单在竖屏下被对话框底边裁切**：`Server name (optional)` 输入框初始被裁到
   1–7px（uiautomator 报 `[106,1718]-[473,1725]`），需先收起 IME 再滑动对话框内容才能输入。
   证据 `E2ECHK5558_s10_scrolled.png`（clipped 1px）、`_s11_kbhidden.png`、`_s12_scrolled2.png`。
   属"新增表单在窄窗的可达性"，与 F11 的**行**布局无关。
2. **认证方式默认 Password 且必填**：空口令提交被 `Enter the password for this connection.` 拦下
   （`E2ECHK5558_s14_added_portrait.png`），本轮以占位口令绕过。行为是否合理需产品侧确认。
3. 复验中一次误 tap 落在项目行的 `Close`（`[922,280]-[1048,406]`）而非对话框 `Close`
   （`[878,468]-[969,509]`）；复查项目列表 4 项仍在（`E2ECHK5558_s25_home_cold.png`），无副作用。
