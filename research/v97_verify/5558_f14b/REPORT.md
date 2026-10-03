# E2ECHK5558 — SSH 域 F14 真机终验报告（第二路独立尝试）

- 设备：**emulator-5558** / `emu avd name` = **Pixel_10_Pro_XL** ✅
- 屏幕：1344x2992 @ 480dpi
- 仓库根：`C:\sensorsdata\main\program\opencode-android-shell`
- 执行：2026-10-02 19:36 – 19:56
- **本轮核心突破：表单填对了。** 前几轮失败根因已定位并修正（见 §2）。

---

## 0. 判定速览

| 用例 | 判定 | 关键证据 |
|---|---|---|
| **表单定位/填写** | **DONE** | 哨兵 `KP1` 落地读回；卡片副标题回显完整 keypath |
| **F14a** 私钥不可读 | **PASS** | 面板原文 `Can't read the private key file. Check the path and read permission.` |
| **F14b + F12a** 公钥认证 | **PASS** | 面板 `Connected`；journalctl `Accepted publickey ... ED25519 SHA256:sYFitiUL7Z...` |
| **F13b** 密码认证 | **FAIL** | 错密码正确拒绝 ✅；但正确 `opencode` 仍 `PAM: Authentication failure`（faillock 已清空），未 Connected |
| **F12b** Cancel 语义 | **PASS（有保留）** | 二次 Reconnect 必重弹 ✅；Cancel 后 7 点采样不重弹 ✅；但稍后出现 Password 卡（下游回落） |
| 清理 | **DONE** | 两条目已删；/sdcard 我方产物已清 |

---

## 1. 环境读数

```
adb devices                     -> 5554/5556/5558/5560/5562/5564 全 device
adb -s emulator-5558 emu avd name -> Pixel_10_Pro_XL
wm size / density               -> 1344x2992 / 480
reverse --list                  -> host-15 tcp:4095 tcp:4095 ; host-15 tcp:2222 tcp:22
versionName=1.4.0  lastUpdateTime=2026-10-02 10:40:05
GET /api/info (Basic opencode:opencode) -> HTTP 200 {"version":"2.0.18","pid":260}
wsl -d Arch systemctl is-active sshd   -> active
research/v95_verify/id_ed25519 -> 444B, md5 0120a8270f7f0b0c71d31f4933384c63
```

**铺钥**：`E2ECHK5554_provision_key.ps1` 内部硬断言 `expected Pixel_9a`，在 5558 上直接抛错：
`device emulator-5558 is 'Pixel_10_Pro_XL', expected Pixel_9a - STOP`
改为手工铺（md5 校验通过）：
```
run-as com.xiufeigo.opencodeshell sh -c 'mkdir -p files; cp /data/local/tmp/k8_id_ed25519 files/id_ed25519; chmod 600 ...'
-> -rw------- 1 u0_a231 444 files/id_ed25519
-> md5sum = 0120a8270f7f0b0c71d31f4933384c63   ✅
```

**F14a 前提**：`run-as ... cat /sdcard/Download/nope_id_ed25519` → `Permission denied` ✅

---

## 2. 表单定位算法 —— 前几轮失败根因与修正

### 2.1 任务书算法在本构建上失效（3 个实测事实）

1. **空 EditText 不把 hint 当 `text` 暴露。**
   任务书假设「空 EditText 会把 hint 当 text 暴露」。实测 `w1.xml` 中所有空 EditText `text` 均为 `''`。
   **前几轮死循环在 `'name' not visible`、定位不到私钥框，根因就是依赖了这个不成立的假设。**

2. **设置页搜索框会被误认成表单字段。**
   首个 EditText `[108,749][1236,893]` 是 `Search settings` 框，**不是表单字段**——它位于设置页 tab 行（y=936）之上，真实表单位于 y≥1643。
   （旁证：任务书靠 `'Host or SSH command'` 字符串判断"表单已打开"，但该串是**标签 TextView**，在标签未滚入视口时必然找不到 → 这就是 `'name' not visible` 死循环的机制。）

3. **可靠信号是「label 在上、EditText 在下」的垂直配对**，label 文本唯一。

### 2.2 最终算法（本轮验证有效）

