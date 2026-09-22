# Wave 5 状态与 Lead 验收记录

> 说明：权威验证报告由独立验证者出（`research/verify_wave4.md` 的后续章节 + 四份 `wave5_*.md` 工作流报告）。本文只记 **Lead 侧独立核对过的事实**与**未决项**，不重复其结论。

## 1. 规模与验收门（Lead 独立聚合磁盘产物）

| 项 | 结果 |
|---|---|
| 单测 | **86 suites / 827 tests / 0 failures / 0 errors / 0 skipped**（wave 5 起点 72/637 → +14 套件 / +190 测试） |
| `clean :app:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL（验证者跑；Lead 复核过结果文件时间与计数） |
| 文档 | `wave5_timeline.md`、`wave5_review.md`、`wave5_composer.md`、`wave5_connect.md` |

四波内容：时间线（会话内搜索 / 分隔条 / 导航轨 / 本轮变更汇总）、评审（面板可拖+复位、filter、行号+变更条、词级 diff、树徽标）、输入区+数据层（`/agent`、`/command`、`/skill`、分页、`+` 四项菜单、Shell 模式、agent/variant、草稿、全角归一化）、连接页+健康检查。

## 2. Lead 在设备上独立复现的项（平板/折叠屏，最新 APK）

| 项 | 证据 | 结论 |
|---|---|---|
| **B1 斜杠命令** | `wave5_shots/v6-slash-tablet.png` | ✅ 输入 `/` 弹出候选：内置 `/model`、`/agent` + 服务端 `/init`、`/review`、`/gsd-*`。根因是中文 IME 把 `/` 送成全角 `／`（`HalfWidthText` 已修） |
| **apply_patch 卡展开 + 染色 diff** | `wave5_shots/v6-patchbody.png` | ✅ 展开后为 `***`/`@@` 结构行 + `-goodbye` 红底 + `+farewell` 绿底；此前"点不开"是分组处于折叠态所致，**不是命中区 bug** |
| 本轮变更汇总（A6） | 同上两张截图 | ✅ `1 个文件已更改` + 完整路径 |
| 消息导航轨（A5） | `v6-slash-tablet.png` 右侧刻度 | ✅ |
| agent chip（B4） | `v6-slash-tablet.png` composer `智能体` | ✅ |
| 发送→流式→回复（回归） | 早前 `final-*` 系列 | ✅ 无回归 |

## 3. 未决项 / 新发现

1. **✅ 斜杠候选长描述行缺 label —— 已由图队修复波次按上游语义解决**（我的临时修法已让位）：
   - 根因：Compose `Row` 先测量无权重子项，超长描述（`customize-opencode`、`jira-SCB-Collector`）吃满整行，把 `weight(1f)` 的 label 挤成 0 dp。
   - 我 10:12 先落了 `weight(1f, fill = false)`；修复波次 10:13–10:14 按**上游语义**重做（新建 `SuggestionRowText.kt`：label 无权重自然宽度、描述槽 `Box(weight(1f))`），并经核实**无半覆盖残留**。
   - 上游规格（Lead 逐行核对）：`_upstream/packages/app/src/composer/editor/editor.tsx:805-810` —— label `class="shrink-0"`、描述 `class="min-w-0 truncate"`；同文件 `812-814` 还有一个 `keybind` 尾槽（我们的 `Item` 无该字段，属可选 parity 项）。
   - 设备复验：待图队给截图（我此前那版已验证 label 出现，`v6-slash-fixed.png`）。
2. 连接页（E1）**需要清空服务器配置**才可见，设备级未验。
3. 其余 wave 5 新功能（B2 `+` 四项菜单、B3 Shell、B5/B6 附件、C1–C3/C5、D 设置页、E2/E3 事件、E5 通知、A1 摘要、A4 队列、A8 导出归档）**只到代码级/单测级**，设备级未跑完。
4. `fork` / `revert` / `share`：server 不支持（`/session/:id/revert`、`/fork`、`/share` 返回 SPA 兜底 HTML）→ 不可实现，非客户端缺口。
5. **构建状态提示**：图队波次在途时构建会在 `processDebugResources` / `compileDebugKotlin`（如 `ui/settings/SettingsServerBridge.kt`）短暂变红，属正常中间态；Lead 最后一次绿基线 = **86 suites / 827 tests / 0 failures**。

## 4. 环境注意（会影响复现）

- **模拟器串号在重启后互换**：`emulator-5554` = 折叠屏（2076×2152），`emulator-5556` = 平板（2560×1600）。跑设备脚本前先 `adb devices` + `wm size` 确认。
- 每次 `install -r` 后必须重加 `adb reverse tcp:4096 tcp:4096`，否则会话列表/面板为空。
- 中文 IME 下 `adb shell input text` 与 `input keyevent` 都可能产出全角字符；涉及 `/`、`@`、`!` 的测试建议先切英文布局（`HalfWidthText` 现已兜底，但输入法仍可能影响 `:`、数字等）。
