# v97 Team Brief — OpenCode Android Shell（Lead 维护，2026-10-02）

> 本文件是所有 teammate 的公共事实源。**先整篇读完再动手**。冲突时以本文件 + `research/HANDOFF_PROMPT.md` 为准。

## 0. 目标（用户在跑什么）

把 `C:\sensorsdata\main\program\opencode-android-shell` 推进到与上游 https://github.com/anomalyco/opencode/tree/v2
**功能一致 / 界面一致 / 接入一致**，并对照 https://opencode.ai/v2/docs/api 补齐 API 面。**只接 V2 后端**（`opencode serve 2.0.18`，不做 V1 兼容）。

## 1. 硬规则（违反=事故）

1. **Gradle 全量闸门只归 Lead**。自旋锁文件 `.agent-gradle-lock`（存在=有全量在跑）。子 agent **只允许** `.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "<精确类名>"`。
   **禁通配、禁全量、禁 assembleDebug、禁 clean、禁装机**（装机由 Lead 或被 Lead 点名的测试员做）。
   抢锁前先看锁；拿到就跑；跑完 `Remove-Item .agent-gradle-lock`（如被拦就用 `[System.IO.File]::Delete`）。
2. `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`，命令用 `.\gradlew.bat`（PowerShell 不能直接跑 `./gradlew`）。
3. `_upstream/` **只读**。改动范围只限 `app/`、`frontend/`、`scripts/`、`research/`。
4. **别按 BACK**（`keyevent 4`）收键盘 → 用 `keyevent 61`(TAB) 或 `keyevent 111`(ESC，设置弹层内禁用)。
5. **禁 `taskkill` 杀 qemu**；禁 bash 后台任务起模拟器（会被连杀子进程）。用：
   `Start-Process "C:\Users\xiufe\AppData\Local\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd <AVD> -no-snapshot-save -no-boot-anim" -WindowStyle Minimized`
6. 用设备前**先核身份**：`adb -s <serial> emu avd name`（重启后 serial↔AVD 会重排，serial 惯性不可信）。
7. **文件所有权不相交**：只改自己任务书写明的文件；要改别人的文件先在群里问 Lead。
8. **不新增用户可见字符串**；必须加就三语同步（`values/`、`values-zh-rCN/`、`values-zh-rTW/` 的 `strings.xml`），`StringsLocaleParityTest` 会把关。
9. 修复必须**补/更新 JVM 单测 pin**；过时 pin 才更新，且**不得降低断言强度**。
10. **不要碰 127.0.0.1:5555**（不是我们的设备）。

## 2. 环境事实

| 项 | 值 |
|---|---|
| 仓库 | `C:\sensorsdata\main\program\opencode-android-shell` |
| 包名 | `com.xiufeigo.opencodeshell` |
| launcher | `.OcActivity`（`am start -n <pkg>/.OcActivity`；MainActivity 不 exported） |
| adb | `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| emulator | `C:\Users\xiufe\AppData\Local\Android\Sdk\emulator\emulator.exe` |
| AVD 池 | Pixel_9a、Pixel_10_Pro_XL、Pixel_10_Pro_Fold、Pixel_Tablet、Small_Desktop、Medium_Desktop |
| 后端 | `http://127.0.0.1:4095` Basic `opencode:opencode`（v2.0.18，WSL Arch） |
| 后端健康检查 | **只用 `GET /api/info`**（`/api/health` 在 2.0.18 上是 404；其他路径回落 SPA 的 HTML） |
| 后端 API 根 | `/api/*`（`/api/session`、`/api/config`、`/api/project`…），OpenAPI：`GET /openapi.json` |
| SSH 测试账号 | `sshtest/opencode`，`ssh -p 2222 sshtest@127.0.0.1`（**`host:port` 形式不支持**，写整条 ssh 命令） |
| 测试私钥 | repo：`research/v95_verify/id_ed25519`，passphrase `keypass123` |
| 后端重启（如挂了） | 见 `research/env-serve-prompt-wedge.md` §5.2；带 `OPENCODE_SERVER_PASSWORD=opencode` 起 |

