# 真机复验报告 — emulator-5562（Tablet 平板宽窗）

- **设备**：`emulator-5562`，Android 15，`2560x1600` @ `320dpi` → **1280dp × 800dp**，全屏，**2.0 px/dp**
- **包名**：`com.xiufeigo.opencodeshell`（app-debug.apk，`install -r` 已在机；本轮未改代码 / 未跑 gradle / 未重装）
- **后端**：`GET http://127.0.0.1:4095/api/info`（Basic opencode:opencode）→ **HTTP 200**
- **adb reverse**：`tcp:4095`、`tcp:4096` 原有配置保持；本轮临时加的 `tcp:2222` 已 `--remove` 还原
- **纪律遵守**：全程未按 BACK（keyevent 4），收键盘只用 IME 收起；只操作 `emulator-5562`；证据文件全部 `E2ECHK5562` 前缀

## 结论总览

| # | 复验项 | 结论 |
|---|--------|------|
| 1 | F9 `SettingsModalOverlay` 键盘态 footer | **PASS** |
| 2 | F9 `SshAuthenticate` | **留白：环境限制** |
| 3 | F11 宽窗一行 Row 回归 | **PASS** |
| 4 | F10 SSH 条目持久化 + 删除清理 | **PASS** |

---

## 前置：默认 IME 不可用（环境事实，已修正）

本机默认 IME 是 **TTS 语音输入法**（`com.google.android.tts/...VoiceInputMethodService`），它**没有可见键盘面**：
点输入框后 `mInputShown=true`，但 `InputMethod` 窗口是 `mDrawState=NO_SURFACE` / `mShownAlpha=0.0`，
`type=ime frame=[0,0][0,0] visible=false`，screencap 像素扫描确认键盘区域**逐 px 无变化**。
不修正则 F9 键盘态无法复验。

修正（仅设备设置，未动 app 代码）：

```
adb -s emulator-5562 shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

> 注意：本 build 上简写 `ime set com.google.android.inputmethod.latin/.LatinIME` 会报
> `Unknown input method ... cannot be selected for user #0`，**必须写全组件名**。

输入 host 前另将 Gboard 从「拼」切到 `English (US)`，保证 `127.0.0.1` 的点号不被换成「。」。

---

## 1. F9 `SettingsModalOverlay` 键盘态 footer — **PASS**（核心）

两个带输入框的弹层各测一遍，**都是键盘态实测 + 实际点击**。

### 1a. 设置 → 服务器 → 「添加 SSH 服务器」（SshDialog）

键盘态与收键盘态各留 screencap，像素扫描给出 y 差值：

| 量 | 收键盘 `E2ECHK5562_06_kbddown_ref` | 键盘弹起 `E2ECHK5562_04_sshdlg_kbdup` |
|---|---|---|
| IME 可见框 | `frame=[0,0][0,0] visible=false` | `frame=[0,1412][2560,1600] visible=true` |
| 弹层卡盒 | y = 888 … 1335（**447 px**） | y = 882 … 1329（**447 px**） |
| 「取消」按钮盒 | y = 1199 … 1262（63 px） | y = 1193 … 1256（63 px） |
| 设置对话框外框 | y = 200 … 1400 | 被收在键盘上沿内（底 1330） |

**footer 底 → 键盘上沿 y 差值 = `1412 − 1256` = 156 px**
（5560 兄弟机参考门槛 105 px → 达标；按键盘可见面像素 1424 起算则为 168 px）

**逐 px 不变**：卡盒高两态都是 447 px、按钮盒高两态都是 63 px，**只有整体上移 6 px，零缩放**。

**可点击实测**（`mInputShown=true` 前提下点 footer）：
- 点「取消」(1438, 1224) → 弹层关闭 → `E2ECHK5562_05_after_cancel.png`
- 点主按钮「添加服务器」(1600, 1225) → 生效并进入连接态 → `E2ECHK5562_10_added.png`
- 表单展开露出密码框 + 红字报错后，footer 仍在 y=1207…1244，**依旧在键盘上沿 1412 之上**（`E2ECHK5562_11_pw_filled.xml`）

### 1b. 真正的 `SettingsModalOverlay` — 「重命名凭据」

`SettingsModalOverlay` 的带输入框调用点是 `CredentialRenameDialog`（`SettingsPanels.kt:2784`）。
路径：设置 → 提供商 → 下滑 → 「重命名」。

| 量 | 收键盘 `E2ECHK5562_20_rename_kbddown` | 键盘弹起 `E2ECHK5562_21_rename_kbdup` |
|---|---|---|
| IME 可见框 | `visible=false` | `frame=[0,1412][2560,1600] visible=true` |
| 弹层卡盒 | y = 635 … 999（**364 px**） | y = 597 … 961（**364 px**） |
| 「取消」按钮盒 | y = 856 … 919（63 px） | y = 818 … 881（63 px） |

