# v29 tA fix-regress E2E 验收报告

**设备**: emulator-5554 (Pixel_10_Pro_Fold)
**APK**: app/build/outputs/apk/debug/app-debug.apk (已通过 harness install 安装)
**后端**: http://127.0.0.1:4095 (opencode v2.0.18)
**日期**: 2026-09-27

---

## 1. 冷启动不崩 (P0) — PASS

带服务器配置冷启动 app 两次 (force-stop → monkey LAUNCHER)，均成功进入主界面。

- 第 1 次：进程 pid 4465，主界面渲染项目 / 最近关闭 / 会话列表。
- 第 2 次：进程 pid 4759，主界面同上。
- logcat 全程无 `FATAL EXCEPTION` / `AndroidRuntime: E`（仅在通知权限弹窗出现时屏幕自然处于「应用启动后」状态，非崩溃）。

证据：
- `v29_tA_01_initial.png` — 通知权限弹窗（首次启动系统提示，非应用闪退）
- `v29_tA_02_aft_notify.png` — 通知权限通过后主界面
- `v29_tA_cold1.png`, `v29_tA_cold2.png` — 两次冷启动后的 Home 视图
- `v29_tA_cold2.xml` — 第 2 次冷启动 UI dump（验证非崩溃，元素齐全：项目、设置、帮助等）

---

## 2. Home 会话行时间格 — PASS

t0 创建了 `E2ECHK timecheck` 会话；65 秒后从 Home 复看，时间由「刚刚」推进为「1 分钟前」。

证据：
- `v29_tA_home_t0.xml` + `v29_tA_home_t0.png` — t0：E2ECHK timecheck 显示「刚刚」
- `v29_tA_home_t1.xml` + `v29_tA_home_t1.png` — t1 (+65s)：显示「1 分钟前」

格式样例（其他会话）：
- 「18 小时前」、「17 小时前」—— 均为「数字 + 单位 + 前」格式
- 没有任何「N/A」「null」「Invalid date」等异常文本

---

## 3. attention 点三场景 — PASS

### 3a. 会话内 dock 弹起时 — PASS

1. 用 `curl POST /api/session` 创建带 `permissions:[{action:shell,resource:*,effect:ask}]` 的 `E2ECHK perm-a`
2. 用 `curl POST /api/session/{id}/prompt` 让模型跑 `echo HELLO_SHELL_ASK`
3. 在 app 内打开该会话，dock 弹出「需要权限 / 拒绝 / 始终允许 / 允许一次」（证据：`v29_tA_t3a_session.png`）
4. 切回 Home 后观察：
   - Home 行 `E2ECHK perm-a` 的 V 头像右上角出现 6dp 蓝点 ✓
   - Tab 条 `E2ECHK perm-a ×` 左侧出现 6dp 蓝点 ✓

证据：
- `v29_tA_t3a_home_dock.png` — Home 视图，E2ECHK perm-a 行头像右上角蓝点亮
- `v29_tA_t3a_session.png` — 进入会话时 dock 弹出
- Tab 条蓝点截取自 `v29_tA_t3a_home_dock.png` 顶部

### 3b. 外部会话挂起（app 不在该会话）— PASS

1. 在 app 仍处于 perm-a 会话 tab 的情况下（已答 Allow once，perm-a 已熄灭）
2. 用 curl 创建 `E2ECHK perm-b`（同样 shell ask）并 prompt 让模型跑 `echo HELLO_PEND`
3. 切到 Home，E2ECHK perm-b 行的 V 头像右上角出现 6dp 蓝点 ✓

证据：
- `v29_tA_t3b_pend_check.png` — Home 视图，E2ECHK perm-b 行头像右上角蓝点亮
- `v29_tA_t3b_dump.xml` — UI dump 中含 `content-desc="有未读"` 标记 [115,822][130,837]，对应 perm-b 行头像右上角

### 3c. 答复（Allow once）后熄灭 — PASS

点击 dock 「允许一次」后：
- 模型回应正常「Shell 工具正常工作，输出为 HELLO_SHELL_ASK」
- 切回 Home，E2ECHK perm-a 行头像右上角蓝点**消失**
- Tab 条 `E2ECHK perm-a` 左侧蓝点**消失**

证据：
- `v29_tA_t3c_home_after2.png` — Home 视图，E2ECHK perm-a 行已无蓝点；后续只剩 E2ECHK perm-b 行的蓝点（属 3b 场景）
- dump 验证：`v29_tA_t3b_dump.xml` 中 perm-a 行无 `有未读` 标记

---

## 4. 模型不掉包 — PASS

1. 用 `curl POST /api/session` 创建 `E2ECHK modelcheck`，指定 `model:{providerID:opencode-go, id:space-bunny-free}`
2. 在 app 中打开该会话，composer 芯片显示「Space Bunny Free」✓（不是 LongCat）
3. 通过 API 发送 prompt `Reply with exactly: OK`
4. 重新 `GET /api/session/{id}`：
   - `model.id == "space-bunny-free"` ✓
   - `model.providerID == "opencode-go"` ✓
