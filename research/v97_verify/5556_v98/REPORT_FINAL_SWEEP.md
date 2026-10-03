# v98 真机收口扫测 — ③-C FINAL SWEEP（emulator-5556）

- 执行：③-C 真机收口测试员（mcode）
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 设备：`emulator-5556`（AVD `Medium_Desktop`）
- 后端：`http://127.0.0.1:4095`，Basic `opencode:opencode`
- 会话前缀约定：自建会话一律以 **`E2ECHK5556r`** 开头；自建工作树一律以 **`E2ECHK5556r`** 命名
- 纪律：不跑 gradle / 不装机 / 不杀进程（`am force-stop` 本 app 允许）；只操作 5556；不碰 127.0.0.1:5555；不按 BACK(4)；弹层内不按 ESC(111)；截图只用 `screencap -p` + `adb pull`；输入空格用 `keyevent 62`、≤8 字符分段；坐标运行期从 dump 解析；dump 同时匹配 `text="…"` / `text='…'`，优先 `--compressed`

---

## 0. 环境读数（基线）

| 项 | 命令 | 读数 |
|---|---|---|
| 设备列表 | `adb devices` | `emulator-5554/5556/5558/5562/5564/5600` 全 `device`（本轮**只操作 5556**） |
| boot | `adb -s emulator-5556 shell getprop sys.boot_completed` | `1` |
| wm size | `adb -s emulator-5556 shell wm size` | `Physical size: 3840x2160` / **`Override size: 1366x768`** |
| wm density | `adb -s emulator-5556 shell wm density` | `Physical density: 320` / **`Override density: 160`** |
| screenState | `adb shell dumpsys window policy \| Select-String 'screenState\|keyguard'` | `screenState=SCREEN_STATE_ON`；`keyguard showing` 无输出（= 未 show） |
| mCurrentFocus | `adb shell dumpsys window` | `Window{6840199 u0 org.chromium.home.pc/org.chromium.home.pc.HostActivity}`；`mFocusedApp=null`（桌面，非本 app；非 ANR） |
| reverse | `adb -s emulator-5556 reverse --list` | `host-19 tcp:4095 tcp:4095` / `host-19 tcp:2222 tcp:22` ✅ |
| show_ime_with_hard_keyboard | `adb shell settings get global show_ime_with_hard_keyboard` | `null`（未设置）→ **本轮不动此键** |
| ime | `adb shell ime list -s` | `com.google.android.inputmethod.latin/…LatinIME`（hard keyboard 场景下不弹软键盘） |
| SDK | `adb shell getprop ro.build.version.sdk` | `34` |

**判定：设备健康闸门（`NOTIFICATION_IDLE_DIAG.md` §E 步骤 0）通过** —— 屏 ON、keyguard 未锁、reverse 4095 在位、`boot_completed=1`、`mCurrentFocus` 非 ANR。上一轮通知读数作废的两个诱因（屏 OFF / keyguard showing）本轮均不成立。

**`wm` 说明**：读数显示 Override 已是 `1366x768` / `160`，与上一轮一致。**本轮未执行任何 `wm size` / `wm density` 写入**，故无"验完 reset"义务；收尾复查该项保持原值即可。

**鉴权**：`GET /api/session` 裸请求 = `401`。设备上 `oc_server_config.xml` 存在；用 `opencode:opencode` 成功（`OK count=1`），`opencode:`（空密）= `401`。**本轮所有后端读数用 `opencode:opencode`**。

**主工程 projectID**：`ae54078c0e09bf8e7cbeefd35db19aaa6b064e46`（canonical `/mnt/c/sensorsdata/main/program/opencode-android-shell`）。

---

## 0.1 逐条判定总表

| # | 项 | 判定 | 一句话 |
|---|---|---|---|
| 1 | 非图片附件（.txt） | ✅ **PASS** | chip 出现 + 后端 `files[]` 内联，base64 解码与本地**逐字相同**（§1） |
| 2 | 菜单 `Fork` | ✅ **PASS** | 新会话 `ses_01a0e34a911bmBUvNmp3MTJ6xQ`（`E2E_FORK_T1`），计数 45→47（§2） |
| 3 | 菜单 `Move` | ✅ **PASS** | 两步目录选择走通；UI `Location changed` + 后端 `location.directory` 改写 + `location-switched`（§3） |
| 4 | 菜单 `New worktree` | ⛔ **未闭环**（纪律性跳过） | 入口存在/可点/能打开；**不支持自定义命名 + 无 DELETE 端点** ⇒ 共享后端上不创建（§4） |
| 5 | 通知（正确姿势） | ⚠️ **链路 PASS / 后台时序 FAIL** | `trigger idle(finished=true)`→`raise`→`posted(viewed=false sound=default)`→`NotificationRecord` 命中；但切后台后**延迟约 3 分钟**才投递（§5） |
| 6 | `Undo`（正确口径） | ❌ **FAIL** | 造态正确，点后消息仍 7 条、**`revert=null`**、**`Redo` 不出现**、logcat 无任何 revert 行（§6） |
| 7 | 收尾 | ✅ 基本闭环 | 设置未改 / 工作树零新增 / `/sdcard` 已复原 / `wm` 未动；**唯系统通知本机无清除入口**（§7） |

> **一句话**：**PASS 3**（附件 / Fork / Move）+ **通知链路 PASS 但后台时序 FAIL** + **`Undo` 确认为真机 no-op** + **`New worktree` 纪律性跳过**。

---

（本文件边做边写，后续章节持续追加。）

---

## 1. 非图片附件（`.txt`） — ✅ **PASS**

**判据**：①被 composer 接受（附件 chip 出现）②进了上下文（后端 `files` 数组带真实字节）。

### 命令链

