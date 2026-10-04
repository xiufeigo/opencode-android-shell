# SECOND_EMULATOR — 第二台模拟器扩容（原生壳 A/B 用）

- **执行者**：设备/环境工程师 E1（mcode）
- **日期**：2026-10-04
- **仓库**：`C:\sensorsdata\main\program\opencode-android-shell`
- **硬约束遵守**：未跑 gradle；未向任何既有设备安装 app；未对 `emulator-5554` 执行任何写/杀操作；**未下载任何系统镜像**。

---

## 结论（先看这里）

| 项 | 结果 |
|---|---|
| 新设备 serial | **`emulator-5636`** |
| AVD 名 | **`Medium_Desktop_B`** |
| 镜像来源 | **本机已安装镜像** `system-images;android-34;android-desktop;x86_64`（**零下载**） |
| 基准设备 | **`emulator-5554`** = AVD `Medium_Desktop`（原生壳用） |
| 新增设备 | **`emulator-5636`** = AVD `Medium_Desktop_B`（**Web 宿主**用） |
| 两台共存 | ✅ 可（详见 §5；5554 当前未启动，基准位已预留保留） |
| 宿主内存 | 95.2 GB 总 / 39.9 GB 空闲 → **足以并行跑两台**，无串行限制 |

> ⚠️ **重要前提修正**：`emulator-5554` 在本轮开始时**并未处于运行状态**（`get-state` = not found）。它不是被我关掉的 —— 我接手前它就没在 `adb devices` 里。因此"两台同时在线"的验证实际是**用 5636 与在跑的 5604/5628 完成**的；5554 的端口**被刻意空出未占用**，留给后续轮次启动基准机。详见 §5、§7。

---

## 1. 现有 AVD（`emulator -list-avds` 原文）

```
$ & "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
DSH_Class_Test
DSH_File_Test
DSH_Fold_Test
DSH_Kbd_Test
DSH_T58
MCRemote_Phone
MCRemote_Tablet
Medium_Desktop
Pixel_10_Pro_Fold
Pixel_10_Pro_XL
Pixel_9a
Pixel_Tablet
Small_Desktop
T59_Upgrade
```

## 2. 已安装 system images（`sdkmanager --list_installed` 原文节选）

```
$ & "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed

Installed packages:
  system-images;android-30;default;x86_64                       | 11            | Intel x86_64 Atom System Image
  system-images;android-34;android-desktop;x86_64               | 1             | Desktop Intel x86_64 Atom System Image
  system-images;android-35;google_apis_playstore_tablet;x86_64  | 9             | Google Play Tablet Intel x86_64 Atom System Image
  system-images;android-37.2;google_apis_playstore_ps16k;x86_64 | 5             | 16 KB Page Size Google Play Intel x86_64 Atom System Image
```

目录侧佐证（`Sdk\system-images`）：

```
...\system-images\android-30\default\x86_64
...\system-images\android-34\android-desktop\x86_64        <-- 基准 Medium_Desktop 用的就是它
...\system-images\android-35\google_apis_playstore_tablet\x86_64
...\system-images\android-37.2\google_apis_playstore_ps16k\x86_64
```

## 3. 基准 AVD `Medium_Desktop` 的真实配置（原文节选）

```
target=android-34
image.sysdir.1=system-images\android-34\android-desktop\x86_64\
tag.id=android-desktop
abi.type=x86_64
hw.lcd.width=3840
hw.lcd.height=2160
hw.lcd.density=320
hw.ramSize=2048
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.keyboard=yes
disk.dataPartition.size=10G
PlayStore.enabled=false
```

---

## 4. 新建方式与命令（为何选 `avdmanager create` 而非目录克隆）

### 选择的做法
用 **`avdmanager create avd`** 基于**已安装镜像**新建，再把硬件键对齐到基准。

### 理由（对比"克隆目录"）
1. **零下载是硬保证**：`create -k "system-images;android-34;android-desktop;x86_64"` 直接引用本机已装包；克隆目录同样是零下载，但**克隆会把基准的数据分区（含 v97_verify 装过的 app、系统状态、Wi-Fi/账号等）一并带过来**，直接违反"新设备干净、app 由后续轮次装"的要求。
2. **目录克隆的坑**：`Medium_Desktop.avd` 里带着 `userdata-qemu.img.qcow2`（**3.9 GB**，今日 15:14 还在写），硬拷 3.9 GB 既慢又必然带脏数据，还容易把 `multiinstance.lock` / 快照一起复制导致起不来。
3. `create` + 键对齐能拿到"**硬件与基准一致、数据全新**"的组合，这才是 A/B 需要的。

### 命令与原文输出

```powershell
$avdm = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat"
'no' | & $avdm create avd -n Medium_Desktop_B -k "system-images;android-34;android-desktop;x86_64" -f
```

