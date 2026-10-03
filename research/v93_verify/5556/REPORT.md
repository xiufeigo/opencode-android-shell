# v93 真机复验报告 — emulator-5556（Small_Desktop 桌面机）

- 设备：`emulator-5556`，1366x768 @160dpi（853x480 dp），桌面 `organized` 显示，应用区 1366x720，底部任务栏 y=720..768
- 包名：`com.xiufeigo.opencodeshell`，入口 `.OcActivity`
- 复验时间：2026-10-01 19:42–19:45（设备时钟 6:44 → 7:42）
- 证据目录：`research/v93_verify/5556/`（54 张 screencap + 57 份 uiautomator dump + 7 个探针脚本）
- 纪律遵守：全程 screencap 为准；未按 BACK（keyevent 4），只用应用内 Close/Command palette 导航与 `am start`；`keyevent 111` 仅用于收软键盘；未动其它设备（127.0.0.1:5555 等全程未接触）；未改代码、未跑 gradle、未装机

## 结论汇总

| 复验项 | 结论 | 关键证据 |
| --- | --- | --- |
| 1. F10 SSH 条目持久化 | **PASS** | `A11_row_baseline.png`、`B02_reenter_servers.png`、`B06_cold_servers.png`（pid 14282 → 14973） |
| 2. Cancel 盖棺 / 持久删除 | **PASS** | `C02_armed.png`、`C03_after_cancel.png`（行区逐像素 0 差异）、`C04_after_delete.png`、`C05/C08` |
| 3. F11 窄窗布局 | **PASS** | `D20_narrow_row.png`、`E18_narrow_final.png`、`E19_zoom_row2.png` |

---

## 1. F10 SSH 条目持久化 — PASS

### 1.0 建条（过程记录）
- `A03_servers.png`：Servers 面板初始只有 `127.0.0.1:4095`（Default），无 SSH tunnels 段
- `A04_form.png` → `A06_form_filled.png`：Add SSH server 表单，Host=`user@192.0.2.1`，Name=`V93P`
- **实测发现（非本轮任务项，供参考）**：SSH 表单**强制要求 Password**。第一次只填 host+name 就点 Add server，提交被拦下并弹出红色提示 `Enter the password for this connection.`（见 `A07_added.png` 中段与 `E13_two_rows.png`），因此补了一个占位口令 `V93Ppw` 才建条成功。
- `A10_settled.png`：目标 `192.0.2.1` 不可达，回落到 `Connection failed` → 密码门对话框
- `A11_row_baseline.png`：**条目以 DISCONNECTED 形态留在列表**，行文本 `V93P` / `Disconnected. The remote server is left running.`，右侧 `Reconnect` `Disconnect` `⋮`

### 1.a 关闭 Settings 回 Home 再进 Servers — PASS
- 步骤：Servers 面板 → Close(1129,108) → Home(`B01_home_after_close.png`) → Command palette(116,54) → `Open settings`(424,173) → `Servers`(255,535)
- 结果：`B02_reenter_servers.png` 中 `SSH tunnels` 段与 `V93P` 行仍在，副标题仍为 `Disconnected. The remote server is left running.`
- dump：`ui_B02_reenter_servers.xml`（`V93P` @ [494,292]-[527,308]）

### 1.b force-stop + 冷启动 — PASS（v91「条目只活在内存」P1 根因已修）
- `am force-stop com.xiufeigo.opencodeshell` → pid `14282` → 空（`B03_after_forcestop.png`：桌面已回 launcher）
- `am start …/.OcActivity` 冷启动 → pid `14973`（**新进程，非热重启**）
- 冷启动 → Home(`B05_cold_home.png`) → palette → Open settings → Servers
- 结果：`B06_cold_servers.png` 条目原样在位；dump `ui_B06_cold_servers.xml`：`SSH tunnels` / `V93P` / `Disconnected. The remote server is left running.`

---

## 2. Cancel 盖棺 + 持久删除 — PASS

### 2.1 确认条 → Cancel → 行原样保留
- `C01_row_menu.png`：行 `⋮` 菜单只有一项 `Delete`(1116,354)
- `C02_armed.png`：行内确认条出现 —— `Delete SSH server?` / `This removes "V93P" from the list. This cannot be undone.` + `Cancel`(990,410) / `Delete`(1067,410)
- `C03_after_cancel.png`：点 Cancel 后确认条消失，行回到原状
- **像素级证据**（`diff.ps1`，行区 x=474..1132, y=277..345）：
  - `C03_after_cancel.png` vs `B06_cold_servers.png`（同一行、确认前的基线）：`diffpx(>12)=0 (0%)  maxsum=0` → **行区 45471 个像素逐点完全一致**，Cancel 没有留下任何残留/错位
  - `C02_armed.png` vs `C03_after_cancel.png`（同区扩到 y=445）：`diffpx=37315 (33.505%)`，差异行集中在 y=335..371（每行 659 像素的整条确认条）→ 证明确认条确实出现过、也确实被 Cancel 完整收回
- 节点数也回到基线：armed 124 nodes → after-cancel 117 nodes（= 基线 117）

