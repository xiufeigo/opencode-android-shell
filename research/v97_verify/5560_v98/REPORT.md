# v98 真机复验报告 —— 冷启动恢复上次 session route/tab

- 复验对象：`research/v97_fix/LAST_ROUTE_RESTORE_FIX.md` 所述 v97 N-1 修复（`navRestoreSettled` 门控 + `NavRestore.toStore(settled, …)` 3 参重载 + `target()` 走 `sessionOrNull`）
- 设备：**emulator-5560 = Pixel_Tablet**（已核实）
- 报告目录：`research/v97_verify/5560_v98/`，证据在 `ev/`
- 测试纪律：未跑 gradle、未装机、未杀进程（仅对目标 app 用 `am force-stop`）、未碰 `127.0.0.1:5555`、未按 BACK(4)、截图全部 `screencap -p` + `adb pull`（未用 exec-out）、只操作自建会话 `E2ECHK5560v98A`

---

## 0. 环境读数

| 项 | 读数 | 判定 |
|---|---|---|
| `adb devices` | `emulator-5560 device … model:Pixel_Tablet` | 与目标一致 |
| `adb -s emulator-5560 emu avd name` | `Pixel_Tablet` | **与任务书要求一致**（serial↔AVD 确实会重排，本轮未踩） |
| `wm size` | `2560x1600` | — |
| `versionName` | `1.4.0` | 与任务书一致 |
| `versionCode` | `26` | — |
| `lastUpdateTime` | **`2026-10-02 11:02:50`** | **与任务书一致**，确认为含本轮修复的闸门构建 |
| `firstInstallTime` | `2026-09-20 06:39:39` | — |
| `adb reverse --list` | `host-16 tcp:4095 tcp:4095`、`host-16 tcp:2222 tcp:22` | 已挂 |
| 后端健康检查 `GET /api/info` | `HTTP=200` `{"version":"2.0.18","pid":260,…}` | 可用（Basic opencode:opencode） |

---

## 1. 建立可复现状态 —— **PASS**

### 1.1 自建会话

冷启动 app → Home → 顶栏 `+` → `创建会话` → 进入 session route（标签条出现 `Untitled ×`）→ 点标题改名。

会话 id / 标题（后续所有步骤均以此为锚）：

- `oc_last_session_id` = **`ses_f03b589d8ffet2m77yIzCNojLS`**
- 标题 = **`E2ECHK5560v98A`**（`E2ECHK5560v98` 前缀，符合共享后端命名纪律）

> 重命名第一次得到 `E2ECHK5560v98-t-eestore`（`input text` 丢字），已重命名一次修正为 `E2ECHK5560v98A`。过程证据：`ev/s3_rename.xml`、`ev/s4_renamed.xml`、`ev/s5_renamed2.xml`、`ev/s5_renamed2.png`。

### 1.2 落盘读数

```bash
adb -s emulator-5560 shell "run-as com.xiufeigo.opencodeshell cat /data/data/com.xiufeigo.opencodeshell/shared_prefs/oc_nav_state.xml"
```

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="oc_last_route">session</string>
    <string name="oc_last_session_id">ses_f03b589d8ffet2m77yIzCNojLS</string>
</map>
```

**判定 PASS** —— `oc_last_route=session` + 合法 id，与期望一致。
证据：`ev/step1_prefs.txt`

> 注：`run-as` 必须用**绝对路径**且不能拆成两个参数（`run-as $PKG cat shared_prefs/...` 会报 `run-as: unknown package: cat`）。

---

## 2. 主断言：冷启动恢复到 session route + tab 在 —— **PASS**

### 2.1 命令

```bash
adb -s emulator-5560 shell am force-stop com.xiufeigo.opencodeshell
adb -s emulator-5560 shell "run-as <pkg> cat <prefs>"      # 复核落盘仍在
adb -s emulator-5560 shell am start -n com.xiufeigo.opencodeshell/.OcActivity
# 连续 8 次 dump，间隔 ~1s
for i in 1..8 { adb -s emulator-5560 shell uiautomator dump /sdcard/A$i.xml
                adb -s emulator-5560 pull /sdcard/A$i.xml ev/A$i.xml }
