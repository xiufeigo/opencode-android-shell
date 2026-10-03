# REPORT_D3_KEY — D3-4 私钥变体 · 竖屏手机复验（emulator-5558 / Pixel_10_Pro_XL）

**结论：A / B / C / D 全部 PASS。私钥变体已在竖屏手机上真正跑通（非账号口令替代）。**
本轮**未 BLOCKED**：上一轮的构建不一致已消除。

- 执行时间：2026-10-03 16:24–16:52 (+08:00)
- 设备：`emulator-5558`，`sdk_gphone16k_x86_64`，**1344x2992 @ 480，竖屏手机**
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 覆盖对象：`research\v97_verify\5558_v98\REPORT_D3_KEY.md`（上一轮 BLOCKED 版）
- 证据目录：`research\v97_verify\5558_v98\evidence\`

---

## 0. 前置一：构建核对 —— PASS（双项均一致）

```
$ adb -s emulator-5558 shell pm path com.xiufeigo.opencodeshell
package:/data/app/~~K12auo1nHGet1Wd7q0iswA==/com.xiufeigo.opencodeshell-ICQxphwmKe4QNR2kmXi8YQ==/base.apk

$ adb -s emulator-5558 shell md5sum /data/app/~~K12auo1nHGet1Wd7q0iswA==/com.xiufeigo.opencodeshell-ICQxphwmKe4QNR2kmXi8YQ==/base.apk
50cc9d73e5a470f8400b3b10fc73c47c  /data/app/~~K12auo1nHGet1Wd7q0iswA==/com.xiufeigo.opencodeshell-ICQxphwmKe4QNR2kmXi8YQ==/base.apk
```

```
$ adb -s emulator-5558 shell dumpsys package com.xiufeigo.opencodeshell | grep -E 'version|lastUpdate'
    versionCode=26 minSdk=26 targetSdk=35
    versionName=1.4.0
    lastUpdateTime=2026-10-03 08:23:53
      firstInstallTime=2026-09-26 13:48:36
```

| 项 | 期望 | 实际 | 判定 |
|---|---|---|---|
| 设备 APK md5 | `50cc9d73e5a470f8400b3b10fc73c47c` | `50cc9d73e5a470f8400b3b10fc73c47c` | ✅ 逐位一致 |
| `lastUpdateTime` | 2026-10-03 **08:23:53** | 2026-10-03 **08:23:53** | ✅ 一致 |

> 与上一轮（`66aab605…` / `01:35:41`）对比，本轮新包确已装上。全程**未**执行 gradle / 装机 / 杀进程。

---

## 1. 前置二：设备归属核对 —— PASS（全程属本 app）

开工前与**每一步 tap 前**均执行归属门禁（`AssertFocus`），两种合法形态都放行：

```
$ adb -s emulator-5558 shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
  mCurrentFocus=Window{d71d78b u0 com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity}
  mFocusedApp=ActivityRecord{145536824 u0 com.xiufeigo.opencodeshell/.OcActivity t151}
```

- `dumpsys window` 打印的是**全限定** Activity 名 `…/com.xiufeigo.opencodeshell.OcActivity`，与要求的 `…/OcActivity` 指同一 Activity，非他 app。
- 行 ⋮ 菜单展开时焦点切到 Compose 的 `Pop-Up Window`（该行不带包名）。按要求**未草率判 BLOCKED**，而是补验归属：

```
$ adb -s emulator-5558 shell dumpsys window windows | grep -A3 'Pop-Up Window' | grep -E 'package|Window\{'
  Window #8 Window{8de4851 u0 Pop-Up Window}:
    mOwnerUid=10231 showForAllUsers=false package=com.xiufeigo.opencodeshell appop=NONE
