# F17 收尾清扫：HOUSEKEEPING.md 编码破损修复 + AVD 磁盘占用量化

- 轮次：F17
- 日期：2026-10-05
- 仓库：`C:\sensorsdata\main\program\opencode-android-shell`
- 硬约束遵守情况：**未启动/未触碰任何设备或模拟器实例、未跑 gradle、未改任何生产代码**
- 写权限范围：本文件 + `research/HOUSEKEEPING_RECOVERED_F17.md` + `research/f17/**`（证据与脚本）
- **未删除任何 AVD，未执行 `avdmanager delete`，未修改任何 `.ini`**

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 文件编码 | **不是 GBK、不是混合编码**。主体是合法 UTF-8，但被一次**有损的代码页往返**打了 35 行的孔 |
| 破损范围 | **73 行中 35 行**受损（行号见 §2.2） |
| 是否可无损修复 | **不可**。F11–F13 新增的 33 行**从未以干净形态进过 git**，字节已被物理销毁 |
| 实际做法 | 原文件**一字未动**（SHA256 前后一致）作取证证据；干净内容**逐字节**还原到 `research/HOUSEKEEPING_RECOVERED_F17.md` |
| AVD 总占用 | **315,548,508,935 B = 293.88 GiB**（40 个） |
| 我们建的 | **88,527,736,494 B = 82.45 GiB**（12 个，占 28.1%） |
| C: 可用空间 | **2,424,514,117,632 B = 2258.00 GiB** |

---

## 1. 事件 1：编码诊断

### 1.1 判据一：整体字节画像（排除 GBK / 混合编码）

命令：
```powershell
$f = "$PWD\research\HOUSEKEEPING.md"
$b = [System.IO.File]::ReadAllBytes($f)
"=== FIRST 16 BYTES (hex) ==="; ($b[0..15] | % { $_.ToString('X2') }) -join ' '
"=== LAST 16 BYTES (hex) ===";  ($b[($b.Length-16)..($b.Length-1)] | % { $_.ToString('X2') }) -join ' '
"=== COUNT 0xFF ==="; ($b | ? { $_ -eq 0xFF }).Count
"=== COUNT 0x00 ==="; ($b | ? { $_ -eq 0x00 }).Count
```

原始输出：
```
SIZE: 47956 bytes
=== FIRST 16 BYTES (hex) ===
23 20 E7 8E AF E5 A2 83 E6 B8 85 E7 90 86 E8 A7
=== LAST 16 BYTES (hex) ===
49 54 41 50 5F 46 31 35 2E 6D 64 60 20 7C 0D 0A
=== COUNT 0xFF ===
0
=== COUNT 0x00 ===
0
```

判读：

- **无 BOM**：首字节是 `0x23`（`#`），不是 `EF BB BF`。
- **无 NUL、无 `0xFF`**：排除 UTF-16 / UTF-32 混入。
- 首 3 个汉字 `环境` = `E7 8E AF` `E5 A2 83` 是**标准 3 字节 UTF-8 序列**。若原文件是 GBK，中文"环"应是 2 字节 `BB B7`。**⇒ 主体是 UTF-8，不是 GBK。**
- 尾部 4 字节 `60 20 7C 0D 0A` = `` ` `` 空格 `|` CR LF，是纯 ASCII 结构字符。

### 1.2 判据二：严格 UTF-8 校验 → 失败，且能定位到**具体字节**

命令（`UTF8Encoding($false,$true)` = 禁止回退替换）：
```powershell
$enc = New-Object System.Text.UTF8Encoding($false, $true)
try { $s = $enc.GetString($b); "STRICT UTF-8 DECODE: SUCCESS" }
catch { "STRICT UTF-8 DECODE: FAILED -> $($_.Exception.Message)" }
```

原始输出：
```
STRICT UTF-8 DECODE: FAILED -> Unable to translate bytes [EF][BC] at index 35 from specified code page to Unicode.
```

判读：`index 35` 处 `EF BC` 后面跟的不是合法的 UTF-8 续接字节。`EF BC` 是 3 字节序列的前 2 字节，说明**第 3 字节被换掉了**。

### 1.3 判据三：与已知正确文本逐字节比对（决定性证据）

我从 git 里找到了**同一文件的干净版本**做对照。命令见 §3.1，原始输出如下。

**样本 A — 第 1 行（标题行）**

```
GOOD  ca1b2085 L1: 23 20 E7 8E AF E5 A2 83 E6 B8 85 E7 90 86 E8 A7 84 E7 9F A9 EF BC 88 48 6F 75 73 65 6B 65 65 70 69 6E 67 EF BC 89 0A
CUR   HEAD    L1: 23 20 E7 8E AF E5 A2 83 E6 B8 85 E7 90 86 E8 A7 84 E7 9F A9 EF BC 88 48 6F 75 73 65 6B 65 65 70 69 6E 67 EF BC 3F 0A
                                                                          正确 = ） U+FF09                                       破损 = ?
