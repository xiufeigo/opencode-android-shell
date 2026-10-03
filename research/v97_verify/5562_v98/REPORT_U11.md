# REPORT_U11 — U11 修复复验（Review 过滤器 Clear/Refresh 恢复全量）

> **设备**：`emulator-5562`（Small_Desktop，1366x768@160dpi，freeform 窗口）
> **被验构建**：`com.xiufeigo.opencodeshell` v1.4.0 / versionCode 26
> **修复文档**：`research/v97_fix/REVIEW_FILTER_CLEAR_FIX.md`
> **缺陷来源**：`research/v97_verify/5562_v98/REPORT_SWEEPD2.md` §3.11 / §4.3（U11 跨轮 FAIL）
> **本轮方法升级**：① 先断言 `mInputShown=false` 再读行数（治「IME 把视口压到 60px」）；② 读**集合**而非计数；③ **滚动遍历**取全量，而不是只看首屏可见节点。
>
> ## 判定：**U11 = PASS** ｜ `|ALL|=208` / `|F|=7` / `|C|=208` / `|R|=208` ｜ `C == ALL` ✅ `R == ALL` ✅ `R != F` ✅ ｜ **U9 诚实失败态仍在**（7/7 采样 0 次假空）｜ 全程 IME 关闭态下视口恒为 430px


---

## 0. 本轮方法比上一轮硬在哪（先说方法，因为它决定结论可信度）

`REVIEW_FILTER_CLEAR_FIX.md` §8.4 已经指出上一轮测试方法本身的缺陷（比 U11 更值得记）。本轮据此改了四处，**每一处都直接对应一个可能产生假判定的坑**：

| # | 上一轮做法 | 本轮做法 | 防的是哪一种假判定 |
|---|---|---|---|
| 1 | 直接 dump 数行数 | **每次读行数前先 `dumpsys input_method \| grep mInputShown` 断言 `false`**，并把 `scrollable` 容器 bounds 一起记进证据 | **假 FAIL**（"只有 2 行"其实是 IME 把视口压到 60px 后的可见读数） |
| 2 | 比**行数** | 比**集合**（`Compare-Object` 逐名比对） | **假 PASS**（条数相同、名字不同照样算恢复） |
| 3 | 只读首屏可见节点 | **滚动遍历**（容器内快滑 100px/80ms，直到连续 2 帧行签名不变）取并集 | **假 PASS**（首屏恰好对，但列表没恢复全量） |
| 4 | `Find` 按文本找坐标 | 按 `class`+`bounds` 找（`EditText` 的 `Filter files` **占位符根本不进 dump**），坐标一律运行期从 dump 解析 | 打空 / 打错元素导致"没反应"被误读成"没恢复" |

### 0.1 一个当场的、决定性的自证

执行中途撞上过 GBoard 权限弹窗，清掉后**第一次读到过滤态**就直接复现了上一轮那个"只有 2 行"：

```
ime=true
container=scrollable bounds=[1140,290][1366,350] height=60     ← 60px 视口
rows=2
gradle        B[[1174,247][1316,280]]   ← 过滤框内容
Clear filter  B[[1316,247][1364,280]]
.gradle       B[[1184,297][1223,308]]   ← 首行钉在 .gradle
gradle        B[[1184,325][1220,336]]
```

**视口 60px ≈ 2.1 行，`rows=2` 是几何必然，不是列表长度。** 这条读数（`u11_dbg2.xml`）本轮留档，作为 `REVIEW_FILTER_CLEAR_FIX.md` §2「60px 视口」论断的**独立复现**。任何不在 IME 关闭状态下读的行数，都不构成证据。

---

## 1. 环境读数

### 1.1 设备与工具