```

### 2.2 落盘复核（force-stop 之后）

```xml
<map>
    <string name="oc_last_route">session</string>
    <string name="oc_last_session_id">ses_f03b589d8ffet2m77yIzCNojLS</string>
</map>
```
证据：`ev/step2_prefs_after_forcestop.txt`

### 2.3 8 棵树逐棵表

命中关键字在**整棵树的原始 XML**（不限 `@text`，因为 `关闭标签页` 是 content-desc 而不是 text）：

- `关闭标签页` = 标签条上**有 tab**（1 = 有）
- `E2ECHK5560v98A` = 我的会话在标签条/标题上出现
- `构建任何内容` = session 空态视图（session route 指纹）
- `今天`/`更早` = Home 的会话分组标题（Home 指纹）

| 树 | 命中关键字 | 判定 |
|---|---|---|
| A1 | **dump 未产出**（`ERROR: null root node returned by UiTestAutomationBridge`） | 冷启动竞态，见 §2.5 |
| A2 | `关闭标签页`=1、`E2ECHK5560v98A`=2、`构建任何内容`=1、`今天/更早`=0 | **session + 有 tab** |
| A3 | 同 A2 | session + 有 tab |
| A4 | 同 A2 | session + 有 tab |
| A5 | 同 A2 | session + 有 tab |
| A6 | 同 A2 | session + 有 tab |
| A7 | 同 A2 | session + 有 tab |
| A8 | 同 A2 | session + 有 tab |

**7/7 棵已捕获的树全部是 session route 且标签条有那个 tab；没有任何一棵出现过 Home 指纹（`今天`/`更早`=0）。**

截图 `ev/A2_session_restored.png`：顶栏标签条显示 `E2ECHK5560v98A ×`，会话 header 为 `E2ECHK5560v98A`，中间为 `构建任何内容` 空态 —— 即**冷启动后第一棵可见树就已经在 session route，且 tab 已重建**。

### 2.4 8 棵树之后复读落盘

```xml
<map>
    <string name="oc_last_route">session</string>
    <string name="oc_last_session_id">ses_f03b589d8ffet2m77yIzCNojLS</string>
