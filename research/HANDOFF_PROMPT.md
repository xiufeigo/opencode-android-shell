# 交接 Prompt — OpenCode Android Shell 循环修复（2026-10-02 16:40 快照）

> 用法：把本文件**整段**贴给接手 agent。它自包含（含目标、纪律、环境、当前状态、待办、口径），不需要读对话历史。接手后第一件事：读 `research/HANDOFF.md` 全文（更早的 v57→v95 背景在那里），再读本文件。

---

## 0. 你是谁 / 在干什么

你是 OpenCode Android 壳工程的**主 agent**，负责持续把工程推进到与上游 https://github.com/anomalyco/opencode/tree/v2 的**功能、界面、后端接入三线一致**，并对照 https://opencode.ai/v2/docs/api 补齐 API 面。

- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 包名：`com.xiufeigo.opencodeshell`
- **只接 V2 后端**：`opencode serve 2.0.18` @ `http://127.0.0.1:4095`，Basic `opencode:opencode`。**不做 V1 兼容。**
- `_upstream/` 目录**只读**（上游源码锚点用）。
- 改动范围只限 `app/`、`frontend/`、`scripts/`、`research/`。

## 1. 完成判据（四条全绿才算这一轮收工）

1. **统一闸门**：`./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL，failures=0 / errors=0 / skipped=0，且 **tests/suites 只增不减**（当前基线见 §4）。
2. **上游对照无「未实现」项**：有意偏离必须以 `research/HANDOFF.md` 的终审表为准，**不许改回**。
3. **模拟器全功能真机复测通过**，新 bug 清零或逐条「不修 + 理由」。
4. **HANDOFF.md 残留队列清空**或逐条结案。

## 2. 铁律（血泪教训，违反过两次以上）

### 环境类
- **模拟器绝不能用 bash 后台任务启动**。bash 任务的 `maxRunMs=3600000` 到点会**连杀子进程**，已造成**两次全舰队覆灭**。必须用 `Start-Process` 分离启动：
  ```powershell
  Start-Process "C:/Users/xiufe/AppData/Local/Android/Sdk/emulator/emulator.exe" -ArgumentList "-avd Pixel_9a -no-snapshot-save -no-boot-anim"
  ```
- **装机 / 派单一律先核 AVD 身份**：`adb -s <serial> emu avd name`。重启后 serial↔AVD 按启动顺序**重排**，serial 惯性不可信（已造成装错机 + 派单错机各一次）。
- 后端恢复必须带密码契约：`OPENCODE_SERVER_PASSWORD=opencode setsid nohup ~/.opencode/bin/opencode serve --hostname 127.0.0.1 --port 4095`（**裸起会生成随机密码，破坏契约**）。脚本在 `/tmp/wedge/start_serve.sh`。
- WSL VM 闲退会带走 systemd 服务（serve 常驻后缓解）；sshd 曾被闲退带走 → `wsl -d Arch -e bash -lc "sudo systemctl start sshd"`。
- 禁 `taskkill` 杀 qemu。禁按 BACK（`keyevent 4`）收键盘。
- `127.0.0.1:5555` 不是我们的设备，勿混入。

### Agent 类
- **你派出的每个 agent / 后台任务都必须主动盯梢**。出现卡住、异常停止、长时间无进展 → **立即 stop + 重派或重试，并说明原因**，不许只被动等完成通知。盯梢方式 = 定期查 in-flight 状态 + 目标环境/设备存活 + 产出物更新时间。
- 盯梢用 `cron_create`（周期任务，绑定当前对话）：`session.mode=sessionId` 时**不要传 agent_name**（会报 validation 错）。收口后 `cron_delete` 删掉，不留后台噪声。手动触发（Run Now）投递可能被 `CRON_DELIVERY_PRIORITY_BLOCKED` 挡下，**周期排程投递正常**，别被单次失败误导。
- **并发上限 4**（第 5 路要等槽位）。
- 修复 agent **文件所有权不相交**；每修复必须补 JVM 单测 pin（**过时 pin 才更新，且要保强度**）；**不新增用户可见字符串**（要加就三语同步，`StringsLocaleParityTest` 把关）。

### Gradle 类
- `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- **全量闸门只归主 agent**，自旋锁 `.agent-gradle-lock`（Windows 上释放要用 `rm -- "<path>"`，走 mavis-trash；`Remove-Item` 会被安全策略拦）。
- 子 agent 只跑精确 `--tests <类名>`，**禁通配 / 禁全量 / 禁 assembleDebug / 禁装机 / 禁杀进程**。