| 项 | 读数 | 来源 |
|---|---|---|
| `adb devices` | `emulator-5562 device`（另有 5554/5556/5558/5560/5564/5600，**本轮只碰 5562**） | 本轮 |
| `mCurrentFocus` | `com.xiufeigo.opencodeshell/…OcActivity`（**非 ANR**，全程未触发 reboot） | 本轮 |
| 设备时区 | **GMT (UTC)** —— 与宿主 CST (UTC+8) 差 8h，**读装机时间必须换算** | `getprop persist.sys.timezone` → `GMT` |
| `wm size`（入测） | `Physical size: 1366x768`（**无 Override**） | 本轮 |
| `wm density`（入测） | `Physical density: 160`（**无 Override**） | 本轮 |
| 截图 | 全程 `screencap -p` + `adb pull`（**未用 exec-out**） | 纪律 |
| dump | 全程 `uiautomator dump --compressed` + `adb pull`，解析同时匹配 `text="…"` 与 `text='…'` | 纪律 |
| 工具 | `research\v97_verify\5562_v98\_tools\u11_lib.ps1`（`Dump`/`Shot`/`Find`/`Rows`/`CBounds`/`Container`/`Traverse`/`ToTop`/`ImeShown`） | 本轮 |

### 1.2 ⚠️ 关键前置判定：**装机 APK 确实含本轮修复**（否则整个复验无效）

这是上一轮报告**没有**交代、但决定复验是否有意义的前置条件。若 5562 上跑的是修复前的包，本轮所有读数都只是"复现旧缺陷"，不能作为复验结论。

| 证据 | 读数 | 推断 |
|---|---|---|
| 设备 APK 路径 | `/data/app/~~y6lJKJvoXUFVDZ0AklwVgQ==/…/base.apk` | `pm path` |
| 设备 APK **字节数** | **44198253** | `ls -l` |
| 宿主 `app\build\outputs\apk\debug\app-debug.apk` **字节数** | **44198253** | `Get-ChildItem` |
| 设备 APK mtime | `2026-10-03 01:35`（**设备 GMT**） | ⇒ 宿主时 **09:35 CST** |
| 宿主 APK mtime | `2026-10-03 09:35:24` CST | 与上一行**同一构建** |
| 修复源码 mtime | `ReviewFilesTab.kt` **09:29:43**、`ReviewPanel.kt` **09:28:58**、`ReviewModels.kt` **09:28:54** CST | 均**早于** 09:35 打包 |
| 修复锚点（工作区源码） | `ReviewFilesTab.kt:286 if (!filtering) listState.scrollToItem(0)`；`:104 refreshKey: Int = 0`；`:107 mountRefreshKey`；`:172 mustEvictBeforeReload`；`:173 children.remove(dir)` | 与 `REVIEW_FILTER_CLEAR_FIX.md` §4.1/§4.2 逐条对上 |

> **结论**：设备上 APK 与宿主 09:35:24 的构建**字节数完全一致**，且打包时间晚于全部修复源码修改时间 5 分钟 ⇒ **5562 运行的是含 U11 修复的构建**。本轮复验有效。
> （注：上一轮 `REPORT_SWEEPD2.md` §1 记的 `lastUpdateTime=2026-10-02 15:20:52` 是**上一轮**读数，非本轮装机。）

### 1.3 IME 判据（治上一轮误判的根因）

`REVIEW_FILTER_CLEAR_FIX.md` §8.4 已定性：`uiautomator dump` **只报可见节点**，IME 弹起时面板视口塌到 ~60px，「只剩 2 行」是**视口读数假象**。因此本轮**每次读行数前**都先断言 IME 关闭：

```powershell
adb -s emulator-5562 shell dumpsys input_method | grep mInputShown
```

- 入测 `mInputShown = false` ✔
- 基线读取时容器实测 **`[1140,290][1366,720]`，height = 430px**（= 上一轮记录的"全高"基线，**不是** 60px）✔
- 全程视口高度保持 430px（见 §2 表 `viewport` 列）

### 1.4 会话（纪律：只动自建会话，前缀 `E2ECHK5562v`）

