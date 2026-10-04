# 环境清理规矩（Housekeeping）

> 立此规矩的原因：产品负责人明确提出 —— **测试完毕后必须关掉我们自己启动的模拟器**，
> 否则会出现 **内存泄漏与 CPU 幽灵占用**（`emulator.exe` / `qemu-system-x86_64.exe` 常驻吃资源）。
> 这条**适用于此后每一轮、每一个 agent**。

## 一、硬规矩（写进每一份派单的收尾）

1. **谁起谁关**：任何 agent 只要启动了模拟器实例，**在收尾时必须关掉自己起的那台**，并**抄出复核输出**：
   - `adb -s <serial> emu kill`
   - 复核：`Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'"` 里**不再有该 AVD**；
   - 复核：`Get-NetTCPConnection -LocalPort <port> -State Listen` **应为空**。
2. **只关自己起的**：判据是 **AVD 名**（我们的是 `OC_*` 与 `Medium_Desktop_B`）。
   **用户自己的 AVD（`MCRemote_*`、`DSH_*`、`T7x_*` …）与任何来源不明的实例，一律禁止 kill。**
3. **不许关 `adb` server**（`adb kill-server`）：它服务的是**共享设备连接**，会扰动用户的设备。
4. **不许用 `-a` / 全量 kill / 通配符杀进程**；只按 **serial** 或**已确认属自己的 AVD 名**关。
5. 若某轮**明确还要继续用**那台设备：**在报告里写明"故意保留 <avd> / <serial>，原因 …"**，
   并在下一轮收尾时补关。

## 二、清理脚本（可复用）

```powershell
# 只关我们自己的（按 AVD 名判定），并复核
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ours = Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match '-avd\s+(OC_\S+|Medium_Desktop_B)' }
foreach ($p in $ours) {
  $port = if ($p.CommandLine -match '-port\s+(\d+)') { $Matches[1] } else { $null }
  if ($port) { & $adb -s "emulator-$port" emu kill }
}
# 复核：进程 0 残留 + 端口已释放
```

## 三、已执行记录

| 时间 | 动作 | 结果 |
|---|---|---|
| 2026-10-04 21:2x | 关闭 `OC_Narrow_Test`（`emulator-5824`） | `OK: killing emulator, bye bye`；**0 残留进程**；**端口 5824 已释放**；用户设备 `5606/5620` 未受影响 |
| 2026-10-05 00:5x | 关闭 `OC_Perf_Test`（`emulator-5860`，端口 5860） | `adb -s emulator-5860 emu kill` → `OK: killing emulator, bye bye`；复核 **0 残留进程**（无 `OC_Perf_Test` 进程）、**端口 5860 无监听**（仅剩 `TimeWait` 残根 `OwningProcess=0`）；用户设备 `5654`(DSH_T80_Tablet)/`5656`(DSH_T80_Phone) **未受影响**；未执行 `adb kill-server`（收尾后 `adb devices` 仍正常列出 3 台） |
| 2026-10-05 01:0x | **关闭** `OC_Notify_Test`（`emulator-5870`，端口 5870，N1 通知轮自建） | `adb -s emulator-5870 emu kill` → `OK: killing emulator, bye bye`；复核 **0 残留进程**（无 `OC_Notify_Test` 进程）、**端口 5870 无 LISTEN**；`adb devices` 已无 `5870`；用户设备 `5654`(DSH_T80_Tablet)/`5656`(DSH_T80_Phone) **仍在、未受影响**；未执行 `adb kill-server`、未用 `-a`/通配符。上表第 40 行"故意保留"已由本行闭环（那一路是 N1 起的就是 N1 关） |
| 2026-10-05 01:3x | **关闭** `OC_NotifyRC`（`emulator-5880`，端口 5880，**N2 通知根因轮自建**） | 杀前确认 `pid=50964 avd=OC_NotifyRC port=5880 mine=True`；`adb -s emulator-5880 emu kill` → `OK: killing emulator, bye bye`；复核 **`OC_NotifyRC` 残留进程数 = 0**、**端口 5880 无 LISTEN**、`adb devices` 已无 `5880`；**未执行** `adb kill-server`、**未用** `-a`/通配符，只按 serial 关。<br>⚠️ **本行同时记录一个观察**：收尾复核时全机 emulator/qemu 进程数为 0，`5654`/`5656`/`5892` 也都不在了（用户实例 + 另一路 `OC_FF_F4`）。N2 全程**只**对 `emulator-5880` 发过 kill，原理上无法波及其它 serial；消失原因**未从本轮日志得出因果**，**如实记录、不代其它轮次下结论** |