### 模拟器使用纪律
- 装机：`adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk`
- 装完补 reverse：`adb -s <serial> reverse tcp:4095 tcp:4095`（SSH 另加 `reverse tcp:2222 tcp:22`）
- 截图：`adb -s <serial> shell screencap -p /sdcard/x.png` + `adb -s <serial> pull /sdcard/x.png`（**别用 exec-out**，会被 adb warning 污染）
- 输入：`input text` 空格不可靠 → 用 `%s` 或逐字符 `keyevent`；≤8 字符分段、段间 `Start-Sleep -Milliseconds 1500`
- 桌面模拟器要软键盘：`adb -s <serial> shell settings put secure show_ime_with_hard_keyboard 1`
- 窄窗：`wm size 560x768` **必须配** `wm density`；验完 `wm size reset`
- Gboard 中文会把 `.` 打成 `。` → 输 host/IP 前切英文

### 测试员纪律
- 共享后端上**只动自己建的会话**，标题必须带设备前缀（如 `E2ECHK5560`），防串台改名。
- 有证据才下结论：每条断言给出「命令 + 截图/XML/dump 路径 + 读数」。**没验到就写未验到**，不许猜。
- 报告写进 `research/v97_verify/<serial>/REPORT.md`（或自己任务指定的目录），**含证据文件相对路径**。

## 3. 模型与推理强度（用户指令）

- 用户要求全部子 agent 用 `minimax-cn/MiniMax-M3.1-Flash-Preview`。**本 session 该 provider 被 harness 拒绝**（`not allowed for this Session`），Lead 已实测两次。
- 替代口径（Lead 决定，报告里注明）：
  - **测试/侦察**：`sensorsdata/glm-5.3-flash`，推理强度 **default**
  - **修复**：`sensorsdata/glm-5.3`，推理强度 **max**
- teammate 的模型由 Lead 在 spawn 时指定；子 subagent 也用同一口径。

## 4. 当前基线（Lead 正在跑全量闸门）

- 上一轮基线：**611 suites / 5522 tests / 0 failures / 0 errors / 0 skipped**；`:app:assembleDebug` SUCCESS。
- 本轮 F14a（SSH key 文件错误归因）**代码已落**：`SshConnectionModel.kt` 新增 `SshErrorCode.KEY_FILE` 分支 + 三语文案 + `SshKeyFileFailureF14Test.kt`。
- 本轮 F14b（jsch ed25519 疑缺 `net.i2p.crypto:eddsa`）**未落**：`app/build.gradle.kts:86` 仍只有 `com.github.mwiede:jsch:0.2.24`，无 `SshEd25519IdentityF14Test.kt`。

## 5. 关键文件索引

| 用途 | 路径 |
|---|---|
| 主交接（v57→v96 全量背景 + 有意偏离表，**不许改回的清单在里面**） | `research/HANDOFF.md` |
| 上一轮交接 prompt（含 F14 来龙去脉、踩坑库） | `research/HANDOFF_PROMPT.md` |
| SSH 连接核心 | `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt` |
| SSH 状态机 + 错误归因 | `.../ui/servers/SshConnectionModel.kt` |
| SSH 表单弹层 | `.../ui/servers/SshDialog.kt` |
| 行级 prompt 出口 | `.../ui/servers/SshPromptOffer.kt` |
| 面板渲染 SSH 卡 | `.../ui/settings/ServersPanel.kt` |
| 依赖表 | `app/build.gradle.kts` |
| 上游锚（只读） | `_upstream/packages/app/src/**`、`_upstream/packages/desktop/src/**` |
| JVM 单测 | `app/src/test/java/com/xiufeigo/opencodeshell/**` |
| v96 复验报告（格式参考） | `research/v96_verify/5560/REPORT.md` |

## 6. 沟通协议

- 完成后回一条**结构化**结论给 Lead：`状态（DONE/BLOCKED/FAIL）` + 改了哪些文件 + 跑了什么命令与结果 + 证据路径 + 未闭环项。
- 中途卡住超 10 分钟无进展 → 立刻回报，不要硬耗。
- 不许擅自扩权：发现别的 bug → 写进报告的「新发现」段，别顺手改。