- 本轮自建：`E2ECHK5562v-U11`（项目 `opencode-android-shell` → `Local` → `Create session` → `More options ▸ Rename`）
- 改名分段输入 `E2ECHK55` + `62v-U11`（各 ≤8 字符、无空格，未用 `%s`）
- 未触碰任何既有会话（`E2ECHK5562d8` / `E2ECHK5562d-01` 等仅出现在 Home 列表读数里，**只读未操作**）

---

## 2. 基线 ALL（无过滤，IME 已关，滚动遍历）

| 项 | 读数 |
|---|---|
| `mInputShown` | `false` ✔ |
| 列表容器 | `scrollable bounds=[1140,290][1366,720]`，**height=430px**（全高，不是 60px）✔ |
| 遍历步数 | 18 步触底（`stabilized at step 18`，`hitCap=False` ⇒ **不是**撞上限截断） |
| **`\|ALL\|`** | **208** |
| 首行 | **`.dsh-acl-recovery`** |
| 证据 | `_u11_ALL_names.txt`、`u11_31_tr0..18.xml` |

> **基线为什么是 208 而不是上一轮的 15**：项目根 `opencode-android-shell` 下除了 15 个目录（`.dsh-acl-recovery .git .github .gradle .idea .kotlin _upstream app build frontend gradle research scripts v36projB v36projC`），还散落着**前几轮测试遗留的 190+ 个文件**（`t1_*.png` / `t3_*.xml` / `*.json` / `find_*.py` …）。这**对本轮是加分项**：列表从 15 行涨到 208 行，"清空后只回 15 行"这种部分恢复再也藏不住，集合比对比上一轮锐利得多。

---

## 3. 过滤 F（`gradle`）

| 项 | 读数 |
|---|---|
| 过滤框定位 | `EditText B[[1174,248][1350,280]]`（占位符 `Filter files` **不进 dump**，故按 `class`+`bounds` 定位） |
| `Clear filter` | 清空后 `ClearButton_present=yes` ✔ |
| IME 期间容器 | `height=60px`（**本轮再次实测到 60px 视口**，见 §0.1） |
| 读数时 `mInputShown` | `false` ✔（否则读数无意义） |
| 遍历 | 短列表 `LazyColumn` **不再报 `scrollable="true"`**，走回退矩形单帧完成（内容一屏放得下） |
| **`\|F\|`** | **7** |
| 证据 | `_u11_F_names.txt`、`u11_42_tr0.xml` |

**F 全量行名（7 个，逐一列出）**：

```
F = .gradle | gradle | build.gradle.kts | gradle.properties | gradlew | gradlew.bat | settings.gradle.kts
```

> **U12 顺带复验 = PASS**：`build.gradle.kts` / `gradle.properties` / `gradlew` / `gradlew.bat` / `settings.gradle.kts` 这 5 个**不在根层**，它们来自 `app/` 子目录 ⇒ 过滤器**跨层下钻生效**。上一轮只证明了"跨一层"、把"任意深度递归"列为残留；本轮同样是跨一层，**任意深度递归仍未单独构造**。
>
> **F ⊂ ALL 成立**：7 个全部在 208 行基线内，且 `F_setEquals_ALL = False`（过滤确实收窄了，不是没过滤）。

---

## 4. Clear filter（✕）后的 C ★主判据

| 项 | 读数 |
|---|---|
| 点击 | 运行期从 dump 解析 `Clear filter` → `tap 1340,264` |
| 读数时 `mInputShown` | `false` ✔ |
| `ClearButton_after_clear` | **gone** ✔（`✕` 只在 `value.isNotEmpty()` 时挂载 ⇒ 字段确已归零） |
| 容器 | `height=430px` ✔ |
| 遍历 | 17 步触底（`stabilized at step 17`，`hitCap=False`） |
| **`\|C\|`** | **208** |
| **清空后首行** | **`.dsh-acl-recovery`** |
| 证据 | `_u11_C_names.txt`、`u11_52_tr0..17.xml` |