**footer 底 → 键盘上沿 y 差值 = `1412 − 881` = 531 px**

**逐 px 不变**：卡盒高两态都是 364 px；内部元素 `768→730`、`839→801`、`999→961` 全部**统一平移 38 px**，
是纯位移、零缩放 —— 正是「恒定高度、逐 px 一致」。

**可点击实测**：`mInputShown=true` 下点「取消」(924, 849) → 弹层关闭
（`E2ECHK5562_22_rename_after_cancel.xml` 中「重命名凭据」已消失）。

### 关于「480dp」的说明

`SettingsScreen.kt:921-922`：

```kotlin
val ime = imeBottomDp.coerceAtLeast(0f)
if (ime <= 0f) return 480f
return minOf(480f, (windowHeightDp - ime).coerceAtLeast(0f))
```

本机窗口高 800 dp，键盘 94 dp，`min(480, 800−94)=480` —— **480dp 上限在本平板上两态都不binding**，
所以实测卡盒是内容高（`SettingsModalOverlay` 364 px、添加 SSH 子弹层 447 px）而非 960 px。
F9 真正要守的不变量「键盘收/放时弹层高度恒定、逐 px 不变」已按像素逐条验证成立。

---

## 2. F9 `SshAuthenticate` — **留白：环境限制，代码 + V92 单测在案**

宿主**无 sshd**，按指示不卡、留白：

```
Test-NetConnection 127.0.0.1 -Port 22   → TcpTestSucceeded = False
Get-Service sshd                       → 不存在（仅 ssh-agent，Stopped）
Get-NetTCPConnection -LocalPort 22     → no listener on 22
```

仍按指示做了尝试并留证：
- `adb -s emulator-5562 reverse tcp:2222 tcp:22` 建立成功（`reverse --list` 可见）
- 点「重新连接」(1820, 752) → **未出现任何 password / passphrase / fingerprint 节点**
- SSH 行停在「已断开连接。远程服务器仍在运行。」→ `E2ECHK5562_15_sshauth_attempt.png` / `.xml`
- 事后 `reverse --remove tcp:2222` 还原

结论：TCP 层即断，握手根本走不到认证请求阶段，**该环境造不出 SSH 认证 prompt**。
记为**留白**，不计入 FAIL。

---

## 3. F11 宽窗一行 Row 回归 — **PASS**（核心）

阈值代码：`SshTunnelHost.kt:339` `internal val SshTunnelRowStackedWidth: Dp = 450.dp`
（<450dp 堆叠，≥450dp 一行 Row）

**实测行宽**：卡盒 x = 862 … 2179 → **1317 px = 658.5 dp**，远高于 450dp 阈值 → 应走一行形态。

**同行像素证据**（`E2ECHK5562_13_wide_row.png` / `.xml`）：

| 元素 | x 区间 | y 区间 |
|---|---|---|
| 行卡盒（同行带） | 862 … 2179 | **694 … 808** |
| 身份列 `127.0.0.1` | 902 … 1000 | 718 … 749 |
| 状态 `已断开连接。远程服务器仍在运行。` | 902 … 1254 | 753 … 785 |
| 按钮 `重新连接`（按钮盒） | 1740 … 1899 | 720 … 783 |
| 按钮 `断开连接` | 1916 … 2076 | 734 … 771 |
| `⋮` 菜单 | 2110 … 2114 | ~750 |

- **同一行**：身份列 y 带 718…749 与控件 y 带 720…783 **重叠 29 px**（720…749），且 x 区间完全分离（身份 ≤1000，控件 ≥1740）。
  若为堆叠形态则应是 x 相近、y 不相交 —— 实测相反，故**未堆叠**。
- **无裁切**：控件按钮盒 y=720…783 完整落在卡盒 694…808 内，上留 26 px、下留 25 px。
- **无裁切**（横向）：`重新连接` 按钮盒 1740…1899 完整可见，文字像素 1772…1871 未触边。

---

## 4. F10 SSH 条目持久化 + 删除清理 — **PASS**

### 表单必填口令（与兄弟机结论一致）

只填 host 点「添加服务器」会被红字拦下（`E2ECHK5562_10_added.png`）：

> 请输入此连接的密码。

注意字段标签虽写作「密码（可选）」，实际**强制必填** —— 与兄弟机发现一致。
补占位口令 `E2E5562pw` 后通过，SSH 隧道分组 `127.0.0.1` 出现在面板中。

### 冷启动持久化

`am force-stop com.xiufeigo.opencodeshell` → `am start` 冷启动 → 重进 设置 → 服务器，
条目仍在，且**各节点 bounds 与重启前逐 px 完全一致**：

| 节点 | 冷启动前 | 冷启动后 |
|---|---|---|
| `SSH 隧道` | [862,602]-[990,646] | **同** |
| `127.0.0.1` | [902,718]-[1000,749] | **同** |
| `已断开连接。远程服务器仍在运行。` | [902,753]-[1254,785] | **同** |
| `重新连接` | [1768,734]-[1872,771] | **同** |
| `断开连接` | [1944,734]-[2048,771] | **同** |

