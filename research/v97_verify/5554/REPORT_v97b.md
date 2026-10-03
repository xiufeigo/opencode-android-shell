# V97b 真机终验报告 — emulator-5554 (Pixel_9a) — 任务 v97-F14

**测试员** v97 真机终验测试员（SSH 域） · **日期** 2026-10-02（本机时 18:03 起）
**仓库根** `C:\sensorsdata\main\program\opencode-android-shell`
**证据目录** `research/v97_verify/5554/`
**状态** 进行中（边跑边写）

---

## 0. 环境读数（`env_v97b_env.txt`）

| 项 | 命令 | 实测读数 | 判定 |
|---|---|---|---|
| 设备身份 | `adb -s emulator-5554 emu avd name` | `Pixel_9a` | ✅ 复核通过（serial↔AVD 未再重排） |
| 设备序列 | `adb devices -l` | `emulator-5554 device product:sdk_gphone16k_x86_64` | ✅ |
| **APK 复核** | `dumpsys package com.xiufeigo.opencodeshell \| Select-String versionName,lastUpdateTime` | `versionName=1.4.0`、`versionCode=26`、`lastUpdateTime=2026-10-02 10:01:32`（设备时）、`firstInstallTime=2026-09-27 04:29:58`、`appId=10230` | ✅ **= Lead 18:01 出的新 F14 构建**（含 bcprov + ed25519 路由修复） |
| 屏幕 | `wm size; wm density` | `Physical size: 1080x2424`、`Physical density: 420` | ✅ Pixel_9a 规格 |
| 隧道 4095 | `adb -s emulator-5554 reverse --list` | `host-15 tcp:4095 tcp:4095` | ✅ 已挂 |
| 隧道 2222 | 同上 | `host-15 tcp:2222 tcp:22` | ✅ 已挂 |
| 硬键盘 IME | `settings get secure show_ime_with_hard_keyboard` | `1` | ✅ |
| 后端 | `GET http://127.0.0.1:4095/api/info`（Basic `opencode:opencode`） | `HTTP 200 {"version":"2.0.18","pid":260,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}` | ✅ 只做只读健康检查 |
| sshd | `wsl -d Arch -e bash -lc "sudo systemctl is-active sshd; ss -tln \| grep ':22 '"` | `active`；`0.0.0.0:22` + `[::]:22` LISTEN | ✅ |

### 0.1 `prep_oldapk.ps1` 冒烟（`log_00_prep.txt`）

| 步骤 | 读数 |
|---|---|
| launch | `mCurrentFocus=Window{988230b u0 com.xiufeigo.opencodeshell/…OcActivity}` |
| Home 顶栏 | `Home [21,127][147,253]` / `New session [153,127][243,253]` / `Command palette [243,127][369,253]` / `Projects [53,275][927,280]` |
| Settings→Servers | `Servers [148,924][507,971]`、`Add SSH server [544,927][801,968]`、默认行 `10.0.2.2:4095 [254,1115][474,1156]` + `Server healthy` + `Server actions [780,1093][906,1219]`；**面板内无任何 E2E 测试条目 = 干净起点** |
| Add 表单 | `Host or SSH command [106,1518]`、字段 `[138,1576][943,1702]`、`Server name (optional)`、`Cancel [554,1779][665,1820]`、`Add server [760,1779][938,1820]` |
| 收表单 | 用表单自身 `Cancel @ 610,1800`（**未按 BACK/ESC**） |
| 结论 | ✅ 冒烟通过，新 APK 可启动、可导航、表单可 dump |

---

## 1. 判定汇总表（滚动更新）

| # | 场景 | 判定 | 证据 |
|---|---|---|---|
| F14a | 不可读私钥 → 专属文案 | *待跑* | — |
| F14b+F12a | 私钥认证 + 空 passphrase → publickey | *待跑* | — |
| F13b | 错密码条目 → kbdint 卡片 | *待跑* | — |
| F12b | Cancel 后不重弹 / 二次必重弹 | *待跑* | — |
| S1 | Ctrl+K → palette | *待跑* | — |
| S2 | Ctrl+Shift+P → palette | *待跑* | — |
| S3 | 设置弹层 IME band | *待跑* | — |
| S4 | 表单 footer 键盘态可达 | *待跑* | — |
| S5 | 纯 SSE 断线卡自愈 | *待跑* | — |

---

## 2. journalctl 关键行原文

*（随场景追加）*

---

## 3. 未闭环 / 未验到清单

*（随进度追加）*
