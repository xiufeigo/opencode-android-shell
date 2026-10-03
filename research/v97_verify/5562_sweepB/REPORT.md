# v97 真机扫测报告 — 会话域广度优先（emulator-5562 = Small_Desktop）

- 测试员：session-7fd8879d（sweepB）
- 日期：2026-10-02
- 设备：`emulator-5562`，`adb -s emulator-5562 emu avd name` → **Small_Desktop**（1366x768 @160dpi，`wm size`/`wm density` 实测一致）
- App：com.xiufeigo.opencodeshell 1.4.0（新 APK，Lead 已装）；启动 `am start -n com.xiufeigo.opencodeshell/.OcActivity`
- 后端：`http://127.0.0.1:4095`，Basic `opencode:opencode`
  - `GET /api/info` → 200 `{"version":"2.0.18","pid":260,...}`（证据：本文件读数，命令见上）
  - `GET /api/provider` → 200，len=2426，`location.directory=/mnt/c/sensorsdata/main/program/opencode-android-shell`，
    provider 列表含 `minimax-cn-coding-plan`(enabled)、`zai-coding-plan`、`xiaomi`、`opencode-go`、`stepfun`、`opencode`… **网关无 403、有可用 provider → 环境不阻塞**
- 会话纪律：只操作标题带 `E2ECHK5562` 前缀的会话；截图 `screencap -p`+`pull`，存本目录。

## 证据索引（命令 + 文件 + 读数）

（逐条在下方各节内联给出）

## 结果总表

| # | 项 | 结论 |
|---|---|---|
| 1 | 建会话+流式收流 | 进行中 |
| 2 | Composer | 进行中 |
| 3 | 时间线 | 待测 |
| 4 | 会话动作 | 待测 |
| 5 | 终端 | 待测 |
| 6 | worktree | 待测 |
| 7 | 通知 | 待测 |
| 8 | 新发现 | 累积中 |

## 新发现（只记录，不改代码）