| 2026-10-05 01:0x–01:2x | **关闭** `OC_FF_F4`（`emulator-5892`，端口 5892，F4 形态覆盖自建；镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`） | `wm size reset` + `wm density reset` → 复核 `Physical size: 2560x1600` / `Physical density: 320`（**已无 Override 行**）；`adb -s emulator-5892 emu kill` → `OK: killing emulator, bye bye`；复核 **0 残留进程**、**端口 5892 LISTEN=0**（仅 `TimeWait` 残根）；用户设备 `5654`(DSH_T80_Tablet)/`5656`(DSH_T80_Phone) **未受影响**；**`OC_NotifyRC`(5880) 不是本轮起的，全程未碰**；未执行 `adb kill-server`、未用 `-a`/通配符。**故意保留：无**。本轮共 boot 3 次（中途静默退出 2 次，同 E7 §1.2 模式，均发生在几何高度超出物理面板时）。报告：`research/env/FORMFACTORS_F4.md` |

| 2026-10-05 01:29–01:53 | **关闭** `OC_Deliver_R2`（`emulator-5900`，端口 5900，**R2 交付重建轮自建**；克隆自 `OC_FF_F4`，镜像 `android-35;google_apis_playstore_tablet` + `pixel_tablet`） | `wm size reset` + `wm density reset` → 复核 `Physical size: 2560x1600` / `Physical density: 320`（**已无 Override 行**）；杀前按 AVD 名确认 `pid=51956 avd=OC_Deliver_R2 port=5900 MINE=True`；`adb -s emulator-5900 emu kill` → `OK: killing emulator, bye bye`；复核 **emulator/qemu 进程数 = 0**、**`OC_Deliver_R2` 残留 = 0**、**端口 5900 LISTEN=0**（仅 `TimeWait` 残根 `OwningProcess=0`）、`.agent-gradle-lock exists = False`；未执行 `adb kill-server`、未用 `-a`/通配符。**故意保留：无**。⚠️ 本轮该 AVD **静默退出 3 次**（`adb devices` 直接掉线、脚本卡在失联 adb 上），模式与 F4 轮 E7 §1.2 一致（几何 2400 > 物理高度 1600）；**规避手段 = 串行跑、同一时刻只开一个 adb 客户端**（早期两次挂死确为两后台任务抢 adb）。本轮**只碰过 `emulator-5900`** 一个 serial；开始时 `adb devices` 为空、emulator/qemu 进程数 0，故**没有任何用户设备可被波及**。后端 `4095` 全程未起停。报告：`research/web/DELIVER_REBUILD_R2.md` |

| 2026-10-05 02:0x–02:2x | **关闭** `OC_Deliver_R2`（`emulator-5910`，端口 5910，**G2 复核收口轮复用**：未新建 AVD，沿用 R2 已验证的镜像+几何组合） | `wm size reset` + `wm density reset` → 复核 `Physical size: 2560x1600` / `Physical density: 320`（**已无 Override 行**）；杀前按 AVD 名确认 `pid=43228 avd=OC_Deliver_R2 port=5910 MINE=True`；`adb -s emulator-5910 emu kill` → `OK: killing emulator, bye bye`；复核 **`OC_Deliver_R2` 残留 = 0**、**端口 5910 LISTEN=0**、`adb devices` 已空、`.agent-gradle-lock exists = False`；未执行 `adb kill-server`、未用 `-a`/通配符。**故意保留：无**。⚠️ 收尾时 `emulator/qemu process count = 1`，**如实记录并未处理**：该进程为 `emulator -kill 51324 -sleep 20`，是 `emu kill` 自拉的**瞬时拆卸辅助进程、不是 AVD**，按本文件规则 2 判据（`-avd (OC_*\|Medium_Desktop_B)`）**不属于我**故未 kill；25 s 后自行退出，复查 `count = 0` / `OC_Deliver_R2 residual = 0` / `port 5910 LISTEN = 0`。⚠️ 本轮该 AVD **静默退出 1 次**（跑 IME 探针时 `adb devices` 掉线、脚本卡在 "waiting for device"），模式与 F4 / R2 轮一致（几何 2400 > 物理 1600）；**不代其它轮次下因果结论**；**只影响 Gap#4**，首屏 5 次数据在此之前已取完。后端 `4095` 全程**未起停**（收尾仍在 LISTEN pid=3748）。本轮**只碰过 `emulator-5910`**；开始时 `adb devices` 为空、emulator/qemu 进程数 0、5910–5918 端口全 FREE，**不存在可被波及的用户设备**。报告：`research/web/GAP_CLOSE_G2.md` |


## 四、仍未处理（等产品负责人决定）

- 我们建过的 **AVD 定义**仍留在磁盘（`OC_Phone_Test`、`OC_Phone_Test_B`、`OC_Phone_Test_C`、
  `OC_Narrow_Test`、`OC_Perf_Test`、`OC_Notify_Test`、`OC_FF_F4`、`OC_NotifyRC`、`Medium_Desktop_B`）。
  **进程已关**，但镜像数据目录仍占磁盘。
  需要的话可以用 `avdmanager delete avd -n <name>` 逐个删（**只删 `OC_*` 与 `Medium_Desktop_B`**）。