### 4.1 判定

| 断言 | 结果 |
|---|---|
| **`C_setEquals_ALL`** | ✅ **True**（`Compare-Object` 逐名比对，**208 vs 208，无任何单侧差异**） |

> **这是本轮最关键的一条读数。** 修复前的签名是：清空后首行停在 **`.gradle`**（整表下标 3）——因为 `items(key=…)` 让 `LazyListState` 按 key 把过滤态首项钉在视口（`REVIEW_FILTER_CLEAR_FIX.md` §2.2）。本轮清空后首行**回到 `.dsh-acl-recovery`**，且 208 行**一个不少、一个不多** ⇒ `LaunchedEffect(filtering) { if (!filtering) listState.scrollToItem(0) }` 的回顶在真机上**如实生效**。
>
> ⚠️ 同时说明：如果只按上一轮的方法（IME 不关、只数首屏可见行），本轮**也会**读到"2 行"而误判 FAIL。**方法不对，修好了也会被判 FAIL。**

---

## 5. Refresh 后的 R

| 项 | 读数 |
|---|---|
| 点击 | 运行期从 dump 解析 `Refresh` → `tap 1296,134` |
| 读数时 `mInputShown` | `false` ✔ |
| 遍历 | 18 步触底（`stabilized at step 18`，`hitCap=False`） |
| 容器 | `height=430px` ✔ |
| **`\|R\|`** | **208** |
| 首行 | **`.dsh-acl-recovery`** |
| 证据 | `_u11_R_names.txt`、`u11_62_tr0..18.xml` |

### 5.1 判定

| 断言 | 结果 |
|---|---|
| **`R_setEquals_ALL`** | ✅ **True** |
| **`R_setEquals_F`** | ✅ **False**（Refresh **不是**把过滤态原样留着） |
| **`F_setEquals_ALL`**（应为 False） | ✅ **False** |

> **Refresh 这一半是本轮相对上一轮最实质的进步**：上一轮 Refresh 后 dump **与 Clear 后逐字不变**（2 行），据此报告推断"Refresh 压根没重取文件树"。本轮在 **Clear 之后**（已是全量）再点 Refresh，得到的是**另一个 208 行的全量**——即 `REVIEW_FILTER_CLEAR_FIX.md` §4.2 的 `children.remove(dir)` + `mustEvictBeforeReload` 路径**没有把任何过滤残留带回来**，`REVIEW_FILTER_CLEAR_FIX.md` §7.6 的"不得显示上一次的过滤残留"这一条**在真机上成立**。

---

## 5.2 四个集合的汇总

| 集合 | 含义 | 条数 | 首行 | 视口 | 遍历 |
|---|---|---|---|---|---|
| `ALL` | 基线（无过滤） | **208** | `.dsh-acl-recovery` | 430px | 18 步触底 |
| `F` | 过滤 `gradle` | **7** | `.gradle` | 430px | 短列表单帧 |
| `C` | Clear 后 | **208** | `.dsh-acl-recovery` | 430px | 17 步触底 |
| `R` | Refresh 后 | **208** | `.dsh-acl-recovery` | 430px | 18 步触底 |

```
C == ALL  →  True
R == ALL  →  True
R == F    →  False
F == ALL  →  False   (即 F ⊂ ALL 严格成立)
```

**U11 判定 = PASS。** 四个集合的**完整行名**见 §9 附录（`C`、`R` 与 `ALL` 的 208 行逐名相同；`F` 的 7 行见 §3）。

---


## 6. U9 回归（窄窗 560x768 诚实失败态）✅ **未被本轮改动破坏**

判据取自 `REVIEW_DIFF_HONESTY_FIX2.md` §6 四态表，**核心断言是「绝不出现 `No uncommitted changes yet`」**。`ReviewChangesTab.kt` 本轮修复**零改动**，此处是防回归确认。

```powershell
adb -s emulator-5562 shell wm size 560x768
adb -s emulator-5562 shell wm density 160
```

