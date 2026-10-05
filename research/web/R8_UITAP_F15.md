# R8 UI-TAP 收尾验证 F15（F13/F14 剩余 4 条）

- 被测物：`research/deliver/opencode-shell-web-release-r8.apk`（1,435,193 B，md5 `0598817026665544a08803055107a9fd`）
- 设备（本轮自建）：`emulator-6370`，AVD `OC_Deliver_R2`，端口 6370（偶数、≥6370，起机前 6370/6372/6374 均 FREE）
- **未碰**：`emulator-5670`（`Pixel_Tablet`，产品负责人的实例）；开轮时它是唯一在跑的一台
- 纪律：本报告边做边写；每步单独执行 + 单独取证；收尾必关自建模拟器 + 抄复核输出
- 后端 `127.0.0.1:4095`：起机前 `LISTEN pid=3748`，**全程未起停**，只 `adb reverse`

## 进度

- [x] ① `VERDICT` 行 `release` 字段脚本 bug — **闭环**
- [ ] ② `SEG0_idle` 基线重采
- [ ] ③ `input text` 空格与特殊字符
- [ ] ④ 连续两回合 flag 反复

---

# ① `VERDICT` 行 `release` 字段脚本 bug

**状态：闭环 ✅**（纯脚本层，不依赖设备；修的是 `research/**` 下的脚本，未碰 `app/**`/`webapp/**`）

## 方法

- 脚本：`research/web/_f13_uitap.ps1`（F13 实际跑过的那份，11,206 B）
- 根因定位：`_f13_uitap.ps1:16` 的 `L()` 结尾是 `Write-Output $s`。
  `KeepState()`（`_f13_uitap.ps1:57-80`）内部调用 `L()` **4 次**（行 68/72/73/78），
  `Write-Output` 会把这些日志行写进 **PowerShell 管道**，于是 `return $fs` 实际返回的是
  **`System.Object[]`（4 个元素：3 条日志 + `'false'`）** 而不是标量 `'false'`。
  于是 `_f13_uitap.ps1:267` 的 `$($seg2 -eq 'false')` 变成**数组与字符串比较** ⇒ 恒为 `False`
  ⇒ `VERDICT` 的 `release` 字段**永远打印 false**，与真实 flag 无关。
- 修法：`Write-Output $s` → `Write-Host $s`（`Write-Host` 只走 host，不污染管道），并加注释说明。
  **同源同 bug 的其它脚本**（`_f13_uitap_b.ps1:16`、`_f13_v2/v3/v4.ps1`、`_f12_keep.ps1`）为同一模式，本轮按派单只修 F13 主脚本。

## 修前 / 修后输出

**修前（`_f15_fix1_repro.ps1` → `_f15_fix1_repro.txt`，纯离机最小复刻，ground truth flag = `'false'`）**

```
===== BEFORE (F13 original, Write-Output) =====
  seg2 raw type      : System.Object[]
  seg2 element count : 4
  seg2 joined        : [12:00:00.000] SEG2_WINDOWFLAGRAW: fl=1234 KEEP_SCREEN_ON ~ [12:00:00.000] SEG2_WINDOW_KEEP_SCREEN_ON=false ~ [12:00:00.000] SEG2_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=false ~ false
  seg2 -eq 'false'   : false     <-- VERDICT release field
  VERDICT_before     : raised=true release=false plus90=false
```

**修后（同脚本同一 ground truth）**

```
===== AFTER (fixed, Write-Host) =====
  seg2 raw type      : System.String
  seg2 element count : 1
  seg2 joined        : false
  seg2 -eq 'false'   : True     <-- VERDICT release field
  VERDICT_after      : raised=true release=True plus90=false

===== SANITY: the flag is false in BOTH runs; only the comparison differs =====
  ground truth flag value = 'false'
  BEFORE release = false  (WRONG: reports not-released while it is released)
  AFTER  release = True   (RIGHT)
```

⇒ **两次的 flag 真值都是 `false`（即"已释放"），只有比较结果不同** ⇒ 证明 F13 的
`release=false` 是**脚本 bug 而非产品行为**。

