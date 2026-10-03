# REPORT_SSH_KEY — 5564 真机复验：SSH 私钥认证 P3 修复

- 轮次：**v98 真机复验**（验 `research/v97_fix/SSH_KEY_AUTH_FIX.md` 的 P3 修复）
- 设备：**emulator-5564**（`sdk_gphone16k_x86_64`，SDK 37，全屏手机形态）
- 包：`com.xiufeigo.opencodeshell` versionName `1.4.0` / versionCode `26`
- 日期：2026-10-03
- 纪律遵守：未跑 gradle、未装机、未杀进程（仅 `am force-stop` 本 app）；只操作 5564；未碰 127.0.0.1:5555；
  **未按 BACK(4)**；**弹层内未按 ESC(111)**；截图一律 `screencap -p /sdcard/…` + `adb pull`；
  坐标全部运行期从 dump 解析（`_k5564.ps1` 的 `ParseNodes` 按 `<node` 切块，**同时匹配** `text="…"` 与 `text='…'`，
  再兜底单引号变体）；未改动 `known_hosts`。

---

## 一、构建核对（闸门）— **一致，放行**

逐位核对 md5 与 `lastUpdateTime`：

```console
$ adb -s emulator-5564 shell pm path com.xiufeigo.opencodeshell
package:/data/app/~~MpL8akylN6gKsuEAW5Twkw==/com.xiufeigo.opencodeshell-J26K1w67bNRqP9O1uE19Cg==/base.apk

$ adb -s emulator-5564 shell md5sum /data/app/~~MpL8akylN6gKsuEAW5Twkw==/com.xiufeigo.opencodeshell-J26K1w67bNRqP9O1uE19Cg==/base.apk
e55f98084f9ec081511a9cfb7eb10f40  /data/app/~~MpL8akylN6gKsuEAW5Twkw==/com.xiufeigo.opencodeshell-J26K1w67bNRqP9O1uE19Cg==/base.apk

$ adb -s emulator-5564 shell dumpsys package com.xiufeigo.opencodeshell | grep -E "lastUpdateTime|versionName"
    versionCode=26 minSdk=26 targetSdk=35
    versionName=1.4.0
    lastUpdateTime=2026-10-03 06:39:05
```

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| APK md5 | `e55f98084f9ec081511a9cfb7eb10f40` | `e55f98084f9ec081511a9cfb7eb10f40` | ✅ 逐位一致 |
| `lastUpdateTime` | `2026-10-03 06:39:05` | `2026-10-03 06:39:05` | ✅ 一致 |

→ **不是 BLOCKED**，闸门放行。

---

## 二、环境读数

### 2.1 设备与隧道

```console
$ adb -s emulator-5564 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22        ← 复核通过（本轮用的就是这条）
```

### 2.2 宿主 sshd

```console
$ wsl -d Arch -e bash -lc "sudo ss -ltnp | grep ':22 '"
LISTEN 0      128           0.0.0.0:22        0.0.0.0:*    users:(("sshd",pid=336,fd=6))
LISTEN 0      128              [::]:22           [::]:*    users:(("sshd",pid=336,fd=7))
```

### 2.3 `sshtest` 未被锁（最易踩的坑）

```console
$ wsl -d Arch -e bash -lc "sudo faillock --user sshtest"
sshtest:
When                Type  Source                                           Valid
(无 V 行 ⇒ 未被锁)
```

### 2.4 公钥在位

```console
$ wsl -d Arch -e bash -lc "sudo ssh-keygen -l -f /home/sshtest/.ssh/authorized_keys"
256 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8 v95-test (ED25519)
```

### 2.5 铺钥（本轮**实际用的命令**）

`research\v97_verify\id_ed25519` 确认已不存在（与修复文档 §7.3 一致），按任务书用
`research\v95_verify\id_ed25519`：**444 B，md5 `0120a8270f7f0b0c71d31f4933384c63`**（与
`SSH_KEY_AUTH_FIX.md` §6 条件 6 期望的指纹一致，闭环了那条「md5 未复核」的未闭环项）。

**先按参考脚本的做法试了一次 `run-as … cp`，失败了**（sdcardfs 权限墙，与脚本注释一致）：

```console
$ adb -s emulator-5564 push research\v95_verify\id_ed25519 /sdcard/E2ECHK5564k_id_ed25519
1 file pushed, 0 skipped. 444 bytes

$ adb -s emulator-5564 shell "run-as com.xiufeigo.opencodeshell cp /sdcard/E2ECHK5564k_id_ed25519 files/id_ed25519"
cp: bad '/sdcard/E2ECHK5564k_id_ed25519': Permission denied      ← app uid 读不到 sdcard
```