5. 在 app 中再次观察 composer 芯片，仍显示「Space Bunny Free」✓

证据：
- API 创建后立即读：`{"id":"space-bunny-free","providerID":"opencode-go","variant":"default"}`
- `v29_tA_t4_session.png` — 打开会话后 composer 芯片显示「Space Bunny Free」
- 发送 prompt 后 API 读：`model= {'id': 'space-bunny-free', 'providerID': 'opencode-go', 'variant': 'default'}`
- `v29_tA_t4_after_prompt.png` — 回复后 composer 仍显示「Space Bunny Free」

---

## 5. 中文残留回归 — PASS

切到简体中文（设置 → 通用 → 语言已是「简体中文」），完整查看「通用」页：

| 上轮残留 | 当前翻译 | 状态 |
|---|---|---|
| Default environment | 默认环境 | ✓ |
| (subtitle) Choose where new sessions start | 选择新会话的启动位置 | ✓ |
| Model reasoning | 模型推理 | ✓ |
| (subtitle) Choose how model reasoning is displayed in timeline | 选择模型推理在时间线中的显示方式 | ✓ |

通用页可见项全部中文化（已截图覆盖首屏、中段、底段）：
- 语言、自动接受权限、显示智能体、终端位置、后续消息行为、默认环境、模型推理、自动换行、时间线详情、显示推理摘要、展开 Shell 工具部分、展开编辑工具部分、文件树 等
- 子标题文案：「选择新会话的启动位置」「选择模型推理在时间线中的显示方式」「在时间线中显示模型推理摘要」「在时间线中默认展开 Shell 工具部分」「在时间线中默认展开编辑、写入和补丁工具部分」「在会话中显示文件树面板」等

按要求「Shell」术语保留英文：
- 左栏菜单项「Shell」保留
- 正文「展开 Shell 工具部分」「Shell 工具」中的 Shell 保留

证据：
- `v29_tA_t5_settings.png` — 通用页首屏（语言、自动接受权限、显示智能体、终端位置、后续消息行为、默认环境）
- `v29_tA_t5b_settings.png` — 中段（默认环境、模型推理、自动换行、时间线开始）
- `v29_tA_t5c.png` — 中段继续（时间线详情、显示推理摘要、展开 Shell 工具部分、展开编辑工具部分、文件树）
- `v29_tA_t5d.png` — 时间线全部条目
- `v29_tA_t5e.png` — 高级 → 文件树
- `v29_tA_t5_models.png` — 通知页（顺便核对，也是全中文）
- `v29_tA_t5_models_page.png` — 项目页（顺带核对，全中文）
- `v29_tA_t5.xml` — 设置首屏 UI dump

---

## 收尾

- `curl -u opencode:opencode -X DELETE /api/session/<id>` 清掉全部 8 个 E2ECHK 会话（modelcheck、perm-a、perm-b、timecheck 及 4 个 agent selector 子会话），全部 204。
- `adb -s emulator-5554 emu kill` → 「OK: killing emulator, bye bye」
- `taskkill //F //IM crashpad_handler.exe` → 3 个成功 + 1 个「拒绝访问」与说明一致

剩余会话（50 条）不含 E2ECHK 标题：

```
Total sessions: 50
E2ECHK: 0
```

---

## 截图归档

`research/v29_verify/tA_fixregress/`：
- 冷启动：`v29_tA_01_initial.png`、`v29_tA_02_aft_notify.png`、`v29_tA_cold1.png`、`v29_tA_cold2.png` + `v29_tA_cold2.xml`
- 时间格：`v29_tA_home_t0.png/.xml`、`v29_tA_home_t1.png/.xml`
- attention：`v29_tA_t3a_pre.png/.xml`、`v29_tA_t3a_session.png`、`v29_tA_t3a_home_dock.png`、`v29_tA_t3b_pend.png`、`v29_tA_t3b_pend_check.png` + `v29_tA_t3b_dump.xml`、`v29_tA_t3c_home_after.png`、`v29_tA_t3c_home_after2.png`
- 模型：`v29_tA_t4_session.png`、`v29_tA_t4_after_prompt.png`、`v29_tA_t4b.xml`、`v29_tA_t4c.xml`
- 中文：`v29_tA_t5.xml`、`v29_tA_t5_settings.png`、`v29_tA_t5b_settings.png`、`v29_tA_t5c.png`、`v29_tA_t5d.png`、`v29_tA_t5e.png`、`v29_tA_t5_models.png`、`v29_tA_t5_models_page.png`、`v29_tA_t5_close.png`、`v29_tA_t5_closed.png`、`v29_tA_t5b.xml`

---

## 总结

| 项 | 结果 |
|---|---|
| 1 冷启动不崩 (P0) | **PASS** |
| 2 Home 时间格 | **PASS** |
| 3a attention 内部 dock | **PASS** |
| 3b attention 外部挂起 | **PASS** |
| 3c attention 答 Allow once 后熄灭 | **PASS** |
| 4 模型不掉包 | **PASS** |
| 5 中文残留回归 | **PASS** |

全部 PASS，无新 bug。