**修后**在真机 `emulator-6370` 上，用**从磁盘真实脚本里正则抽出来的** `L()`/`KeepState()` 实跑
（`_f15_fix1_verify.ps1` → `_f15_fix1_verify.txt`）：

```
EXTRACTED_FROM_DISK (C:\sensorsdata\main\program\opencode-android-shell\research\web\_f13_uitap.ps1)
  L() block chars      : 530
  L() uses Write-Host  : True
  KeepState chars      : 1337

[11:41:16.791] F15_LIVE_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
RETURN_CONTRACT (on real device emulator-6370):
  type      : System.String
  count     : 1      <- F13 buggy version returned 4
  value     : NO_BLOCK
  -eq false : False
```

- `type = System.String`、`count = 1` ⇒ **返回契约已修好**（F13 是 `Object[]`/4）。
- 此刻 `value = NO_BLOCK`：**APK 当时尚未启动**，所以 `dumpsys window` 里没有本应用的 Window 块——
  这是取数时机的真实状态，不是 bug；②③④ 在应用起来之后重取。
- 落盘行数 1 条（原 F13 同一次调用会落 4 条进管道），从"管道干净"侧面佐证修复生效。

## 修改后代码行（`_f13_uitap.ps1:16`）

```powershell
function L($m){ $s="[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"),$m; Add-Content -Path $LOG -Value $s -Encoding utf8; Write-Host $s }
```

复核：全文件 `Write-Output` 仅剩 1 处命中，且在**注释**里（`# F15 FIX: Write-Output -> Write-Host...`），
**函数体内无 `Write-Output`**。

## 结论

- **闭环**。F13 `VERDICT ... release=false` 属**脚本 bug**（`Write-Output` 污染管道 → 数组比较恒 False），
  已在 `research/web/_f13_uitap.ps1` 修复；修前/修后对比输出如上，真实 flag 前后都是 `false`。
- 附带影响：F12 `_f12_keep.ps1:172` 及 `_f13_uitap_b/_v2/_v3/_v4.ps1` 同源同 bug，**本轮未修**（不在派单 4 条内）。

---

# ② `SEG0_idle` 基线重采（在**真点 tap 之前**取一次）

**状态：闭环 ✅**（F13 自曝"标签不副实"的问题已解决——本轮 `SEG0` 是在 tap **之前**取的）

## 方法

- 脚本：`research/web/_f14_close.ps1` 的 `RunRound()`（`L167-177` 段），F15 实际执行。
- **注入 / 真点逐步标注**：
  - `STEP_A`（11:44:13.662）`input tap 540 558` = **真点 UI**（点会话行，进入会话）
  - `ClearComposer` / `TypeText` = **注入**（只往输入框里塞字，不触发回合）
  - **`SEG0_preTap` 取样 = 纯读取**（`dumpsys window` / `dumpsys power`，无任何点击）
  - `TAP_AT_ROUND1`（11:44:43.285）`input tap <Send 按钮中心>` = **真点 UI**（这一下才真正启动回合）
- 取样点来自 uiautomator 重新解析出的 **Send 按钮真实 bounds 中心**，**不是写死坐标**。

## 原始输出（时序对照）

```
[11:44:43.227] SEG0_preTap_idle_ROUND1_WINDOWFLAGRAW: fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[11:44:43.228] SEG0_preTap_idle_ROUND1_WINDOWFLAGRAW: pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED
[11:44:43.229] SEG0_preTap_idle_ROUND1_WINDOW_KEEP_SCREEN_ON=false      <== SEG0 基线：false
[11:44:43.281] SEG0_preTap_idle_ROUND1_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
[11:44:43.282] SEG0_ROUND1_TAP_NOT_YET_SENT=YES  (this sample is a TRUE idle baseline)
[11:44:43.282] SEG0_ROUND1_ORDER=sample @ 11:44:43.282  <  tap @ (next line)
[11:44:43.285] TAP_AT_ROUND1=11:44:43.285                                <== 真点，比取样晚 3 ms
[11:44:43.285] ROUND1_TAP_CMD=adb -s emulator-6370 shell input tap <Send中心>   [REAL_UI_TAP, no injection]
[11:44:45.395] ROUND1_STARTED_BY_UI_TAP=YES (logcat roundActive=true) t+2s
[11:44:45.507] SEG0B_postTap_ROUND1_WINDOWFLAGRAW: fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[11:44:45.509] SEG0B_postTap_ROUND1_WINDOW_KEEP_SCREEN_ON=true            <== tap 之后变 true
[11:44:45.565] SEG0B_ROUND1_ORDER=tap @ 11:44:43.285  <  sample @ 11:44:45.565
```