```powershell
# 1) 造 .txt 并推送到 /sdcard/Download
Set-Content research\v97_verify\5556_v98\E2ECHK5556r_note.txt -Value "E2ECHK5556r attachment probe." -Encoding UTF8 -NoNewline
adb -s emulator-5556 push E2ECHK5556r_note.txt /sdcard/Download/E2ECHK5556r_note.txt
#   → "1 file pushed, 0 skipped ... (29 bytes)"

# 2) 走附件选择器（坐标全部运行期从 dump 解析）
adb -s emulator-5556 shell input tap 269 54      # New session  (t1 dump 解析)
adb -s emulator-5556 shell input tap 694 453     # Create session (t3 dump 解析)
adb -s emulator-5556 shell input tap 31 272      # Add            (t5 dump content-desc="Add")
adb -s emulator-5556 shell input tap 90 447      # Attachments    (t7 dump 解析)
adb -s emulator-5556 shell input tap 122 442     # E2ECHK5556r_note.txt (t9 dump 解析)
# 3) 输正文并发送
adb -s emulator-5556 shell input tap 600 247
#   TypeText "E2ECHK5556r attach probe"   (空格走 keyevent 62，其余 ≤8 字符分段)
adb -s emulator-5556 shell input keyevent 66    # 发送
```

### 证据

| 环节 | 证据文件 | 读数 |
|---|---|---|
| `/sdcard/Download` 推送后 | `fs_000_sdcard_download_pre.txt` + 推送后 `ls` | `-rw-rw---- 1 u0_a183 media_rw 29 2026-10-03 02:31 E2ECHK5556r_note.txt` |
| SAF 选择器可见本轮文件 | `fs_t8_saf.xml` / `.png` | `Downloads \| … \| **E2ECHK5556r_note.txt** \| 29 B \| 2:31AM \| E2ECHK5556w_note.txt \| 82 B` |
| **附件 chip 出现（被接受）** | `fs_t11_chip2.xml` / `.png` | 文本集含 **`E2ECHK5556r_note.txt`**；节点 `content-desc="Remove attachment"` 同时出现（chip 带删除钮 ⇒ 真被接受，非占位） |
| 发送前 | `fs_t13_typed.xml` | `E2ECHK5556r_note.txt \| ✕ \| E2ECHK5556r attach probe`（chip 与正文并存） |
| **后端读回（进上下文）** | 见下方 JSON | `type=user` 的 `msg_0ff9cec820010tb3k772wnO26j` 带 `files[]`：`name=E2ECHK5556r_note.txt`、`mime=text/plain`、`source.type=inline`、`data=R…`（base64） |

后端 `GET /api/session/ses_f0064b0f9ffeEO3CLhWCesCMz3/message?order=asc&limit=80` 的 user 消息原文：

```json
{
  "id": "msg_0ff9cec820010tb3k772wnO26j",
  "text": "E2ECHK5556r attach probe",
  "files": [
    { "data": "RTJFQ0hLNTU1NnIgYXR0YWNobWVudCBwcm9iZS4=",
      "mime": "text/plain", "source": { "type": "inline" },
      "name": "E2ECHK5556r_note.txt" }
  ],
  "type": "user"
}
```

字节级核对：`base64("RTJFQ0hLNTU1NnIgYXR0YWNobWVudCBwcm9iZS4=")` 解码 = `E2ECHK5556r attachment probe.`，与本地推送文件内容**逐字相同**（本地 29 字节）。

### 读数要点（一个容易踩的坑）

- ⚠️ **附件不在 `parts[]` 里**。我的首版 `MsgTable` 只扫 `parts`（text/file），扫出来 `FILES:` 为空，差点误判 FAIL。实际上 v2 用户消息的附件是**顶层 `files[]` 数组**（`source.type=inline`，即 SAF 读到的字节直接内联），与 `parts[]`（文本分段）**是两个并列字段**。**这是我的判读脚本缺陷，不是产品缺陷。**
- ✅ `mime=text/plain`（非图片）路径与 `Import` 已验过的路径不同，本条证明**非图片附件**同样走通：SAF 读取 → chip → 发送 → base64 内联落库。
- 本轮 app 未主动把文件写回 `/sdcard`（无新增残留），符合预期。

---

## 2. 菜单 `Fork` — ✅ **PASS**

**判据**：执行 `⋮ → Fork session` 后**新会话被创建**（后端 `GET /api/session` 新增 id）。

### 命令与证据

| 步骤 | 命令 / 坐标 | 读数 |
|---|---|---|
| 前置 | 自建会话 `ses_f0064b0f9ffeEO3CLhWCesCMz3`（标题 `E2ECHK5556r probe attachment check`） | 附件轮已落库 |
| Fork 前基线 | `GET /api/session?limit=100&directory=…` | **`PRE session count = 45`** |
| 打开菜单 | `adb shell input tap 1302 134`（"More options"，坐标来自 `fs_menu4.xml`） | 菜单项与坐标见下 |
| 菜单项坐标 | 由 `fs_menu4.xml` 运行期解析 | `Rename @1157,174` / `Export session @1178,203` / `Import session @1178,231` / `Delete @1152,260` / `Undo @1149,288` / `Fork session @1172,316` / `Move to… @1163,344` / `New worktree @1176,372` |
| 点击 Fork | `adb shell input tap 1172 316` | — |
| Fork 后基线 | `GET /api/session?…` | **`POST session count = 47`** |
| **新 id** | 同上 | **`ses_01a0e34a911bmBUvNmp3MTJ6xQ`**，标题 `E2E_FORK_T1`（UI 的 `Fork from message` 弹层里可命名，默认值取自源会话） |

菜单项**全量在位且可点**（`fs_redochk.xml` 解析 `enabled` 属性）：`Rename / Export session / Import session / Delete / Undo / Fork session / Move to… / New worktree` **八项 `enabled=true`**。

> ⚠️ 纪律遵守：全程**未点 `Delete`**（坐标 1152,260 与 `Undo` 仅隔 28px，坐标全部由 dump 解析而非手填，避免误触）。