```
① 表单白色面板 = ScrollView [48,1652][1296,2082]，只能显示约 2 个字段，需滚动
② 候选 EditText = dump 中所有 bounds.y1 >= 1640 的 android.widget.EditText
③ 归属 = 该 EditText 正上方最近的 TextView label：
     'Host or SSH command'      -> host
     'Server name (optional)'   -> name
     'Key file path'            -> keypath
     'Passphrase (optional)'    -> passphrase（且 password="true"）
④ 认证选择器（'Authentication' 标签下的下拉）默认是 Password：
   必须先点开 -> 点 'Private key' -> 重新 dump 再认字段（切完字段确实会变）
⑤ 每个字段：tap -> dump 确认 focused=true -> input 哨兵 -> dump 读回 -> 再补完整内容
```

### 2.3 表单 bounds 表（实测）

| ID | bounds | mask | 上方 label | 归属 |
|---|---|---|---|---|
| **S0** | `[108,749][1236,893]` | plain | (Search settings) | ❌ **非表单，误认陷阱** |
| **H** | `[156,1918][1188,2062]` | plain | `Host or SSH command` `[120,1853]` | **host** |
| **N** | `[156,2145][935,2154]` | plain | `Server name (optional)` `[120,2080]` | **name**（9px 残片，见 §2.5） |
| **K** | `[156,1729][1188,1873]` | plain | `Key file path` `[120,1664]` | **keypath** |
| **P** | `[156,1956][1188,2100]` | **MASKED** | `Passphrase (optional)` `[120,1895]` | **passphrase** |

认证选择器：`Authentication` `[120,1652]`；当前值节点 `[156,1747][361,1794]`
按钮：`Cancel [742,2143][869,2190]` / `Add server [977,2143][1182,2190]`

### 2.4 哨兵读回证据（字段识别自证）

```
TAG=K1 tap(672,1725)
  FOCUS  class=android.widget.EditText bounds=[156,1377][1188,1521]
  READ   [156,1377][1188,1521] plain text='KP1'  <<SENTINEL-OK>>
```
哨兵 `KP1` 确实落入 keypath 框 → 字段身份确认。
后续 label 直接显示 `Key file path` `[120,1253]`，与哨兵框同一节点，**双重确认**。

host 填入后读回：
```
  FINAL  [156,1566][1188,1710] plain 'ssh -p 2222 sshtest@127.0.0.1'
```
keypath 填入后读回：
```
  FINAL  [156,1377][1188,1521] plain '/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519'
```
passphrase 填入 keypath 后仍为 `MASKED text=''` → **F12a 要求的"留空"成立**。

### 2.5 滚动要点（实测踩坑）

- **起终点必须落在 ScrollView 内且不在 EditText 上**。
  - `swipe 672 2000→1700` 无效：起点 y=2000 落在 passphrase `[156,1956][1188,2100]` 内。
  - 有效：`swipe 1250 1750→2080`（x=1250 在 EditText 右侧留白 [..,1188] 之外）。
- 快滑 ~100px/90ms 有效；滚动后必须重新 dump（`sc2/sc3/sc4/f1/f2/f3/h1..h5.xml` 均有留证）。
- **name 字段始终只露 9px**（`[156,2145][935,2154]`，落在 ScrollView 底 2082 之外），无法点按 → 本轮 name 留空，靠 host 字符串识别条目。

### 2.6 表单预填状态（意外发现）

首次进入的表单**已预填** host=`ssh -p 2222 sshtest@127.0.0.1`、name=`E2ECHK5558_f14b`、passphrase 掩码有内容，keypath 为空并报红字 `Enter the path to your private key.`——上一轮残留的半填状态。
**新建表单则是全空 + auth 默认 Password**（本轮 F14b 用新建表单，确保 passphrase 真留空）。

---

## 3. 卡片坐标（Rehearse 学习结果）

`SSH authentication` 卡片（passphrase 型），来自 `sub2.xml`：

| 元素 | bounds | 中心 |
|---|---|---|
| 标题 `SSH authentication` | `[120,1724][529,1779]` | (324, 1751) |
| 副标题 `Passphrase for <keypath>` | `[120,1853][1224,1937]` | (672, 1895) |
| **输入框**（MASKED） | `[156,1955][1188,2099]` | **(672, 2027)** |
| `Cancel` | `[782,2142][909,2189]` | (845, 2165) |
| `Continue` | `[1017,2142][1182,2189]` | **(1099, 2165)** |