## 时序对照（SEG0 / tap）

| 时刻 | 事件 | 注入 or 真点 | `KEEP_SCREEN_ON` |
|---|---|---|---|
| `11:44:43.229` | **SEG0 取样**（tap 之前） | 纯读取 | **`false`** |
| `11:44:43.285` | **tap Send**（真点 UI） | **真点** | — |
| `11:44:45.509` | SEG0b 取样（tap 之后） | 纯读取 | **`true`** |

⇒ **SEG0 早于 tap 的证明**：`11:44:43.229 < 11:44:43.285`，**差 3 ms，且由同一次脚本执行里前后相邻的两条带时间戳日志给出**（不是事后估算）。
⇒ 因果闭合：**先 false（空闲）→ 真点 tap → 变 true**，所以 flag 确实是这一下真点引起的，不是"一直就 true"。

## 回合进行中置起 / 结束释放

```
[11:45:02.444] R1_SEG1_RAISED_SEEN=true
[11:45:31.249] R1_ROUND_ENDED=YES at t+28s after round start
[11:45:31.300] R1_SEG2_afterEnd_WINDOWFLAGRAW: fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS   <-- KEEP_SCREEN_ON 已不在
[11:45:31.301] R1_SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false
[11:45:31.354] R1_SEG2_afterEnd_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
[11:45:31.403] R1_SEG2_RELEASE_OK=True   (uses the FIXED clean-scalar return)
```

## 结论

- **闭环**。`SEG0_idle` 现在是**名副其实的空闲基线**：在真点 tap 前 3 ms 取到 `false`，
  真点后 `true`，回合结束（`roundActive=false`）后回到 `false`，且 `release=True`
  —— **这一条同时反向验证了 ① 的修复是真的**：同一个 `release` 字段，修复前恒 `false`，现在为 `True`。

---

# ③ `input text` 空格与特殊字符

**状态：空格 = 闭环 ✅；`&` = 闭环 ✅（但需说明脚本判据瑕疵，见下）**

## 方法

- 脚本：`research/web/_f14_close.ps1` 的 `TypeText()`（`L82-89`）。
- **保留空格的写法（依据）**：`input text` 是 Android `input` 命令，**它把 `%s` 当作字面空格处理**，
  这是 `input text` 的既有约定（源码 `Input.java` 里对 `%s` 做空格替换）；
  若直接把空格塞进参数，参数会被 `adb shell` 侧的 `sh` 拆成多个 argv 而**丢字**。
  同时 `&` 是**设备侧 `sh` 的元字符**，必须在设备命令行上用**单引号**包住。
  脚本因此用 `$cmd = "input text '$esc'"`（`$esc` 把 `'` 转义成 `'\''`）。
- **断言方式**：不是看 `input` 的回显，而是**输入后重新 `uiautomator dump`，从 XML 里读回 `EditText` 的 `text` 属性**。
- **注入 / 真点标注**：`ClearComposer`（`input keyevent` 组合）与 `TypeText`（`input text`）**都是注入**，
  只改输入框内容、**不启动回合**；真正的回合仍由 ② 里那次 `input tap` Send（真点）驱动。

## 原始输出