**改用 `E2ECHK5554_run.ps1` 里 `EnsureKey` 的真实做法（base64 走 stdin，不经 sdcard）**：

```powershell
$bytes=[IO.File]::ReadAllBytes("research\v95_verify\id_ed25519")     # 444
$b64=[Convert]::ToBase64String($bytes)
adb -s emulator-5564 shell "run-as com.xiufeigo.opencodeshell sh -c 'echo $b64 | base64 -d > files/id_ed25519'"
adb -s emulator-5564 shell "run-as com.xiufeigo.opencodeshell chmod 600 files/id_ed25519"
```

双侧指纹一致：

```console
$ adb -s emulator-5564 shell "run-as com.xiufeigo.opencodeshell md5sum files/id_ed25519"
0120a8270f7f0b0c71d31f4933384c63  files/id_ed25519
-rw------- 1 u0_a230 u0_a230 444 2026-10-03 06:40 files/id_ed25519
repo md5 = 0120a8270f7f0b0c71d31f4933384c63   ⇒ MATCH
```

> 参考脚本 `E2ECHK5554_provision_key.ps1` 本体**没有直接跑**（它 `.` 的是 `E2ECHK5554_run.ps1`
> 里的 `EnsureKey`，且写死了 `-Dev emulator-5554`）。我按它内部 `EnsureKey` 的同一手法在 5564 上手工执行。

---

## 三、主判据（P3）— **PASS**

### 条目配置

Servers 面板在 **设置 → 服务器**（`ui/settings/ServersPanel.kt`）下，**不在**抽屉或命令面板里
（命令面板全文过滤 `server/服务器/SSH` 只命中「MCP 服务器」，这是本轮实测到的路由事实）。

| 字段 | 值 |
|---|---|
| Host | `ssh -p 2222 sshtest@127.0.0.1` |
| 认证方式 | **私钥**（`Private key`） |
| 密钥文件路径 | `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519` |
| Passphrase | 卡片未暴露该字段 ⇒ 表单侧留空，改由交互卡补收（见下） |

### 断言 ①：**不出现 `Password:` 卡** — ✅

连接过程中弹出的**唯一**一张卡是 `SSH 认证` 卡，卡上文案原文（dump 解析）：

```plain
[38] 'SSH 认证'
[39] 'Passphrase for /data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'
[40] '取消'
[41] '继续'
```

即 **passphrase 卡（`promptPassphrase`）**，而**不是**要求账号口令 `opencode` 的 `Password:` 卡。
这正是 `SSH_KEY_AUTH_FIX.md` §4.3 表格里预期的形态：

> passphrase 错 / 缺失 → publickey 是唯一方法 → jsch 报 `Auth fail`；**或有 passphrase 时走
> `promptPassphrase` 弹 passphrase 卡（不再是要账号口令的 `Password:` 卡）**

→ **断言 ① 成立**。

### 断言 ②：最终进入 `Connected` — ✅

填入 `keypass123` → 点「继续」后，行状态文案原文：

```plain
[30] 'SSH 隧道'
[31] '127.0.0.1'
[32] '已连接'          ← Connected
[33] '重新连接'
[34] '断开连接'
```

证据：`screenshot res_key.png` / `shot after_pass.png`（`res_key.png` 为点击「继续」后的终态）。

### 断言 ③：服务端 `Accepted publickey` — ✅

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd -n 20 --no-pager | tail -8"
Oct 03 14:50:59 HomeMINISFORUM sshd-session[55722]: Accepted publickey for sshtest from 127.0.0.1 port 51772 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 03 14:50:59 HomeMINISFORUM sshd-session[55722]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

**判定性**：是 `Accepted publickey`（不是 `Accepted password` / `Accepted keyboard-interactive`），
且指纹 `SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8` 与 §2.4 的 `authorized_keys`
**逐字一致**。

> 同一时间窗内**没有**任何 `Accepted keyboard-interactive` 或 `Accepted password`（见 §三下对照表）。

### 本轮的「退化指纹」对照表

