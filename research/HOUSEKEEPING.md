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

## 四、仍未处理（等产品负责人决定）

- 我们建过的 **AVD 定义**仍留在磁盘（`OC_Phone_Test`、`OC_Phone_Test_B`、`OC_Phone_Test_C`、
  `OC_Narrow_Test`、`Medium_Desktop_B`）。**进程已关**，但镜像数据目录仍占磁盘。
  需要的话可以用 `avdmanager delete avd -n <name>` 逐个删（**只删 `OC_*` 与 `Medium_Desktop_B`**）。