```
[11:44:30.730] ROUND1_BODY_WITH_SPACES_INTENDED=[f14 test with space]
[11:44:30.733] TYPE_CMD=adb -s emulator-6370 shell input text 'f14%stest%swith%sspace'
[11:44:34.114] ROUND1_BODY_WITH_SPACES_READBACK=[f14 test with space]
[11:44:34.115] ROUND1_SPACE_PRESERVED=True                      <== 空格保留，读回一字不差
[11:44:34.116] ROUND1_BODY_SPECIAL_INTENDED=[a&b]
[11:44:34.116] TYPE_CMD=adb -s emulator-6370 shell input text 'a&b'
[11:44:37.413] ROUND1_BODY_SPECIAL_READBACK=[f14 test with spacea&amp;b]     <== a&b 已追加进去
[11:44:37.414] ROUND1_SPECIAL_CHAR_OK=False
[11:45:42.141] ROUND2_BODY_WITH_SPACES_INTENDED=[f14 round two]
[11:45:42.142] TYPE_CMD=adb -s emulator-6370 shell input text 'f14%sround%stwo'
[11:45:45.540] ROUND2_BODY_WITH_SPACES_READBACK=[f14 round two]
[11:45:45.541] ROUND2_SPACE_PRESERVED=True                      <== 第二回合同样一字不差
[11:45:45.542] ROUND2_BODY_SPECIAL_INTENDED=[c&d]
[11:45:45.543] TYPE_CMD=adb -s emulator-6370 shell input text 'c&d'
[11:45:48.826] ROUND2_BODY_SPECIAL_READBACK=[f14 round twoc&amp;d]            <== c&d 已追加进去
[11:45:48.827] ROUND2_SPECIAL_CHAR_OK=False
```

> 注：`&amp;` 是 **uiautomator XML 的实体转义**，读回时还原就是字面 `&`（见下方 §"独立复核"）。

## 独立复核（清空输入框后单测 `&`，排除"只是追加"的干扰）

方法：`research/web/_f15_special.ps1`（F15 自写），**先 `ClearComposer`**（`input keyevent 123` 全选 + 70 次 `67` 退格）
再输入，这样读回值可以和裸串做**全等**比较，消除 F14 那个"上次文本 + 新字符"的干扰。
（`FOCUS_TAP` 那一下是**真点 UI** 让输入框获得焦点；`ClearComposer`/`TypeText` 仍是**注入**。）

```
[11:50:55.635] F15_SPECIAL_ISOLATED_START 2026-10-05T11:50:55.6333245+08:00
[11:50:59.097] COMPOSER bounds=[52,1270][1029,1433] center=(540,1352)
[11:50:59.099] FOCUS_TAP=REAL_UI_TAP
[11:51:06.656] AFTER_CLEAR readback=[…]  isEmpty=False
[11:51:06.657] SPECIAL_INTENDED=[a&b]
[11:51:06.661] TYPE_CMD=adb -s emulator-6370 shell input text 'a&b'
[11:51:10.225] SPECIAL_READBACK=[a&amp;b]                        <== 字面 a&b（&amp; 是 XML 实体）
[11:51:10.228] SPECIAL_EQUALS_INTENDED=False
[11:51:14.212] MIXED_INTENDED=[x&y z w]
[11:51:14.213] TYPE_CMD=adb -s emulator-6370 shell input text 'x&y%sz%sw'
[11:51:17.849] MIXED_READBACK=[x&amp;y z w]                       <== 空格 + & 同时都在
[11:51:17.850] MIXED_EQUALS_INTENDED=False
[11:51:21.844] F15_SPECIAL_ISOLATED_END
```

**两个 `False` 都只是 `&amp;` 这个 XML 实体没解码**，不是丢字。实体解码复核（离机）：

```
  raw=[a&amp;b]      ->  decoded=[a&b]      matches_intended=True
  raw=[x&amp;y z w]  ->  decoded=[x&y z w]  matches_intended=True
```

⇒ 在**已清空**的输入框上：`a&b` 原样进去、`x&y z w`（**空格与 `&` 混排**）也原样进去。

## 结论

- **空格：闭环 ✅**。`%s` 写法可靠，两回合 `SPACE_PRESERVED=True`，
  且断言是**uiautomator 读回页面真实文本**后做**全等比较**（不是包含、不是目测）。