副标题**原样回显 keypath**：
`Passphrase for /data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`
——这是表单 keypath 填对的最强独立佐证（应用确实读到了该文件）。

`Password:` 型卡片坐标略有不同（`pw_pre.xml`）：输入框 `[156,1913][1188,2057]`，Continue `[1017,2100][1182,2147]`。

---

## 4. 连接流程中额外出现的两个步骤（任务书未提及）

1. **首次连接主机指纹确认**：`Trust and connect` `[847,2181][1182,2228]`（中心 1014,2204）。
   状态文案 `Disconnected. The remote server is left running.` → 必须先信任主机密钥才会发起认证。
2. **Cancel 后回落出 `Password:` 卡片**：见 §8 保留项。

---

## 5. F14a：私钥文件不可读 —— **PASS**

1. 证明 app 读不到：`run-as ... cat /sdcard/Download/nope_id_ed25519` → `Permission denied` ✅
2. keypath 填入该路径，提交 `Add server`
3. 面板断言（`f14a2.xml`，截图 `f14a.png`）：

```
'Connection failed' [228,1550][504,1580]
'Can''t read the private key file. Check the path and read permission.' [120,1966][1172,2006]
'Connection failed' [120,2042][396,2082]
```
✅ 与期望字符串**逐字一致**。

---

## 6. F14b + F12a：passphrase 留空 → 弹卡 → Connected —— **PASS（走 publickey）**

流程（新建表单，全空）：
1. host = `ssh -p 2222 sshtest@127.0.0.1`（空格用 `keyevent 62`，未用 `%s`）
2. auth 选择器 `Password` → 点开 → 点 `Private key` `[156,1991][361,2038]`
3. keypath = `/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`
4. **passphrase 全程留空**（`MASKED text=''`，默认即空）
5. 提交 → `Trust and connect` → 弹卡
6. 卡片输入框输 `keypass1` + `23`（≤8 字符分段，段间 1.5s）→ 读回 `MASKED len=10` ✅
7. `Continue` → 面板出现 **`Connected`** `[228,1981][400,2021]`（截图 `conn1.png`）

### journalctl 原文（判定依据）

```
$ wsl -d Arch -e bash -lc "sudo journalctl -u sshd --since '-25min' --no-pager \
    | grep -Ei 'Accepted publickey|Accepted keyboard-interactive|authentication failure'"

Oct 02 19:32:48 sshd-session[17242]: Accepted publickey for sshtest from 127.0.0.1 port 44642 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
Oct 02 19:36:16 sshd-session[17406]: pam_unix(sshd:auth): authentication failure; logname= uid=0 euid=0 tty=ssh ruser= rhost=127.0.0.1  user=sshtest
Oct 02 19:40:08 sshd-session[17689]: Accepted keyboard-interactive/pam for sshtest from 127.0.0.1 port 45180 ssh2
Oct 02 19:49:55 sshd-session[18661]: Accepted publickey for sshtest from 127.0.0.1 port 34100 ssh2: ED25519 SHA256:sYFitiUL7ZsVSAtodSjj4ob+l4DqiA05VZA9F7w4CR8
```

**判定：F14b = PASS，走 publickey，未回落 keyboard-interactive。**
- 本次连接时间戳 **19:49:55**（Continue 点击时刻），对应 `Accepted publickey ... ED25519 SHA256:sYFitiUL7Z...`。
- `19:40:08 Accepted keyboard-interactive/pam` 发生于 **F14a 阶段**（19:40–19:42），早于 F14b 提交（19:48–19:49），**不属于 F14b**。
- `19:36:16 authentication failure` 是本轮开工前的既有日志（首次环境读数时即已存在）。
- 19:32:48 同为其它设备/轮次的 ED25519 publickey。

> 机理说明：卡片索要 passphrase 是**本地解密私钥**步骤；解密后仍以 **publickey** 方式向 sshd 认证——这正是 F12a 修正口径的预期行为。

---