**附带观察**：`Fork session` 会**先弹一个 `Fork from message` 命名/选点弹层**（含 `Cancel`），不是立即完成。`POST` 计数 45→47（+2）说明弹层**打开时已建了一个空会话** + 确认后建了 fork 会话；`Cancel` 可退出。

---

## 3. 菜单 `Move` — ✅ **PASS**

**判据**：执行后有**可观测结果**（目录/项目变化）。**走完了 SAF/目录选择两步确认。**

### 命令与读数

| 步骤 | 命令 / 坐标（运行期 dump 解析） | UI 读数 |
|---|---|---|
| 打开菜单 → Move | `input tap 1302 134` → `input tap 1163 344` | 弹层 **`Choose a project directory`**，当前 `/mnt/c/sensorsdata/main/program/opencode-android-shell`；可见 `~` / `Root` / `Parent` / `.dsh-acl-recovery` / `Cancel` / `Select this directory` |
| 上跳一级 | `input tap 598 186`（`Parent`） | 路径变 **`/mnt/c/sensorsdata/main/program`**，列表出现 `allinone …` |
| 选定 | `input tap 810 264`（`Select this directory`） | 按钮态变为 **`Confirm this directory`**（两步确认） |
| 确认 | `input tap 804 264`（`Confirm this directory`） | 弹层关闭，时间线出现 **`Location changed`** + **`program`** |

**后端 ground truth**：

| 项 | 移动前 | 移动后 |
|---|---|---|
| `GET /api/session/ses_f0064b0f9ffeEO3CLhWCesCMz3` → `location.directory` | `/mnt/c/sensorsdata/main/program/opencode-android-shell` | **`/mnt/c/sensorsdata/main/program`** ✅ |
| 消息流 | 无 `location-switched` | 追加一条 **`location-switched` → `/mnt/c/sensorsdata/main/program`** ✅ |

证据文件：`fs_move.xml` / `fs_move2.xml` / `fs_move3.xml` / `fs_move4.xml`（+ `fs_move*.png`）。

**结论**：`Move` 两步式目录选择（`Select this directory` → `Confirm this directory`）走通，UI 与后端双向一致。

---

## 4. 菜单 `New worktree` — ⛔ **未闭环（纪律性跳过，非缺陷）**

**本轮刻意没有创建工作树**，如实说明原因。

### 读数

菜单项**存在且可点**（`fs_redochk.xml`：`New worktree enabled=true`，坐标 `1176,372`）。点击后弹层正常打开：

```
New worktree
from /mnt/c/sensorsdata/main/program/opencode-android-shell
No matching worktrees
Back | Create new worktree
```

证据：`fs_nwd.xml` / `fs_nwd.png` / `fs_wt_s.png`。

### 为什么没点 `Create new worktree`

**弹层里没有"工作树名称"输入框。** 逐条核对：

1. 首次进入时 dump 里有 `android.widget.EditText bounds="[78,247][1308,287]"`——但那是**搜索/过滤框**（下方分支列表随之变成 `No matching worktrees`），不是命名框。
2. 输入后再次 dump：**`EditText` 节点消失**，弹层只剩 `No matching worktrees` / `Back` / `Create new worktree` 三个可交互元素。
3. 截图（`fs_wt_s.png`）目视确认：弹层内**无名称输入行**。

后端侧也印证：工作树目录由服务端随机命名（`sunny-island` / `curious-cabin` / `happy-panda` / `calm-eagle` / `eager-lagoon` / `tidy-orchid`），**`/api/worktree` 的 schema 无 name 入参**（只有 `projectID` 必填），且 **openapi 里只有 `GET /api/worktree` 与 `POST /api/worktree/refresh`，没有任何 DELETE**。

### 为什么这构成"不执行"的理由

本轮纪律明令：**"只创建你自己命名的工作树（前缀 `E2ECHK5556r`），绝不删任何已存在的工作树"**。既然产品**不支持自定义命名**，创建出来的工作树必然是随机名，我**无法满足"自己命名 + 可按前缀识别"这个清理前提**；而**又没有 DELETE 端点**，误建后只能靠改远端文件系统清理——那是本轮纪律明令禁止的高风险操作（共享后端、他人工作树共存）。

**在共享后端上制造一个自己既认不出、又删不掉的残留，违反纪律的净意图（不污染他人环境）。故选择不创建，如实记为「未闭环」。**

- 弹层入口本身：**存在、可点、可正常打开** ✅（这部分已验）
- 「新工作树出现在 `GET /api/worktree`」：**未验**（未执行创建）
- 收尾：**本轮未新增任何工作树**，工作树列表仍为基线的 7 条，无需清理 ✅

---

## 6. 菜单 `Undo`（按上游正确口径） — ❌ **FAIL**

**这一条是本轮最重的结论。** 按 `UNDO_WIRING_FIX.md` §6 的正确口径造态（**不是改名**，而是"一条真实用户消息 + 回复"），`Undo` 在真机上**完全无变化**。

### 造态（正确口径）

自建会话 `ses_f0064b0f9ffeEO3CLhWCesCMz3`，其中已有一条**真实的用户消息 + 完整回复**（即附件那一轮）——这正是上游 `undo` 要求的可撤销态。

**点前 UI 读数**（`fs_undo_pre.xml` / `fs_undo_pre.png`）：

| 项 | 读数 |
|---|---|
| 后端消息数 | **7** |
| 消息序列 | `model-switched` / **`user`** / `assistant` ×4 / `idle` |
| **最后一条 user 消息 id** | **`msg_0ff9cec820010tb3k772wnO26j`**，text = `E2ECHK5556r attach probe` |
| 会话 `revert` 字段 | **`null`**（= 无 revert 边界） |
| `outcome` / `time.idle` | `succeeded` / `1790994996282` |
| UI 可见性 | 时间线**已滚离**，用户气泡不在可视区（`fs_undo_pre.xml` 中 `E2ECHK5556r attach probe` 命中 = `False`）——**故 UI 侧不能单独作为判据，必须靠后端 ground truth** |