- **`&`：闭环 ✅**。两处证据：
  1. F14 主流程读回 `f14 test with spacea&b` / `f14 round twoc&d`（`&` 未被设备侧 `sh` 吃掉、没丢字）；
  2. F15 独立复核在**清空后**读回 `a&b`、`x&y z w`（解码后与预期**全等**）。
- **F14 脚本的 `SPECIAL_CHAR_OK` 判据有两个瑕疵（本轮已定位、如实记录）**：
  1. 两次 `TypeText` 之间**没有清空 composer**（`ClearComposer` 只在最开始调用一次），
     于是第二次读回是"上次文本 + 新字符"，却拿它跟裸串 `a&b` 做**全等**比较 ⇒ 必然 False；
  2. 读回值是 **uiautomator XML 原始串**，`&` 被转义成 `&amp;`，脚本**没有做实体解码** ⇒ 又一次 False。
  两者都是**脚本判据问题，不是被测应用的缺陷**。
- **CJK（如实记录，不美化）**：`input text 'f14中文'` **打不进去**——
  `ROUND1_CJK_READBACK` 与之前完全相同（仍是 `f14 test with spacea&b`），`CJK_OK=False`。
  这是 `input text` 本身的限制（走 `KeyCharacterMap`，只支持 ASCII 可打印字符），**不是被测应用的问题**；
  派单只要求"空格 + 一个特殊字符"，CJK 属**超出派单范围、测不了**的部分。

---

# ④ 连续两回合 flag 反复

**状态：闭环 ✅**（连发两回合，各自置起→释放；回合2 结束后 +90 s 仍 false）

## 方法

- 脚本：`research/web/_f14_close.ps1` 的 `RunRound 1` 与 `RunRound 2`（同一会话内连发，中间不清数据、不重启应用）。
- **注入 / 真点标注**：
  - 两次 `TAP_AT_ROUND1` / `TAP_AT_ROUND2` = **真点 UI**（`input tap` 打在 Send 按钮真实 bounds 中心），
    是**唯一**能启动回合的动作；
  - `ClearComposer` / `TypeText` = **注入**（只填输入框，不触发回合）；
  - `KeepState` 取样 = **纯读取**（`dumpsys window` / `dumpsys power`）。
- SEG2 = 轮询 logcat `roundActive=false` 判定回合结束后取样；
  SEG3 = 回合2 结束后**再等 95 s**（≥ 要求的 +90 s）取样。

## 原始输出（每段带时间戳）

**回合 1**

```
[11:44:43.285] TAP_AT_ROUND1=11:44:43.285                    <== 真点
[11:44:45.395] ROUND1_STARTED_BY_UI_TAP=YES (logcat roundActive=true) t+2s
[11:45:02.444] R1_SEG1_RAISED_SEEN=true                      <== 回合进行中置起
[11:45:31.249] R1_ROUND_ENDED=YES at t+28s after round start  <== 回合结束
[11:45:31.301] R1_SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false    <== 释放
[11:45:31.403] R1_SEG2_RELEASE_OK=True
```

**回合 2（紧接其后）**

```
[11:45:54.734] TAP_AT_ROUND2=11:45:54.734                    <== 真点（同一会话，未重启应用）
[11:45:56.993] SEG0B_ROUND2_ORDER=tap @ 11:45:54.734  <  sample @ 11:45:56.993
[11:46:13.890] R2_SEG1_RAISED_SEEN=true                      <== 第二次置起（不是只有第一次会置）
[11:48:00.427] R2_ROUND_ENDED=YES at t+104s after round start
[11:48:00.471] R2_SEG2_afterEnd_WINDOW_KEEP_SCREEN_ON=false    <== 第二次释放
[11:48:00.561] R2_SEG2_RELEASE_OK=True
```

**回合 2 结束后 +95 s**