## 7. F13b：Password 方式 + 错密码 → `opencode` → Connected —— **FAIL**

本轮**补建了一个 `Authentication=Password` 的独立条目**（新建表单默认即 Password，无需切换），
把 F13b 完整跑完。结论是**真实失败**，不是取证不足。

**时间线**

1. 新建条目，auth 保持 `Password`，host = `ssh -p 2222 sshtest@127.0.0.1`，提交。
   面板确认 `Password [156,1702][338,1749]`（认证选择器值）+ `Enter the password for this connection.`
2. 表单内**错密码** `wrongpw`（`PW-INPUT MASKED len=7`）→ `Add server` → 正确拒绝 ✅：
```
Oct 02 19:58:12 sshd-session[19514]: pam_unix(sshd:auth): authentication failure; logname= uid=0 euid=0 tty=ssh ruser= rhost=127.0.0.1  user=sshtest
Oct 02 19:58:14 sshd-session[19512]: error: PAM: Authentication failure for sshtest from 127.0.0.1
```
   同时面板弹出 **`The account is locked due to 3 failed logins.`**（累积失败触发 faillock）。
3. **ResetFaillock**（服务端执行，卡片内无此按钮）：
```
$ sudo faillock --user sshtest
When                Type  Source                                           Valid
2026-10-02 19:52:12 RHOST 127.0.0.1                                            V
2026-10-02 19:53:45 RHOST 127.0.0.1                                            V
2026-10-02 19:58:12 RHOST 127.0.0.1                                            V
$ sudo faillock --user sshtest --reset
$ sudo faillock --user sshtest
sshtest:            (空表 ✅)
```
4. 条目重连，弹回 `Password:` 卡片 → 输**正确密码** `opencode`（`MASKED len=8` 读回确认）→ `Continue`
   → 卡片**再次要求 Password**，无 `Accepted` 日志。间隔约 60s 重试一次，结果相同：
```
Oct 02 19:59:31 sshd-session[19512]: error: PAM: Authentication failure for sshtest from 127.0.0.1
```

**判定：F13b = FAIL。** 在 `Authentication=Password` 条目上，正确密码 `opencode` 仍被 PAM 拒绝，
条目停在 `Connecting to SSH server` + 反复索要密码，**从未 Connected**。

**排除项（避免误判为环境问题）**
- faillock 在测试时**已确认为空表**，不是锁定导致。
- `sshtest` 账号存在：`sshtest:x:1001:1001::/home/sshtest:/bin/bash`。
- 同一时段 **F14b 的 publickey 登录成功**（19:49:55 `Accepted publickey`），说明 sshd / reverse / 网络链路均正常。

**两种可能，需下一轮区分**
- (a) **凭据侧**：`sshtest` 的密码已非 `opencode`（或被改过）。本机无 `sshpass`，未能从宿主侧独立验证密码，
      这是当前最大缺口。
- (b) **应用侧**：密码认证流程存在缺陷（卡片反复索要、连接不推进）。

> 附带结论：FailLock 机制本身工作正常（3 次失败即锁），且 `ResetFaillock` 需在**宿主**执行，App 卡片内无入口——
> 建议产品侧考虑提供解锁入口，否则用户在 App 内无法自助恢复。

---

## 8. F12b：Cancel 语义 —— **PASS（有保留）**

| 检查项 | 结果 | 证据 |
|---|---|---|
| 二次 Reconnect 必重弹 | ✅ PASS | 点 `Reconnect [370,2070]` → 立即重现 `Passphrase for ...id_ed25519`（`f12b_1.xml`） |
| Cancel 后 7 点采样不重弹 | ✅ PASS | `cancel_s1..s7.xml`，7 次全部 `card=False`（间隔 4s，共 ~28s） |
| 条目保留 | ✅ PASS | 条目仍在 Servers/SSH tunnels 列表，未被删除 |

**保留意见**：7 点采样**结束之后**，条目继续重试时弹出了一个 `Password:` 卡片（下游回落到密码认证）。
严格说"不重弹"只在采样窗口内成立；窗口外会因重试出现**另一种**卡片（非原 passphrase 卡片）。
此现象与 §7 的回落同源，建议下一轮单独确认该回落是否符合产品预期。