### 测试员类
- 只操作自己那台设备；会话/证据带 `E2ECHK<serial>` 前缀。
- 以 `shell screencap -p /sdcard/x.png` + `pull` 为准（`exec-out` 偶被 adb warning 污染）；重定向用 `cmd /c "adb ... > file"`。
- `input text` 空格不可靠 → 用 `keyevent 62`；≤8 字符分段、段间 sleep 1.5。
- launcher 是 `.OcActivity`。

## 3. 环境现状（2026-10-02 16:40 核实）

| 项 | 状态 |
|---|---|
| 舰队 | 五台全在线，**映射每次重启必重排，用前 `emu avd name` 核**：5554=Pixel_Tablet、5556=Small_Desktop、5558=Medium_Desktop、**5560=Pixel_9a**、5562=Pixel_10_Pro_Fold |
| reverse | 五台 4095/4096；**2222→22 在 5560 和 5556** |
| 后端 | 4095 HTTP 200（`{"version":"2.0.18","pid":260}`），setsid 常驻 |
| sshd | active，测试账号 `sshtest/opencode`，三认证全开（含 `KbdInteractiveAuthentication yes` drop-in `/etc/ssh/sshd_config.d/10-v94-kbdint.conf`），公钥已装 `authorized_keys` |
| faillock | 干净（连错密码会锁号：`wsl -d Arch -e bash -lc "sudo faillock --user sshtest --reset"`） |
| 测试私钥 | repo 副本 `research/v95_verify/id_ed25519`，passphrase `keypass123`（openssh-key-v1 加密 ed25519）；设备副本 `/sdcard/v95/id_ed25519`（**属主 u0_a218，app 读不了**，见 §5 F13a）+ `files/id_ed25519`（app 私有目录，v96 已放置可读） |
| 设备装机 | 5560 上是 F13 构建（15:13 装的） |

## 4. 进度基线

- **统一闸门：611 suites / 5522 tests / 0 failures / 0 errors / 0 skipped**（assembleDebug SUCCESS）。
- 修复与测试全走 `minimax/MiniMax-M3.1-Flash-Preview` + 推理 **max** + 多路并发（测试 5 路 / 修复 4 路）。
- v93–v96 四轮复验全部有报告，证据在 `research/v9{3,4,5,6}_verify/<serial>/`。
- SSH 域已销账项：F8（删除入口）、F9（键盘 footer）、F10（持久化+级联删）、F11（450dp 堆叠）、F12a（passphrase 留存）、F12b（行级 prompt 出口）、F13a（IME Next 路由）、F13b（dispose 自拆）。

## 5. 当前在途（接手时先做这个）

### ⚠️ 两路 F14 worker 正在跑，**别重复派单、别抢它们的文件**
| Task ID | 干什么 | 拥有的文件 |
|---|---|---|
| `bg_916a4fe8-7fcd-40e4-8982-032799094209` | **F14a** 错误归因假话修复 | `SshConnectionModel.kt`、`SshModel.kt`、三语 `strings.xml`、`SshKeyFileFailureF14Test.kt` |
| `bg_e5f833b7-154a-4288-9200-0250b2f8ecfc` | **F14b** jsch ed25519 eddsa 依赖验证+修复 | `app/build.gradle.kts`、`SshEd25519IdentityF14Test.kt` |

盯梢 cron 已挂：**`9b118d79-4270-4f14-a602-5d344b2883d4`**（每 20 分钟，Asia/Shanghai）。两路收口后删掉它。

**接手第一步**：`task_query(status=running)` 确认状态 → 读各自 `task_output` → 收口。

### F14 两项的来龙去脉（必须理解再动手）

