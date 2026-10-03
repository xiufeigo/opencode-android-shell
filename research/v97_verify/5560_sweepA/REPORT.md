# v97 真机扫测报告 — emulator-5560 (Pixel_Tablet, 2560x1600 @320dpi)

> 任务：Home / 导航与标签 / 命令面板 / 设置 域「广度优先」功能扫测（广度优先，不改代码）。
> 测试员：v97 sweepA。设备核验：`adb -s emulator-5560 emu avd name` = **Pixel_Tablet**（2026-10-02）。
> APK：`versionName=1.4.0`（已装）。后端 `GET /api/info` → 200 `{"version":"2.0.18","pid":260}`。
> reverse：`tcp:4095 tcp:4095`、`tcp:2222 tcp:22` 均在。`show_ime_with_hard_keyboard=1`。
> 证据目录：本目录（`research/v97_verify/5560_sweepA/`）。

## 环境读数（基线）

| 项 | 值 |
|---|---|
| `wm size` | Physical size: 2560x1600（未改） |
| `wm density` | 320 |
| `ro.build.version.release` | 15 |
| 命令 | `adb -s emulator-5560 shell wm size` / `wm density` |

## 0. 启动基线

![boot](00_boot.png)

- `am start -n com.xiufeigo.opencodeshell/.OcActivity` → `mCurrentFocus=...OcActivity`。
- Home 渲染：左=项目列表（mighty-river / v27projC / sensorsdata / .copilot / E2ECHK_renamed /
  opencode-android-… / E2ECHK_xiufeigo / `/`），右=会话列表（搜索框「搜索会话」+「更早」分组）。
- 布局读数（截图 2560x1600）：抽屉列 x≈168..508；项目列 x≈272..900；会话列 x≈900..2640；
  设置/帮助 位于抽屉底部 y≈1349/1413。

---

## 逐条判定表

> 判定：**PASS** / **FAIL** / **未验到**。每条给「命令 + 证据文件 + 读数」。

### 1. Home 项目