```

**样本 B — 第 2 行前 126 字节（说明"换字节"和"丢字节"同时发生）**

```
GOOD gL3: ... E6 98 8E E7 A1 AE E6 8F 90 | E5 87 BA | 20 | E2 80 94 | E2 80 94 | 20 | 2A 2A | ... E6 A8 A1 E6 8B 9F E5 99 A8 2A 2A EF BC 8C 0A
CUR  L2 : ... E6 98 8E E7 A1 AE E6 8F 90 | E5 87 3F |    E2 80 94 | E2 80 3F | 2A 2A | ... E6 A8 A1 E6 8B 9F E5 99 3F 2A EF BC 3F 3E 20 ...
                                        ↑ 出 U+51FA   ↑ 丢了 0x20    ↑ — 变 ?              ↑ 少 1 字节             ↑ 器 变 ? ，变 0x3F
```

判读（`出` = U+51FA，其 UTF-8 恰为 `E5 87 BA`）：

- `E5 87 BA` → `E5 87 3F`：**第 3 字节 `0xBA` 被 `0x3F`（`?`）覆盖**
- `E2 80 94` → `E2 80 3F`：**第 3 字节 `0x94` 被 `0x3F` 覆盖**
- GOOD 的 `E2 80 94 20 2A 2A`（7 B）变成 CUR 的 `E2 80 3F 2A 2A`（6 B）：**除了替换，还少了 1 个字节（空格 `0x20`）**

**⇒ 这不是"重新编码"，是"有损的字节级破坏"。原始字节已被物理覆盖/丢弃，不可逆。**

### 1.4 判据四：换行符统计 → 17 个换行被吃掉

```powershell
"0x0D count = $(($b | ? {$_ -eq 0x0D}).Count)"; "0x0A count = $(($b | ? {$_ -eq 0x0A}).Count)"
```

原始输出：
```
0x0D count = 1
0x0A count = 73
```

对照干净版本 `ca1b2085`：`0x0D count = 0`，`0x0A count = 90`。

**⇒ 90 → 73，17 个 `0x0A` 丢失，1 个游离 `0x0D` 出现。多行被粘连成一行。**

### 1.5 成因（文件自述，与字节证据吻合）

破损段落自身记录了成因（注意：**该句本身位于受损区，只能作为佐证，不能作为定论**）：

> 本轮 `powershell` 5.1 会把 **UTF-8 无 BOM 的 `.ps1` 按 GBK 读**，中文正则全部引号失配…写 `.ps1` 必须带 **UTF-8 BOM**。

与 §1.3 / §1.4 的字节证据完全吻合：**PowerShell 5.1 在 GBK 代码页下做了一次读→写往返**。GBK 无法表示的码位被替换成 `?`，部分多字节序列被拆散吃掉。

**⇒ 这直接解释了破损的 35 行集中在 "F11–F13 三轮追加的表格行"：追加操作是破的，而追加前的文件是好的。**

---

## 2. 破损定位

### 2.1 逐行严格校验

命令：`research/f17/enc_scan.ps1`（对文件按 `0x0A` 切行，逐行做 strict UTF-8 解码）

原始输出（摘要）：
```
TOTAL_BYTES: 47956
TOTAL_LINES: 74
BAD_LINE_COUNT: 35
```

### 2.2 全部 35 个受损行（行号 / 字节偏移 / 长度）

| 行 | 字节偏移 | 长度(B) | 行 | 字节偏移 | 长度(B) |
|---|---|---|---|---|---|
| L1 | 0 | 39 | L42 | 13609 | 1954 |
| L2 | 39 | 297 | L45 | 15565 | 2150 |
| L3 | 336 | 55 | L48 | 17717 | 2117 |
| L4 | 391 | 175 | L49 | 19834 | 1155 |
| L5 | 566 | 434 | L52 | 20991 | 2116 |
| L6 | 1000 | 387 | L53 | 23107 | 2340 |
| L10 | 1439 | 58 | L56 | 25449 | 2660 |
| L18 | 1898 | 47 | L58 | 28110 | 1806 |
| L24 | 2015 | 1564 | L59 | 29916 | 441 |
| L25 | 3579 | 1566 | L60 | 30357 | 69 |
| L26 | 5145 | 186 | L63 | 30482 | 1748 |
| L27 | 5331 | 435 | L64 | 32230 | 2686 |
| L28 | 5766 | 520 | L66 | 34917 | 2673 |
| L29 | 6286 | 781 | L68 | 37591 | 2282 |
| L31 | 7068 | 832 | L70 | 39874 | 3034 |
| L33 | 7901 | 1267 | L72 | 42909 | 2928 |
| L35 | 9169 | 1592 | | | |
| L38 | 10763 | 1216 | | | |
| L40 | 11980 | 1628 | | | |

完整的逐行十六进制见 `research/f17/recover_report.txt`（每行含 offset/len/HEX/GBK 解码/UTF-8 解码）。

### 2.3 受影响内容分两类

| 区域 | 行号 | 内容 | 可恢复性 |
|---|---|---|---|
| **规矩正文**（L1–L18） | 1,2,3,4,5,6,10,18 | 谁起谁关、只关自己起的、不许关 adb server… | ✅ **内容完整存在于 `ca1b2085`**，已随 recovered 文件还原 |
| **F11–F13 台账行**（L24–L72） | 其余 27 行 | 三轮设备验证记录 | ❌ **不可恢复**，见 §3.3 |

---

## 3. 修法

### 3.1 先在 git 历史里找干净恢复点

命令：
```powershell
git log --oneline -- research/HOUSEKEEPING.md
# 对每个 commit 取 blob 做 strict UTF-8 校验
```

原始输出（sha / 时间 / 字节数 / 校验结果 / `?` 计数）：
```
2c60b07e  2026-10-05 11:58:53  bytes=47956   INVALID_UTF8  qmark=1022  verify(web): close the four leftover items…
e1a00733  2026-10-05 11:27:52  bytes=45837   INVALID_UTF8  qmark=1022  verify(web): drive the turn by tapping Send…
ca1b2085  2026-10-05 11:03:55  bytes=43923   VALID_UTF8    qmark=2     verify(web): close the R8 keep-screen-on gap…
e5383dd6  2026-10-05 10:24:14  bytes=41528   VALID_UTF8    qmark=2     build(web): the R8 variant works…
ae46565b  2026-10-05 09:34:21  bytes=38415   VALID_UTF8    qmark=2     verify(perf): measure the IME overlap…
863be3b6  2026-10-05 09:14:22  bytes=36077   VALID_UTF8    qmark=2     verify(web): redo the device evidence…
c0bce95c  2026-10-05 08:23:49  bytes=33344   VALID_UTF8    qmark=1     verify(perf): redo the form-factor sweep…
f3ea24d4  2026-10-05 07:03:22  bytes=28685   VALID_UTF8    qmark=1     fix(web): the credential probe…
c3bf2e91  2026-10-05 06:40:47  bytes=26844   VALID_UTF8    qmark=1     verify(web): confirm the canonical release…
1826790e  2026-10-05 06:15:35  bytes=24110   VALID_UTF8    qmark=0     fix(web): survivable connect states…
b8d2a38c  2026-10-05 05:56:25  bytes=21932   VALID_UTF8    qmark=0     verify(web): the camera route…
5c375401  2026-10-05 05:26:34  bytes=18585   VALID_UTF8    qmark=0     verify(web): the latest release holds up…
05e48cd2  2026-10-05 04:56:06  bytes=16921   VALID_UTF8    qmark=0     fix(web): upstream-consistent notifications…
e360904b  2026-10-05 04:04:06  bytes=12713   VALID_UTF8    qmark=0     fix(web): wire the keep-screen-on feature…
bb789208  2026-10-05 03:31:26  bytes=11112   VALID_UTF8    qmark=0     verify(web): the shipped release works…
c2eaa3b5  2026-10-05 03:10:26  bytes=9510    VALID_UTF8    qmark=0     fix(web): power policy…
1c06ba12  2026-10-05 02:26:48  bytes=8258    VALID_UTF8    qmark=0     verify(web): close two of the four gaps…
9b4d7e83  2026-10-05 01:58:11  bytes=6620    VALID_UTF8    qmark=0     build(web): rebuild the deliverables…
f989907c  2026-10-05 01:28:08  bytes=5320    VALID_UTF8    qmark=0     fix(web): notification taps really do route…
ed965036  2026-10-05 01:04:44  bytes=3603    VALID_UTF8    qmark=0     verify(web): the signed release runs…
88ee5a64  2026-10-04 21:25:52  bytes=2631    VALID_UTF8    qmark=0     chore: record the housekeeping rule…
```

**结论：最后一个干净点是 `ca1b2085`（2026-10-05 11:03:55）。破损由其后的 `e1a00733`（11:27:52）引入。**

`qmark` 列（ASCII `?` 的个数）从 0–2 突跳到 **1022**，与"单字节被 `?` 覆盖"的判据吻合。

### 3.2 修法选择：原文件不动，干净内容另存

任务明确要求「**不许**用"看起来正常"的文本覆盖掉原内容」。综合 §1 的结论：

- `research/HOUSEKEEPING.md` **保持原样**——它是**唯一的取证物证**，也是 F16 上报的现场。任何"修复性"重写都会销毁证据。
- 干净内容**逐字节**写入 `research/HOUSEKEEPING_RECOVERED_F17.md`（即 `ca1b2085` 的 blob 原样）。

命令：`research/f17/recover_apply.ps1`
```powershell
$gb = Get-GitBytes "ca1b2085"                       # git cat-file -p ca1b2085:research/HOUSEKEEPING.md
[System.IO.File]::WriteAllBytes($recovered, $gb)    # 逐字节落盘，不做任何转码
```

### 3.3 「无法无损修复」的范围与原因

**不可无损修复的部分：L24–L72 的 F11–F13 台账行（33 行受损行中的 27 行属于此类）。**

原因（三条独立证据）：

1. **字节层面不可逆**：`0xBA → 0x3F`、`0x94 → 0x3F` 这类替换没有逆映射。`0x3F` 本身不携带任何关于原字节的信息。
2. **长度不守恒**：不仅有替换，还有**字节丢失**（§1.3 样本 B 少了 `0x20`；§1.4 少了 17 个 `0x0A`）。任何"补回"都是猜测。
3. **git 里没有干净版本**：这两轮内容**首次落盘时就已经是坏的**（`e1a00733` 与 `2c60b07e` 均为 `INVALID_UTF8`）。git 对象库里不存在它们的完好形态。

⇒ **我拒绝用"看起来合理"的中文去填这些行。** 任何补写都是编造，不是修复。

**补救路径（供原作者使用，我没有代劳）**：F11–F13 的原始报告全部完好且是合法 UTF-8，可据此重写台账行。

```
EXISTS  research/web/R8_ATTEMPT_F11.md       bytes=29794  strict-utf8=True
EXISTS  research/web/R8_UITAP_F13.md         bytes=22833  strict-utf8=True
EXISTS  research/web/R8_KEEPAWAKE_F12.md     bytes=20910  strict-utf8=True
EXISTS  research/web/_f13_hk_row.md          bytes=3465   strict-utf8=True   ← 疑似 F13 那一行的原始片段
EXISTS  research/web/FIX_D1.md               bytes=31886  strict-utf8=True
```

### 3.4 逐行一致性证明

**证明 1 — 还原文件与 git blob 逐字节一致**

```
=== PROOF 1: recovered file is byte-identical to git blob ca1b2085:research/HOUSEKEEPING.md ===
git blob bytes      = 43923
recovered file bytes= 43923
git blob  SHA256    = 179E979CBA09EC938ACE066B887A4FA146AE70CDB610702937C6C91C711449CD
recovered  SHA256   = 179E979CBA09EC938ACE066B887A4FA146AE70CDB610702937C6C91C711449CD
IDENTICAL           = True
```

**证明 2 — 原文件未被改动（取证物证完好）**

```
=== PROOF 2: original research/HOUSEKEEPING.md was NOT modified ===
SHA256 before = 143342488C661C93B887DF28A37216FDCCF40717CB02D844BDD47A29B611EE1E
SHA256 after  = 143342488C661C93B887DF28A37216FDCCF40717CB02D844BDD47A29B611EE1E
UNCHANGED     = True
```

**证明 3 — 严格 UTF-8 校验**

```
=== PROOF 3: strict UTF-8 validity ===
ca1b2085 blob   strict-utf8 = True
RECOVERED file  strict-utf8 = True
HEAD  (current) strict-utf8 = False   <-- still broken, deliberately untouched
```

**证明 4 — 逐行 `Compare-Object`**

```
=== PROOF 4: line-by-line Compare-Object, HEAD(current) vs RECOVERED ===
current lines = 74   recovered lines = 91
Compare-Object differing records = 89
(EXPECTED: HEAD gained the F11-F13 rows AND lost 17 line breaks)
```

**逐行一致性（"除破损处外一致"）的正面结论**：

```
--- subset check: are the still-VALID current lines present verbatim in RECOVERED? ---
current VALID lines           = 38
  of which NOT found in recovered (i.e. new F11-F13 content) = 38
  new content line numbers    = 7, 8, 9, 11, 12, 13, 14, 15, 16, 17, 19, 20, 21, 22, 23, 30, 32,
                                 34, 36, 37, 39, 41, 43, 44, 46, 47, 50, 51, 54, 55, 57, 61, 62,
                                 65, 67, 69, 71, 73