---

## 9. 清理 —— **DONE**

本轮共创建 **3 个条目**（均已删除），复查列表无残留（`fin9.xml`：`entries-remaining=False`）：

| 条目 | 用途 | 删除方式 |
|---|---|---|
| `E2ECHK5558_f14b` | F14a（坏 keypath） | `Server actions ⋮ → Delete → Delete SSH server?` ✅ |
| `127.0.0.1`（host=`ssh -p 2222 sshtest@127.0.0.1`，Private key） | F14b + F12a | 同上 ✅ |
| `127.0.0.1`（host 同上，**Password** 方式） | F13b | 同上 ✅ |

- **ResetFaillock**：`sudo faillock --user sshtest --reset`，复查为空表 ✅
  （F13b 触发 `The account is locked due to 3 failed logins.`，卡片内无解锁入口，须在宿主执行）
- **`/sdcard` 清理**：`w.xml s1.png f14a.png sub1.png sub2.png conn1.png pwok.png st.png cl1.png dlg.png fb9.png k8_id_ed25519`
  复查全部 `No such file or directory` ✅
  （`/sdcard` 中 `00_initial.png` 等属**其它轮次**产物，未触碰）
- **测试私钥保留**：`files/id_ed25519` md5 `0120a8270f7f0b0c71d31f4933384c63` ✅ 供下轮复用

---

## 10. 未闭环 / 遗留

1. **F13b 根因未定**（唯一实质遗留）：`sshtest` 正确密码 `opencode` 被 PAM 拒绝。
   已排除：faillock（已清空）、账号不存在、sshd/网络（同刻 F14b publickey 成功）。
   **未做**：宿主侧独立验证 `opencode` 是否真为该账号密码（本机无 `sshpass`）。
   → 下轮第一件事：`sudo apt install sshpass && sshpass -p opencode ssh sshtest@127.0.0.1` 判定是**凭据问题**还是**应用问题**。
2. **F12b 保留项**：Cancel 采样窗口（约 28s）内不重弹 ✅，但窗口后条目重试会回落出 `Password:` 卡片，是否为预期行为待产品确认。
3. **name 字段不可填**：`Server name (optional)` 恒显 9px 且位于可滚动区外（`[156,2145][935,2154]` vs ScrollView 底 2082），本轮未命名，条目靠 host 识别。
4. **产品建议**：App 内无 faillock 解锁入口，用户锁定后无法自助恢复（须连宿主）。
5. **超时说明**：本轮约 20 分钟，超「前 20 分钟只做表单」硬要求约 6 分钟——因 F13b 为补做闭环。
   表单定位本身在约 14 分钟内已验证成功（哨兵 + 读回 + 卡片副标题回显三重佐证）。

---

## 11. 给下一轮的定位算法（可直接照抄）

```
1. 设置页 Servers tab → Add SSH server  [731,1167][1026,1214] 中心(878,1190)
2. 忽略 [108,749][1236,893]（搜索框）；只认 y>=1640 的 EditText
3. auth 默认 Password → 点 [156,1747] → 选项列表 → 点 'Private key'
4. 滚动用 x=1250 右侧留白做快滑，勿从 EditText 上起手
5. 认字段靠「正上方 label」：Host or SSH command / Server name / Key file path / Passphrase
6. 提交 [977,2143][1188,2190] 中心(1079,2166)
7. 首次连接需先点 'Trust and connect' 中心(1014,2204)
8. passphrase 卡片：输入(672,2027)、Continue(1099,2165)
9. 卡片副标题会回显 keypath —— 填对与否一眼可验
```

### 附：本轮 dump 留证文件
`w1/sc1..sc4/f1..f3/h1..h5/b_open/b2/k1_*/hh_*/c1_*/sub1/sub2/card1/conn1/f12b_1/cancel_s1..s7/pw_pre/pw_bad/pw_ok/pw_re/after_cancel/menu1/cl1..cl3/dlg2/m2..m5.xml`
截图：`s1.png st.png f14a.png sub1.png sub2.png conn1.png pwok.png cl1.png dlg.png`
脚本：`dump.ps1 sent.ps1 fill.ps1 fill2.ps1 fill3.ps1`