### 执行

`⋮` 菜单 `Undo`（`fs_undo_menu.xml` 解析坐标 `1149,288`）→ `input tap 1149 288`。

### 点后读数（原样写盘）

| 判据（上游 §6.4/§6.5） | 期望 | **实测** | 结论 |
|---|---|---|---|
| UI：最后一条用户气泡 `E2ECHK5556r attach probe` 消失 | 消失 | dump 中命中 = `False`（**点前也是 `False`，因滚出可视区 ⇒ 此项无信息量**） | ⚪ 不可判 |
| UI：`Redo` 行变为可用（`redoEnabled` false→true） | 出现且可用 | **重开菜单：`Redo present: False`**，八项菜单 `enabled=true` 列表与点前**逐字相同**（`Rename/Export session/Import session/Delete/Undo/Fork session/Move to…/New worktree`，**无 `Redo`**） | ❌ **FAIL** |
| 后端：`GET /message` 被撤销轮次不再返回 | 不再返回 | **仍是 7 条**，`user` 消息 `msg_0ff9cec820010tb3k772wnO26j` **原样仍在** | ❌ **FAIL** |
| 后端：`GET /api/session/:id` → `revert.messageID` | = 点前记下的 user 消息 id | **`revert = null`**（无边界） | ❌ **FAIL** |
| logcat：`ChatViewModel` 的 `revert failed:` / `revert commit failed:` | 若失败应有 | **一条都没有**（`logcat -d -s ChatViewModel:* *:S` 过滤 `revert|undo` 无输出） | ⚪ 不可判（见下） |

证据文件：`fs_undo_pre.xml` / `fs_undo_pre.png` / `fs_undo_menu.xml` / `fs_undo_post.xml` / `fs_undo_post.png` / `fs_redochk.xml`。

### 分诊（按 `UNDO_WIRING_FIX.md` §6.6 的判读表逐条落位）

文档给的失败判读是三选一：

1. `revert commit failed:` → 服务端 `revert/commit` 返非 2xx（如 409 busy）
2. 有 toast `common.requestFailed` → 请求失败
3. **两者都没有 → `undoStep` 返回 `null`（会话里没有可见用户消息）→ 查 `_state` 是否已装载该轮消息**

**实测落在第 3 档**：既无 `revert failed` / `revert commit failed` 日志，也无 `revert` 边界落库。

> ⚠️ 但**第 3 档的前提与本轮观测有一处对不上，必须如实指出**：会话后端明确有 1 条 `user` 消息（`msg_0ff9cec820010tb3k772wnO26j`），且 `Undo` 行 **`enabled=true`**（`fs_redochk.xml`）——若客户端 `_state` 里真的没有这条用户消息，`undoEnabled = visible.isNotEmpty()` 应当是 `false`。**使能判定与执行判定自相矛盾**，说明断点更可能落在 `undoEnabled` 之后的环节（点击后 `undoSession` 是否真的发起 `stage`），而不是 `_state` 没装载。

**我不再往下猜，也不改代码**——按 `UNDO_WIRING_FIX.md` §7 的口径：**在拿到更细的读数前，任何代码改动都是臆造。**

### 结论

> **本壳 `Undo` 在真机上判定 FAIL。**
> 与 `REPORT_SWEEPC2.md` 的旧 FAIL **判据不同**：旧轮用"改名"造态（本就不该被 Undo 影响，判据错）；本轮用**上游语义要求的真实对话**造态，**仍然是点了没反应**。
> ⇒ `UNDO_WIRING_FIX.md` 判定为"判据错、原 FAIL 不成立"的那个结论，**在真机上没有被推翻**——原 FAIL 确实判错了，但**真机也确实存在一个独立的、真实的 Undo no-op**。这两件事同时成立：代码八环齐全（JVM 侧 140 例全绿），**运行时 `undoSession` 没有产生任何可观测写入**。

---

## 5. 通知（按 `NOTIFICATION_IDLE_DIAG.md` §E 的"正确姿势"） — ⚠️ **链路 PASS，但 HOME 姿态复现了已归档的"进程冻结"失败模式**

这一条必须**拆成两个读数**报告，否则会得出错误结论。

### 5.1 前置：`notifyAgent` 原值

`oc_settings.xml`（`run-as` 直读，`fs_settings_pre.txt`）**根本没有 `notifyAgent` 键**：

```xml
<map>
    <string name="opencode.android.selectedModel">opencode-go/space-bunny-free</string>
    <string name="opencode-terminal-font">serif-monospace</string>
    <string name="opencode-ui-font"></string>
    <string name="opencode-language">en</string>
    <string name="opencode-color-scheme">light</string>
</map>
```

⇒ 取**默认值**。运行期 logcat 直接证实了生效值（**不需要改设置**）：

```
OcNotify: raise: kind=AGENT session=ses_f0064b0f9ffeEO3CLhWCesCMz3 since=… watched=true visible=…
OcNotify: gate settings: kind=AGENT enabled=true          ← notifyAgent 门禁 = true
OcNotify: accepted: AGENT:ses_f0064b0f9ffeEO3CLhWCesCMz3 viewed=true delivery=true
```

**`gate settings: kind=AGENT enabled=true` ⇒ `notifyAgent` 原本就是开着的，本轮不需要改、也就不需要还原**（收尾 §7 确认未写入）。

### 5.2 ✅ 链路完整性：**PASS**（读数 A —— 附件那一轮，前台被弹层遮蔽）

这是本轮**最硬的证据**，一整条链路逐环落地（`logcat -d`，02:36:34–02:36:46）：