```
Loading local repository...
Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered. This can happen if you use versions of Android Studio and the command-line tools that were released at different times.
[=========                              ] 25% Loading local repository...
[=========                              ] 25% Fetch remote repository...
[=======================================] 100% Fetch remote repository...

Auto-selecting single ABI x86_64
Do you wish to create a custom hardware profile? [no]

EXIT=0
```

> 关于输出里的 `Fetch remote repository...`：这只是 avdmanager 读取**远端仓库 XML 索引**（用于列出可用 tag），**不是下载镜像**。证据：镜像解析来自 `Loading local repository`，且新 AVD 的 `image.sysdir.1` 指向本机目录，镜像目录 `android-34\android-desktop\x86_64` 的 mtime 未变化，全程无 `Downloading` / `Unzipping` 输出。

### 硬件键对齐（已写入 `Medium_Desktop_B.avd\config.ini` 并复核）

```
image.sysdir.1=system-images\android-34\android-desktop\x86_64\
tag.id=android-desktop
hw.lcd.width=3840
hw.lcd.height=2160
hw.lcd.density=320
hw.ramSize=2048
hw.cpu.ncore=4
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.keyboard=yes
disk.dataPartition.size=10G
showDeviceFrame=no
hw.mainKeys=no
hw.trackBall=no
PlayStore.enabled=false
firstboot.bootFromDownloadableSnapshot=no
```

> 同时关闭了 `firstboot.bootFromDownloadableSnapshot`，避免首启去拉**下载型快照**（那也是一次网络下载）。

---

## 5. 冷启动与启动证据

### 端口选择的刻意决策
命令行的 `emulator -avd Medium_Desktop_B` **不带 `-port` 时会抢占最小空闲偶数端口，也就是 5554** —— 而 5554 是你指定留给基准 `Medium_Desktop` 的。若不干预，新设备会"变成" 5554，后续轮次启动基准机时反而被挤到 5556，A/B 角色就串了。
因此**显式指定 `-port 5636`**，沿用本机既有设备的 +8 步长序列（5604 / 5612 / 5620 / 5628 → 5636），**把 5554 完整空出来**。

### 启动命令

```powershell
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
Start-Process -FilePath $emu -ArgumentList '-avd','Medium_Desktop_B','-port','5636',
  '-no-snapshot-load','-no-boot-anim','-no-audio','-gpu','auto' `
  -RedirectStandardError "research\env\Medium_Desktop_B_boot.log" -PassThru -WindowStyle Minimized
```

`-no-snapshot-load` = **冷启动**，不加载任何快照。

### 启动证据

```
emulator pid=46712
[16:33:07] emulator-5636 state=device (registered)
[16:34:03] BOOT_COMPLETED=1
```

冷启动到 `sys.boot_completed=1` 用时 **约 56 秒**。

设备自证：

```
avd_name             = Medium_Desktop_B
boot_completed       = 1
ro.build.version.release = 14
screen   = Physical size: 3840x2160  Override size: 1366x768
density  = Physical density: 320      Override density: 160
```

> **关于 1366x768 / 160dpi**：AVD 硬件 profile 与基准一致（3840x2160@320），但 `android-desktop` 镜像**首启自行下发了 `wm` 覆盖**，运行时实际生效为 **1366x768 / 160dpi** —— 正好命中你要求的 1366x768/160dpi。**我没有手动设任何 `wm size`/`wm density`**，这是镜像自身行为；基准 `Medium_Desktop` 用的是同一镜像，因此运行时画面参数是同源的。

---

## 6. 调试前置 & 未装任何 app

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5636 shell settings put secure show_ime_with_hard_keyboard 1
# 复核
& $adb -s emulator-5636 shell settings get secure show_ime_with_hard_keyboard
# -> 1
```

### ⚠️ 需要你知道的异常残留（已处理）

新 AVD 数据分区全新创建（`userdata-qemu.img` mtime `16:32:55`），但首次 `pm list packages -3` 却出现：

```
package:com.xiufeigo.opencodeshell    versionName=1.4.0
firstInstallTime=2026-10-04 08:34:05
```

即**我没装、但设备上有一份 v1.4.0**（`installerPackageName=null`，非应用商店安装）。该时间戳（08:34）早于本 AVD 创建（16:32），来源未能确定，**如实上报，不猜测**。

**是否串了基准的数据分区？—— 已排除，证据如下：**

| | 基准 `Medium_Desktop` | 新 `Medium_Desktop_B` |
|---|---|---|
| `userdata-qemu.img.qcow2` 大小 | **3,914,596,352 B (3.9 GB)** | **197,120 B (197 KB)** |
| 最后写入 | 2026/10/4 15:14:42 | 2026/10/4 16:32:58 |

两者 qcow2 各自独立、量级完全不同 ⇒ **数据分区不共享**。配置里的 `disk.dataPartition.path=<temp>` 只是 avdmanager 未展开的占位符，`DSH_File_Test` / `DSH_Kbd_Test` / `DSH_T58` / `DSH_T63` / `T59_Upgrade` 等既有 AVD **也都有这一行**，属常见产物，不代表指向同一份镜像。