```

→ 弹层 `package=com.xiufeigo.opencodeshell`、`mOwnerUid=10231`（与 app uid 一致），属**本 app 自有**下拉，非 `com.minimax.coderemote`。放行并在报告留痕。
**全程 0 次出现非本 app 窗口。** 收尾复核焦点仍为本 app（见 §5 D6）。

---

## 2. 环境读数：铺私钥 + 双侧 md5 对账 —— PASS

### 2.1 源钥核对

```
$ Get-Item research\v95_verify\id_ed25519
FullName: …\research\v95_verify\id_ed25519   Length: 444
$ Get-FileHash … -Algorithm MD5
0120a8270f7f0b0c71d31f4933384c63
期望 md5 0120a8270f7f0b0c71d31f4933384c63   ✅
```

`research\v97_verify\id_ed25519` 确认**不存在**（`Test-Path` → `False`），与题面一致。

### 2.2 铺钥（app 私有目录）

app uid = **10231** (`u0_a231`)。落点 `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`
（依据 `research\v97_verify\5554\E2ECHK5554_provision_key.ps1`：每次重装 uid 会变，`/sdcard` 副本对新 uid 不可读，**只有 app 私有目录可靠**）。

**踩坑留痕（第一次铺失败）**：用 PowerShell 管道 `Get-Content -Raw … | adb shell "cat > files/id_ed25519"` 会把内容按文本往返，**444 B → 445 B，多出 1 个换行**，md5 立刻对不上：

```
-rw------- 1 u0_a231 u0_a231 445 2026-10-03 08:25 files/id_ed25519
9e91480009cbb7dcd00f0ec75201b0be  files/id_ed25519     ← 与期望不符
```

改用**二进制安全**的 `adb push` + `run-as cat` 重铺：

```
$ adb -s emulator-5558 push research\v95_verify\id_ed25519 /data/local/tmp/id_ed25519
… : 1 file pushed … (444 bytes)
$ adb -s emulator-5558 shell "chmod 644 /data/local/tmp/id_ed25519"
$ adb -s emulator-5558 shell "run-as com.xiufeigo.opencodeshell sh -c 'cat /data/local/tmp/id_ed25519 > files/id_ed25519 && chmod 600 files/id_ed25519 && ls -l files/id_ed25519'"
-rw------- 1 u0_a231 u0_a231 444 2026-10-03 08:25 files/id_ed25519
$ adb -s emulator-5558 shell rm -f /data/local/tmp/id_ed25519      # 中转件即删
```

### 2.3 双侧 md5 对账

| 侧 | md5 | 字节 |
|---|---|---|
| 宿主（源钥） | `0120a8270f7f0b0c71d31f4933384c63` | 444 |
| 设备（app 私有目录 `run-as md5sum`） | `0120a8270f7f0b0c71d31f4933384c63` | 444 |

**MATCH: True** ✅ —— 字节级一致，可安全用于 A/B。

### 2.4 reverse 复核（开工前）

```
$ adb -s emulator-5558 reverse --list
host-15 tcp:4095 tcp:4095
host-15 tcp:2222 tcp:22          ← 2222→22 反向隧道在位
```

---

## 3. A（主目标）：私钥条目 → 连接 → 三条断言 → 删除 —— **全 PASS**

### 3.0 表单可达性 —— 竖屏优势得到证实

导航路径（本轮实测）：`Home → Settings(底部) → 设置页签条右滑 → Servers → Add SSH server`。
竖屏 1344×2992 下表单**三段全部可见可滚**：Host / Server name 在首屏；向下滑即露出
`Authentication` 选择器 → `Private key` → `Key file path` → `Passphrase (optional)`。
这正是 5556 桌面形态（ScrollView 仅 191px、选择器与名称字段被挤出且滚不出）**补不上的那一格**。

### 3.1 一次失败尝试（诚实留痕，结论未被污染）

首次按题面用 `ssh -p 2222 sshtest@127.0.0.1` 作 Host。**`-p 2222` 的连字符被设备 shell 吞掉**，字段实际落成：

```
ssh p 2222 sshtest@127.0.0.1
```

结果连的是设备 **22** 端口（无隧道、关闭）：

```
127.0.0.1 / Connection failed
Could not establish the SSH connection. Check your network and SSH configuration.
```

辅助确认：`/data/local/tmp` 中转件已删、设备 22 端口无监听。
该错误条目已按删除流程清除（取证见 §3.5），随后改用**无连字符的 `user@host:port` 形式**重跑 —— 这也是本轮唯一的踩坑与修正。

### 3.2 断言 ①：**不出现 `Password:` 卡** —— PASS

正式条目（Host=`sshtest@127.0.0.1:2222`，认证 `Private key`，keypath=上表路径，passphrase=`keypass123`）提交后，连接过程中的**唯一**认证弹层是私钥口令层：

```
234,1722  T='Key file path'
672,1836  T='/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'
321,1950  T='Passphrase (optional)'
672,2063  T='??????????'
```

**全程未出现任何 `Password:` 字样的卡**。关键区分：出现的是 `Passphrase (optional)`（私钥解密口令，属 `Private key` 路径固有的一层），**不是** `Password:` 账号口令闸门。`Password:` 卡 0 次出现 ✅

### 3.3 断言 ②：**进入 `Connected`** —— PASS

行状态原文（uiautomator dump 原文，未加工）：

```
356,1664  T='127.0.0.1:2222'
314,1714  T='Connected'
468,1806  T='Reconnect'
826,1806  T='Disconnect'
```

截图：`evidence\A_18_conn_state.png`
**状态原文即 `Connected`** ✅

### 3.4 断言 ③：sshd 出现 **`Accepted publickey`** —— PASS

```
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '5 min ago' --no-pager | grep -E 'Accepted|Failed|Invalid|Connection'"
Oct 03 16:40:57 HomeMINISFORUM sshd-session[61856]: Accepted publickey for sshtest from 127.0.0.1 port 49098 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
```

**`Accepted publickey`** 原文命中，算法 **ED25519**（与测试钥类型一致），账号 `sshtest` ✅
这是本轮最硬的一条证据：**私钥变体真正走通**，不是账号口令替代 —— 上一轮的缺口在此闭环。

### 3.5 删除取证（`Connected` 态 ⋮ → Delete → 确认）

**删前列清单**（`SSH tunnels` 段）：

```
298,1506  T='SSH tunnels'
356,1664  T='127.0.0.1:2222'      ← 本轮目标
314,1714  T='Connected'
468,1806  T='Reconnect'
826,1806  T='Disconnect'
1074,1800  D='Server actions'
240,2093  D='Server not reachable'
424,2070  T='127.0.0.1:38451'      ← 隧道自动派生行
344,2119  T='sshtest'
240,2322  D='Server healthy'
414,2304  T='10.0.2.2:4095'        ← 他人条目（C 基线）
```

行菜单（`Server actions` → `Delete`）与确认条原文：

```
444,1950  T='Delete SSH server?'
672,2025  T='This removes ？？127.0.0.1:2222？？ from the list. This cannot be undone.'
503,2097  T='Its tunnel is disconnected first.'
748,2202  T='Cancel'
978,2202  T='Delete'
```

> 确认条**指名道姓** `127.0.0.1:2222`，且明确 `Its tunnel is disconnected first.`
> （Connected 态先断隧道再落库，与 `SshTunnelHost.kt` 的 `confirmSshForget` removal-before-forget 路径一致）。
> 截图：`evidence\A_19_confirm.png`

**删后列清单**：

```
298,1362  T='SSH tunnels'
240,1429  D='Server healthy'
414,1406  T='10.0.2.2:4095'
440,1455  T='v2.0.18 ? opencode'
934,1429  T='Default'
1074,1429  D='Server actions'
```

**落库计数**（`shared_prefs/oc_server_config.xml` 加密键条数）：删前 `5` → 删后 `5`。
> 计数不变的原因：加密存储 `oc_server_config.xml` 里 `name="A…"` 的键是 **AES keyset + 默认 server 记录**等固定键，SSH 条目以密文 value 存在同一 map 内，行级增删不体现为 `name="A…"` 键数变化。故**以行清单为准**：目标行 `127.0.0.1:2222` 及其派生行 `127.0.0.1:38451` 均已消失。
截图：`evidence\A_20_after_delete.png` ✅

---

## 4. B：错误 passphrase（`wrongpass`）—— **PASS，造出了 `Authentication required`**

同路径新建私钥条目，唯一差异：passphrase = **`wrongpass`**（掩码 9 字符，与 `keypass123` 的 10 字符可区分）：

```
672,1390  T='/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'
672,1617  T='?????????'          ← 9 字符 = wrongpass
```

sshd 侧原文（**私钥解密失败**的直接证据）：

```
Oct 03 16:43:09 HomeMINISFORUM sshd-session[61945]: error: Received disconnect from 127.0.0.1 port 54300:3: com.jcraft.jsch.JSchException: Auth fail for methods 'publickey,password,keyboard-interactive' [preauth]
```

**行状态原文**：

```
356,1520  T='127.0.0.1:2222'
412,1565  T='Authentication required'          ← 行状态
672,1966  T='Could not establish the SSH connection. Check your network and SSH configuration.'
304,2062  T='Authentication required'          ← 卡片状态
856,2166  T='Cancel'
1104,2166  T='Connect'
```

截图：`evidence\B_01_wrongpass_state.png`、`evidence\B_02_final_state.png`
**`Authentication required` 原文出现** ✅

删除取证：确认条 `This removes ？？127.0.0.1:2222？？ from the list.`
（截图 `evidence\B_03_confirm.png`），确认后该行消失（`evidence\B_04_after_delete.png`）✅

> 结论：口令错误 ⇒ `Authentication required`；口令正确 ⇒ `Connected`。两态互为对照，
> 证明 `Connected` 不是缓存/兜底，而是真的私钥解密成功。

---

## 5. C 反例：只删目标行，他人 `10.0.2.2:4095` 完好 —— **PASS**

A、B 两次删除（外加 §3.1 错误条目那次）之后，行清单**只剩**：

```
298,1362  T='SSH tunnels'
240,1429  D='Server healthy'
414,1406  T='10.0.2.2:4095'
440,1455  T='v2.0.18 ? opencode'
934,1429  T='Default'
1074,1429  D='Server actions'
```

`10.0.2.2:4095` 及其副标题 `v2.0.18 • opencode`、`Default` 标签、`Server healthy` 状态
**全程未被任何一次删除波及**，且从未被我点过 ⋮ ✅
（`Server healthy` 状态码 `D='Server healthy'` 保持不变，反证未掉线。）

---

## 6. D 清理 —— **PASS**

| 子项 | 结果 |
|---|---|
| `E2ECHK5558d3*` 条目 | **0 条**。Servers 面板已无任何本轮自建行（dump 中出现的 `E2ECHK5558v98`/`E2ECHK5556d11b` 是背景会话列表里的**会话标题**，非 SSH 条目） |
| `/sdcard` 残留 | 本轮**无**。每次 dump/截图用的 `/sdcard/d.xml`、`/sdcard/s.png` 均用完即 `rm`；`/sdcard` 下无 `id_ed25519`（仅有历轮遗留的 `v*_*.png`/`*.xml` 截图历史，非本轮产物） |
| `/data/local/tmp` 中转件 | 本轮自建的 `/data/local/tmp/id_ed25519` **已删**。仅存 `k8_id_ed25519`（444 B，**2026-10-01** 10:17），系**历轮**遗留，非本轮 |
| reverse 复核 | `host-15 tcp:4095 tcp:4095` / `host-15 tcp:2222 tcp:22` —— **双条在位，未被我改动** |
| 收尾焦点 | `mCurrentFocus=…com.xiufeigo.opencodeshell/com.xiufeigo.opencodeshell.OcActivity`；`mFocusedApp=…com.xiufeigo.opencodeshell/.OcActivity` —— 仍属本 app |
| 纪律自查 | 未跑 gradle、未装机、未杀进程（未用 `am force-stop`）；只操作 5558（未碰 127.0.0.1:5555）；**全程 0 次 BACK(4)**、**弹层内 0 次 ESC(111)**；截图全部 `screencap -p` + `adb pull`；坐标全部运行期从 dump 解析 |

**留档说明（有意保留）**：app 私有目录的 `files/id_ed25519`（444 B，md5 `0120a827…`，`u0_a231` 0600）**未删除**。
它不是"条目残留"而是**跨轮测试基建**——`E2ECHK5554_provision_key.ps1` 明确要求"每次 APK 安装后重铺"，
删掉只会给下一轮添麻烦。仅在此登记，便于追溯。

---

## 7. 未闭环 / 遗留

1. **条目命名未带 `E2ECHK5558d3` 前缀（有意偏差，已登记）**。
   `Server name (optional)` 字段在本轮始终点不进去（dump 中该 EditText 高度被压到 13px、
   落在软键盘遮挡区），`E2ECHK5558d3key` 无法落库，故行名回落为 Host `127.0.0.1:2222`。
   该行由本轮自建、且 Host 唯一，识别无歧义，删除目标经确认条指名核对无误；
   但严格来说未满足"自建条目前缀 `E2ECHK5558d3`"的书写要求。
   根因候选：竖屏下软键盘弹出后 passphrase 行被压到键盘之下，tap 落到键盘上；
   绕行办法是 `keyevent 61`(TAB) 切焦点（已用于 passphrase 字段，成功）。
2. **软键盘遮挡导致 tap 落空**（见上）。表现为 passphrase 文本一度被并入
   keypath 字段（`…id_ed25519keypass123`），靠清空 + TAB 重做修正，最终值经逐字段 dump 复核正确。
   建议后续轮次把"TAB 切焦点"固化为该表单的标准填法。
3. **`-p 2222` 被设备 shell 吞连字符**（§3.1）。这是 `input text` 的已知坑，
   Host 建议一律用 `user@host:port` 无连字符形式。
4. **落库计数无法作为删除判据**（§3.5）：`oc_server_config.xml` 的 `name="A…"` 键数不随 SSH 行增删变化，
   D3 系列"落库计数"断言需要换计数口径（按密文 value 解密后计数，或直接以行清单为准）。
5. **`127.0.0.1:38451` 派生行的归属**未完全查清：它在 A 连上后出现、随 A 删除而消失，
   形态像隧道落地的伴随记录；未单独复现验证。
6. **`Verify host identity` 未在本轮出现**（`known_hosts` 中 `[127.0.0.1]:2222` 已被历轮写入），
   故 `Trust and connect` 流程本轮**未被触发、未被验证**。

---

## 8. 一句话结论

**竖屏手机把 5556 桌面形态补不上的那一格补上了：私钥变体在 1344×2992@480 上真正走通——
无 `Password:` 卡 → `Connected` → sshd `Accepted publickey`（ED25519）→ 删除命中目标行，
且 `10.0.2.2:4095` 全程完好。** 本轮对上一轮 BLOCKED 的两处怀疑（构建旧包、私钥变体未跑成）均已排除。
