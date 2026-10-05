# HANDOFF_FIX_F10 — §4.5 口径更新（旧包证据 → 已在交付包重做）+ IME 内容级重叠补测

- 轮次：**F10 收尾轮** · 2026-10-05（硬期限 2026-10-07 前；文档明晚要交）
- 设备：`emulator-6270`（AVD `OC_Deliver_R2`，端口 **6270**，**本轮自建 → 本轮关**）
- 写权限范围：`HANDOFF_WEB_SHELL.md` + `research/web/**` + `research/perf/**` + `research/env/**`
- **未碰**：`app/**`、`webapp/src/**`、`_upstream/**`、`.github/**`、`research/deliver/**`、别人的报告

---

## 0. 一句话

**§4.5 那句「错误态与常亮的设备证据取自旧构建」已作废**，改成
**「已在当前交付包 `76ca064b…` 上重做（V12）」**；
顺带把 V12 留下的**唯一缺口（IME 内容级重叠）**在设备上补测并**闭环为 0 px**。

---

## 1. 事件一：§4.5 口径更新（纯文档）

### 1.1 改了什么（逐条，可 diff 复核）

| 位置 | 改前 | 改后 |
|---|---|---|
| §4.5 开头「最重要的一句话」 | 「**错误态三态与回合常亮三段的设备证据，取自 `bc7f3018…` 与 `627f8ed8…` 两份包**；除非已在 `76ca064b…` 上重跑出新证据，否则这两节的 ✅ PASS 不覆盖当前交付包」 | 「**已在当前交付包 `76ca064b…` 上重做完成（V12 轮，`emulator-6250`）**」，并注明 **未沿用** `bc7f3018…`/`627f8ed8…`/`b063148d…` 任何结论；原表述**已于 F10 作废** |
| §4.5 分级表 · 接入健壮性三态 | 🟡 验于 `bc7f3018…`/`627f8ed8…`，**非交付包** | 🟡 **V12 已在交付包 `76ca064b…` 上重做**（旧轮次降为「历史」） |
| §4.5 分级表 · 回合常亮三段 | 🟡 验于 `bc7f3018…`/`627f8ed8…`，**非交付包** | 🟡 **V12 已在交付包 `76ca064b…` 上重做**（+95 s 仍 `flagSet=false`） |
| §4.5 分级表 · 使用规则第 3 条 | 「**F9 没有做这件事**，也不打算用文档措辞假装做过」 | 「**F10 已为「接入健壮性三态 + 回合常亮三段 + 首屏 3 次」做完这一步**（V12 轮）；**仍靠旧包或仍是"测不到"的项一律不变，不得因这一条顺手升级**」 |
| §4.5 分级表 · 新增首屏行 | — | 🟡 **V12 首屏 3 次**：中位数 **667 ms**（极值 664–680，n=3） |
| §4.5 开头 · IME 那格 | 「内容级重叠**测不到**」（V12 原话） | 「**已在交付包上测得内容级重叠 = 0 px（余量 52 px），该缺口已闭环**」；仍测不到的只剩**页面级**可视视口度量 |
| §4 汇总表 · 三态/常亮两行 + F9 读法提示 | 「验于 `bc7f3018…`/`627f8ed8…`，**非交付包**」 | 「**V12 已在交付包 `76ca064b…` 上重做**」，并**显式保留 🟡 级别**（复验仍需重跑设备） |
| §6 未闭环清单 · 第 8 条 | 「当前交付包未单独复验三段结论」 | **划掉并标注「已闭环（V12）」**，但**保留**第 9 条（`ROUND_END` 快路径 / 30 min 天花板仍未验）与 V12 回合起点的驱动方式说明 |
| §4.3 / §6 未闭环清单 · 第 19 条 | 「会话消息 composer 的 IME 重叠**未测**」 | **划掉并标注「已闭环（F10）」**，附口径提示（取自无障碍节点树、页面级仍测不到） |

### 1.2 遵守的红线

- ✅ **A5 提出的其它未闭环项一条没删**。第 8 条是**划线 + 标注已闭环**，并把 V12 的
  `ROUND_END` 快路径 / 30 min 天花板**留在第 9 条不动**；耗电 mAh ❌ 无证据、CDP 页面级 ❌ 测不到等**原样保留**。