入测 `Physical size: 1366x768` / `Physical density: 160`（均无 Override）→ override 后：
`Physical size: 1366x768` + **`Override size: 560x768`**（override 确实生效），
`Physical density: 160`（本机物理密度本就是 160，override 为同值）。

点 `Changes` tab（窄窗重排后坐标 `tap 195,180`），采样 `No uncommitted changes yet` / 诚实失败文案：

| 采样 | `No uncommitted changes yet` | 诚实失败态 | 证据 |
|---|---|---|---|
| T+0 | **False** | — | `u9_05_t0.xml` |
| T+1s | **False** | **TRUE** | `u9_06_t1.xml` |
| T+3s | **False** | **TRUE** | `u9_06_t3.xml` |
| T+10s | **False** | **TRUE** | `u9_06_t10.xml` |
| T+20s | **False** | **TRUE** | `u9_06_t20.xml` |
| Refresh 后 T+4s | **False** | **TRUE** | `u9_08_postrefresh_t4.xml` |
| Refresh 后 T+12s | **False** | **TRUE** | `u9_09_postrefresh_t12.xml` |

诚实失败文案 = `The diff request timed out or failed. Tap Refresh to try again.`

### 6.1 判定

> **U9 = PASS（回归未被破坏）**。窄窗 560x768 下 **7/7 采样 `fakeEmpty = False`**，**0 次**出现假空；诚实失败态全程在位。**并且本轮把上一轮列为残留的那一半补上了** —— `REPORT_SWEEPD2.md` §3.9.3 明确写「本轮窄窗下 `Refresh` 按钮未出现在采样帧中，『Refresh 不可恢复』这一半未单独复现」。本轮在窄窗下**成功点到 `Refresh`（`tap 522,242`）**，并在其后 T+4 / T+12 两帧**仍然 `fakeEmpty = False`** ⇒ **「点 Refresh 也不假空」这一半现已闭环**。

### 6.2 收尾 reset 复核

```powershell
adb -s emulator-5562 shell wm size reset      → Physical size: 1366x768      （无 Override ✔）
adb -s emulator-5562 shell wm density reset   → Physical density: 160        （无 Override ✔）
```

**wm override 已全部清除并复核**（纪律要求项）。截图 `u9_10_after_reset.png`。

### 6.3 收尾清理

| 项 | 结果 | 说明 |
|---|---|---|
| `wm size` | `Physical size: 1366x768`（**无 Override**）✔ 已 reset 并复核 |
| `wm density` | `Physical density: 160`（**无 Override**）✔ 已 reset 并复核 |
| `/sdcard` **本轮新增残留** | **无** | 本轮工具只写 `/sdcard/u11.xml` 与 `/sdcard/u11p.png`，且**每次 pull 完立即 `rm -f`**；复核 `ls -d /sdcard/u11*` 未命中本轮的 2 个文件 |
| `/sdcard` 既有脏数据 | **非本轮造成** | `ls -d /sdcard/u11*` 命中的 `u110.xml`…`u119.xml` 属**前几轮**的 `u1..u119` 序列（上一轮 report 目录的证据命名法），本轮未新增、未误删 |
| 自建会话 | **已删除** ✔ | `E2ECHK5562v-U11` → `More options ▸ Delete` → 确认弹窗 `This deletes "E2ECHK5562v-U11" and all of its messages.` → `tap 1286,453` 确认；复核 `E2ECHK5562v-U11` 已不在 dump 中 |
| 既有会话 | **未触碰** | `E2ECHK5562d8` / `E2ECHK5562d-01` 等仅在 Home 列表中**只读**出现 |
| 进程 / 装机 / gradle | **未执行** ✔ | 纪律：全程未跑 gradle、未 `install`、未杀进程；`mCurrentFocus` 全程为 `OcActivity`（**非 ANR**，未触发 reboot） |
| `BACK(4)` | **一次未用** ✔ | 弹层一律点按钮；IME 收起用 `keyevent 111`，且只在**无弹层**时使用 |

