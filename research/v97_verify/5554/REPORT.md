# V97 真机终验 · 准备段报告 — emulator-5554 (Pixel_9a)

**段位** 准备段（环境与脚本；不依赖新 APK 的检查已全部跑完）
**日期** 2026-10-02 · **测试员** task-2 真机测试员（v97）
**设备** `emulator-5554`（`emu avd name` → `Pixel_9a` 已核实；屏幕 1080x2424 @ 420dpi；`show_ime_with_hard_keyboard=1` 已是 1）
**未触碰** 5556 / 5558 / 5560 / 5562 / 127.0.0.1:5555
**证据目录** `research/v97_verify/5554/`

---

## 0. serial↔AVD 映射（本轮实测，Lead 重启舰队后已重排）

实测时间 17:20 前后（`env_serial_avd_map.txt`）：

| serial | AVD（`emu avd name`） | 备注 |
|---|---|---|
| **emulator-5554** | **Pixel_9a** | **本测试员用机** |
| emulator-5556 | Medium_Desktop | 不碰 |
| emulator-5558 | Pixel_10_Pro_XL | 不碰 |
| emulator-5560 | Pixel_Tablet | **不是** Pixel_9a（惯性记忆已失效） |
| emulator-5562 | Small_Desktop | 不碰 |

⚠️ 惯性口径「5560=Pixel_9a」本轮**已失效**；任何装机/派单前必须重跑 `emu avd name`。

---

## 1. 环境自检（全部实测，证据文件在括号内）

| 项 | 命令 | 实测读数 | 结论 |
|---|---|---|---|
| 后端 | `GET http://127.0.0.1:4095/api/info`，Basic `opencode:opencode` | HTTP 200 `{"version":"2.0.18","pid":260,"urls":["http://127.0.0.1:4095"],"paths":{"tmp":"/tmp/opencode"}}` | ✅（`env_backend_info.txt`） |
| reverse 4095 | `adb -s emulator-5554 reverse tcp:4095 tcp:4095` | `host-15 tcp:4095 tcp:4095` | ✅ 已挂（`env_serial_avd_map.txt`） |
| reverse 2222 | `adb -s emulator-5554 reverse tcp:2222 tcp:22` | `host-15 tcp:2222 tcp:22` | ✅ 已挂 |
| sshd | `wsl -d Arch -e bash -lc "sudo systemctl is-active sshd"` | `active` | ✅（`env_sshd_key.txt`） |
| faillock | `sudo faillock --user sshtest --reset` 后查表 | 表为空（仅表头） | ✅ 干净 |
| ssh 端口 | `ss -tln \| grep ':22 '` | `0.0.0.0:22` + `[::]:22` LISTEN | ✅ |
| 测试账号公钥 | `ssh-keygen -y -P keypass123`（WSL /tmp 拷贝，规避 /mnt/c 0777 拒读） | `ssh-ed25519 AAAAC3…HeiXS v95-test`，与 `authorized_keys` 逐字节一致 | ✅（`env_sshd_key.txt`） |
| 已装 app | `pm path` + `dumpsys package` | `com.xiufeigo.opencodeshell` v1.4.0 (code 26)，`lastUpdateTime=2026-10-02 09:19:29`（设备时），uid=10230 (u0_a230) | ✅ = **旧 APK**，等 Lead 装 F14 构建（`env_app_installed.txt`） |

## 2. 测试密钥（已就位，装机后需重放）

- repo 副本 `research/v95_verify/id_ed25519`（444 字节，md5 `0120a8270f7f0b0c71d31f4933384c63`）
- 已通过 base64→`run-as` 写入 app 私有目录：`/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519`
- 设备侧 md5 与 repo **逐字节一致**；`run-as ls -l files/`：`-rw-rw-rw- u0_a230 u0_a230 444`
- **阴性对照**：`run-as … cat /sdcard/v95/id_ed25519` → `Permission denied`（F14a 情境在本机成立，`/sdcard` 副本属主仍是旧 uid u0_a218）
- **重放口径**：每次 APK 安装后先跑 `E2ECHK5554_provision_key.ps1`（幂等，含 md5 校验，失败即非零退出）

## 3. 脚本清单与用法（`research/v97_verify/5554/`，全部纯 ASCII + PS5.1 解析零错）

| 文件 | 用途 |
|---|---|
| `E2ECHK5554_run.ps1` | 公共库：Shot/Ui/Nodes/Tap/TypeText/坐标解析/导航（GotoSettings/GotoServers/OpenAddForm）/Fill-SshForm/Answer-Card（tight block）/Rehearse-Card/EnsureKey/ResetFaillock/SshdLog。纪律内建：无 BACK(4)、设置弹层内无 ESC(111)、截图走 screencap+pull（无 exec-out） |
| `E2ECHK5554_shot.ps1` | 截图帮手：`.\E2ECHK5554_shot.ps1 -Name xxx` |
| `E2ECHK5554_ui.ps1` | dump 帮手：`-Name xxx [-Nodes]` |
| `E2ECHK5554_tap.ps1` | 按 dump 正则点中心：`-Pattern '^Servers\t' [-Index n]`（坐标运行期解析，[int] 强转已内建） |
| `E2ECHK5554_type.ps1` | 向已聚焦字段打 ASCII：`-Text '…'`（≤8 字符分段、段间 1.5s、词间 KEYCODE_SPACE 62） |
| `E2ECHK5554_provision_key.ps1` | 密钥重放（每次装机后必跑） |
| `E2ECHK5554_prep_oldapk.ps1` | 无副作用准备性检查（装机后可当冒烟重跑） |
| `E2ECHK5554_f14a.ps1` | F14a：不可读私钥 → 断言 key 文件文案（断言点写在文件头注释） |
| `E2ECHK5554_f14b_f12a.ps1` | F14b+F12a 修正口径：passphrase 留空 → 弹卡输 keypass123 → Connected + sshd `Accepted publickey`；首跑加 `-RehearseOnly` 学坐标 |
| `E2ECHK5554_f13b.ps1` | F13b：错密码条目 → 弹卡输 opencode → Connected；每次 Reconnect 前自动 ResetFaillock |
| `E2ECHK5554_f12b.ps1` | F12b：Cancel 后 7 点采样不重弹、行保留；二次 Reconnect 必重弹 |
| `E2ECHK5554_v82five.ps1` | v82 五条**骨架**（见 §6 限制） |
| `E2ECHK5554_nodes.py` / `E2ECHK5554_rowscan.py` | 节点表 / 像素行扫描（自 v95 复用件，设备无关） |