`journalctl -u sshd` 里 14:03 之前那些 `Accepted keyboard-interactive/pam`（13:46 / 13:59 / 14:03）
属于**本轮之前**的其它验证。把窗口收到本轮会话之内（本轮宿主时间 14:39 起）：

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '14:45' --no-pager | grep -E 'Accepted|Failed'"
Oct 03 14:50:59 HomeMINISFORUM sshd-session[55722]: Accepted publickey for sshtest from 127.0.0.1 port 51772 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 03 14:53:02 HomeMINISFORUM sshd-session[55969]: Accepted password for sshtest from 127.0.0.1 port 54466 ssh2
```

**本轮窗口内一共只有这两条。** 对照本轮之前的历史：；本轮会话从 14:39 起，落在本轮的只有两条：

| 时间 | sshd 事件 | 归属 |
|---|---|---|
| 13:46:24 / 13:59:11 / 14:03:19 | `Accepted keyboard-interactive/pam` | **本轮之前**（退化期残留） |
| **14:50:59** | **`Accepted publickey` … `SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8`** | **本轮主判据** |
| **14:53:02** | **`Accepted password` for sshtest** | **本轮口令对照** |

→ 本轮窗口内**再无 `keyboard-interactive`**。P3 描述的「privatekey 失败后偷偷换账号口令成功」**未复现**。

---

## 四、口令对照（不得退化）— **PASS**

同 Host，认证方式 = **密码**，口令 `opencode`：

```console
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd -n 16 --no-pager | tail -6"
Oct 03 14:53:02 HomeMINISFORUM sshd-session[55969]: Accepted password for sshtest from 127.0.0.1 port 54466 ssh2
Oct 03 14:53:02 HomeMINISFORUM sshd-session[55969]: pam_unix(sshd:session): session opened for user sshtest(uid=1001) by sshtest(uid=0)
```

UI 终态：

```plain
[36] '127.0.0.1'
[37] '已连接'          ← Connected
[38] '重新连接'
[39] '断开连接'
```

- sshd 侧是 **`Accepted password`** ✅（不是 publickey）
- 行状态 `已连接` ✅
- 全程**没有**弹任何卡（口令已在表单侧给全，jsch 直接用 `plan.password`），
  说明 §4.2 的「口令认证路径不得退化」成立：`password,keyboard-interactive` 两种方法**仍可用**，
  收窄没有把它砍成只剩一种。

证据：`ctl_res2.png`。

---

## 五、可诊断性（坏 keypath）— **PASS**

同 Host，认证方式 = **私钥**，但
`密钥文件路径 = /data/user/0/com.xiufeigo.opencodeshell/files/nope`（**不存在的路径**），
口令字段填了一个占位值 `dummy`（仅为推进逐段显现的卡片）。

**断言：给出可诊断的失败，且不静默回落成弹 `Password:` 卡并"成功"。** — 两者都成立。

### 卡上文案原文（dump 解析）

```plain
[24] '/data/user/0/com.xiufeigo.opencodeshell/files/nope'
[25] '口令（可选）'
[26] '无法读取私钥文件，请检查路径与读取权限。'     ← 可诊断失败文案
[27] '连接失败'                                    ← 阶段 failed
[28] '取消'
[29] '连接'
```

取消该卡后，**该行的 resting 状态同样持久显示失败**（不是回落到"看起来连上了"）：

```plain
[32] '连接失败'
[33] '无法读取私钥文件，请检查路径与读取权限。'
[34] '重新连接'
```

### 关键点

1. 失败文案正是 `SSH_KEY_AUTH_FIX.md` §4.3 预期的**既有 `KEY_FILE` 句子**（F14a 归因，**未新增用户可见字符串**）。
2. 卡上出现的是一个**中性标签 `口令（可选）`**（optional password），
   **不是** `SSH_KEY_AUTH_FIX.md` §4.4 记录的那张 **`Password:` 卡**，
   也**没有**要求账号口令 `opencode`。
3. **服务端零痕迹**：失败发生在 `addIdentity` 读文件那一步，连接根本没建起来。
   本轮窗口（`--since 14:45`）内 sshd 只有两条 `Accepted`，就是主判据与口令对照那两条，
   **没有任何 `Accepted keyboard-interactive` / `Accepted password` 兜底**。

证据：`dg_res.png`（失败卡）、`dg_close.png`（行 resting 失败态）。

---

## 六、清理

| 项 | 结果 |
|---|---|
| 自建 SSH 条目 | **3 条全部删净**（私钥 / 口令 / 坏 keypath 各一条；面板已回到原始两条 `127.0.0.1:4096` / `127.0.0.1:4095`，`4096` 仍为「默认」） |
| 连接产生的隧道 / 本地 API 行 | 随条目一并删除（删除确认卡原文：`删除 SSH 服务器？` / `将把「127.0.0.1」从列表中移除，且无法撤销。`） |
| `/sdcard` 残留 | **无新增**。本轮 push 过的 `/sdcard/E2ECHK5564k_id_ed25519` 已 `rm -f` |
| `/sdcard` 现存文件 | 全部是**别的轮次/别的设备**的（`37_e2echk_*`、`E2ECHK5560_*`、`t1_e2echk_*`、`v10_e2echk_*`），非本轮产物，未动 |
| dump/截图中间件 | `_k5564.xml` 每次 dump 后 `rm -f`；`screencap` 落 `/sdcard/_k5564.png`，pull 完即删 |
| `known_hosts` | **未动**。`files/ssh/known_hosts` mtime `2026-10-03 05:46`，早于本轮会话（06:39 起）⇒ 本轮未写入 |
| app 私有目录 | `files/id_ed25519` 按设计保留（铺钥是复验前提，每次装机都要重铺） |

`known_hosts` 现值（仅供下轮对照，本轮未改）：

```plain
[127.0.0.1]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP4xhCsSfuwShy6+t+va8TMG1R1brte/e+EImnSO8sQM
```

---

## 七、未闭环 / 偏差（如实记录）

1. **条目没能命名为 `E2ECHK5564k-key` / `E2ECHK5564k-pw`。**
   本轮走的是 Servers 面板里的**快捷新增卡**（`SshDialog`，标题「添加 SSH 服务器」），
   它只暴露 Host / 认证方式 / 密钥文件路径，**没有 `Server name` 字段**；带 `Server name` 的
   完整表单是 `ServersPanel.kt` 里的 `AddEditServerDialog`（经行内 ⋮ → 编辑 进入），
   本轮在时间预算内没能走到。⇒ 实际创建的是两条以 Host 串命名的条目，清理时按「面板回到原始两条」
   逐条核对确认删净。**这与任务书要求的前缀命名不符，属执行偏差。**

2. **可诊断性已执行**（见 §五，PASS）。原先记录为「未执行」是本报告初稿时的状态，
   后续在时间预算内补跑完成，此条已更新。

3. **重连形态未观察**：秘密刻意不持久化，重连一条 resting 行时预期弹
   `Passphrase for …id_ed25519` 卡。本轮只做到首次连接，未做断线重连。

4. **口令对照只证明了 `Accepted password` 一种**；`keyboard-interactive` 分支
   （§4.2 声称未收窄）本轮未单独触发。

5. **主判据的 passphrase 是交互补收的，不是表单给的**：快捷新增卡**没有** Passphrase 字段，
   所以私钥以「未解密」状态加载并触发 `promptPassphrase`。
   填对 `keypass123` 后即走 publickey 成功。
   **「表单直接填 passphrase、一次握手到位」这条形态本轮未覆盖**（需 `AddEditServerDialog`）。

6. **logcat 噪声大**：`log_keyauth.txt`（805 KB）几乎全是 Gboard/IME 的 `Keyboard*` 噪声，
   本 app 未见自有 jsch 日志行（`jsch`/`PreferredAuthentications` 级别无输出），
   故服务端 sshd 日志是本轮唯一判定性证据。**app 侧缺可观测性**本身值得记一笔。

7. **`adb` 不在 PATH**（本轮第一次 `adb devices` 直接 `CommandNotFoundException`），
   实际用的是 `C:\Users\xiufe\AppData\Local\Android\Sdk\platform-tools\adb.exe`。

8. **卡片表单是逐段显现的**，且「添加服务器」按钮位置随已填字段上下移动
   （观察到 1224 / 1142 / 1602 三个位置），坐标必须每段重新从 dump 解析 ——
   `_k5564.ps1` 因此按索引/几何解析节点，**没有**硬编码点击点。

9. **Servers 入口的位置**：`设置 → 服务器`（`ui/settings/ServersPanel.kt`）。
   抽屉顶部只有 `首页 / 新建会话 / 命令面板` 三个图标，命令面板里搜 `server` 也**不会**命中 Servers
   （本轮实测全文过滤只出「MCP 服务器」）。这一路由事实建议写进下轮测试员的操作说明。

---

## 八、结论

| 判据 | 结论 |
|---|---|
| 构建 md5 一致 | ✅ `e55f98084f9ec081511a9cfb7eb10f40` |
| `Private key` 条目不弹 `Password:` 卡 | ✅ 只弹 `Passphrase for …id_ed25519` |
| 进入 `Connected` | ✅ `已连接` |
| sshd 为 `Accepted publickey` | ✅ 指纹与 `authorized_keys` 逐字一致 |
| 口令认证未退化 | ✅ `Accepted password` + `已连接` |
| 坏 keypath 可诊断失败 | ✅ `连接失败` + `无法读取私钥文件…`，服务端零兜底 |
| 清理 | ✅ 条目删净 / `/sdcard` 无新增 / `known_hosts` 未动 |

**P3 在真机上已闭环：选了 `Private key` 之后不再被问账号口令，私钥真的被服务端接受了；
私钥不可用时给出可诊断失败而不是换个口令悄悄成功。**
唯一遗留的是条目命名偏差（§七.1）与两个未覆盖形态（重连、表单直填 passphrase）。