</map>
```
证据：`ev/step2_prefs_after_trees.txt`

**判定 PASS** —— 仍是 `session` + **同一个 id**，没有被改写回 home。这正是本次修复要保住的那条不变量。

### 2.5 关于 A1 —— 诚实标注（不影响结论）

A1 与 D1 两次都在 `am start` 之后**立即** dump，uiautomator 报 `null root node returned by UiTestAutomationBridge`：此时窗口尚未建立，**uiautomator 无法取到任何树**。这是取树的竞态，**不是**观察到了一棵 Home 树。

因此"第一棵树起就在 session"这条断言的可证据化形式是：**最早一棵「能够取到」的树就已经是 session**，且**全程 14 棵已捕获树（A2–A8、D2–D8）中零棵 Home**。为了压掉这个不确定窗口，做了第二轮复现（§2.6），结论一致。

### 2.6 复现轮（`ev/D2.xml`–`ev/D8.xml`）

重新种同一个合法 id → force-stop → `am start` → 立即开始 8 连 dump：

| 树 | 命中关键字 | 判定 |
|---|---|---|
| D1 | dump 未产出（同 §2.5） | 竞态 |
| D2–D8 | `关闭标签页`=1、`E2ECHK5560v98A`=2、`构建任何内容`=1、`今天/更早`=0 | **7/7 session + 有 tab** |

结束后落盘仍为 `session` + `ses_f03b589d8ffet2m77yIzCNojLS`（`ev/step2b_prefs_after.txt`）。**两轮独立复现，结果一致。**

---

## 3. 对照实验（落盘改 `home`）—— **PASS，且与第 2 步可区分**

### 3.1 造状态

force-stop 后重写 prefs（`run-as` + `cat >`，单行 XML 规避 CRLF 污染）：

```xml
<map><string name="oc_last_route">home</string><string name="oc_last_session_id">ses_f03b589d8ffet2m77yIzCNojLS</string></map>
```
证据：`ev/step3_prefs_seeded_home.txt`（注意 session id 键**故意保留**，确保差异只来自 `oc_last_route` 这一个变量）

### 3.2 8 棵树

| 树 | `关闭标签页`(tab) | `E2ECHK5560v98A` | `构建任何内容`(session) | `今天/更早`(Home) | 判定 |
|---|---|---|---|---|---|
| B1 | dump 未产出 | — | — | — | 竞态 |
| B2 | **0** | 1 | **0** | **1** | **Home** |
| B3 | 0 | 1 | 0 | 1 | Home |
| B4 | 0 | 1 | 0 | 1 | Home |
| B5 | 0 | 1 | 0 | 1 | Home |
| B6 | 0 | 1 | 0 | 1 | Home |
| B7 | 0 | 1 | 0 | 1 | Home |
| B8 | 0 | 1 | 0 | 1 | Home |

**7/7 全 Home、无 tab。** 截图 `ev/B8_home_control.png` 可见项目栏 + 会话分组列表，标签条为空。

> B 树里 `E2ECHK5560v98A` 命中 1 次是**正常的**：那是 Home 会话列表里我自己那一行，不是标签条上的 tab。区分靠 `关闭标签页`=0 与 `构建任何内容`=0。

结束后落盘保持 `oc_last_route=home`（`ev/step3_prefs_after.txt`），未被改写。

### 3.3 可区分性判定

| 磁盘 `oc_last_route` | 已捕获树数 | session 指纹 | tab | Home 指纹 | 结论 |
|---|---|---|---|---|---|
| `session`（A/D 轮） | 14 | 14/14 | **14/14 有** | 0/14 | 恢复到 session |
| `home`（B 轮） | 7 | 0/7 | **0/7 有** | 7/7 | 停在 Home |

**两组表现完全可区分 → 修复生效**（若门控失效/代码没装上，两组应同为 Home，正如缺陷报告 v97b 的指纹）。

---

## 4. dead id 优雅回落 —— **PASS**

### 4.1 造状态

```xml
<map><string name="oc_last_route">session</string><string name="oc_last_session_id">ses_v98deadbeefdeadbeefdeadbeef</string></map>
```
证据：`ev/step4_prefs_seeded_dead.txt`

### 4.2 8 棵树

| 树 | `关闭标签页`(tab) | `构建任何内容`(session) | `今天/更早`(Home) | 判定 |
|---|---|---|---|---|
| C1 | dump 未产出 | — | — | 竞态 |
| C2–C8 | **0** | **0** | **1** | **7/7 全 Home、无 tab** |

截图 `ev/C8_dead_fallback.png`：干净的 Home（项目栏 + 会话列表），**标签条为空**。

### 4.3 崩溃检查

```bash
adb -s emulator-5560 logcat -d -t 400 -v time | Select-String -Pattern 'FATAL|IllegalArgumentException|destination'
```

- `FATAL EXCEPTION` / `IllegalArgumentException` / `Navigation destination` / `ANR in` → **`NO_MATCH`**
- 放宽到只搜 `FATAL` → **`NO_FATAL`**
- 进程存活：`pidof com.xiufeigo.opencodeshell` = **`12191`**
- 原始日志留存：`ev/step4_logcat_tail400.txt`（401 行）

> 说明：未执行 `logcat -c` 清缓冲（会影响到同机其他 agent 的取证），改为取尾部 400 行后按模式过滤；`ANR`/`FATAL` 模式覆盖了任务书要求的全部关键字。

### 4.4 落盘收敛

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="oc_last_route">home</string>
</map>
```
证据：`ev/step4_prefs_after.txt`

`oc_last_session_id` 键**已被移除** —— 这正是 `toStore("home", null)` 的行为（`putString(KEY_SESSION_ID, null)` 删除该键）。落盘**收敛为 home**，与报告 B 方案期望一致，且没有每帧重写/无死循环（8 棵树的 Home 树字节数恒为 52104，无抖动）。

---

## 5. 总表