**F14a — 错误归因假话**：`SshConnectionModel.kt:156-159` 的 `sshFailure()` 把 `filenotfound` / `no such file` / `privatekey` 一律归成 `SshErrorCode.INPUT`，面板于是显示 **target 文案**「Enter a host or SSH connection command…」，但真实原因常是**私钥文件不可读**。v95、v96 两轮测试员都被这句假话带偏，误判成「target 丢失的 app 缺陷」。修法 = 新增 key 文件专属错误码 + 三语文案（有意偏离上游：上游用系统 ssh 报 stderr 全文，Android jsch 只有异常 message）+ pin。

**F14b — jsch ed25519 公钥不被 offer**：真机实测，私钥条目（key 在 app 私有目录、passphrase 正确）**发起了连接**（sshd 有会话），但**公钥始终未被 offer**，服务端回落 keyboard-interactive 要密码。密钥本身无罪（`ssh-keygen -y` 推公钥与 `authorized_keys` 逐字节一致）。假设：`app/build.gradle.kts:86` 只有 `implementation("com.github.mwiede:jsch:0.2.24")`，而 **mwiede jsch 的 ed25519 签名需要 `net.i2p.crypto:eddsa` optional 依赖**——缺 eddsa 时 jsch 静默跳过该 identity。worker 的任务是**先写 JVM 测试复现（RED）再加依赖转 GREEN**；**证伪即停，不许为交付而交付**。

### F13a 的根因已翻案（重要，别再往错方向查）
「切 Private key 恒报 `oc_ssh_error_input`」**不是** IME / `onNext` / target 丢失——**F13a 那个修复本身是对的**，真问题是**密钥文件权限**：app 重装后 uid 从 u0_a218 变 **u0_a230**，`/sdcard/v95/id_ed25519` 属主仍是 u0_a218:media_rw 660 → app **Permission denied**（sdcardfs 忽略 chmod）→ `jsch.addIdentity()`（`SshConnection.kt:92`，在 `connect()` 之前）抛异常 → **一个 socket 都不开** → sshd 零会话。同串 target 用 Password 认证实测连通，`parseSshTarget` 无罪。

### F12a 验证口径已修正（下一轮照这个验）
`SshConnection.kt:240` 快路径 `config.passphrase?.let { return true }` —— `promptPassphrase` 弹卡**只在条目完全没存 passphrase 时可达**；存了错值更早在 `addIdentity` 就抛。
**v97 口径**：私钥条目**不填 passphrase** → 连接 → 弹卡输 `keypass123` → **Connected** + sshd `Accepted publickey for sshtest`。

## 6. 接手后的待办队列（按优先级）

1. **收口两路 F14 worker** → 跑全量闸门（目标：**只增不减**于 611/5522/0/0）。
2. **派 v97 真机终验**（目标机 5560=Pixel_9a，先 `emu avd name` 核）：
   - 装 F14 构建；
   - **F14a**：制造私钥文件不可读场景（key 指到不存在的路径或 `/sdcard` 不可读路径）→ 断言面板显示 **key 文件错误文案**（不再显示 target 假话）；
   - **F14b + F12a 修正口径**：私钥条目 auth=KEY、key=`/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`、**passphrase 留空** → 提交 → 弹卡输 `keypass123` → **Connected** + sshd `Accepted publickey` 佐证（端到端坐实 ed25519 通了）；
   - **F13b 回归**：口令条目错密码 → 弹卡 → 紧凑输 `opencode` → **Connected**；
   - **F12b 回归**：Cancel 后不重弹、行保留、新 Reconnect 必重弹；
   - **清理**：删条目确认不复活、设备不留垃圾。
3. **HANDOFF.md 终更**：v97 结果、F14 销账、eddsa 结论、测试员新事实。
4. **残留观察（未定性，不下 bug 结论）**：`wm size` 窄窗后行级 Reconnect 惰性——同期 launcher 抽屉抢焦、reset 后恢复、无第二目击。
5. **已知不修结案（别重开）**：N1 `host:port` 不支持（上游 `parseTarget` 同款）、N4 sshtest 密码漂移（环境案）、N5 底部 ⋮ 菜单贴屏沿被当外部点击（低优，有规避路径）、5556「Cancel 删行」（v93 像素级盖棺=误触）。
6. **有意偏离表 20+ 条在 `research/HANDOFF.md`，不许改回**。