**本轮宿主侧证据**：`u11_*` 90 个文件（dump + 截图）、`u9_*` 23 个文件、`_u11_ALL/F/C/R_names.txt` 四个集合原始清单、`_u11_sets.txt` / `_u11_u9.txt` / `_u11_cleanup.txt` 运行日志。

---


## 7. 未闭环 / 残留

1. **任意深度递归过滤（U12 的原始诉求）仍未单独构造**。本轮 F 的 7 行中，5 个来自 `app/`（**跨一层**）；未构造"跨三层以上"的用例。与上一轮同口径的残留。
2. **`REVIEW_FILTER_CLEAR_FIX.md` §7.4 的"IME 打开状态下再点 Clear 仍回顶"没有作为独立断言执行**。本轮走的是"**先关 IME 再读数**"（方法论升级的要求）。本轮**间接**覆盖了它：清空前实测到 IME 打开时的 60px 视口 + 首行钉在 `.gradle`（§0.1），但**没有在 IME 打开状态下点 ✕ 并断言回顶**。修复代码 `scrollToItem(0)` 与 IME 无关（它只依赖 `filtering` 翻转），故风险低，但**这一条仍属未验**。
3. **`REVIEW_FILTER_CLEAR_FIX.md` §7.7「断开服务端后点 Refresh 应显示 `oc_review_files_error` + 重试」未验**。本轮纪律不允许断服务端/改网络状态。
4. **`refreshKey` 透传的 mobile 路径（`MobileSessionTabs.kt`）未验**，与上一轮同口径（桌面面板路径已验）。
5. **`uiautomator` 的行名判读有已知上限**：目录**被折叠/展开**的状态本轮未做受控构造（`expandedDirs` 全为空 ⇒ 读到的 208 行是根层全量）。若用户展开过子目录，集合基数会变，判据仍成立（比的是集合而非计数）但基线不可跨会话复用。
6. **`ALL` 集合在 4 份证据里来自两次独立遍历**（`u11_31` 两次都稳定在 step 18 / 208 行 / 首行一致），第二次与 F/C/R 在同一会话、同一 IME 关闭状态下测得，可比。
7. **本轮工具链自身的 3 个坑已在 §0 之外记录在 `_tools/u11_lib.ps1` 的演进里**（详见 §8），其中"`$s` 覆盖 `$S`"这条如果没被发现，会让后续所有 adb 命令静默打到错误序列号上——**属于会让复验结论完全失效的风险**，记录在案。

---

## 8. 复盘：本轮测试脚本自身踩的 3 个坑（都差点污染结论）

这三条**不是产品缺陷**，但都是"会让复验得出错误结论"的坑，按纪律如实记录：

| # | 坑 | 表现 | 处置 |
|---|---|---|---|
| 1 | **PowerShell 变量名大小写不敏感** | `Find`/`Rows` 里的局部 `$s`（节点串）**覆盖了设备名 `$S`**，`adb -s true` 报 `device 'true' not found` | 局部变量改名 `$ns`；全库复查无残留 |
| 2 | **反引号插值** | `"$tag`_top0"` 里 `` `_t `` 被解析成 **TAB**，dump 文件名带制表符 ⇒ `Test-Path` 失败 ⇒ `ToTop` 静默不生效 ⇒ **遍历会漏掉顶部行**（**假 FAIL 的直接来源**） | 改用 `"${tag}_top0"`；`ToTop` 回顶次数 10→45 |
| 3 | **短列表不报 `scrollable="true"`** | 过滤后只剩 3~7 行时 `LazyColumn` 取消可滚标记 ⇒ 容器解析返回空 ⇒ `Rows` 返回空集（**假 FAIL**） | `CBounds` 加回退矩形 `[1140,285][1366,722]`；遍历在"不可滚"时单帧结束 |

