# HOUSEKEEPING —— F18 轮（本轮专属收尾记录）

> 承 `research/HOUSEKEEPING.md` 的硬规矩（谁起谁关 + 抄复核输出 + 只关 `OC_*`/`Medium_Desktop_B`）。
> **为什么单开一份**：`research/HOUSEKEEPING.md` 主体表格存在历史编码损坏（GBK/UTF-8 乱码），
> 为避免二次破坏既有内容，本轮记录单列于此，并在主文件口径上同源。

## 一、本轮起停的实例

| 时间 | 动作 | 细节 | 报告 |
|---|---|---|---|
| 2026-10-05 12:31–13:0x | **关闭** `OC_Deliver_R2`（`emulator-6410`，端口 **6410**，**F18 手机形态两缺陷修复轮自建**；未新建 AVD，沿 R2/G2/P6/F4/V6/V7/F5/V10–V12 已验证镜像） | 镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`（**未传 `-skin`**，承 V7 教训：`pixel_tablet` 非合法 skin 名）。几何手机档 `wm size 1080x2340` + `wm density 480` ⇒ `1080/(480/160)=360dp`、`2340/3=780dp`，实测回读 `innerW=360 / innerH=780 / dpr=3` 自洽；平板回归用 `wm size/density reset` 后的物理 `2560x1600@320`（实测 `1280x800 / dpr=2`）。选端口理由：**偶数、≥6410**，起前实测 6410/6412/6414/6420/6422/6424/6430 全部 FREE。开轮时 `adb devices` 已有 `emulator-5680`(`DSH_T79_Phone`) ⇒ 按规则 2 **全程未碰、未 kill**。后端 `4095` **全程未起停**（开轮与收尾均 `LISTEN pid=3748`，**同一 pid**），只做 `adb reverse`。杀前按 AVD 名 + 端口确认 `PID=28396 avd=OC_Deliver_R2 port=6410 MINE=True`；`wm size reset` + `wm density reset` → 复核 **`Physical size: 2560x1600` / `Physical density: 320`**（**无 Override 行**）；`reverse --remove tcp:4095` → 复核 `reverse --list` **空**；`adb -s emulator-6410 emu kill` → `OK: killing emulator, bye bye` / `OK`。复核 **`OC_*/Medium_Desktop_B` residual = 0**、**端口 6410/6412/6414 全 FREE**、`.agent-gradle-lock exists = False`（用**单条顶层 `rm --`** 释放，回执 `mavis-trash: moved to trash`，**未绕过**）。**未执行** `adb kill-server`、**未用** `-a`/通配符，`emu kill` **只带一个 serial**。⚠️ **复核时残留 3 个进程都不是我的**：一个 `PID=52244 -avd T88_Phone -port 5700`（**别人的实例，未碰**）、两个 `PID=36412/42452 … -kill 47516 -sleep 20`（`emu kill` 自拉的**瞬时拆卸辅助进程**，命令行**不带 `-avd`**，按规则 2 判据**不属于我**故**未 kill**，自然退出）。⚠️ 如实记录：开轮时在列的 `emulator-5680`(`DSH_T79_Phone`) 收尾时已不在 `adb devices`；本轮**只对 6410 发过一条 `emu kill`** ⇒ 其消失**不是本轮造成的**，**未从本轮日志得出因果**，不代其它轮次下结论。**故意保留：无。** 本轮**只碰过 `emulator-6410`** 一个 serial。 | `research/web/FIX_F18_PHONE.md` |

## 二、故意保留项

- **无**。本轮过程产物 `research/env/out_f18/`（截图、读数、反向验证日志、CDP 采集器 `cdp.ps1`）留在仓内 ——
  原因：它们是 §3 两处修复的**原始证据**（`d1/d2/d3` 三连读数、`green.log`/`reverse_red.log`），
  删除会掏空本轮的证据链。**不影响分发**（分发件只在 `research/deliver/`）。
- `OC_Deliver_R2` **AVD 定义未删** —— 原因：长期资产，R2/G2/P6/F4/V6/V7/F5/F10–F12 等多轮共用。

## 三、收尾复核输出（原文）

```
# 杀前确认是自己起的
PID=28396 MINE_CHECK avd=OC_Deliver_R2 port6410=True

# 撤销 override（按 serial）
$ adb -s emulator-6410 shell wm size reset   →  Physical size: 2560x1600
$ adb -s emulator-6410 shell wm density reset → Physical density: 320        ← 无 Override 行

# 摘 reverse（只摘自己那条）
$ adb -s emulator-6410 reverse --list         →  host-16 tcp:4095 tcp:4095
$ adb -s emulator-6410 reverse --remove tcp:4095
$ adb -s emulator-6410 reverse --list         →  （空）

# 只按 serial 关
$ adb -s emulator-6410 emu kill
OK: killing emulator, bye bye
OK

# 复核
emulator/qemu process count = 3
PID=52244 CMD=… -avd T88_Phone -port 5700 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save
PID=36412 CMD=…\emulator  -kill 47516 -sleep 20
PID=42452 CMD=…\emulator  -kill 47516 -sleep 20
OC_*/Medium_Desktop_B residual = 0
PORT 6410 LISTEN=False
PORT 6412 LISTEN=False
PORT 6414 LISTEN=False
$ adb devices
List of devices attached
emulator-5700	device
.agent-gradle-lock exists = False        （单条顶层 rm -- 释放；回执 mavis-trash: moved to trash）
4095 LISTEN pid=3748                     ← 与开轮前同一 pid，后端全程未起停
```
