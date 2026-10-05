# 干跑体检（-DryRun）— selfcheck_short

> **本次没有测耗电，也没有产出任何 mAh。** 这一步只回答一句话：*这台机器能不能测*。
> 产物目录：research/perf/out_battery/dryrun_selfcheck_short/ （不与正式测量目录混用）

| 体检项 | 结果 | 说明 |
|---|:--:|---|
| adb 可用 | ✅ | C:\sensorsdata\main\program\opencode-android-shell\research\perf\selfcheck\adb_stub.ps1 |
| 设备在线 | ✅ | R5CT30ZZZZZ（adb devices: R5CT30ZZZZZ	device） |
| App 已安装 | ✅ | com.xiufeigo.opencodeshell.web.debug |
| Activity 可解析 | ✅ | com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity |
| 未插电 | ✅ | dumpsys battery plugged=0 |
| 有电池模型 | ✅ | currently on battery = true（模拟器会在此 exit 4） |
| 电量 / 电池温度 | — | level=80% / 30°C |
| rail 电流可读 | ✅ | 能积分出主指标 mAh |
| 屏幕亮度 | — | brightness=120（0-255，-1=自动）/ mode=0（0=手动，1=自动） |
| 是否常亮 | — | keepScreenOn=no |
| 唤醒状态 | — | Awake |

## 可比性前置：没有发现问题。

> 真正开始测量（会 force-stop App、清 batterystats 统计、跑 5 分钟）时，把 -DryRun 去掉即可。