- ✅ **只改这一处口径**，没大改无关章节。改动集中在 §4.5 + 与之直接冲突的**同口径交叉引用**
  （§4 汇总表两行、§6 第 8/19 条）；§0–§3、§5、§7 之后的章节未动。
- ✅ **没有把 🟡 偷偷升级成 ✅ 静态可复验**——复验仍需重跑设备，级别保持 🟡。
- ✅ **没有把「窗口级 189 px」当成内容级答案**（见第 2 节与 §4.5 显式对照表）。

### 1.3 md5 复核（`Get-FileHash`，与磁盘一致）

```
$ Get-FileHash research/deliver/opencode-shell-web-release-nor8.apk -Algorithm MD5
Algorithm : MD5
Hash      : 76CA064B7DE9246EE446F765A9198B37
size      : 3975389
```

**F10 独立复核值 = `76ca064b7de9246ee446f765a9198b37`（与 V12 轮记载逐位一致，3,975,389 B）。**

设备侧双向复核（本轮装包时实测）：

```
PREINSTALL_MD5 =76ca064b7de9246ee446f765a9198b37  size=3975389
POSTINSTALL_MD5_RAW=76ca064b7de9246ee446f765a9198b37 /data/app/~~vO5yaYccGlDwIZJBZDpvVQ==/com.xiufeigo.opencodeshell.web.debug-8iFCSHHViy2Rh6a5tQEyTw==/base.apk
MD5_MATCH_LOCAL_EQ_DEVICE=True
```

⇒ **磁盘 == 装机 == 交付包，证据确实取自当前交付包。**

---

## 2. 事件二：IME 内容级重叠补测（设备）

完整方法 / 原始输出 / 收尾复核见 **`research/perf/IME_CONTENT_OVERLAP_F10.md`**。此处只给结论。

| 项 | 结果 |
|---|---|
| 装包 | md5 `76ca064b…` 双向复核 ✅ |
| 前置断言（已鉴权 + 数据已到，**非骨架屏**） | `probe OK code=200`、`AUTH_FAILED=0`、10 个 API GET、2 个真实 session id、`M.g1.firstContent` ✅ |
| 进入会话 | `Counting from 1 to 4000…`（真实会话详情页） |
| 聚焦 composer → 断言 | `PRE=mInputShown=false` → **`POST=mInputShown=true`** ✅（同一次设备端 shell 内「点+读」，避免竞态） |
| 键盘顶边 | **`y=1474`** —— `dumpsys window` 的 `frame=[0,1474][1080,1600]` 与 `touchable region=SkRegion((0,1474,1080,1600))` 与 **`screencap` 逐行像素扫描**（`KEYBOARD_BAND_TOP=1474`）**三重互证** |
| composer 实际 bounds | `uiautomator` 真实节点：`EditText [52,1144][1029,1307]`（`focused=true`）；composer 内**最低**节点 `[157,1304][913,1422]` |
| **内容级重叠量** | **= 0 px**（最保守取 composer 外沿 1422 vs 键盘 1474 ⇒ **0 px，余量 52 px**；只取输入框 1307 ⇒ 0 px，余量 167 px） |
| CDP | ❌ 仍不可用（`DEVTOOLS_SOCKETS=0`，结构性）——**如实记录，未用措辞假装做过** |

### 2.1 「窗口级 189 px」与「内容级 0 px」不矛盾（本轮核心澄清）

| | 量的是什么 | 值 | 能否回答「内容有没有被键盘压住」 |
|---|---|---|---|
| **窗口级**（V12） | 宿主**窗口矩形** ∩ 键盘矩形 | **189 px** | ❌ **不能**。本宿主 `sim={adjust=resize}` + `pfl=EDGE_TO_EDGE_ENFORCED` + `FIT_INSETS_CONTROLLED`，**窗口保持全高 1600 不缩**，键盘靠 insets 内边距避让 ⇒ 窗口矩形 ∩ 键盘**必然等于键盘高度**，**非 0 属正常** |
| **内容级**（F10） | 浏览器**实际排版内容**（composer 节点真实矩形）∩ 键盘**实际绘制带** | **0 px** ✅ | ✅ 这才是派单问的「预期 0」，**实测就是 0** |

