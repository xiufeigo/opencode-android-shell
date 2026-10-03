# v93 真机复验 — emulator-5560（Fold 折叠屏展开态）

- Serial: `emulator-5560`，`sdk_gphone16k_x86_64`，API 37
- 规格：`wm size` 物理 2076x2152，`wm density` 390 → **2.4375 px/dp**；`overrideConfig` **sw852dp / w852dp / h883dp**（展开大屏，宽窗）
- 包名：`com.xiufeigo.opencodeshell`（APK 已由主 agent 装好，本轮未装机、未跑 gradle、未改任何代码）
- 后端：`GET http://127.0.0.1:4095/api/info` → HTTP 200 `{"version":"2.0.18",...}`；`adb reverse tcp:4095/4096` 在位
- 证据目录：`research/v93_verify/5560/`（全部带 `E2ECHK5560` 前缀）
- 未按 BACK（keyevent 4）；收键盘一律 `keyevent 111`（ESC，沿用 v91 `mkentry.ps1` 口径）
- 未触碰 127.0.0.1:5555 及其他设备；本轮创建的 SSH 条目已在收尾删除，Servers 面板回到初始两条 WS 行

## 结论汇总

| 复验项 | 结论 | 主证据 |
|---|---|---|
| 1. F9 SettingsModalOverlay 键盘态 footer | **PASS** | `E2ECHK5560_04_sshdlg_kbdup.png` / `E2ECHK5560_10_kbddown_restored.png` / `E2ECHK5560_16_narrow_kbdup.png` |
| 2. F9 SshAuthenticate 键盘态 footer | **留白（环境限制）** | `E2ECHK5560_13_reconnect_t28.png` + 本文「环境事实」段 |
| 3. F11 宽窗（≥450dp）一行 Row 回归 | **PASS** | `E2ECHK5560_12_row_wide.xml` / `E2ECHK5560_11_wide_row.png` |
| 4. F11 窄窗（<450dp）堆叠形态 | **PASS** | `E2ECHK5560_14_narrow_410dp.png` / `E2ECHK5560_16_narrow.xml` |

---

## 项 1 — F9 SettingsModalOverlay 键盘态 footer：**PASS**

路径：启动 app → 设置 → 服务器 → 添加 SSH 服务器 → 点主机框起键盘。

### 1a. 宽窗（852dp）键盘弹起态

| 量 | 像素事实 |
|---|---|
| 键盘上沿 | **y=1352**（x=300 列扫描：y≤1349 为 `36,36,36` 应用底色，y=1352 起为 `238,237,246` Gboard 面板；与 `dumpsys window windows` 的 `touchable region=(0,1352,2076,2152)` 一致） |
| footer 取消 | `[1398,1202]-[1460,1247]` |
| footer 添加服务器 | `[1548,1202]-[1703,1247]` |
| footer 底距键盘上沿 | **1247 → 1352 = 105px（43.1dp）**，footer 完整落在可视带内 |
| footer 是否真绘制 | rect `[1380,1190]-[1720,1265]` 亮像素 **771**、maxLum 250（按钮面与标签都在，不是空节点） |

截图 `E2ECHK5560_04_sshdlg_kbdup.png`：卡片抬升，footer 两枚按钮悬在键盘上方可见。
截图 `E2ECHK5560_05b_state.png`：主机框有值时主按钮 **实心蓝底 enabled**，仍停在键盘上沿之上。
截图 `E2ECHK5560_08_pwfield.png`：焦点移到密码框（表单已滚），footer 随卡片抬到 `[1398,1119]-[1460,1164]` / `[1548,1119]-[1703,1164]`，底距键盘仍为 188px。

### 1b. footer 可点（键盘弹起态实测）

键盘**没有收起**的状态下点击 footer 主按钮 `(1626,1142)`：连接动作真实发生——
`E2ECHK5560_09_after_submit.png` 中行状态转为「连接失败 / 无法建立 SSH 连接」，且 SSH 弹层按钮文案由「添加服务器」变为「连接」，说明点击命中且提交链路执行。
（该次点击同时是项 2 的连接尝试，见下。）

### 1c. 键盘收起后形态恢复（逐 px）

以 `x=630` 列（卡内左侧）扫描卡盒边框：

| 状态 | 卡顶 | 卡底 | 卡高 |
|---|---|---|---|
| 首次打开·键盘落（`E2ECHK5560_03_sshdlg_kbddown.png`） | 1191（41,41,41）/ 1194（34,34,34） | 1719（34）/ 1720（46,46,46） | 529px = 217.0dp |
| 起键盘→`keyevent 111` 收起（`E2ECHK5560_10_kbddown_restored.png`） | 1191 / 1194 | 1719 / 1720 | 529px = 217.0dp |