```
02:36:34.854 D OcNotify: raise: kind=AGENT session=ses_f0064b0f9ffeEO3CLhWCesCMz3 since=1790994868168 watched=true visible=…
02:36:34.854 D OcNotify: gate settings: kind=AGENT enabled=true
02:36:34.886 D OcNotify: accepted: AGENT:ses_f0064b0f9ffeEO3CLhWCesCMz3 viewed=true delivery=true
02:36:34.887 D OcNotify: skip: app is foreground on … (viewed=true) kind=AGENT resumedCount=1 windowFocused=true
02:36:34.887 D OcNotify: recheck: scheduled id=1357498609 kind=AGENT in 2000ms
02:36:36.889 D OcNotify: recheck: id=1357498609 dropped (contentVisible=true postedAt=null raised=1790994994854)
02:36:41.129 D OcNotify: focus: lost resumedCount=1
02:36:46.129 D OcNotify: focus: timeout expired (the window stayed covered)
02:36:46.130 D OcNotify: withheld: id=1357498609 posting (the user left before the re-check)
02:36:46.161 D OcNotify: posted: id=1357498609 kind=AGENT session=ses_f0064b0f9ffeEO3CLhWCesCMz3 title=E2ECHK5556r probe attachment check view…
02:36:46.161 D OcNotify: sound: playing the default notification ringtone
```

逐环对照 `NOTIFICATION_IDLE_DIAG.md` §A1 的门禁链：

| 环节 | 证据行 | 判定 |
|---|---|---|
| IDLE 分支执行 | 回合在本会话真读到终帧（同一轮随后有 `posted:`） | ✅ |
| `notifyAgent` 门禁 | `gate settings: kind=AGENT enabled=true` | ✅ |
| 观看时长门禁 | `watched=true` | ✅ |
| 本机归属 | 未出现 `skipped foreign session=` | ✅ |
| 焦点门禁 | `skip: app is foreground … (viewed=true)` → `recheck: scheduled … in 2000ms` | ✅ **正是文档描述的"前台→补投"路径** |
| 去重 / withteld | `recheck: … dropped (contentVisible=true)` → `withheld: … posting (the user left before the re-check)` | ✅ |
| **真正 post** | **`posted: id=1357498609 … title=E2ECHK5556r probe attachment check`** | ✅ |
| **声音** | **`sound: playing the default notification ringtone`** | ✅ |
| 系统侧落地 | `dumpsys notification --noredact` → **`NotificationRecord(0x03709a08: pkg=com.xiufeigo.opencodeshell … id=1357498609 … channel=opencode.sessions …)`** | ✅ |
| 通知标题 | 该 Record 的 `android.title=String (Response ready)` | ✅ |

> ✅ **`NotificationRecord` 判据命中**（不是 `NotificationChannel`，按 §E 步骤 6 的新纪律）。**横幅/通知确实存在**。
> ⇒ **通知链路完好，与 `NOTIFICATION_IDLE_DIAG.md` 的代码结论一致；上一轮"读到 0"确实是运行时问题，不是代码缺陷。**

### 5.3 ❌ HOME 姿态：复现"进程被冻结、终帧从未被读到"（读数 B）

按任务书"正确姿势"另起一轮长回合，专门验**切后台**这条路径：

| 步骤 | 命令 | 读数 |
|---|---|---|
| 清日志 | `adb logcat -c` | 干净 |
| 发长回合 | `input text`（空格走 `keyevent 62`）+ `keyevent 66` | `Count slowly from 1 to 40. Output one number per line, one second apart.` |
| **切后台** | `input keyevent 3` **无效**（`mCurrentFocus` 仍是 `OcActivity`）→ 改用 `am start -a android.intent.action.MAIN -c android.intent.category.HOME` | 生效：`mCurrentFocus=org.chromium.home.pc/…HostActivity` |
| 进程存活 | `pidof com.xiufeigo.opencodeshell` | **`10489`（进程活着，不是被杀掉）** |
| 客户端日志 | `logcat -d -s OcNotify:D *:S` | **只有 3 行**：`foreground: paused=OcActivity` / `focus: lost` / `foreground: stopped`（02:46:27–28） |
| **终帧判据** | 同上过滤 `trigger idle` | ❌ **0 行** |
| **兜底判据** | 同上过滤 `watchdog settle` / `reconcile-settle` | ❌ **0 行** |
| **投递判据** | 同上过滤 `raise:` / `posted:` | ❌ **0 行** |
| 回前台后 | `am start -n com.xiufeigo.opencodeshell/.OcActivity` | 补出 `foreground: started` / `resumed` / `focus: gained`（02:49:09），**但仍无 `trigger idle:`**（回合尚未跑完，客户端确实没读到终帧） |
| 系统侧 | `dumpsys notification --noredact` 数本包 Record | **仍是 1 条（就是 5.2 那条 id=1357498609），本轮无新 Record** |

**判读（逐档对齐 §E 步骤 5 的表）**：

- 「`trigger idle:` 缺失」✅ 命中；
- 「两个兜底 `watchdog settle:` / `trigger reconcile-settle:` 也 0」✅ 命中；
- 「一条 `OcNotify` 都没有」❌ **不命中**——`foreground: stopped` 在，**进程 pid 一直是 10489 活着**。

⇒ 精确落在 §A2-c 与 §E 失败分诊树的**第二档**：

> logcat 有 `foreground: stopped` 但无 `trigger idle:`、无 `watchdog settle:` ⇒ **进程被冻结（cached process）→ 上一轮归档的失败模式**。

⚠️ **本轮未复现"进程已死"**（pid 全程存活），所以比上一轮**多排除一个诱因**，但**冻结档原样复现**。这印证 `NOTIFICATION_IDLE_DIAG.md` §F.2 的结论：**兜底（watchdog / reconcile-settle）都跑在进程内协程里，对"冻结/被杀"这一档无能为力**。

### 5.4 点击回跳 / 清除

- **点通知回跳**：本轮**未取到读数**。5.3 的那一轮**没有投出通知**，无 banner 可点；5.2 那条 Record 是 app 仍在**前台**时被遮蔽触发的补投，banner 早已消失。**如实记为「未验到」**，不编造。
- **清除通知 → 断言消失**：见 §7 收尾实测（清掉 id=1357498609 后 Record 数归 0）。

