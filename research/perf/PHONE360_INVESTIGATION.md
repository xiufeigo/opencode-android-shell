# PHONE360 调查：360dp 上"数据区停在 Loading 骨架屏"是不是真问题？

**轮次**：V10 ｜ **日期**：2026-10-05 ｜ **调查员**：小屏可用性调查工程师
**设备**：`emulator-6210`（AVD `OC_Deliver_R2`，镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`）
**宿主端口**：6210（偶数、≥6210；起之前实测 6210–6224 全 FREE）
**后端**：`127.0.0.1:4095` 由 Lead 起好（`LISTEN pid=3748`），**我全程未起停**，只做 `adb reverse tcp:4095 tcp:4095`
**APK**：`webapp/build/outputs/apk/debug/webapp-debug.apk`（debug 才有 WebView CDP）
**WebView**：`versionName=124.0.6367.219`（`tooOld=false`，G1 前置检查放行）

---

## 一、结论（二选一）

> # ✅ 只是慢/时序 —— **不是缺陷**
>
> 更准确地说：**V9 那张截图根本不是"360dp 小屏缺陷"，而是两个测量缺陷叠加**：
> ① V9 那轮 **口令是错的**（后端 401），页面永远等不到数据 —— 这与视口宽度无关；
> ② V9 **切完几何立刻截图**，而骨架屏要 ~2.2 s 才出现、数据 ~2.4–2.6 s 才到。
>
> 在**同一台设备、同一轮、口令正确**的条件下，360dp 与 412dp **全部 6 次冷启都离开了 Loading**，
> 两档到达时间差 ~95 ms（远小于轮内抖动 191 ms），**没有任何一档"永不离开"**。

---

## 二、对照表（同一台设备 / 同一轮 / 每档 3 次**冷启**）

**"冷启"的定义（回应任务里"是冷启还是热切"这一问）**：
每轮都是 `am force-stop` → **等 3 s** → `am start -W`，`am` 自报 `LaunchState: COLD`。
**几何切换 ≠ 观测起点**：切 `wm size/density` 后先 `Start-Sleep 4 s` 让 `system_server` 收敛，
再 `force-stop` + 3 s 静置，然后才 `am start` 并把 **T0 定在 `am start` 那一瞬**。
⇒ **不存在"切完就热切、截图太早"**：每次都是真正的进程冷启，且采样从 T0 起以 ~150–220 ms 间隔持续到数据出现。

### 到达时间（ms，T0 = `am start`）

| 几何 | `wm` | 运行 | 外壳出现 (nodes>5) | 进入骨架屏 (loading=True) | **离开 Loading（真实文本出现）** | 是否离开 |
|---|---|---|---|---|---|---|
| **360dp** | 720x1280@320 | run1 | 1403 | 2178 | **2388** | ✅ 离开 |
| **360dp** | 720x1280@320 | run2 | 1292 | 2162 | **2529** | ✅ 离开 |
| **360dp** | 720x1280@320 | run3 | 1340 | 2164 | **2581** | ✅ 离开 |
| **412dp** | 824x1640@320 | run1 | 1331 | 2129 | **2343** | ✅ 离开 |
| **412dp** | 824x1640@320 | run2 | 1359 | 2151 | **2405** | ✅ 离开 |
| **412dp** | 824x1640@320 | run3 | 1322 | 2184 | **2464** | ✅ 离开 |

| 汇总 | 外壳均值 | 骨架屏时长 | **离开 Loading 均值** |
|---|---|---|---|
| 360dp | 1345 ms | ~331 ms | **2499 ms** |
| 412dp | 1337 ms | ~249 ms | **2404 ms** |
| 差 | +8 ms | +82 ms | **+95 ms** |

**判读**：360dp 比 412dp 慢 **95 ms**，而同一档内三次运行的自身抖动是 **191 ms**（360dp 2388→2581）
⇒ **这 95 ms 落在噪声里，不构成"360dp 更慢"的证据，更不构成缺陷。**

### 视口读回（证明两档真的不同，且都生效）

```
READBACK wmSize: Physical size: 2560x1600 | Override size: 720x1280 |      <- 360dp
READBACK wmDensity: Physical density: 320 |
READBACK wmSize: Physical size: 2560x1600 | Override size: 824x1640 |     <- 412dp
READBACK wmDensity: Physical density: 320 |
CDP 视口: vp=360x640  (360dp 档)   /   vp=412x820  (412dp 档)
```

**CDP 直接读回视口**是本轮最硬的证据：`window.innerWidth` 分别是 360 / 412，
证明**页面自身看到的就是两档不同宽度**（不是"切了 `wm` 但页面没变"）。

---

## 三、排除"截图太早"的过程（逐步留证）

### 步骤 1：先抓到了骨架屏**真实存在**的那一瞬间

不是只截"有数据"的终态，而是把 `loading=True` 那一帧也抓到了 —— **6 次全部抓到**：

```
T+2178ms vp=360x640 nodes=493 loading=True  txt=Home|0|All projects|Loading
T+2162ms vp=360x640 nodes=493 loading=True  txt=Home|0|All projects|Loading
T+2164ms vp=360x640 nodes=493 loading=True  txt=Home|0|All projects|Loading
T+2129ms vp=412x820 nodes=493 loading=True  txt=Home|0|All projects|Loading
T+2151ms vp=412x820 nodes=493 loading=True  txt=Home|0|All projects|Loading
T+2184ms vp=412x820 nodes=493 loading=True  txt=Home|0|All projects|Loading
```

**两档的骨架屏时长分别是 ~331 ms 与 ~249 ms** —— 骨架屏是**转瞬即逝**的，
**根本不是一个"停在上面不动"的状态**。这一点直接否掉"卡死"。

### 步骤 2：抓到骨架屏**前后相邻**的两帧（同一档连续采样，不是两次独立启动）

360dp run1 完整时间轴：

```
T+0     am start -W ... LaunchState: COLD | TotalTime: 1111
T+1403  vp=360x640 nodes=64  loading=False txt=              <- 外壳已起，但数据区还空
T+1630  vp=360x640 nodes=64  loading=False txt=
T+1802  vp=360x640 nodes=64  loading=False txt=
T+2178  vp=360x640 nodes=493 loading=True  txt=Home|0|All projects|Loading   <- 骨架屏
T+2388  vp=360x640 nodes=910 loading=False txt=Home|0|All projects|Today|O|Counting from 1 to 4000, one num   <- 数据到
```

412dp run1 同样形态：

```
T+1331  vp=412x820 nodes=62  loading=False txt=
T+2129  vp=412x820 nodes=493 loading=True  txt=Home|0|All projects|Loading
T+2343  vp=412x820 nodes=910 loading=False txt=Home|0|All projects|Today|O|Counting from 1 to 4000, one num
```

**"骨架屏 → 数据"这一步在两档都发生了，且都在启动后 ~2.4 s 内完成。**

### 步骤 3：截图体积反证（终态截图 vs V9 空白截图）

| 截图 | 字节 | 含义 |
|---|---|---|
| `out_v9/v9_phone360dp_render.png` | **23,971 B** | V9 那一轮：白屏 + 骨架屏（`whitePct=99.2`） |
| `out_v10/phone360dp_run1..3.png` | **104,799 / 105,256 / 105,256 B** | 本轮 360dp：真实数据 |
| `out_v10/settled_360dp.png` | **118,510 B** | 本轮 360dp 静置终态 |
| `out_v10/settled_412dp.png` | **167,927 B** | 本轮 412dp 静置终态 |

**同一档几何，字节数差 4.4 倍** ⇒ V9 那张不是"360dp 渲染不出来"，而是**那一轮压根没数据可渲染**。

### 步骤 4：V9 那张截图为什么"像卡住"（原始行）

V9 自己的原始输出（`research/perf/out_v9/sweep_raw.txt`，360dp cell）：

```
LOG 10-04 23:29:50.821  6821  6821 I OcWebHost: F5 probe before load path=/api/info
LOG 10-04 23:29:50.843  6821  6821 W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
...
  proxyGET=0  OCRES=54  DIALOG_TAPS=(438,735)