### 2.2 确认删除 → 行消失 → 重进/冷启动保持已删
- `C04a_rearmed.png` 再次 arm，`Delete`(1067,410) 确认
- `C04_after_delete.png`：整个 `SSH tunnels` 段连同 `V93P` 行消失，只剩 `127.0.0.1:4095`
- `C05_reenter_deleted.png`：Close → palette → Open settings → Servers，**条目没有回来**（dump 仅 `Add SSH server` / `127.0.0.1:4095`）
- `C08_cold_servers_deleted.png`：再做一次真冷启动（pid `15399` → 空 → `15539`）后重进 Servers，**依然没有回来** → 删除同样是持久的

---

## 3. F11 窄窗布局（~350dp 宽） — PASS

### 3.0 怎么把窗口弄窄的（过程记录）
- 先试 `cmd activity task resize 113 0 0 560 720` → **无效**（该桌面 shell 的 organized 任务不认这个 bounds；`D03_narrow_560.png` 与 `D02` 字节级同大小=画面无变化）
- 顺带记录：点窗口右上角装饰按钮 (1330,21) 会把窗口整个收走（`D00_after_restore_click.png` 桌面全空、焦点落到 `org.chromium.home.pc`），需要 `am start` 才能拉回（`D01_restored.png`）
- 改用任务允许的 `wm` 路线：`cmd window size 560x768` → 560px / 160dpi = **350dp 宽**，`D04_wm560.png` 可见应用整体切到单列窄版式（左下角出现 `Settings` / `Help` 两行，这正是 v91 脚本里 `Tap 583 631 # Settings` 的由来）
- 复验完 `cmd window size reset` → 回到 1366x768（`E20_reset_wide.png`），设备已复原

### 3.1 窄窗 Servers 面板与堆叠行 — PASS
- 窄窗下进入 Settings → 左侧导航变成横向 tab strip，Servers 需用 `Search settings` 搜 `servers` 跳转（`D07_search.png` → `D11_narrow_servers.png`）
- 在窄窗里新加两条 SSH 条目：
  - `D20_narrow_row.png`（第 1 条，名字 41 字符）
  - `E18_narrow_final.png` / `E19_zoom_row2.png`（第 2 条，名字 75 字符）
- **四条判定逐条核对**（坐标取自 `ui_E18_narrow_final.xml`，行卡片左右边界 x=56..504）：

| 判定 | 实测 | 结论 |
| --- | --- | --- |
| 身份列（名字+target）独占一行 | 行 1：名字 y=385..401、状态行 y=403..417，均在 x=76 起的一整行；按钮行整体落到 y≈433..449 | ✅ |
| 按钮行换到第二行 FlowRow | `Reconnect` [235,433]-[301,449]、`Disconnect` [353,433]-[422,449]、`Server actions ⋮` [446,415]-[494,463]（48px 高按钮，中心 y=439 对齐第二行） | ✅ |
| 长名字 Ellipsis 不溢出 | 75 字符名渲染为 `V93P-ellipsis-probe-AAAAAABBBBCCCCDDDDEEEEFFFFGGG…`，节点 bounds [76,482]-[484,498]，**止于 x=484 < 卡片右沿 504**；放大图 `E19_zoom_row2.png` 可见省略号后无溢出、无压边 | ✅ |
| 无按钮被裁切 | 最右 `⋮` 右沿 x=494，卡片右沿 504，Reconnect/Disconnect 完整描边与文字（`E19_zoom_row2.png` 3x 放大可见按钮四边完整） | ✅ |

- 附带事实（不计入判定）：同一面板里的**普通 server 行**（`127.0.0.1:4095`）在窄窗下仍把 `Default` `⋮` 放在身份右侧同一行（`[96,618]` 身份 vs `[404,627]` Default，y≈630 同行），未走堆叠分支；其控件组只有 2 个小按钮、同样没有裁切。SSH 隧道行才是本次 F11 的目标形态，已按预期堆叠。
- 附带事实：`E20_reset_wide.png` 显示恢复 1366 宽后，两条条目回到「身份左 / 按钮右」同行布局，第 2 条同样带省略号，窄→宽来回切换无残留错位；两条条目在整个 resize 往返中都在。

---

## 遗留状态（供后续轮次接手）
- Servers 面板现存两条本轮 F11 验证条目：`V93P-narrow-window-ellipsis-check-0123456789`、`V93P-ellipsis-probe-AAAAAABBBB…MMM`（host 均为 `user@192.0.2.1`，口令 `V93Ppw`），均为 DISCONNECTED。任务书未要求删除，作为窄窗取证样本保留；要清可在 ⋮ → Delete 走同一确认条。
- 设备显示已 `wm size reset` 回 1366x768；未按 BACK、未杀任何后台进程（除任务书 1.b 明确要求的 `am force-stop` 两次）。
- 复用的探针脚本（serial 已固定为 emulator-5556，输出目录已指向本目录）：`nav.ps1`（按文本 tap / 分段 input / dump+shot）、`shot.ps1`、`dump.ps1`、`rowscan.ps1`、`hist.ps1`；本轮新增 `diff.ps1`（区域逐像素对比）、`zoom.ps1`（裁剪放大取证）。
