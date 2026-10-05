# 干跑体检（-DryRun）— selfcheck_warn

> **本次没有测耗电，也没有产出任何 mAh。** 这一步只回答一句话：*这台机器能不能测*。
> 产物目录：research/perf/out_battery/dryrun_selfcheck_warn/ （不与正式测量目录混用）

| 体检项 | 结果 | 说明 |
|---|:--:|---|
| adb 可用 | ✅ | C:\sensorsdata\main\program\opencode-android-shell\research\perf\selfcheck\adb_stub.ps1 |
| 设备在线 | ✅ | R5CT30ZZZZZ（adb devices: R5CT30ZZZZZ                device product:SM_R5CT30 model:SM_R5CT30 device:ZZ1 transport_id:1） |
| App 已安装 | ✅ | com.xiufeigo.opencodeshell.web.debug |
| Activity 可解析 | ✅ | com.xiufeigo.opencodeshell.web.debug/com.xiufeigo.opencodeshell.web.WebHostActivity |
| 未插电 | ✅ | dumpsys battery plugged=0 |
| 有电池模型 | ✅ | currently on battery = true（模拟器会在此 exit 4） |
| 电量 / 电池温度 | — | level=80% / 30°C |
| rail 电流可读 | ✅ | 能积分出主指标 mAh |
| 屏幕亮度 | — | brightness=-1（0-255，-1=自动）/ mode=1（0=手动，1=自动） |
| 是否常亮 | — | keepScreenOn=yes |
| 唤醒状态 | — | Awake |

## ⚠ 不拦下、但会影响可比性的提醒

- 屏幕亮度是**自动**（brightness=-1, mode=1）⇒ 两轮之间可能自动变亮 ⇒ 不可比。先在手机设置里固定为手动，或加 -SetBrightness 120（结束会自动复原）。
- 窗口里有 FLAG_KEEP_SCREEN_ON（常亮）。两轮必须**同样**开着或同样关着，否则不可比。

> 真正开始测量（会 force-stop App、清 batterystats 统计、跑 5 分钟）时，把 -DryRun 去掉即可。