证据：`E2ECHK5562_14_after_coldstart.png` / `.xml`（对照 `E2ECHK5562_13_wide_row.xml`）

### 删除并确认保持已删

`⋮` → `删除` → 确认框「删除 SSH 服务器？/ 将把「127.0.0.1」从列表中移除，且无法撤销。」
→ `删除`：

- 删除后：`SSH 隧道` 分组 **不存在**，裸 `127.0.0.1` 行 **不存在**（`E2ECHK5562_25_deleted.xml`）
- **再次** `force-stop` + 冷启动 + 重进 设置 → 服务器：仍不存在
  （`E2ECHK5562_26_reentry_still_deleted.png` / `.xml`）
- 面板已还原为最初两条本地服务器（`127.0.0.1:4095` 默认 / `127.0.0.1:4097`），**无残留**

---

## 证据文件清单（`research/v93_verify/5562/`）

| 文件 | 内容 |
|---|---|
| `E2ECHK5562_00_launch.png` | 冷启动首页（平板双栏全屏） |
| `E2ECHK5562_01_settings.png` | 设置弹层 |
| `E2ECHK5562_02_servers.png` | 服务器面板（基线，仅两条本地服务器） |
| `E2ECHK5562_03_sshdlg_kbddown.png/.xml` | 添加 SSH 弹层（收键盘） |
| `E2ECHK5562_04_sshdlg_kbdup.png/.xml` | **F9 核心**：键盘弹起，footer 在键盘上沿 156px 之上 |
| `E2ECHK5562_05_after_cancel.png/.xml` | **F9**：键盘态点「取消」成功关闭（可点击实证） |
| `E2ECHK5562_06_kbddown_ref.png/.xml` | **F9**：收键盘基准（卡盒 447px / 按钮 63px） |
| `E2ECHK5562_07_gboard_cn.png` | Gboard 中文「拼」态 |
| `E2ECHK5562_08_gboard_en.png` | Gboard 切 English (US) |
| `E2ECHK5562_09_host_typed.png/.xml` | host `127.0.0.1` 已输入（点号正确） |
| `E2ECHK5562_10_added.png/.xml` | 键盘态点主按钮；红字「请输入此连接的密码。」 |
| `E2ECHK5562_11_pw_filled.png/.xml` | 口令已填，footer 仍在 y=1207…1244 |
| `E2ECHK5562_12_entry_added.png/.xml` | SSH 条目已建，连接失败（预期，宿主无 sshd） |
| `E2ECHK5562_13_wide_row.png/.xml` | **F11 核心**：宽窗一行 Row（658.5dp 行宽） |
| `E2ECHK5562_14_after_coldstart.png/.xml` | **F10**：force-stop 冷启动后仍在，bounds 逐 px 一致 |
| `E2ECHK5562_15_sshauth_attempt.png/.xml` | **留白**：reverse 2222 + 重新连接，未出认证 prompt |
| `E2ECHK5562_16_menu.xml` / `17_local_menu.xml` | ⋮ 菜单项（SSH 行仅「删除」） |
| `E2ECHK5562_18_providers.png/.xml` / `19_prov_scroll.xml` | 提供商面板 → 找到「重命名」 |
| `E2ECHK5562_20_rename_kbddown.png/.xml` | **F9**：`SettingsModalOverlay` 收键盘（卡盒 364px） |
| `E2ECHK5562_21_rename_kbdup.png/.xml` | **F9 核心**：键盘弹起，footer 距键盘 531px，卡盒仍 364px |
| `E2ECHK5562_22_rename_after_cancel.xml` | **F9**：键盘态点「取消」成功关闭（可点击实证） |
| `E2ECHK5562_23_before_delete.xml` / `23b_ssh_menu.xml` | 删除前状态与 SSH 行菜单 |
| `E2ECHK5562_24_delete_confirm.png/.xml` | 删除确认框 |
| `E2ECHK5562_25_deleted.png/.xml` | 删除后：SSH 隧道分组消失 |
| `E2ECHK5562_26_reentry_still_deleted.png/.xml` | **F10 收尾**：冷启动重进后仍为已删 |
| `E2ECHK5562_nav.ps1` / `E2ECHK5562_pix.ps1` | 探针脚本（自 5560 拷改 serial 与路径） |

## 备注

- 「与修复前逐 px 一致」只能在本机**跨键盘态 / 跨冷启动**做逐 px 对照（已做，全部一致）；
  跨设备与修复前 APK 比对不在本轮授权内（不装包），故以代码阈值 + 本机实测几何佐证。
- F11 未在本轮制造 <450dp 窄窗形态（平板全屏即宽窗主回归场；窄窗由 5560 覆盖）。