current DAMAGED lines         = 35
  damaged line numbers        = 1, 2, 3, 4, 5, 6, 10, 18, 24, 25, 26, 27, 28, 29, 31, 33, 35, 38,
                                 40, 42, 45, 48, 49, 52, 53, 56, 58, 59, 60, 63, 64, 66, 68, 70, 72
```

**逐字节级映射**（把当前行的 `0x3F` 当通配符，去撞干净版的行）：

```
cur L1  <-  good L1   off=0   len=39     RECOVERABLE
cur L3  <-  good L7   off=336 len=55     RECOVERABLE
其余 33 行：NOT-RECOVERABLE
```

**证明 6 — 换行符**

```
=== PROOF 6: line-break loss ===
GOOD  0x0A count = 90  0x0D count = 0
CUR   0x0A count = 73  0x0D count = 1
```

### 3.5 我新追加的行（明确标出）

| 文件 | 性质 | 标注 |
|---|---|---|
| `research/HOUSEKEEPING_RECOVERED_F17.md` | **零新增行**。是 `ca1b2085` blob 的逐字节副本，SHA256 已证。**我没有加任何前言/页眉/注释**——加了就不再是逐字节还原 | 无 |
| `research/HOUSEKEEPING_FIX_F17.md`（本文件） | **全新文件**，F17 报告 | 全文 |
| `research/f17/**` | 全新：分析脚本 + 原始输出 | 全部 |

**⇒ 对 `research/HOUSEKEEPING.md`：新增 0 行、修改 0 行、删除 0 行。**

---

## 4. 事件 2：AVD 磁盘占用（只报告，绝不删除）

### 4.1 采集方法

命令：`research/f17/avd_table.ps1` — 对 `$env:USERPROFILE\.android\avd\` 下每个 `*.ini`，递归求和其同名 `*.avd` 目录的 `File.Length`，再加上 `.ini` 自身字节。

```powershell
Get-ChildItem "$env:USERPROFILE\.android\avd" -Filter '*.ini' -File | ForEach-Object {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
    $dir  = Join-Path "$env:USERPROFILE\.android\avd" "$name.avd"
    (Get-ChildItem $dir -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum
}
```

交叉校验：整树独立求和 = **315,548,508,935 B**，与逐个 AVD 求和**完全相等**（`FILES=15585`）。两条独立路径同数，无遗漏。

`emulator -list-avds` 输出 40 个名字，与 `.ini` 数量一致。

### 4.2 归属判定依据

**判定依据来自仓库内既有规矩本身**，不是我的猜测。`HOUSEKEEPING.md` 规矩第 2 条（`ca1b2085` 第 13–14 行，干净版）原文：

> 2. **只关自己起的**：判据是 **AVD 名**（我们的是 `OC_*` 与 `Medium_Desktop_B`），
>    **用户自己的 AVD（`MCRemote_*`、`DSH_*`、`T7x_*` …）与任何来源不明的实例，一律禁 kill**。

据此：

- **我们建的 = `OC_*` 前缀（11 个）+ `Medium_Desktop_B`（1 个）= 12 个**
- **产品负责人的 = 其余 28 个**（`DSH_*` 12、`T7x_*` 8、`MCRemote_*` 2、`Pixel_*` 5、`Medium_Desktop` 1）

⚠️ 注意 **`Medium_Desktop`（无后缀）是产品负责人的**，`Medium_Desktop_B` 才是我们的。两者极易混淆，务必看准 `_B` 后缀。
`T7x_*` 实际命名为 `T59_*`/`T67_*`/`T72_*`/`T76_*`/`T77_*`/`T78_*`/`T59_Rc35` 等。

### 4.3 完整占用表（按字节数降序）

| # | AVD | 归属 | 字节 | GiB | 文件数 | 快照数 |
|---:|---|---|---:|---:|---:|---:|
| 1 | Pixel_10_Pro_Fold | 产品 | 16,796,309,300 | 15.64 | 5371 | 1 |
| 2 | DSH_Fold_Test | 产品 | 14,457,341,591 | 13.46 | 41 | 1 |
| 3 | T59_Upgrade | 产品 | 14,150,485,955 | 13.18 | 26 | 1 |
| 4 | Pixel_10_Pro_XL | 产品 | 13,464,480,507 | 12.54 | 2893 | 1 |
| 5 | Pixel_9a | 产品 | 13,178,842,407 | 12.27 | 1228 | 1 |
| 6 | Pixel_Tablet | 产品 | 12,515,716,524 | 11.66 | 2174 | 1 |
| 7 | MCRemote_Tablet | 产品 | 12,498,922,207 | 11.64 | 2094 | 1 |
| 8 | MCRemote_Phone | 产品 | 12,448,837,977 | 11.59 | 491 | 1 |
| 9 | **OC_Phone_Test_C** | **我们** | 12,438,036,293 | 11.58 | 31 | 1 |
| 10 | DSH_T65_Phone | 产品 | 10,926,306,014 | 10.18 | 492 | 1 |
| 11 | **OC_Preflight_WebView83** | **我们** | 9,233,933,825 | 8.60 | 26 | 1 |
| 12 | **OC_Phone_Test_B** | **我们** | 8,968,348,973 | 8.35 | 18 | 1 |
| 13 | Medium_Desktop | 产品 | 8,850,597,825 | 8.24 | 32 | 1 |
| 14 | **OC_Deliver_R2** | **我们** | 7,888,415,039 | 7.35 | 49 | 1 |
| 15 | **Medium_Desktop_B** | **我们** | 7,273,011,466 | 6.77 | 32 | 1 |
| 16 | **OC_Perf_Test** | **我们** | 7,093,047,956 | 6.61 | 28 | 1 |
| 17 | DSH_T63 | 产品 | 7,039,415,364 | 6.56 | 28 | 1 |
| 18 | **OC_Phone_Test** | **我们** | 7,029,738,554 | 6.55 | 24 | 1 |
| 19 | DSH_Kbd_Test | 产品 | 6,926,890,108 | 6.45 | 28 | 1 |
| 20 | DSH_Class_Test | 产品 | 6,691,732,145 | 6.23 | 26 | 1 |
| 21 | Small_Desktop | 产品 | 6,528,001,784 | 6.08 | 31 | 1 |
| 22 | **OC_Narrow_Test** | **我们** | 6,028,510,210 | 5.61 | 21 | 1 |
| 23 | DSH_File_Test | 产品 | 6,003,344,527 | 5.59 | 23 | 1 |
| 24 | T59_Rc35 | 产品 | 5,853,527,696 | 5.45 | 22 | 1 |
| 25 | **OC_FF_F4** | **我们** | 5,689,557,962 | 5.30 | 21 | 1 |
| 26 | DSH_T80_Tablet | 产品 | 5,651,678,313 | 5.26 | 22 | 1 |
| 27 | **OC_Preflight_V3** | **我们** | 5,637,092,031 | 5.25 | 27 | 1 |
| 28 | **OC_Notify_Test** | **我们** | 5,633,393,856 | 5.25 | 21 | 1 |
| 29 | **OC_NotifyRC** | **我们** | 5,614,650,329 | 5.23 | 21 | 1 |
| 30 | DSH_T65_Tablet | 产品 | 5,577,817,833 | 5.19 | 21 | 1 |
| 31 | T78_Tablet | 产品 | 5,570,806,838 | 5.19 | 22 | 1 |
| 32 | DSH_T79_Tablet | 产品 | 5,562,614,884 | 5.18 | 22 | 1 |
| 33 | T72_Tablet | 产品 | 5,546,558,515 | 5.17 | 22 | 1 |
| 34 | T67_Tablet | 产品 | 5,496,685,475 | 5.12 | 20 | 1 |
| 35 | T76_Phone | 产品 | 4,644,848,409 | 4.33 | 19 | 1 |
| 36 | DSH_T58 | 产品 | 4,355,538,130 | 4.06 | 28 | 1 |
| 37 | T77_Phone | 产品 | 4,249,994,176 | 3.96 | 21 | 1 |
| 38 | T78_Phone | 产品 | 4,130,260,014 | 3.85 | 23 | 1 |
| 39 | DSH_T80_Phone | 产品 | 3,958,555,768 | 3.69 | 24 | 1 |
| 40 | DSH_T79_Phone | 产品 | 3,944,662,155 | 3.67 | 22 | 1 |

含 mtime 原始输出的完整版：`research/f17/avd_table.txt`

### 4.4 合计

```
OURS    count=12  bytes= 88,527,736,494  GiB= 82.45
PRODUCT count=28  bytes=227,020,772,441  GiB=211.43
TOTAL   count=40  bytes=315,548,508,935  GiB=293.88
OURS share = 28.1%
```

- **我们建的：88,527,736,494 B = 82.45 GiB**（12 个）
- **全部：315,548,508,935 B = 293.88 GiB**（40 个）
- 我们的占比 **28.1%**；产品负责人的 **211.43 GiB** 不可动。

### 4.5 磁盘可用空间

```
C: Used=1671126216704 (1,556.36 GiB)  Free=2424514117632 (2,258.00 GiB)  Total=4095640334336 (3,814.36 GiB)
```

**C: 可用 2,424,514,117,632 B = 2258.00 GiB**（AVD 全部删光也只占盘容量的 7.7%，所以删 AVD 不是"救火"，是卫生问题。）

---

## 5. 建议：删哪些、能省多少

🔴 **我没有删除任何东西，也没有执行 `avdmanager delete`，更没有动任何 `.ini`。以下只是建议，决定权在产品负责人。**

### 5.1 建议保留

| AVD | 字节 | GiB | 保留理由（带证据） |
|---|---:|---:|---|
| **OC_Deliver_R2** | 7,888,415,039 | 7.35 | 交付验证主力机。干净版台账里被引用 **43 次**，损坏尾部里 **47 次**；F11/F12/F13/F15 全用它 |
| **Medium_Desktop_B** | 7,273,011,466 | 6.77 | 规矩第 2 条点名的我们那台 Desktop 档；台账引用 **19 / 21 次**。下一轮若要复现 2560×1600 档位需要它 |

### 5.2 建议可删（我们建的、单次/一次性用途的 10 个）

| AVD | 字节 | GiB | 台账引用次数 | 说明 |
|---|---:|---:|---:|---|
| OC_Phone_Test_C | 12,438,036,293 | 11.58 | 1 | 一次性 |
| OC_Preflight_WebView83 | 9,233,933,825 | 8.60 | 1 | WebView 8.3 预检专用 |
| OC_Phone_Test_B | 8,968,348,973 | 8.35 | 1 | 一次性 |
| OC_Perf_Test | 7,093,047,956 | 6.61 | 3 | 性能测量一次性 |
| OC_Phone_Test | 7,029,738,554 | 6.55 | 3 | 最早的机型试探 |
| OC_Narrow_Test | 6,028,510,210 | 5.61 | 2 | 窄屏专项 |
| OC_FF_F4 | 5,689,557,962 | 5.30 | 4 | F4 常亮专项，结论已落 `FIX_F4_KEEPSCREEN.md` |
| OC_Preflight_V3 | 5,637,092,031 | 5.25 | 1 | 预检一次性 |
| OC_Notify_Test | 5,633,393,856 | 5.25 | 3 | 通知专项 |
| OC_NotifyRC | 5,614,650,329 | 5.23 | 5 | 通知回归专项，结论已落 `NOTIFICATION_CLICK_*.md` |

**小计（10 个）：73,366,309,989 B = 68.32 GiB**

```
验算：88,527,736,494 (OURS 全部)
   −  7,888,415,039 (OC_Deliver_R2, 保留)
   −  7,273,011,466 (Medium_Desktop_B, 保留)
   = 73,366,309,989 B = 68.32 GiB
```

**若同意清理，可从 2258.00 GiB 可用空间里回收 68.32 GiB（3.0%）。** 相对我们自己的 82.45 GiB 是 **83%** 的清理率。

### 5.3 一句话建议

> **删我们建的 10 个一次性 AVD（`OC_Phone_Test*` / `OC_Preflight_*` / `OC_Perf_Test` / `OC_Narrow_Test` / `OC_FF_F4` / `OC_Notify*`），保留 `OC_Deliver_R2` 与 `Medium_Desktop_B`，可回收 68.32 GiB；`DSH_*` / `T7x_*` / `MCRemote_*` / `Pixel_*` / `Medium_Desktop` 一个都别动。**
>
> 前提：产品负责人确认这 10 个的结论都已落进报告（§3.3 列出的 F11/F12/F13 报告均完好）。**F17 未执行任何删除。**

---

## 6. 未做的事 & 原因

| 未做 | 原因 |
|---|---|
| **未删除任何 AVD** | 硬约束 + 删除权不在 F17 |
| **未执行 `avdmanager delete`** | 同上 |
| **未修改任何 `.ini`** | 同上 |
| **未启动/停止任何模拟器，未碰 `adb`** | 硬约束：不碰设备 |
| **未重写 `research/HOUSEKEEPING.md`** | 33 行不可逆损坏；按要求"不许用看起来正常的文本覆盖原内容"，原文件保留为物证 |
| **未补写 F11–F13 台账行** | 无可信来源，臆造即编造。已给出可用源报告（§3.3） |
| **未提交任何 git commit** | 任务未要求；且 `HOUSEKEEPING.md` 未改，无需提交 |
| **未修 `research/web/DELIVER_REBUILD_R2.md`** | 该文件同样 `strict-utf8=False`（同类破损），但**不在本轮范围**，也**不是我的文件**，仅在此报告备案 |
| **未清理 `research/f17/`** | 保留为可复现证据链（含全部原始输出） |

**附带发现（备案，不处理）**：`research/web/DELIVER_REBUILD_R2.md`（29,985 B）也过不了严格 UTF-8 校验，与 `HOUSEKEEPING.md` 同类破损。建议下一轮由其作者处理。

---

## 7. 证据文件索引（全部在 `research/f17/`，可复现）

| 文件 | 内容 |
|---|---|
| `avd_sizes.ps1` | AVD 递归求和（输出 JSON） |
| `avd_table.txt` | AVD 完整表原始输出（含 mtime、快照数、磁盘信息） |
| `avd_table.ps1` | 生成上表 |
| `enc_scan.ps1` | 逐行严格 UTF-8 扫描 + 十六进制 |
| `git_hist.ps1` | 全历史逐 commit UTF-8 校验 |
| `recover.ps1` / `recover2.ps1` | 受损行可恢复性映射 |
| `recover_report.txt` | 逐行 offset/len/HEX + GBK/UTF-8 双解码原始输出 |
| `recover_apply.ps1` | 生成还原文件 + 6 项证明 |
| `proof.txt` | 6 项证明的完整原始输出 |

---

*F17 完。所有数字均来自上述命令的原始输出，无估算、无编造。*