## 7. 测试员踩坑库（复验前必读，能省一小时）

**v96 最新**：
- **app uid 重装会变**（u0_a218→u0_a230），/sdcard 测试资产属主失配 → app 读不了。测试密钥放 app 私有目录：`run-as com.xiufeigo.opencodeshell sh -c 'echo <base64> | base64 -d > files/id_ed25519'`。
- 表单卡片外 `x=1000` 处 `input swipe` 可滚表单且不误触字段。
- Delete 确认框渲染在屏外时，**滚动列表**（别滚弹框）可露出按钮。

**v95c**：
- **Settings 对话框内禁用 ESC**（`keyevent 111`）——会关掉整个对话框。收键盘用 `keyevent 61`(TAB)（移焦并顺带收 IME）。
- **Add SSH 表单里向下拖 = drag-to-dismiss**，会把表单/确认框甩掉。
- 表单滚动手势必须落在表单卡片内；固定起点在键盘弹起时会落到键盘上 → 静默不滚。
- **错密条目会自烧 faillock**：`setPassword(config.password)` + 服务端 kbdint = 3 次自动重试即锁号，卡面变 `(10 minutes left to unlock)` 且 Continue 置灰。**每次 Reconnect 前先 ResetFaillock**。
- **认证应答必须单脚本连续完成**（「卡出现→输入→Continue」一气呵成），中途 `screencap`/`uiautomator` 会让服务端会话过期 → 正确密码被判失败。
- `Server actions` 是 `content-desc` 不是 `text`；按行定位用「行标题 y+300」窗口（`+200` 会漏）。
- PowerShell：`$pid` 只读；`.ps1` 含非 ASCII 且无 BOM 会被 PS 5.1 按 ANSI 解析报错（该目录 `.ps1` 保持 ASCII）。

**更早**：SSH target 必须写 `ssh -p 2222 sshtest@127.0.0.1`（`host:port` 会被当主机名）；表单强制要求 Password；Gboard 中文输入法把点号换成「。」（输 host 前切英文）；5562 默认是 TTS 语音 IME（无键盘面，须 `ime set` 全组件名）；窄窗用 `wm size 560x768`（验完 reset）；掩码点读数有 UTF-8 伪影，别拿点数当密码长度判据。

## 8. 关键文件索引

| 用途 | 路径 |
|---|---|
| **主交接文档**（v57→v96 全量背景 + 有意偏离表） | `research/HANDOFF.md` |
| SSH 连接核心 | `app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt` |
| SSH 状态机 + 错误归因 | `.../ui/servers/SshConnectionModel.kt` |
| SSH 表单弹层（onCancel/onDismiss 修复处） | `.../ui/servers/SshDialog.kt` |
| 共享控件（OcTextField 的 onNext 修复处） | `.../ui/settings/SettingsComponents.kt` |
| 行级 prompt 出口（F12b） | `.../ui/servers/SshPromptOffer.kt` |
| 面板渲染 SSH 卡 | `.../ui/settings/ServersPanel.kt` |
| 依赖（eddsa 待加） | `app/build.gradle.kts:86` |
| 上游锚（只读） | `_upstream/packages/app/src/servers/ssh/context.tsx:58-69`、`controller.ts`、`authentication-state.ts`、`_upstream/packages/desktop/src/main/ssh/command.ts:45-111` |
| v96 复验报告（本轮最新） | `research/v96_verify/5560/REPORT.md` |
| 可复用测试执行件 | `research/v95_verify/5560/E2ECHK5560_*.ps1`、`_rowscan.py` |

## 9. 已知噪声（别当 bug 查）

- bash 工具偶发 guardian 超时 → 重试即过。
- chromium 悬浮层抢焦点。
- launcher 抽屉在窄窗抢触摸。

---

**一句话现状**：F8–F13 六个修复已落地并真机坐实，闸门 611/5522/0/0；v96 挖出 F14 两项（错误归因假话 + jsch ed25519 缺 eddsa 疑点）正在并发修复，**接手后先收口这两路 → 跑闸门 → 派 v97 真机终验 → 终更 HANDOFF**。