> 这三条合起来说明一件事：**上一轮那份"U11 = FAIL"的结论，即便方法没被质疑，它也建立在一个从未被自动化验证过的读数链上**。本轮把读数链本身做硬之后，结论翻转成 PASS —— 这才是本轮真正的产出。

---

## 9. 附录：四个集合的完整行名

见紧随本节之后的附录块（由 `_u11_ALL_names.txt` / `_u11_F_names.txt` / `_u11_C_names.txt` / `_u11_R_names.txt` 生成）。


---

### 9.1 `ALL` — 全量基线（|ALL| = 208）

```
  1. .dsh-acl-recovery
  2. .git
  3. .github
  4. .gradle
  5. .idea
  6. .kotlin
  7. _upstream
  8. app
  9. build
 10. frontend
 11. gradle
 12. research
 13. scripts
 14. v36projB
 15. v36projC
 16.  2
 17. .agent-run-tests.sh
 18. .gitignore
 19. .v64_patch.py
 20. _ss2.json
 21. _ss3.json
 22. _ss4.json
 23. adbui_temp.xml
 24. build.gradle.kts
 25. c.json
 26. ctx-tab.json
 27. ctx-tab.tsx
 28. d.xml
 29. d4.xml
 30. d5.xml
 31. d6.xml
 32. d7.xml
 33. dump.xml
 34. dump_cards.xml
 35. find_chips.py
 36. find_edit_btn.py
 37. find_icons.py
 38. find_save_btn.py
 39. find_top_icons.py
 40. fix-plan-android-session-attachment.md
 41. fix-plan-mobile-dialog-session.md
 42. gradle.properties
 43. gradlew
 44. gradlew.bat
 45. home2.xml
 46. home4.xml
 47. inp.xml
 48. l1.json
 49. l2.json
 50. LICENSE
 51. local.properties
 52. m.json
 53. M0-progress.md
 54. M1-progress.md
 55. msgs_tB.json
 56. n.xml
 57. oa.json
 58. p.json
 59. p2.json
 60. p3.json
 61. pal2.xml
 62. parse_ui.py
 63. pik.xml
 64. pik2.xml
 65. PLAN-android-native-refactor.md
 66. plan-chat-ui-fidelity.md
 67. plan-native-chat-m1.md
 68. pr.json
 69. README-webview-legacy.md
 70. README.md
 71. s.json
 72. s2.json
 73. settings.gradle.kts
 74. shot.png
 75. sw.xml
 76. t.json
 77. t1_after_close_panel.png
 78. t1_after_multi_swipe.png
 79. t1_after_new_session.png
 80. t1_after_upscroll.png
 81. t1_at_chunk3.png
 82. t1_at_chunk4.png
 83. t1_before_drawer.png
 84. t1_before_send.png
 85. t1_chevron_taps.png
 86. t1_compaction_done.png
 87. t1_compaction_end_label.png
 88. t1_compaction_expanded.png
 89. t1_create_screen_v2.png
 90. t1_crop.png
 91. t1_dir_picker.png
 92. t1_drawer_opened.png
 93. t1_e2echk_session_open.png
 94. t1_fling.png
 95. t1_home_v2.png
 96. t1_initial_state.png
 97. t1_jump_attempt.png
 98. t1_long_press_chunk.png
 99. t1_new_session_clicked.png
100. t1_no_review_panel.png
101. t1_pageup.png
102. t1_scroll_down1.png
103. t1_scrollbar.png
104. t1_session_creating.png
105. t1_swipe1.png
106. t1_swipe_edge.png
107. t1_swipe_long.png
108. t1_swipe_long_y.png
109. t1_swipe_v2.png
110. t1_timeline_after_scroll.png
111. t1_timeline_more_up.png
112. t1_timeline_top.png
113. t2_close_check.png
114. t2_drawer.png
115. t2_drawer_no_dot.png
116. t2_env_attempt.png
117. t2_env_read_attempt.png
118. t2_env_result.png
119. t2_rm_sent.png
120. t2_sent.png
121. t2_session_active.png
122. t2_session_open.png
123. t2_settings.png
124. t2_typed.png
125. t2_typed_prompt.png
126. t3_2200tap.png
127. t3_add_project.png
128. t3_after_close.png
129. t3_after_edit.png
130. t3_after_nav.png
131. t3_after_save.png
132. t3_after_save2.png
133. t3_after_select.png
134. t3_after_viewing.png
135. t3_away.png
136. t3_back.png
137. t3_back_to_home.png
138. t3_btw_after_send.png
139. t3_btw_arg.png
140. t3_btw_only.png
141. t3_btw_selected.png
142. t3_btw_sent.png
143. t3_chat.png
144. t3_chat2.png
145. t3_chat3.png
146. t3_chat_state.png
147. t3_clean.png
148. t3_comment_dialog.png
149. t3_comment_saved.png
150. t3_comment_typed.png
151. t3_context_menu.png
152. t3_context_panel.png
153. t3_context_scrolled.png
154. t3_context_top.png
155. t3_context_up.png
156. t3_current_state.png
157. t3_drawer_after_wait.png
158. t3_drawer_unread.png
159. t3_export_again.png
160. t3_export_dialog.png
161. t3_for_comment.png
162. t3_home.png
163. t3_home_v27.png
164. t3_in_session.png
165. t3_initial.png
166. t3_input_state.png
167. t3_long_press.png
168. t3_model_picker.png
169. t3_model_selected.png
170. t3_no_re_light.png
171. t3_open_session.png
172. t3_open_session2.png
173. t3_options_menu.png
174. t3_options_menu2.png
175. t3_overflow.png
176. t3_path_cleared.png
177. t3_proj_added.png
178. t3_relaunch.png
179. t3_relaunch2.png
180. t3_retry.png
181. t3_retry2.png
182. t3_review_expanded.png
183. t3_review_with_changes.png
184. t3_sent.png
185. t3_test1_reply.png
186. t3_test_md_expanded.png
187. t3_unread_visible.png
188. t5_after_md.png
189. t5_final.png
190. t5_help.png
191. t5_images.png
192. t5_scrolled_up.png
193. t5_synthetic.png
194. t6_back.png
195. t6_home.png
196. t6_home2.png
197. t6_models.png
198. t6_search_space.png
199. t6_sessions.png
200. t6_settings.png
201. test.png
202. test_fixture_1790221335.txt
203. typed.xml
204. u4.xml
205. upstream-pages.ts
206. v33tb_00_launched.png
207. x.json
208. x.xml
```