两次 vscan 输出**逐行完全相同**；footer y 也回到 `1580..1625`（与首帧一致，x 位移仅因按钮文案由「添加服务器」变「连接」而右对齐重排）。
卡高 217dp 远在 480dp 上限内。

### 1d. 窄窗（410dp）同项复核

`wm size 1000x2152` → `overrideConfig w410dp h883dp`：

- 键盘落：主机框 `[128,1433]-[873,1550]`，footer `[563,1599]-[625,1644]` / `[713,1599]-[868,1644]`
- 键盘起：键盘上沿 **y=1333**（x=200 列：1331 仍 `65,65,65`，1333 起 `238,237,246`），footer `[563,1183]-[625,1228]` / `[713,1183]-[868,1228]` → 底距键盘 **105px**，同样钉住
- 证据：`E2ECHK5560_15_narrow_dlg_kbddown.png`、`E2ECHK5560_16_narrow_kbdup.png`

### 口径说明（记录，非缺陷）

本机 `settingsModalOverlayMaxDp = min(480, windowHeightDp − ime)`：窗口 883dp、键盘 328dp → `min(480, 555) = 480`，
**键盘起落两态都取 480**，即 480dp 封顶在本机恒不触发（符合「键盘收耗时恒 480dp 逐 px 不变」的约定）。
本机键盘态真正起作用的是卡片自身的 band/floor/lift（`serverDialogBandDp` / `serverDialogBandLiftDp` / `serverDialogFloorDp`），
上表 1a–1d 量到的 footer 钉位即该链路的实测结果。

---

## 项 2 — F9 SshAuthenticate：**留白（环境限制，代码 + V92 单测在案）**

`SshAuthenticate` 只在 `status.prompt != null` 时渲染（`ui/servers/SshDialog.kt:228-245`，prompt 分支早退），
即必须有一个**可达的 SSH 服务器**主动发出 trust / password / passphrase 提问。本环境造不出：

| 尝试 | 结果 |
|---|---|
| 宿主 sshd | `Get-Service sshd` 无此服务；`netstat` 无 `:22` 监听 → 无法按 `adb reverse tcp:2222 tcp:22` 接 |
| 宿主 Docker（拉 `ssh-test` 密码认证容器） | `docker version --format {{.Server.Version}}` 返回空 = 无 daemon |
| 宿主 python paramiko（起本地密码 sshd） | `import paramiko` → `ModuleNotFoundError` |
| 模拟器自带 sshd | `which sshd` / `/system/bin/ssh*` / `/vendor/bin/ssh*` 全空 |
| 公网 SSH：`ssh.github.com:443` | 模拟器侧 `toybox nc -w 5 ssh.github.com 443` → rc=0（**出网正常**），但 app 内 SSH 客户端 ~9s 后失败：`连接失败` + `无法建立 SSH 连接。请检查网络和 SSH 配置。`（`E2ECHK5560_09_after_submit.png`）；点「重新连接」再等 28s 同样失败（`E2ECHK5560_13_reconnect_t28.png`）→ 握手未成，自然没有 host-key trust 提问 |
| 公网 SSH 认证方法探测 | `git@gitea.com` → `Permission denied (publickey)`（仅公钥）；`git@gitlab.com:22` 在本网络无 banner 返回 |

**未出现 SshAuthenticate（含 trust prompt 分支）**，因此「点密码框起键盘 → footer 钉在可视带内」无法在本机实测。
在案代码依据：`app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshAuthenticate.kt`（band/floor/lift + 钉 footer，prompt 分支读同一套 insets）；
单测依据：`app/src/test/java/com/xiufeigo/opencodeshell/SettingsModalImeBandV92Test.kt`。
建议终审用独立 SSH 测试后端（密码认证）补验该项。

---

## 项 3 — F11 宽窗一行 Row 回归：**PASS**

窗口 852dp → 对话框 `min(852−32, 980) = 820dp`，本机 SSH 行实测 **x 626..1920 = 1294px = 531.0dp ≥ 450dp**（`SshTunnelRowStackedWidth`），
故 `sshTunnelRowStacked(maxWidth) = false`，走修复前后同一条 `Row` 分支。

弹层打开态（`E2ECHK5560_12_row_wide.xml`）：

| 元素 | bounds |
|---|---|
| 身份列 名称 `ssh.github.com:443` | `[773,976]-[1065,1015]` |
| 身份列 阶段 `连接失败` | `[773,1020]-[877,1057]` |
| 身份列 错误行 | `[773,1062]-[1323,1099]` |
| 重新连接 按钮盒 | `[1409,980]-[1601,1097]` |
| 断开连接 按钮盒 | `[1621,980]-[1799,1097]` |
| ⋮ 命中区 | `[1799,980]-[1916,1097]` |