```

**关键：`AUTH FAILED code=401` + `proxyGET=0`** ⇒ 那一轮**一个数据请求都没成功**。
`proxyGET=0` 意味着后端**从未向页面交付过数据**，此时无论多宽的屏幕都只会停在骨架屏。

**反证：V9 的 401 并非 360dp 独有 —— 全部 6 个 cell 都是 401**（`out_v9/formfactor.json`）：

| V9 cell | uiAvailableMs | proxyGET | renderWhitePct | dialogLikely |
|---|---|---|---|---|
| phone360dp | 10857 | **0** | 99.2 | true |
| fold442dp | 10745 | **0** | 99.6 | true |
| fold708dp | 10888 | **0** | 99.8 | true |
| bound700dp | 10769 | **0** | 99.7 | true |
| bound820dp | 10809 | **0** | 99.7 | true |
| tablet1366x768 | 10950 | **0** | 99.7 | true |

**1366dp 的平板也是 `proxyGET=0` + 99.7% 白** ⇒ 这与视口宽度**没有任何相关性**，
是**那一次运行的环境问题（口令错）**，被 V9 误读成了"小屏问题"。

> ⚠️ 这一条也说明 V9 关于"第二切换点落在 820–1366dp 之间"的推论**不能由那批数据支撑** ——
> 那批 6 个 cell **没有一个拿到了数据**，因此无法判断任何断点行为。本轮不评价该结论（见"未闭环"）。

---

## 四、根因：V9 现象的根因（不是 360dp 的）

**是环境/凭据问题，不是视口尺寸问题。** 证据链：

1. **V9 那轮口令错**：日志 `F5 probe AUTH FAILED code=401`；本轮首次播种时我在同一台设备上
   也复现了完全相同的形态 —— 用的就是这个 AVD 上残留的旧口令：

   ```
   10-04 23:49:35.521 3483 3483 W OcWebHost: F5 probe AUTH FAILED code=401; showing retryable dialog
   10-04 23:50:04.774 3628 3628 I OcWebHost: no password in SecretStore, asking
   10-04 23:50:13.517 3628 3628 I OcWebHost: credentials stored backend=http://127.0.0.1:4095 passwordPresent=true
   10-04 23:50:13.697 3628 3628 I OcWebHost: F5 probe OK code=200, loading official UI
   ```

   `pm clear` → 重新播种正确口令 `opencode` ⇒ 立刻变成 `probe OK code=200`，页面正常加载。

2. **代码侧也对得上**：`WebHostActivity.kt:359-367` 走"先探再加载"，
   `BackendProbe.kt:135-141` 把 401 判成 `AuthFailed`；
   而**上游 SPA 在 `/api/**` 全 401 时没有任何 401 提示分支**，骨架屏就是它"等不到数据"的样子
   （`BackendProbe.kt:12-25` 的类注释把这条写得很清楚）。
   ⇒ **"停在骨架屏"本来就是错口令的必然表现，与屏幕宽度无关。**

3. **本轮正确口令下的网络证据：请求全部成功发出**（`OcWebHost/Proxy` 原始行，6 轮一致）：

   ```
   PROXY_401=0
   API_GETS=GET http://127.0.0.1:4095/api/event ;; GET http://127.0.0.1:4095/api/info ;;
           GET http://127.0.0.1:4095/api/location ;; GET http://127.0.0.1:4095/api/project ;;
           GET http://127.0.0.1:4095/api/session?limit=5000&order=desc&parentID=null ;;
           GET http://127.0.0.1:4095/api/session/active ;; GET http://127.0.0.1:4095/api/location?location%5Bdirectory%5D= ;;
           GET http://127.0.0.1:4095/api/vcs?location%5Bdirectory%5D= ;;
           GET http://127.0.0.1:4095/api/session/ses_efc722bf9ffeWaRdoFTwbmYj1Y ;;
           GET http://127.0.0.1:4095/api/session/ses_f408ec4a0ffeKjpcGKG4kfGEBR
   ```

   ⇒ **后端请求确实发出了**、会话列表接口 `limit=5000` 也确实取回来了 ⇒ 排除了
   "小屏下虚拟化列表不渲染导致不发请求"这一类假设。

4. **CDP 关键选择器**（会话列表容器非空）：`nodes` 从 493（骨架屏）→ **910**（数据），
   `innerText` 从 `Home|0|All projects|Loading` → `Home|0|All projects|Today|O|Counting from 1 to 4000, one num…`
   ⇒ **会话列表容器在两档都非空、都渲染出真实会话**（截图 `settled_360dp.png` / `settled_412dp.png` 可见
   "Counting from 1 to 4000, one number per line / opencode-android-shell"、"Friendly greeting / DSH 临时会话" 等）。

### 判定：上游行为 还是 我们的缺陷？

- **V9 那个"停在骨架屏"的画面本身 = 上游行为**（错口令 ⇒ `/api/**` 全 401 ⇒ 上游 SPA 只能显示骨架屏）。
- **但"错口令时用户没有自救入口"曾经是我们的缺陷，且 V7/F5 已修**：现在错口令会先弹
  `AUTH FAILED` 可重试对话框（`WebHostActivity.kt:397-401`），日志 `F5 probe AUTH FAILED code=401; showing retryable dialog` 正是它。
  本轮**在正确口令下**不再触发，所以**当前代码不需要改动**。

### 方案（不改代码）

**本轮结论为"不是缺陷"，因此不提出任何代码改动。** 依据：`research/web/V8_VERIFY.md` 已把
F6（匿名探测）判定为"修好后的不变量"；本轮在正确口令下 `probe OK code=200`、
6/6 冷启均正常，**没有观察到任何需要改代码的行为**。
按任务硬规矩（先定性、给方案再动手），此处**停在报告，不动 `webapp/src/**`**。

> 唯一可提的**流程改进（非代码）**：V9 的采集脚本把"口令错"与"小屏慢"混在了同一批 cell 里，
> 结论被污染。建议后续任何视口对比都**先断言 `probe OK code=200` 且 `proxyGET>0`**，再纳入统计。
> 这是采集口径问题，不需要改产品代码。

---

## 五、证据文件

| 文件 | 内容 |
|---|---|
| `research/perf/out_v10/fine_raw.txt` | **主证据**：6 次冷启的逐帧 CDP 时间轴、`wm` 读回、`PROXY_401`、`API_GETS` |
| `research/perf/out_v10/fine_results.json` | 机器可读结果（`shellMs` / `leftLoadingMs`） |
| `research/perf/out_v10/settled_360dp.png` | 360dp 静置终态：真实会话列表（118,510 B） |
| `research/perf/out_v10/settled_412dp.png` | 412dp 静置终态：真实会话列表（167,927 B） |
| `research/perf/out_v10/phone360dp_run1..3.png` | 360dp 三次冷启终态（104–105 KB） |
| `research/perf/out_v10/closeout.txt` | 收尾复核原始输出 |
| `research/perf/out_v9/sweep_raw.txt` | V9 原始证据（引用，未改动） |

---

## 六、收尾复核（原始输出）

```
=== V10 CLOSEOUT 2026-10-05T08:0x ===
--- 关闭前确认是我起的实例 ---
pid=... avd=OC_Deliver_R2 port=6210 MINE=True          (关闭前逐条核对)

--- wm size/density reset ---
RESET_READBACK_SIZE:    Physical size: 2560x1600      <- Override 已消失
RESET_READBACK_DENSITY: Physical density: 320         <- Override 已消失

--- adb reverse / forward 清理（仅 tcp:4095）---
REVERSE_LIST_AFTER:                                   <- 空

--- emu kill（仅 emulator-6210）---
OK: killing emulator, bye bye
OK

--- 复核 ---
emulator/qemu proc count = 0
OC_*/Medium_Desktop_B count = 0
port 6210 : FREE
port 6212 : FREE
port 6214 : FREE
adb devices:  List of devices attached   （空）
4095 STILL LISTEN pid=3748 (untouched by me)   <- Lead 的后端，全程未起停
gradle lock exists = False
=== CLOSEOUT DONE（未用 adb kill-server / 未用 -a / 未用通配符 kill）===
```

**纪律声明**：
- ✅ 只 kill 了 **我自己起的** `emulator-6210`（AVD `OC_Deliver_R2`）
- ✅ **未**执行 `adb kill-server`、**未**用 `-a`、**未**用通配符 kill
- ✅ 用户的 `MCRemote_*` / `DSH_*` / `T7x_*` 与来源不明实例**一个都没碰**
- ✅ `4095` 后端**未起停**（收尾时仍是同一个 `pid=3748`）
- ✅ **无故意保留项**（`OC_*` / `Medium_Desktop_B` 残留 = 0，端口 6210 已 FREE）
- ⚠️ `OC_Deliver_R2` 等 AVD **未删除**（本轮只启动/关闭，未做 `avdmanager delete avd`）——
  这是 AVD 定义的长期资产，不属于"收尾泄漏"；此前 R2/G2/P6/F4/V6/V7/F5 等轮次共用同一 AVD。
- ⚠️ 调试脚本留在 `research/perf/_v10_*.ps1`（**故意保留**）：本轮 CDP 采集器
  （`InitCdp`/`CdpEval`/per-run 持久 websocket）可复用于后续视口对比。**原因**：重建成本高于收益。

### 我碰过的 serial

- **`emulator-6210`** —— 本轮**唯一**碰过的 serial（起 → 测 → 关）。
- 全程**只有这一个**。未接触任何其他设备。

---

## 七、未闭环（明确不装作"通过"）

1. **V9 的"第二切换点落在 820–1366dp 之间"我没有复测，也不敢背书。**
   V9 那批 6 个 cell **全部 `proxyGET=0` / 401**，没有任何一档拿到过数据，
   所以**那批数据无法判断任何断点行为**。本轮只做了 360 vs 412，**没有**覆盖 820–1366 区间。
   ⇒ 该结论需**另起一轮**（正确口令 + 数据到位）重测。**本轮不评价、不推翻。**
2. **首屏白屏（~0–1.3 s）本轮只做了粗采样**（首帧 CDP 可用即 T+1292~1403 ms），
   采样循环在 `wmUrl` 就绪前有 ~150 ms 空档，**首帧空白期未做毫秒级刻画**。
   本轮结论只覆盖"数据区是否离开 Loading"，**不覆盖首屏白屏时长**。
3. **只测了 2 个几何**（360dp / 412dp）。折叠屏、横屏、多窗口均未测。
4. **只测了 debug APK**（唯一带 WebView CDP 的构建）。release APK 的表现**未验证**。
5. **"进入骨架屏"时刻有 ~150–220 ms 采样粒度**，`loading=True` 只抓到 1 帧/次；
   骨架屏真实时长是**区间估计**（360dp ~331 ms、412dp ~249 ms），不是精确值。
6. **后端负载未受控**：`127.0.0.1:4095` 上有其它会话在跑，响应时间受共享影响；
   95 ms 的差值**不能**被解读为"360dp 布局更慢"，只能说"本轮未观测到有意义差异"。
