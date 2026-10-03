# v68 B1-B5 面板集群真机回归（emulator-5558 / Pixel_9a / v1.4.0）

会话：`E2ECHK5558-A`（项目 v35projB）、`E2ECHK5558-B`（项目 本地）— 测完均已删除。
模型：`opencode-go/space-bunny-free`（未 403，变体测试后已改回「默认」）。
截图：`research/v68_verify/5558/`（00~61）

| 项 | 结论 |
|---|---|
| 1. B1 `/` 不抢焦点 + 10 字符不丢 | PASS |
| 1. B1 `@` 实时过滤 | **FAIL** |
| 2. B1 极端态（面板开+连点 composer 3 次） | PASS |
| 3. B2 跨 tab 不泄漏 | PASS |
| 4. B4 空文本收起 | PASS |
| 5. B5 点击命中（/btw、@explore、/model） | PASS |
| 6. B3 会话隔离 | **FAIL** |
| 7. 清理 | PASS |

## 附带发现（未在任务单内）
- C1 tab 标签与 header 标题在切 tab 后不刷新（显示「会话」/「新建会话」），再次操作后恢复。
- C2 `/btw` 一次性输入框不弹命令/提及面板；且该卡片存在期间主 composer 无法获得焦点。
- C3 会话重命名第一次 Enter 未提交，第二次成功。
- C4 每新建一个会话都会插入一条「模型已切换」系统消息。
- `/model` 打开后约 1~3s 先闪「没有匹配的结果」再出列表。
- IME ✓ 已知残留 bug 本轮未触发。