### 2.2 最强替代证据链（CDP 不可用时用了什么）

不是「测不到就交白卷」，而是换了一条**同样能量到真实布局矩形**的通道：

1. **Android 无障碍节点树**（`uiautomator dump`）—— 本镜像上**读到了 WebView 内节点**（105 个节点，
   含 composer `EditText` 的**真实布局矩形**与 `focused=true`）。
2. **截图像素扫描**—— 独立于 `dumpsys` 再测一次键盘顶边（1474），两条来源一致。
3. **截图肉眼复核** —— `out_f10/ime_shown.png` 可见键盘深色带起于 composer 之下。

⚠️ **口径诚实性**：第 1 条**不是** CDP `getBoundingClientRect()`，而是**无障碍节点矩形**；
对静态布局两者相等，若页面用 CSS transform 可能差几 px。**本轮闭环的是 composer 这一个元素，
范围比「整个可视视口」窄**；`visualViewport.height` / `document.body` 可视高**仍测不到**（同样因无 CDP）。

---

## 3. 收尾（housekeeping）

原文见 `research/web/out_f10/closeout.log`：

```
MINE_COUNT=2  (pid=37776 / pid=35508, avd=OC_Deliver_R2 port=6270 MINE=True)
BACKEND_4095: LISTEN pid=3748        ← 开轮与收尾同一 pid，全程未起停
REVERSE_AFTER: (空)                   ← 只摘自己那条
WM_SIZE_AFTER: Physical size: 2560x1600        ← 已无 Override
WM_DENSITY_AFTER: Physical density: 320       ← 已无 Override
EMU_KILL: OK: killing emulator, bye bye  OK
VERIFY emulator/qemu procs = 0
VERIFY OC_*/Medium_Desktop_B residual = 0
VERIFY port 6270/6272/6274: FREE
VERIFY adb devices: (空)
VERIFY 4095 backend still: LISTEN pid=3748
LOCK .agent-gradle-lock exists = False
```

- **未执行** `adb kill-server`、**未用** `-a` / 通配符，**只按 serial `emulator-6270` 关**。
- **故意保留：无。**
- 开轮时 `adb devices` 为空、全机 emulator/qemu 进程数 0 ⇒ **不存在可被波及的用户设备**；
  用户的 `MCRemote_*` / `DSH_*` / `T7x_*` **全程一个都没碰**。
- 端口 **6270**（偶数、≥6270），启动前实测 6270/6272/6274/6276/6278 全 FREE。
- 镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet` 档位，几何**全部走 `wm` 覆盖**
  （`wm size 1080x1600` + `wm density 420`，高度 ≤1600 主动规避 F4/R2/V6 记录的静默退出形态），
  **未传 `-skin pixel_tablet`**（非合法 skin 名，V7 教训）。**本轮全程未静默退出。**

### 碰过的 serial

| serial | AVD | 端口 | 起 | 关 |
|---|---|---|---|---|
| `emulator-6270` | `OC_Deliver_R2` | 6270 | 本轮自建 | 本轮 `emu kill` ✅ |

**本轮只碰过 `emulator-6270` 一个 serial。**

---

## 4. 交付物

| 文件 | 内容 |
|---|---|
| `HANDOFF_WEB_SHELL.md` | §4.5 口径更新 + 同口径交叉引用同步（§4 汇总表、§6 第 8/19 条） |
| `research/web/HANDOFF_FIX_F10.md` | 本文件（改了什么 + md5 复核值） |
| `research/perf/IME_CONTENT_OVERLAP_F10.md` | 方法 / 原始输出 / 重叠像素量 / 收尾复核 / 碰过的 serial |
| `research/web/out_f10/` | 原始产物：`ime_shown.png`、`ime_uia.xml`、`ime_window_dump.txt`、`ime_input_method.txt`、`home.png`、`sess.png`、`p1.log`、`closeout.log` |
| `research/web/_f10_*.ps1` | 可复现脚本（boot / install+seed / closeout） |

**未提交 git**（派单未要求）。