| 步骤 | 断言 | 判定 |
|---|---|---|
| 0 | emulator-5560 = Pixel_Tablet | PASS |
| 0 | versionName=1.4.0 / lastUpdateTime=2026-10-02 11:02:50 | PASS |
| 0 | 后端 `/api/info` 200、reverse 4095/2222 已挂 | PASS |
| 1 | 自建会话落盘 = `session` + 合法 id | **PASS** |
| 2 | 冷启动恢复到 session route | **PASS**（14/14 已捕获树） |
| 2 | 标签条有那个 tab | **PASS**（`关闭标签页` 14/14 命中） |
| 2 | 8 棵树后落盘仍 `session` + 同一 id | **PASS** |
| 3 | 对照组（落盘 `home`）8 棵全 Home | **PASS**（7/7） |
| 3 | 两组**可区分** | **PASS** |
| 4 | dead id 不崩 | **PASS**（无 FATAL/ANR/IllegalArgumentException，进程 12191 存活） |
| 4 | dead id 8 棵全 Home、无 tab | **PASS**（7/7） |
| 4 | 落盘收敛为 home | **PASS** |

---

## 6. 未闭环 / 未验到

1. **「第一棵树」的严格形式未验到**。`am start` 之后 uiautomator 立即 dump 必然撞上窗口未建立的竞态（A1/D1 两次均 `null root node`），因此**无法**在真机上证明"第 0 帧就已经是 session"。已能证明的最强命题是：**最早一棵可取的树即是 session，且全部 14 棵可取树中 0 棵 Home**。修复报告 §A 断言 2 原本就允许"`r1` 可能是 Home、`r2` 起必须是 session"，本次实测比它更强（`r2` 已经是 session）。
2. **未做 logcat 打点测量首帧交错**（写回 vs 读回的毫秒差）。门控把两个半边在**逻辑上**上解耦，实机行为已证明与交错无关；但具体交错量仍未测。**未验到。**
3. **只覆盖"落盘值"这三条路径**。报告 §C 的其余条目（会话内新建/重命名/切 tab/关 tab 行为不变、restore prune 语义）本轮**未复验**，属**未验到** —— 任务书只要求 A/B/C 中的第 1–4 步。
4. **`oc_last_session_id` 键在 dead id 场景被删除**这一副作用本报告只做了观察、未判定是否符合产品预期（与报告期望"落盘收敛为 home"一致，但键消失是额外读数）。
5. **未复现"同一 session id 连续 3 次以上冷启动"**（本轮每种状态各跑 2 轮 / 1 轮），未验证长期稳定性。
6. 未跑任何 JVM 单测（任务书禁止 gradle），本报告全部读数来自真机。

---

## 7. 证据文件索引（相对 `research/v97_verify/5560_v98/`）

| 路径 | 内容 |
|---|---|
| `ev/step1_prefs.txt` | 第 1 步落盘读数（session + id） |
| `ev/step2_prefs_after_forcestop.txt` | force-stop 后落盘复核 |
| `ev/step2_prefs_after_trees.txt` | 8 棵树后落盘复核（仍 session + 同 id） |
| `ev/step2b_prefs_after.txt` | 复现轮 8 棵树后落盘复核 |
| `ev/A2.xml` … `ev/A8.xml` | 第 2 步 7 棵 session 树 |
| `ev/A2_session_restored.png` | 冷启动恢复到 session + tab 的截图 |
| `ev/D2.xml` … `ev/D8.xml` | 第 2 步复现轮 7 棵 session 树 |
| `ev/step3_prefs_seeded_home.txt` | 对照组落盘改 home 的读数 |
| `ev/step3_prefs_after.txt` | 对照组 8 棵树后落盘（保持 home） |
| `ev/B2.xml` … `ev/B8.xml` | 对照组 7 棵 Home 树 |
| `ev/B8_home_control.png` | 对照组 Home 截图 |
| `ev/step4_prefs_seeded_dead.txt` | dead id 种入读数 |
| `ev/step4_prefs_after.txt` | dead id 冷启动后落盘（收敛 home） |
| `ev/C2.xml` … `ev/C8.xml` | dead id 7 棵 Home 树 |
| `ev/C8_dead_fallback.png` | dead id 回落截图 |
| `ev/step4_logcat_tail400.txt` | logcat 尾部 401 行原始日志 |
| `ev/s0_start.xml`、`ev/s0_home.png` | 起始 Home 树与截图 |
| `ev/s1_newsession.*`、`ev/s2_session.*` | 新建会话流程 |
| `ev/s3_rename.xml`、`ev/s4_renamed.xml`、`ev/s5_renamed2.*` | 会话改名过程 |