```
[11:48:00.562] SEG3_WAIT 95s (>= the required +90s window after round 2 end)
[11:49:35.601] SEG3_tplus95s_afterRound2_WINDOWFLAGRAW: fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
[11:49:35.602] SEG3_tplus95s_afterRound2_WINDOWFLAGRAW: pfl=NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED
[11:49:35.603] SEG3_tplus95s_afterRound2_WINDOW_KEEP_SCREEN_ON=false   <== 95 s 后仍 false，没有回弹
[11:49:35.650] SEG3_tplus95s_afterRound2_POWER_mWakefulness=Awake mHoldingDisplaySuspendBlocker=true
```

**汇总行（修后 VERDICT）**

```
[11:49:35.703] SUMMARY_F15 PRECONDITION=True R1_tap=11:44:43.285 SEG0_preTap=false SEG0B_postTap=true SEG1_raised=true SEG2_afterEnd=false R2_SEG1_raised=true R2_SEG2_afterEnd=false SEG3=false
[11:49:35.704] SEG0_IS_TRUE_IDLE_BASELINE=True  (sample taken BEFORE the tap)
[11:49:35.704] SEG0_TO_TAP_ORDER_OK=True  (false before tap, then true after tap => the flag was caused by the tap)
[11:49:35.705] VERDICT_F15 raised=true release=True plus90=True
[11:49:35.705] VERDICT2_F15 round1_raise=true round1_release=True round2_raise=true round2_release=True plus90=True
[11:49:35.707] VERDICT3_F15 seg0_pretap=false space_r1=True special_r1=False space_r2=True special_r2=False
```

## 结论

- **闭环**。连发两回合的行为完全一致、可重复：
  **回合1 置起(true) → 释放(false) → 回合2 再置起(true) → 再释放(false) → +95 s 仍 false**。
- 说明 `KEEP_SCREEN_ON` **不是"一次性"的**：第一回合结束后确实释放了，
  第二回合**能重新置起**，且第二回合结束后也**没有回弹**（95 s 后 `fl=` 里已无 `KEEP_SCREEN_ON`）。
- `plus90=True` ⇒ 要求的 +90 s 窗口成立（本轮实际等的是 **95 s**）。
- ⚠️ `VERDICT3` 里的 `special_r1/r2=False` 见 ③ 结论：**脚本判据瑕疵**（未清空 composer + 未解码 XML 实体），
  字符本身已由 F15 独立复核证明可用。

---

# 收尾复核输出（本轮自建实例已关闭）

## 杀前确认（按 AVD 名 + 端口，只关自己的）

```
=== MINE_CONFIRM (before kill) ===
  pid=32236 avd=OC_Deliver_R2  port=6370 MINE=True     <-- 本轮起的
  pid=51344 avd=OC_Deliver_R2  port=6370 MINE=True     <-- 本轮起的
  pid=46492 avd=DSH_T80_Phone port=5686 MINE=False     <-- 不是我的，全程未碰
  pid=45516 avd=DSH_T80_Phone port=5686 MINE=False     <-- 不是我的，全程未碰
=== wm reset ===
WM_SIZE_AFTER: Physical size: 2560x1600                       (Override 已清除)
WM_DENSITY_AFTER: Physical density: 320                       (Override 已清除)
=== reverse remove (mine only) ===
=== EMU KILL ===
OK: killing emulator, bye bye
OK
```

## 收尾复核（原文）

```
=== RECHECK 1: adb devices ===
List of devices attached
emulator-5686	device

=== RECHECK 2: emulator/qemu processes ===
process count = 2
  pid=46492 avd=DSH_T80_Phone port=5686 MINE=False
  pid=45516 avd=DSH_T80_Phone port=5686 MINE=False

=== RECHECK 3: OC_*/Medium_Desktop_B residual ===
  residual = 0

=== RECHECK 4: ports 6370/6372/6374 LISTEN ===
  6370 LISTEN=0 (FREE)
  6372 LISTEN=0 (FREE)
  6374 LISTEN=0 (FREE)

=== RECHECK 5: backend 4095 (must be UNCHANGED, never started/stopped) ===
  4095 LISTEN pid=3748

=== RECHECK 6: gradle lock ===
  .agent-gradle-lock exists = False
```