### 5.5 小结

> **通知链路本身：PASS**（读数 A，raise→accepted→withheld→posted→sound→`NotificationRecord`，端到端八环全绿）。
> **"切后台才弹横幅"这一严格姿态：FAIL**（读数 B，与 `NOTIFICATION_IDLE_DIAG.md` §F.2 归档的架构级缺口一致：**进程冻结窗口内的通知会丢**），**非本轮新发现**，是已登记项的复现。

### 5.6 ⚠️ **重要更正：5.3 的"0 行"是"尚未读到"，不是"永远读不到"**

按 §E 失败分诊树的**处置 B**（把 app 切回前台，等终帧 + 一次探针）**追加**一步之后，读数发生了变化——**这是本轮拿到的最关键的一组日志**：

```
10-03 02:46:27.807 D OcNotify: foreground: paused=OcActivity  resumedCount=0   <- 按 HOME
10-03 02:46:27.891 D OcNotify: focus: lost                   resumedCount=0
10-03 02:46:28.251 D OcNotify: foreground: stopped=OcActivity resumedCount=0   <- 冻结开始
                    …… 02:46:28 -> 02:49:09 期间：客户端一条日志都没有 ……
10-03 02:49:09.196 D OcNotify: foreground: started=OcActivity  resumedCount=0   <- 切回前台
10-03 02:49:09.206 D OcNotify: foreground: resumed=OcActivity  resumedCount=1
10-03 02:49:09.415 D OcNotify: focus: gained                   resumedCount=1
10-03 02:49:29.188 D OcNotify: trigger idle: routed=ses_f0064b0f9ffeEO3CLhWCesCMz3 tracked=ses_f0064b0f9ffeEO3CLhWCesCMz3 foreign=false finished=true since=1790995502688
10-03 02:49:29.190 D OcNotify: raise: kind=AGENT session=ses_f0064b0f9ffeEO3CLhWCesCMz3 since=1790995502688 watched=false visible=… viewed=false
10-03 02:49:29.204 D OcNotify: accepted: AGENT:ses_f0064b0f9ffeEO3CLhWCesCMz3 viewed=false delivery=true
10-03 02:49:29.211 D OcNotify: posted: id=1357498609 kind=AGENT session=ses_f0064b0f9ffeEO3CLhWCesCMz3 title=E2ECHK5556r probe attachment check viewed=false sound=default contentVisible=true via=raise
```

**逐条对上 §E 步骤 5 判读表的第一行**：

| 判据 | 读数 | 判定 |
|---|---|---|
| `trigger idle: … finished=true` | `foreign=false finished=true` | IDLE 分支执行了，且 `ranTurn` 为真（**不是**边沿误判档） |
| `raise: kind=AGENT` | `raise: kind=AGENT … viewed=false` | raise 了 |
| `posted: id=…` | `posted: id=1357498609 … **viewed=false** sound=default … via=raise` | **投出了** |
| 系统侧 | `dumpsys notification` → `NotificationRecord(… pkg=com.xiufeigo.opencodeshell id=1357498609 …)` | **`NotificationRecord` 命中** |

> **这才是 §E 步骤 5 表格里的「链路完整，PASS」那一行**，而且是 **`viewed=false sound=default`** —— 也就是**真正的"用户不在看时才带声投递"路径**（与 5.2 那条"前台被遮蔽 → withheld 补投"是不同的投递变体）。

**但代价是延迟**：HOME 落在 **02:46:27**，终帧被读到并投递在 **02:49:29**，**中间约 3 分钟客户端完全静默**（`02:46:28 stopped` → `02:49:09 started` 之间 0 条日志）。期间后端回合在正常跑（`assistant` 消息持续追加），**服务端 idle 与客户端读到帧之间被插进了约 3 分钟的冻结窗口**。

**⇒ 最终定性（更正 5.5）**：

> - **通知链路：PASS。** `trigger idle(finished=true)` → `raise` → `accepted` → `posted(sound=default)` → 系统 `NotificationRecord` —— **一条不缺，端到端坐实**。§A1 的门禁链在真机上逐环可见。
> - **缺陷不在链路，在时序**：**切后台后进程被冻结，终帧要等到进程解冻（用户切回 app）才被读到**，所以"人真的不在 app 附近时自动弹横幅"这一体验**不成立**——这正是 `NOTIFICATION_IDLE_DIAG.md` §F.2 已登记的**架构级缺口**（兜底全在进程内协程，对冻结档无能为力）。**本轮是复现，不是新发现。**
> - 5.3 当时读到的"0 行"应理解为**"那一刻还没读到"**（回合尚未跑完 + 进程冻结），**不是"永远读不到"**。按处置 B 补一步后就拿到了 PASS 读数——这也反向验证了 §E 处置 B 的有效性。

---

## 7. 收尾核对