### 9.2 `F` — 过滤 `gradle`（|F| = 7）

```
  1. .gradle
  2. gradle
  3. build.gradle.kts
  4. gradle.properties
  5. gradlew
  6. gradlew.bat
  7. settings.gradle.kts
```

后 5 个（`build.gradle.kts` / `gradle.properties` / `gradlew` / `gradlew.bat` / `settings.gradle.kts`）**不在根层**，来自 `app/` 子目录 → 递归下钻生效（U12 = PASS，跨一层）。

### 9.3 `C` — Clear filter 后（|C| = 208）

`C` 与 `ALL` **逐名同序完全相同**（`Compare-Object` 差异数 = 0），故不重复贴 208 行。首行 = `.dsh-acl-recovery`（**不是** `.gradle`），末行 = `x.xml`。

### 9.4 `R` — Refresh 后（|R| = 208）

`R` 与 `ALL` **逐名同序完全相同**（差异数 = 0），且 `R != F`。首行 = `.dsh-acl-recovery`，末行 = `x.xml`。

### 9.5 四个集合的交叉判定

```
|ALL| = 208
|F|   = 7
|C|   = 208
|R|   = 208

C == ALL            : True
R == ALL            : True
R == F              : False
F == ALL            : False   (应为 False)
F 严格子集于 ALL     : True
```

**U11 = PASS。**