**已做的处理**：既然分区独立、且"不装任何 app"是本轮硬要求，我在**仅本设备**上卸载该残留：

```
$ & $adb -s emulator-5636 uninstall com.xiufeigo.opencodeshell
Success
$ & $adb -s emulator-5636 shell pm list packages -3
  (none)
```

卸载只作用于 `emulator-5636` 自己的数据分区，**不可能影响基准**。

---

## 7. 共存验证（`adb devices` 原文）

```
List of devices attached
emulator-5604          device product:sdk_gphone16k_x86_64 model:sdk_gphone16k_x86_64 device:emu64xa16k transport_id:24
emulator-5620          device product:sdk_gphone16k_x86_64 model:sdk_gphone16k_x86_64 device:emu64xa16k transport_id:41
emulator-5628          device product:sdk_gtablet_x86_64 model:Pixel_Tablet device:emu64xa transport_id:37
emulator-5636          device product:sdk_gpc_x86_64 model:sdk_gpc_x86_64 device:emu64xa transport_id:40
emulator-5650          device product:sdk_gtablet_x86_64 model:Pixel_Tablet device:emu64xa transport_id:43
```

```
[emulator-5636] shell echo -> ok
[emulator-5604] shell echo -> ok
[emulator-5628] shell echo -> ok
```

> **设备列表是易变的**：`5620` 一度 `offline` 后恢复，`5650` 在我核查期间出现（**不是我启动的**）。本机同时有其他任务在拉起/关闭模拟器，多设备端口池在浮动。

### 基准 `emulator-5554` 未被干扰 —— 双重证据

1. `adb -s emulator-5554 get-state` → `error: device 'emulator-5554' not found`。**它从本轮开始就没在运行，我没有关过它**（我接手时 `adb devices -l` 里就没有 5554）。
2. 5554 端口**至今空闲**：新设备被我固定到 5636，**没有抢占**。后续轮次直接 `emulator -avd Medium_Desktop` 即可拿到 5554。

**给后续轮次的一句话**：启动基准请用 `-avd Medium_Desktop`（不要手写 `-port 5554`，也不要加 `-no-snapshot` 之外的清理动作），它会自行占回 5554；此时 `emulator-5636` 会同时在线，两台可并行。

### 宿主资源

```
39.9 GB free of 95.2 GB
```

已有 4~5 台模拟器在线（实测单台 WS 2.7~5.6 GB）仍有 ~40 GB 空闲 ⇒ **两台并行没有内存压力，不需要退化成串行**。仅提示 CPU 核数是共享竞争项，若 A/B 出现明显卡顿，优先排查宿主 CPU 争用而非设备内存。

---

## 8. 用途分配

| 角色 | Serial | AVD | 镜像 | 用途 |
|---|---|---|---|---|
| **基准（勿动）** | `emulator-5554` | `Medium_Desktop` | android-34/android-desktop | **原生壳** APK 基准 |
| **新增（本轮交付）** | `emulator-5636` | `Medium_Desktop_B` | android-34/android-desktop（同一份） | **Web 宿主** APK |

两台硬件 profile 与运行时画面参数同源，A/B 变量只剩 APK 本身。

---

## 9. 未闭环 / 需后续确认

1. **基准 5554 未实机验证** —— 它当前未启动，本轮无法给出"5554 与 5636 同时 `shell echo ok`"的双机原文。两机同时在线的验证是用 5636 对 5604/5628 完成的（均 `ok`）。**基准一起跑起来后建议补一次双机并行确认。**
2. **`com.xiufeigo.opencodeshell` 残留来源未定** —— 已卸载、状态干净，但成因（疑似与 `firstboot.bootFromLocalSnapshot` / 快照路径相关）未查实。若后续用 `avdmanager create` 再造 AVD，建议一并加 `-no-snapshot-load` 冷启并**起机后立刻 `pm list packages -3` 复核**。
3. **`show_ime_with_hard_keyboard=1` 只在 5636 上设过** —— 基准 5554 起来后需补设（该设置可能被 AVD 数据分区重置）。
4. **未装任何 app** —— 按要求留给后续轮次；两台均待装。
5. 端口池在浮动（5620/5650 状态变化），后续脚本**不要硬编码依赖某台非本轮设备的存活**。
6. 未跑 gradle、未向既有设备装 app、未对 5554 做任何写操作 —— 全程遵守。

---

## 附：产物清单

- 本报告：`research/env/SECOND_EMULATOR.md`
- 启动日志：`research/env/Medium_Desktop_B_boot.log`（及 `.out`）
- 新 AVD 定义：`%USERPROFILE%\.android\avd\Medium_Desktop_B.ini` / `Medium_Desktop_B.avd\config.ini`