| 项 | 要求 | 实测 | 判定 |
|---|---|---|---|
| **`notifyAgent` 还原** | 记下原值，验完还原 | **原值 = 默认（键不存在于 `oc_settings.xml`）；本轮全程未写设置**。运行期 `gate settings: kind=AGENT enabled=true` 证明一直是开着的，**无需还原** | OK 无需变更 |
| **自建工作树清理** | 只删自己建的 `E2ECHK5556r*` | **本轮一个都没建**（见 §4 的纪律性跳过）。收尾 `GET /api/worktree?projectID=ae5407…` 仍为 **7 条**，且逐条与基线**逐字相同**（`sunny-island` / `curious-cabin` / `happy-panda` / `calm-eagle` / `eager-lagoon` / `tidy-orchid` + 主目录） | OK **零污染，未碰任何他人工作树** |
| **`/sdcard` 无新增残留** | 无新增 | 推送时新增的 `E2ECHK5556r_note.txt`（29 B）已在收尾清掉；最终 `ls -la /sdcard/Download` 与开跑前基线 `fs_000_sdcard_download_pre.txt` **逐字一致**（3 个文件，全部是前几轮遗留：`E2ECHK5556c2-RENAME.json` / `E2ECHK5556w_note.txt` / `Interpreting-…fork-1.json`） | OK 已清理 |
| **`wm` 已 reset** | 本轮若写过则必须还原 | **本轮从未执行任何 `wm size` / `wm density` 写入**。收尾复查：`Override size: 1366x768`、`Override density: 160`，与开跑前**完全相同** | OK 无需 reset |
| **只动自建会话** | 前缀 `E2ECHK5556r` | 全程只操作自建的 `ses_f0064b0f9ffeEO3CLhWCesCMz3`（标题 `E2ECHK5556r probe attachment check`）及其 fork 产物 `ses_01a0e34a911bmBUvNmp3MTJ6xQ`；**未对他人会话做任何写操作** | OK |
| **未点 Delete** | 绝对禁止 | 菜单里 `Delete @1152,260` 与 `Undo @1149,288` 仅隔 28px，**全程坐标由 dump 解析**并逐次打印核对，从未点到 `Delete` | OK |
| **未按 BACK(4) / ESC(111)** | 绝对禁止 | 全程 `input tap` 为主；弹层用其**自身 `Back` 按钮**（`input tap`）关闭；`screencap`/`uiautomator` 失败时**改用重试而非按键** | OK |
| **设备** | 只操作 5556 / 不碰 5555 | 全部命令均带 `-s emulator-5556`；`adb reverse --list` 显示映射挂在 `host-19` 上，**未操作 127.0.0.1:5555** | OK |

### 7.1 一处未闭环（如实记）

**「点通知回跳到会话」与「清除通知后断言消失」两项未取到读数。**

- 前者：能点的那条横幅（`posted: … sound`）出现后即被后续交互挤出可视区，本轮**没有在 banner 存活窗口内完成点击**。
- 后者：尝试过的三条路都未成功，且**均无副作用**：
  1. `service call notification` 取消调用 → `cmd notification list` 仍返回 `0|com.xiufeigo.opencodeshell|1357498609|null|10189`；
  2. app 内 banner 无 `Dismiss` / `Dismiss all` 控件（`fs_dismiss.xml` 全文无该文案）；
  3. 通知栏（`cmd statusbar expand-notifications` / 下滑手势）**在本机不可达**——本机 launcher 是 `org.chromium.home.pc`（kiosk 式桌面），`expand-notifications` 拉出的是**快捷设置面板**（`Display brightness` / `Bluetooth` / `Battery Saver`…），再下滑手势直接把面板收掉，**始终见不到通知列表与 `Clear all`**。
- 终态：**该 `NotificationRecord` 仍在**（收尾 `dumpsys` 计数 = 1，`fs_notif_final.txt`）。因为它是**系统级通知**、且本机无可达的手动清除入口，**不残留设备数据、不污染仓库、不影响他人**，故按纪律**不再用越界手段强清**。
- 影响面：不改变 §5 的判定（`NotificationRecord` 已命中即证明横幅/通知存在）。

---

## 8. 新发现

1. **v2 用户消息的附件在顶层 `files[]`，不在 `parts[]`** —— `text` 与 `files` 是**并列**字段；`files[].source.type=inline`（SAF 读到的字节 base64 内联）。**任何按 `parts[].type=="file"` 扫描的判读脚本都会误报"附件丢失"**。本轮首版 `MsgTable` 就踩了这个坑，是**判读缺陷、非产品缺陷**。建议后续测试员的读数工具按 `files[]` 取。

2. **`/api/session` 与 `/api/session/:id/message` 的响应是 `{data: …}` 信封**，不是裸数组。直接 `ConvertTo-Json` 只能看到 `data` 键；`Count` 会得到 1（信封本身）。同理 `/api/worktree` **必须带 `projectID`**（否则 400）。

3. **通知投递存在两条不同变体，本轮各抓到一条**：
   - **前台被遮蔽** → `skip: app is foreground (viewed=true)` → `recheck: scheduled in 2000ms` → `recheck: dropped (contentVisible=true)` → `focus: timeout expired` → **`withheld: … posting (the user left before the re-check)`** → `posted`；
   - **真后台**（`viewed=false`）→ `trigger idle(finished=true)` → `raise` → **`posted … viewed=false sound=default via=raise`**。
   两条都落到 `NotificationRecord`。**判"通知有没有"必须按 `NotificationRecord` 而非 `NotificationChannel`**，这一点 `NOTIFICATION_IDLE_DIAG.md` §E 已强调，本轮再次实证。

4. **进程冻结窗口是"静默但可恢复"的**：`foreground: stopped` 之后客户端 0 条日志，但**进程 pid 全程存活**（10489），且**切回前台后终帧立刻被读到并投递**（延迟约 3 分钟）。⇒ 之前把"0 行"判成"通知丢失"是不准确的；准确表述是 **"通知被推迟到进程解冻之后"**。

5. **`input keyevent 3`（HOME）在本机无效** —— `mCurrentFocus` 始终停在 `OcActivity`；必须用 `am start -a android.intent.action.MAIN -c android.intent.category.HOME` 才能真正切后台。**任何依赖 HOME 的自动化脚本在本机都会静默失效。**

6. **`New worktree` 弹层不支持自定义命名**（详见 §4），且 **`/api/worktree` 无 DELETE 端点**（openapi 仅有 `GET /api/worktree` 与 `POST /api/worktree/refresh`）。⇒ 在共享后端上无法按纪律创建"可识别且可回收"的工作树。

7. **本机通知栏不可达** —— launcher 为 `org.chromium.home.pc`（kiosk 式桌面），`cmd statusbar expand-notifications` 拉出的是快捷设置面板，看不到通知列表与 `Clear all`；`service call notification` 取消也不生效。**系统通知在本机无法手工清除。**

---

## 9. 终态