→ 三个尾控件与身份列**同处 y 980..1097 同一水平带**，身份列实测宽 550px（773..1323），文本完整，无堆叠、无 0 宽挤压、无按钮标签断词。
干净态（`E2ECHK5560_14_wide_row.xml`，关闭弹层后）：名称 `[773,976]-[1065,1015]`、重新连接标签 `[1443,995]-[1567,1040]`、⋮ `[1799,959]-[1916,1076]`，同带。
截图：`E2ECHK5560_11_wide_row.png`、`E2ECHK5560_13_reconnect_t28.png`。

---

## 项 4 — F11 窄窗堆叠形态：**PASS**

`adb -s emulator-5560 shell wm size 1000x2152` → `overrideConfig` 读回 **sw410dp w410dp h883dp**。
行宽 `min(410−32, 980) − 120 = 258dp < 450dp` → 走 `FlowRow` 堆叠分支。

`E2ECHK5560_16_narrow.xml`：

| 元素 | bounds |
|---|---|
| 身份列 名称（独占第一行） | `[187,1204]-[479,1243]` |
| 身份列 阶段行 | 同行，随身份列 |
| 重新连接（第二行） | 标签 `[286,1322]-[410,1367]` |
| 断开连接 / ⋮（第二行） | ⋮ `[722,1281]-[839,1398]`，按钮带 y 1281..1398 |

→ 身份列独占一行、三个控件共享下一行并右对齐，**标签完整未断词**。截图 `E2ECHK5560_14_narrow_410dp.png`。

**复位**：`wm size reset` → 读回 `Physical size: 2076x2152`；宽窗行几何逐值回到 `名称 [773,976]-[1065,1015]`、`⋮ [1799,959]-[1916,1076]`（`E2ECHK5560_17_restored.png` / `E2ECHK5560_19_restored.xml`），无状态泄漏。

---

## 收尾与设备状态

- 本轮自建 SSH 条目 `ssh.github.com:443` 已按 F8 路径删除（⋮ → 删除 → 确认）：`E2ECHK5560_18_menu.png` → `E2ECHK5560_19_armed.png` → `E2ECHK5560_20_deleted.png`；
  删除后 Servers 面板回到 `127.0.0.1:4095 [822,875]-[1029,914]` / `127.0.0.1:4096 [822,1069]-[1030,1108]`，与开测基线 `E2ECHK5560_02_servers.xml` 同坐标。
- 共享 WS 条目未被删除或改动（全程未点 WS 行的 ⋮）。
- 显示尺寸已复位，无 `wm density` 改动，adb reverse 未变。

## 探针与工具口径

- `E2ECHK5560_nav.ps1`：`am start` 后的导航/输入/截图封装（serial 写死 emulator-5560，前缀 E2ECHK5560）。截图走 **设备端 `screencap -p` + `pull`**，并校验 PNG 签名 `89-50-4E-47`——`exec-out screencap -p >` 会被 adb 的 `[Warning]` 行污染成非 PNG（首轮踩到，已修）。
- `E2ECHK5560_pix.ps1`：像素探针（`vscan` 列扫描找色带边界 / `bright` 矩形亮像素计数 / `rowdark` 逐行暗像素）。所有几何结论以 screencap 像素为准，a11y dump 仅作辅助（uiautomator dump 偶发滞后，见 `E2ECHK5560_05b.xml` 的 61 节点残树）。
- 输入口径：`input text` 分段 ≤8 字符、段间 sleep 1.5s；本机 Gboard 默认中文会把 `.` 提交成 `。`（`E2ECHK5560_05_host_typed.png`），用 `input keyevent 204` 切到 English 后点号正常（`E2ECHK5560_06_dot_test.png`，空格键显示 English）。

## 遗留 / 建议

1. **项 2 需独立 SSH 测试后端补验**（密码认证 sshd + `adb reverse`），本轮环境无 sshd/容器/paramiko，公网 git SSH 均仅公钥且本客户端握手失败。
2. 观察（不判 bug）：`E2ECHK5560_09_after_submit.png` 中第二次连接尝试（连接失败后弹层内又出现一条红色 `无法建立 SSH 连接…` + 灰色 `连接失败`）在键盘收起后于表单内重复显示，与行内状态文案同义；属既有信息冗余，不在本轮 F9/F11 验收面内。
3. 观察（不判 bug）：窄窗 410dp 下设置导航退化为横向 tab（`扩展/服务器/Shell/实验性功能/关于`，`E2ECHK5560_14_narrow_410dp.png`），与行堆叠同时发生，属既有响应式设计。