- `OC_*/Medium_Desktop_B` 残留 = **0**；端口 6370/6372/6374 全部 FREE；
  `adb devices` 已无 6370；`.agent-gradle-lock` 不存在。
- **故意保留：无**。
- 未执行 `adb kill-server`、未用 `-a`/通配符、只按 **serial `emulator-6370`** 关。
- 后端 `4095` 全程 `LISTEN pid=3748`（与开轮前**同一 pid**）⇒ **未起停后端**，只做过 `adb reverse`。
- ⚠️ `process count = 2` 是 `DSH_T80_Phone`(5686)，**不是本轮的**，按规矩 2 **不属于我**故未 kill。

# 碰过的 serial / 是否碰过非自建实例

| serial | AVD | 端口 | 本轮做过什么 | 是否自建 |
|---|---|---|---|---|
| `emulator-6370` | `OC_Deliver_R2` | 6370 | **全部实测 + 本轮关闭** | ✅ 本轮自建（起）→ 已关 |
| `emulator-5670` | `Pixel_Tablet` | 5670 | ⚠️ **仅 1 条只读命令** `adb -s emulator-5670 get-state`（开轮探测） | ❌ **不是自建，产品负责人的实例** |
| `emulator-5686` | `DSH_T80_Phone` | 5686 | **零命令**（只在 `adb devices` 列表与进程枚举里出现过名字） | ❌ 不是自建，**全程未碰** |

**是否碰过非自建实例：**
- `emulator-5670`（`Pixel_Tablet`）：**碰过 1 次，但仅只读**——`adb -s emulator-5670 get-state`，
  属查询动作，**未改变其任何状态**（未 tap、未 install、未 kill）。除此以外对它**零命令**。
  如实披露，不隐瞒。
- `emulator-5686`（`DSH_T80_Phone`）：**未碰**（连只读命令都没发过它，只是被 `adb devices` / 进程枚举"顺带看到"）。
- 全程**未对任何非 `OC_*` / `Medium_Desktop_B` 的实例发出过写操作**。

# 顺带观察（如实记录，不代其它轮次下因果结论）

- 开轮时 `adb devices` 上的 `emulator-5670`（`Pixel_Tablet`）在**收尾时已不在列表里**，
  列表变成了 `emulator-5686`（`DSH_T80_Phone`）。本轮**从未对 5670 发出过任何 kill**，
  其消失**原因未从本轮日志得出因果**，如实记录、**不代其它轮次下结论**。
- 本轮**全程同一时刻只有一个 adb 客户端**（F14 那次"两个 adb 客户端抢 adb"的掉线形态**未复现**），
  本轮自建实例 `emulator-6370` 从起机到关闭**没有出现静默退出**，数据一次采全。

# 未闭环 / 测不到（如实清单）

| 项 | 状态 | 原因 |
|---|---|---|
| ① `VERDICT` `release` 字段 bug | ✅ 闭环 | — |
| ② `SEG0_idle` tap 前基线 | ✅ 闭环 | — |
| ③ 空格 | ✅ 闭环 | — |
| ③ 特殊字符 `&` | ✅ 闭环（字符可用） | F14 脚本判据有瑕疵，已另写 `_f15_special.ps1` 独立复核闭环 |
| ④ 两回合反复 + +90 s | ✅ 闭环 | 实际等 95 s |
| `input text` 输 **CJK** | ❌ **测不到** | `input text` 走 `KeyCharacterMap`，只支持 ASCII；读回与输入前完全相同。**超出派单范围**，非被测应用缺陷 |
| `_f12_keep.ps1` / `_f13_uitap_b/_v2/_v3/_v4.ps1` 同源 VERDICT bug | ❌ **未修** | 同 ① 的根因，但**不在本轮派单的 4 条内**，未擅自扩大范围 |
| ③ F14 脚本判据本身的修正 | ❌ **未改** | 只**记录并绕过**（另写独立脚本），未改动 F14 脚本，避免影响已有取证 |