**建议执行序**（新 APK 装好后）：
1. `E2ECHK5554_prep_oldapk.ps1`（当冒烟）→ 2. `E2ECHK5554_provision_key.ps1` → 3. `E2ECHK5554_f14a.ps1` → 4. `E2ECHK5554_f14b_f12a.ps1 -RehearseOnly` 学坐标 → 5. 带学到的 `FieldX/Y ContX/Y` 正式跑 F14b+F12a → 6. `E2ECHK5554_f13b.ps1`（可先 `-RehearseOnly`）→ 7. `E2ECHK5554_f12b.ps1`（在 F13b 条目上）→ 8. 清理 + `E2ECHK5554_v82five.ps1`。

## 4. 旧 APK 上的准备性检查（已跑，无副作用，无结论性断言）

命令序列：`E2ECHK5554_prep_oldapk.ps1`（launch → Settings → Servers → Add SSH 表单 dump → Cancel 关表单）：

| 检查 | 读数 | 证据 |
|---|---|---|
| 启动 | `mCurrentFocus=…OcActivity` | `E2ECHK5554_prep_01_home.png` |
| Settings→Servers 面板 | 行仅默认 `10.0.2.2:4095`（Server healthy · Default · v2.0.18 • opencode）+ `Add SSH server` 按钮 | `E2ECHK5554_prep_02_servers_panel.png` + `ui_E2ECHK5554_nav_tmp.xml` |
| 表单可 dump | `Host or SSH command` / `Server name (optional)` / `Cancel` / `Add server` 全部在节点表（紧凑态，Host y=1576） | `E2ECHK5554_prep_03_form_open.png` |
| 收表单 | 用表单自身 Cancel 按钮（@610,1800，来自 dump），未用 BACK/ESC | `E2ECHK5554_prep_04_after_cancel.png` |
| 截图管线 | 4 张 PNG 101KB–145KB，全部 sane | 同上 |

**未在旧 APK 上跑任何 F14 断言**（按任务纪律）；F14a 断言文案的源码锚已核实：`strings.xml:877` = `Can't read the private key file. Check the path and read permission.`，分类器 `SshConnectionModel.kt:173-175`。

## 5. 下一步待跑清单（等 Lead 装 F14 构建后）

1. 重放 `E2ECHK5554_provision_key.ps1`（uid 可能变）。
2. F14a / F14b+F12a / F13b / F12b 按 §3 顺序跑，证据落本目录。
3. v82 五条：**先让 Lead 贴 task-2 原文**（见 §6），填好 `E2ECHK5554_v82five.ps1` 的判据再跑。
4. 清理：删测试条目（E2E5554K1/K2/C1）、faillock 复位、`/sdcard` 无残留（密钥私有目录副本按 Lead 意见保留或 `run-as … rm files/id_ed25519`）。

## 6. 限制与未验到（如实）

1. **task-2 任务系统不可达**：本会话无 `team_task_get` 命令/工具（实测 `CommandNotFoundException`），**v82 五条口径原文未取到**。已按 `research/HANDOFF.md` 的 v82 修复面重构五槽位骨架（S1 Ctrl+K=113 39、S2 Ctrl+Shift+P=113 44、S3 设置弹层 IME band、S4 表单 footer IME 可达、S5 SSE 恢复退休——S5 需动共享后端，默认只被动取证并标注 NEEDS LEAD）。**判据常量处留了 `TODO(TASK-2 TEXT)`，未填原文前不下结论。**
2. **F14a 阴性对照在本机成立**（§2），但真实 F14 断言未跑（旧 APK，按纪律）。
3. **弹卡应答的键盘态坐标未在本机学过**（`FieldX/Y ContX/Y` 暂用 v96/5560 同规格屏的 540/1641/866/1347 作初值）：本机首跑必须先 `-RehearseOnly`，学完再正式跑。
4. `authorized_keys` 直读被拒（非 root `ls` Permission denied），改用 `sudo cat` 内容核验通过；权限位本身本轮未记录（v96 已记 `-rw------- sshtest sshtest`）。
5. WSL 侧偶发 UTF-16 乱码告警（`wsl: 检测到 localhost 代理配置…NAT 模式`），不影响命令结果。

## 7. 纪律自查

- 只操作 emulator-5554；`127.0.0.1:5555` 未触碰。共享后端本轮只做了只读 `GET /api/info`，未建会话。
- 全程未按 BACK(4)、未在设置弹层发 ESC(111)；收表单用其自身 Cancel。
- 未跑 gradle、未装 APK、未杀进程。
- 推送/重定向均走 `cmd /c` 或 `pull`；截图零 exec-out。