| # | 断言 | 判定 | 命令 | 证据 | 读数 |
|---|---|---|---|---|---|
| 1.1 | 项目列表渲染 | PASS | `am start .OcActivity` | [00_boot.png](00_boot.png) | 8 行渲染：mighty-river / v27projC / sensorsdata / .copilot / E2ECHK_renamed / opencode-android-… / E2ECHK_xiufeigo / `/`；每行 ⋮ + 铅笔 + × |
| 1.2 | 新建项目 → 目录选择器打开 | PASS | tap (756,399) 项目头 ⧉+ 图标 | [01_newproj_click.png](01_newproj_click.png) | 弹「选择项目目录」；路径框 `/home/xiufeigo/.local/share/opencode/worktree/7…`；面包屑 `~`/`根目录`/`上级`；底栏 `取消`/`选择此目录`（置灰） |
| 1.3 | 选择器 `~` 面包屑可达（非死区） | PASS | tap (926,282) | [02_picker_tilde.png](02_picker_tilde.png) | 路径框变 `/home/xiufeigo`；列表渲染 `.agents` `.android` `.antigravity-ide-server` `.cache` …；`选择此目录` 由灰转**蓝（可用）**；底部 cwd 标签同步 `/home/xiufeigo` |
| 1.4 | 选择器目录浏览（进目录） | PASS | tap (1060,535) → `.cache` | [03_picker_into_dir.png](03_picker_into_dir.png) | 路径框 `/home/xiufeigo/.cache`；列表全部刷新为 antigravity/chrome-devtools-mcp/claude/crontab/…；(.**` 隐藏目录可见**) 底部 cwd 标签同步 |
| 1.5 | 两段确认「选择此目录」 | PASS | tap (1592,1456) 再 tap (1565,1456) | [04_picker_confirm1.png](04_picker_confirm1.png) [05_newproj_done.png](05_newproj_done.png) | 第 1 段：列表区替换为确认视图（居中显示 `/home/xiufeigo/.cache`），按钮文案 `选择此目录`→**`确认选择此目录`**；第 2 段：弹层关、项目行 **`.cache`** 新增并自动选中，会话区切到「这里还没有内容 / 创建一个会话以开始 + 新建会话」空态 |
| 1.6 | 项目行 ⋮ 菜单各项 | PASS（枚举） | tap (596,465) | [08_proj_menu.png](08_proj_menu.png) | 6 项：`新建会话` / `导入会话` / `编辑项目` / `刷新工作区` / `清除通知` / `关闭`（含分隔线） |
| 1.6a | 项目行铅笔图标语义 | PASS（澄清） | tap (684,465) | [06_proj_rename_editor.png](06_proj_rename_editor.png) | 铅笔 = **「在该项目新建会话」**（进入新会话页，左上 `<` 返回），**不是**重命名入口。重命名在 ⋮→`编辑项目`。 |
| 1.7 | 编辑项目面板渲染 | PASS | ⋮→`编辑项目` tap(327,809) | [09_proj_edit.png](09_proj_edit.png) | 面板含：标题`编辑项目`+路径、`项目名称`框、`图标`+`选择图片`+`建议：128x128px`、`颜色`6 色（无/orange/pink/cyan/green/gray）、`工作区启动脚本`(占位 `例如 bun install`)+说明`创建新工作区 (worktree) 后运行一次。`、脚注`该项目未在服务器注册`、`取消`/`保存` |
| 1.8 | **项目改名（列表/标题同帧更新）** | **FAIL** | tap 名称框 (1280,533) → `input text` / `keyevent` ×4 次 | [09_proj_edit.png](09_proj_edit.png) [10d_editdlg.xml](10d_editdlg.xml) [14a_field_tap_correct.png](14a_field_tap_correct.png) | **面板内两个 EditText 均 `enabled="false" focusable="false"`**（XML 实测：名称框 `[120,485][2440,581]` text=".cache" en=false；脚本框 `[120,930][2440,1026]` en=false）。4 次独立输入（`input text` ×2、`CTRL+A`+`input text`、`keyevent 54`）全部**零字符落地**，字段恒为 `.cache`；`dumpsys input_method` 恒 `mInputShown=false mServedView=null`。同帧 `保存` 按钮亦 `enabled=false`。**无法改名 → 改名同帧更新断言不可达** |
| 1.9 | 输入管线对照（证明 1.8 非环境噪声） | PASS（对照） | tap 搜索框 (1600,293) → `input text zzqx` | [14c_search_typed.png](14c_search_typed.png) [14c_search_typed.xml](14c_search_typed.xml) | Home 搜索框 XML **`enabled="true" focusable="true" focused="true"` text="z z q x"**，IME 弹出。→ 同一设备/同一条 adb 注入管线，**仅编辑项目面板的字段禁用** |
| 1.10 | 编辑项目面板 `取消` 可用 | PASS | tap (2298,1233) | [16a_after_cancel_correct.png](16a_after_cancel_correct.png) | 面板关闭，EditText 数由 2→1（仅剩搜索框），底色恢复 |
| 1.11 | 项目删除（确认框、删后不复活） | PASS | ⋮→`关闭` tap(360,1106) → 确认 tap(2394,909) | [18a_proj_menu_exact.png](18a_proj_menu_exact.png) [19a_proj_close.png](19a_proj_close.png) [19b_proj_closed.png](19b_proj_closed.png) | 确认框文案**完整**：标题`关闭项目?`、正文`「.cache」将从此列表中移除。项目及其会话仍保留在服务器上，不会删除任何内容。`、按钮`取消`/`关闭`；确认后行消失、全 XML 无 `.cache` 残留、会话区恢复完整分组 |
| 1.12 | 「最近关闭」空态 | **未验到（可达性受限）** | 源码判定 + 尝试 | 无截图 | **源码**：`HomeProjects.kt:274-275` `showsClosed = projects.isEmpty() && recentlyClosed.isNotEmpty()` —— 该分区**仅在「打开的项目列表为空」时渲染**（有意偏离在案=「最近关闭」仅空态）。同时 `HomeScreen.kt:1348` 注释确认列空态。本机项目列有 8 个共享项目（别的测试员的），清空它们会破坏共享后端状态 → **不制造该场景**。功能逻辑已由源码+单测覆盖，本设备上**未验到**。
| 1.15 | 项目删除不复活（导航往返验证） | PASS | 关 `.cache` → 进 `设置`(516,1348) → 点 `关闭`(2182,248) 回 Home | [21a_settings.png](21a_settings.png) [22a_back_home.png](22a_back_home.png) | Home→Settings→Home 一轮后 XML 仍无 `.cache` → **不复活** |
| 1.13 | 项目 ⋮ 菜单项精确坐标（供复现） | 记录 | DumpXml | [18b_proj_menu_exact.xml](18b_proj_menu_exact.xml) | 菜单容器 `[248,560][472,1170]`；`新建会话`(360,624) `导入会话`(360,720) `编辑项目`(360,816) `刷新工作区`(360,912) `清除通知`(360,1008) `关闭`(360,1106) |
| 1.14 | 项目行三控件精确坐标 | 记录 | DumpXml | [17_home_rows.xml](17_home_rows.xml) | 每行：`⋮`=`[584,y][656,y+96]`、铅笔=`[656,y][728,y+96]`、`×`=`[728,y][824,y+96]`；行高 96、间隔 96。项目头新建图标 `[708,356][804,424]` |

