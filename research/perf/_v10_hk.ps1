$ErrorActionPreference='Stop'
$p = "C:\sensorsdata\main\program\opencode-android-shell\research\HOUSEKEEPING.md"
$gbk = [System.Text.Encoding]::GetEncoding(936)

$entry = @"

## V10 收尾（2026-10-05 08:0x–08:1x）—— 360dp 骨架屏调查

| 时间 | 动作 | 细节 | 报告 |
|---|---|---|---|
| 2026-10-05 08:0x | **关闭** ``OC_Deliver_R2``（``emulator-6210``，端口 **6210**）—— **V10 360dp vs 412dp 对照调查** | 镜像 ``android-35;google_apis_playstore_tablet`` + ``pixel_tablet``，**未用** ``-skin pixel_tablet``（非合法 skin 名）。几何**全部**用 ``wm size/density``：360dp=``720x1280@320``、412dp=``824x1640@320``，起前实测 6210–6224 全 FREE。关闭前逐条核对 ``avd=OC_Deliver_R2 port=6210 MINE=True``；``wm size reset`` + ``wm density reset`` 后读回 **``Physical size: 2560x1600`` / ``Physical density: 320``**（**无 Override**）。``adb -s emulator-6210 emu kill`` → ``OK: killing emulator, bye bye`` / ``OK``。复核：**emulator/qemu 进程 = 0**、**``OC_*``/``Medium_Desktop_B`` 计数 = 0**、端口 **6210/6212/6214 全 FREE**、``adb devices`` 空、``.agent-gradle-lock exists = False``。``4095`` **全局未停**（收尾时 ``LISTEN pid=3748``，与开工时同 pid，我只做过 ``adb reverse tcp:4095 tcp:4095``，收尾 ``reverse --remove``）。**未执行** ``adb kill-server``、**未用** ``-a``、**未用**通配符 kill；用户的 ``MCRemote_*``/``DSH_*``/``T7x_*`` 与来源不明实例**一个都没碰**。**只碰过 1 个 serial：``emulator-6210``**。⚠️ **故意保留**：``research/perf/_v10_*.ps1``（本轮 CDP 采集器，可复用于后续视口对比，重建成本高于收益）；``OC_Deliver_R2`` AVD 定义**未删**（长期资产，R2/G2/P6/F4/V6/V7/F5 等轮次共用）。**结论：360dp 不是缺陷** —— 6/6 冷启均离开 Loading（360dp 2388/2529/2581 ms vs 412dp 2343/2405/2464 ms，差 95 ms < 轮内抖动 191 ms）；V9 那张骨架屏是**口令错导致 401 + 切完立刻截图**的测量缺陷（V9 六个 cell 全部 ``proxyGET=0``，1366dp 平板同样 99.7% 白） | `research/perf/PHONE360_INVESTIGATION.md` |
"@

# append as GBK bytes => existing content stays byte-identical
$bytes = $gbk.GetBytes($entry)
$fs = [System.IO.File]::Open($p, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write)
$fs.Write($bytes, 0, $bytes.Length)
$fs.Close()
"appended_bytes=$($bytes.Length)  new_size=$((Get-Item $p).Length)"
"--- tail readback (GBK) ---"
$all = $gbk.GetString([System.IO.File]::ReadAllBytes($p))
$all.Substring($all.Length-400)
