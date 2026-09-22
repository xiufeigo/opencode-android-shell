# 最终验收记录（Lead 亲测）

> 范围：wave5（7 波）+ wave6（A1 摘要 / A8 导出）+ 此前路线 C 的全部交付。
> 基线：**94 suites / 924 tests / 0 failures / 0 errors**（含 Lead 补的 `session.summary.toggle` 快捷键行与其结构测试更新）。APK 31.1 MB。
> 证据截图：`research/wave6_shots/`（15 件）、`research/verify_wave4_shots/`（55 件）、`research/wave4_shots/`（25 件）。
> 设备：平板 `emulator-5556`（2560×1600）、折叠屏 `emulator-5554`（2076×2152；缩宽 1080×2152 验证移动形态后已 `wm size reset`）。

## 1. 本轮（Lead）新验证通过的项

| 项 | 证据 | 说明 |
|---|---|---|
| **A8 会话导出（端到端）** | `acc-export-saf2.png` + 落盘文件 | ⋮ →「导出会话」→ 系统 `documentsui` 保存对话框，文件名 **`android-e2e.json`**（= `SessionExport.filename()` 真实输出）→ SAVE → 文件 69,114 B 落在 `/sdcard/Download/`；解析确认根键**恰好** `info, messages`，**27 条消息**，消息形如 `{info:{id,sessionID,role,time,summary,agent,model}, parts:[…]}`，part 为服务端原始 `{id,sessionID,messageID,type,text}`。保真度**高于**我给的重建版——集成方走了原始 JSON 路径。 |
| **A1 摘要面板（桌面）** | `acc-summary2.png` | `Ctrl+Shift+Y` → 右上 bottom-end popover：项目（可折叠）→ 工作区 `System32`；服务器 → `http://127.0.0.1:4096`；关闭行 |
| **命令面板** | `acc-ctrl-k.png` | `Ctrl+K` 打开，候选带快捷键提示（`新建会话 Ctrl+Shift+S`、`切换终端 Ctrl+``） |
| **C4 三口径** | `acc-menu.png` | 评审面板顶部 `Git 变更 / 分支变更 / 上一轮变更`（active 高亮），真实 `/vcs/diff` 接线 |
| **C5 no-git 空态** | `acc-menu.png` | `创建 Git 仓库` + 说明（System32 非 git 仓库，符合预期） |
| **D1 设置真页** | `acc-settings.png`、`acc-settings-notify.png` | 导航已是 外观/通知/项目/工作树/扩展/实验性功能(唯一仍「未提供」)/关于；通用页新增「时间线」段（时间线详情=紧凑、显示推理摘要、展开 Shell/编辑工具部分，均带描述）；通知页为「系统通知 + 智能体/权限/错误」真开关 |
| **头部 ⋮ 菜单** | `acc-overflow.png` | 重命名 / **导出会话** / 删除（三入口在平板与折叠屏均出现） |
| **折叠屏移动形态** | `acc-fold-mobile.png` | 缩宽 443dp：四个整宽 tab（会话/变更/文件/终端）+ 内容正常；已复原 2076×2152 |
| 早前已验 | 斜杠菜单、`apply_patch` 染色 diff、A2 搜索入口、A5 导航轨、A6 变更汇总、B4 智能体 chip、发送→流式→回复、Working 行 | 见 `route_c_result.md` / `wave5_status.md` |

## 2. 仍未验证（如实登记）

| 项 | 原因 |
|---|---|
| 折叠屏移动端 `⋯ → 会话详情`（A1 的移动形态入口） | tab 栏 ⋯ 的点击目标在 dump 与截图间坐标不一致，Lead 连试三次未命中；建议用集成方的脚本化坐标复核 |
| 首启整屏连接页（E1） | 需先清空服务器配置才能看到；Lead 未擅自改用户配置 |
| E5 通知横幅、附件进度/粘贴、设置录制链路的设备行为 | 只到代码级 + 单测级 |
| `Ctrl+K` 间歇无响应 | Lead 观察到 2 次失败 / 多次成功；纯映射有单测保证、设备无 keybind 覆盖；未复现稳定 repro（疑与 adb chord 注入 + 中文 IME 有关），**不作为 bug 上报** |

## 3. 永久不可实现（server / 上游阻塞）

`POST /session/:id/revert`、`/fork`、`/share`（SPA 兜底不存在）；**归档**（上游自己 `SHOW_HOME_SESSION_ARCHIVE=false` + `archive() => Promise.reject(...)` 带 TODO，本机 v1 无 `time.archived`、v2 端点返回 HTML）；**A4 待发送队列**（读写走 v2 inbox，1.18.31 无端点）；**v2 `/api/*` 11 条路径**。

## 4. 剩余小尾巴（4 项，不阻塞）

1. 摘要图标 1:1（现用 `ic_oc_grid` 顶替上游 `window-analytics`）
2. `CompositionLocalProvider(LocalOcFontFamily)` 放开 UI/code 字体两行
3. `ChatViewModel` 公开刷新入口（D2 的 Providers 连接/断开才生效）
4. 2 个过期字符串清理

已登记未做：摘要折叠态持久化、横幅点击跳会话、系统通知/音效、pty 多端同步。

## 5. 交付物与状态

- 代码：`app/src/**`（工作树未提交；本轮新增目录 `ui/session/summary/**`、`ui/session/export/**`）
- APK：`app/build/outputs/apk/debug/app-debug.apk`（31.1 MB）
- 文档：`route_c_result.md`、`wave5_status.md`、`wave5_specs` 系列 7 份、`wave6_{summary,export,specs}.md`、`verify_wave4.md`、本文件
- 证据：`wave6_shots/`、`verify_wave4_shots/`、`wave4_shots/`；仓库根 0 张图片
- **git 未提交**（拆分建议：数据层 / 会话界面 / 移动形态 / 外壳接线 / wave5 四波 / A1 摘要 / A8 导出 / 验收文档）