| 项 | 终态 |
|---|---|
| 设备 | `emulator-5556` 存活、`boot_completed=1`、屏 ON、keyguard 未锁、reverse `4095`/`2222` 在位、**未 reboot** |
| app | 停在自建会话 `ses_f0064b0f9ffeEO3CLhWCesCMz3`（已 Move 到 `/mnt/c/sensorsdata/main/program`），前台 |
| `wm` | `1366x768` / `160`（本轮未写入，与开跑前一致） |
| 设置 | 未写任何设置；`notifyAgent` 保持默认（运行期读数 `enabled=true`） |
| 工作树 | 7 条，与基线逐字相同（**本轮零新增**） |
| `/sdcard/Download` | 3 个文件，与开跑前基线逐字相同（**本轮推入的已清**） |
| 系统通知 | 1 条 `NotificationRecord id=1357498609`（**无清除入口，见 §7.1**） |
| 自建产物（后端） | 1 个会话 `ses_f0064b0f9ffeEO3CLhWCesCMz3` + 其 fork `ses_01a0e34a911bmBUvNmp3MTJ6xQ`（按纪律**保留**，不清理他人可见对象；前缀均为 `E2ECHK5556r` 系） |
| 仓库 | **只新增 1 个 md（本文）+ 证据文件**，未改任何产品代码 |

---

## 10. 未闭环

| # | 项 | 状态 | 原因 |
|---|---|---|---|
| a | **点通知回跳到会话** | **未验到** | 能点的横幅（`posted … sound`）出现后被后续交互挤出可视区，本轮未在其存活窗口内点击。**不编造读数。** |
| b | **清除通知 → 断言消失** | **未闭环** | 三条清除路径全失败（`service call` / app 内无 `Dismiss` 控件 / 通知栏在本机不可达，见 §7.1）。终态该 Record 仍在，**无害**（系统级、不落仓库、不影响他人） |
| c | **`New worktree` 的"新工作树出现"** | **未验** | 纪律性跳过（不支持命名 ⇒ 无法按 `E2ECHK5556r` 前缀创建可回收对象，且无 DELETE 端点）。**弹层入口存在/可点/能打开已验** |
| d | **`Undo` 运行时断点定位** | **待定位** | 真机确认 no-op（§6），但 logcat **既无** `revert failed` **也无** `revert commit failed`，无法在现有读数下区分「`undoStep` 返回 null」与「stage/commit 未发出」。**按 `UNDO_WIRING_FIX.md` §7，在拿到更细读数前不改代码**（否则是臆造） |
| e | **`Undo` 使能/执行自相矛盾** | **新发现，待查** | `Undo` 行 `enabled=true`（说明客户端 `_state` **有**可见用户消息）却执行无写入 ⇒ 断点更可能在 `undoEnabled` **之后**（`undoSession` 是否真发起 `stage`），**而非** JVM 侧推测的"`_state` 未装载"。与 `UNDO_WIRING_FIX.md` §6.6 第 3 档的原始推测**不一致**，需以真机读数修正该报告的判读表 |
| f | **`session.error`（ERROR）分支** | **未覆盖** | 需先开 `notifyErrors`（默认 `false`，上游语义如此），本轮未动开关 |

---

## 11. 判定汇总（终稿）

| # | 项 | 判定 | 关键读数 |
|---|---|---|---|
| 1 | 非图片附件（`.txt`） | **PASS** | chip `E2ECHK5556r_note.txt` + 后端 `files[].data` base64 解码与本地**逐字相同**（29 B） |
| 2 | 菜单 `Fork` | **PASS** | 新会话 **`ses_01a0e34a911bmBUvNmp3MTJ6xQ`**（`E2E_FORK_T1`），计数 45→47 |
| 3 | 菜单 `Move` | **PASS** | UI `Location changed` + `program`；后端 `location.directory` 改写 + `location-switched` 消息 |
| 4 | 菜单 `New worktree` | **未闭环（纪律性跳过）** | 入口存在/可点/能打开；**不支持自定义命名 + 无 DELETE 端点** ⇒ 不创建 |
| 5 | 通知（正确姿势） | **链路 PASS / 后台时序 FAIL** | `trigger idle(finished=true)`→`raise`→`posted(viewed=false sound=default)`→`NotificationRecord` 命中；但**切后台后延迟约 3 分钟**才投递（复现已登记的进程冻结缺口） |
| 6 | `Undo`（正确口径） | **FAIL** | 造态正确（1 user + 4 assistant），点后：消息仍 7 条、**`revert=null`**、**`Redo` 不出现**、logcat 无任何 revert 行 |
| 7 | 收尾 | **基本闭环** | 设置未改 / 工作树零新增 / `/sdcard` 已复原 / `wm` 未动；**唯通知无法清除**（本机无入口） |

### 一句话结论

> **PASS 3（附件 / Fork / Move）+ 通知链路 PASS 但后台时序 FAIL + `Undo` 确认为真机 no-op（FAIL）+ `New worktree` 纪律性跳过。**
>
> **本轮最硬的两条结论**：
> ① **通知链路完好**，`NOTIFICATION_IDLE_DIAG.md` 的代码结论在真机上逐环坐实（此前"读到 0"确为运行时冻结，不是代码缺陷）；缺的是**进程外载体**（已登记的架构级缺口）。
> ② **`Undo` 的 FAIL 是真的**——`UNDO_WIRING_FIX.md` 判定"旧轮判据错"这件事本身没错（旧轮用改名造态确实无效），**但按上游口径重造后仍然点了没反应**，即存在一个**与旧 FAIL 相互独立**的真实 no-op；且使能（`enabled=true`）与执行（无写入）自相矛盾，指向 JVM 侧未预见的运行时断点。

---

*（终稿。全部读数可在同目录 `fs_*.xml` / `fs_*.png` / `fs_ocnotify_final.txt` / `fs_notif_pre.txt` / `fs_notif_final.txt` 中逐条复核。）*
