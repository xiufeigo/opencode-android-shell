# P0：4095 `opencode serve` 处理 prompt 后自旋（Rsl / 单核打满 / 全 HTTP 无响应）环境与复现调查（2026-09-26）

> 环境/配置类任务：**未改 app 仓库任何代码**，未改 `_upstream/`，未动 git / 模拟器 / adb
> （只读地看过 Windows 进程表与 `netstat -ano`，**没有运行 adb 或模拟器命令**）。
> WSL 侧改动物：临时起过 4 个诊断实例（4096/4097/4098/4099，**已全部 kill**）、
> 在 `/tmp/wedge/` 留了诊断脚本、建/删过测试 session（**已删净**）。
> **4095 进程（PID 195733）全程未重启、未改动**，结尾 `/api/info` = 200。

## 0. 结论（TL;DR）

1. **三组复现全部为阴性：独占 / SSE 挂载 / 并发，一次自旋都没复现出来。**
   累计 **32 轮**「建会话→prompt→等回包」**全部拿到真回复**，另有 116 次 fire-and-forget prompt（churn）
   与 1 次被 `kill -TERM` 打断的 prompt；**1084 次健康探测无一次超时**，最大 316 ms
   （且那次出现在新进程启动窗口）。**「SSE 挂载才自旋」的假设被否证**：
   隔离实例上「无任何客户端」与「`curl -N /api/event` 挂载中」两种条件下行为完全一致。
2. **并发也不是充分条件**：2 路并发 prompt、6 路并发 prompt、以及 60 s 内 116 次
   「建会话+prompt+DELETE」churn，全部无自旋；`kill -TERM` 打断 in-flight prompt 再重启，
   新进程 3/3 正常。**v18 那轮 4/4 复现时的「另一路并发重启」没有被本轮证成充分原因。**
3. **本轮无法复现 = 不能给上游报 bug**。源码里有 2 处「无延时 `continue`」的可疑死循环
   （§8），但跑着的二进制是 **2.0.18**、`_upstream` checkout 是 **2.0.11**，
   且没有自旋现场，**只能作为候选线索，不能作为结论**。
4. **真正查清的是运维层两件事（这是本轮可落地的产出）：**
   - **启动命令不可靠的机制**：从 Git Bash 里用 `wsl.exe -d Arch -- bash -lc '<脚本>'`
     启动会**被 MSYS2 改写路径**（`/home/...` → `C:/Program Files/Git/home/...`，
     `/tmp/...` → `C:/Users/.../AppData/Local/Temp/...`），因为改写后的路径含空格而**静默失败**；
     另外**直接把脚本当 wsl.exe 的命令**（`wsl.exe -d Arch -- /tmp/x.sh`）时，
     wsl.exe 会话一退出就会把**还没启动完**的子进程带走（即使有 `setsid nohup`）。
     两者叠加就是历史报告里「有时活有时死」的全部来源。**定版命令见 §5。**
   - **API 基路径是 `/api/*`**；`/`、`/info`、`/event`、`/health`、任意不存在的路径
     都会返回 **200 `text/html`** 的 SPA fallback —— 拿这些当健康信号会**误判成功**（§6）。
5. **`--service` 与 4095 是两套东西**（§7）：4095 是 `serve --port 4095`（mode `default`），
   从不写 `service.json`；`--service` 是 CLI/TUI 用 `Service.ensure` 选举出来的共享后端，
   会注册租约、被替换时自杀。**关键推论：`opencode run …` 根本不连 4095**，
   所以「CLI 5 秒正常 vs app 自旋」这个对照**同时变了两个变量**（客户端协议 + 服务端进程），
   不能拿来证明「SSE 是自旋原因」。
6. 测试轮建议：**排他使用后端**（同一时刻只有一路在发 prompt / 重启），
   起实例后**必须回读 `/api/info` 200 再开工**，并保留 §3 的自旋取证配方备用。

---

## 1. 环境与取证口径

### 1.1 本次调查时的现场

| 项 | 值 |
|---|---|
| 后端进程 | `opencode serve --port 4095`，**PID 195733**，`lstart` = 2026-09-26 17:04:36，PPID=195715(`/init`)，`cwd` = `/mnt/c/sensorsdata/main/program/opencode-android-shell` |
| 版本 | 二进制 `opencode v2.0.18`（`opencode --version`）；`service.json` 里也记 2.0.18 |
| **源码 checkout** | `_upstream/package.json` = **2.0.11** → **与运行二进制不一致**，§8 的源码结论只能当线索 |
| 进程 environ | 只有 `OPENCODE_SERVER_USERNAME` / `OPENCODE_SERVER_PASSWORD`（都是 `opencode`），**没有任何 `*_API_KEY`** |
| 鉴权 | HTTP Basic `opencode:opencode`（`/api/info` 不带 = 401） |
| 日志 | `~/.local/share/opencode/log/opencode.log`（28 MB，**所有实例共写同一文件**，靠 `run=<id>` 区分进程；4095 的 run id = `9fbedd66`） |
| DB | `~/.local/share/opencode/opencode.db`（4095 与 `--service` 共用） |

### 1.2 「谁在用后端」——先查清楚（这是本轮最容易踩的坑）

- **模拟器在跑**：Windows 侧 `emulator.exe`(47768) + `qemu-system-x86_64.exe`(36404)，
  两者 `StartTime` = **17:18:08**（就在本轮调查开始前 2 分钟被另一路测试重启过）。
- **app 通过 `adb reverse` 挂在 4095 上**：Windows 侧 `127.0.0.1:4095` 的监听者与转发者是
  `wslrelay.exe`(14244)，而**两条长连接（ESTABLISHED）的持有者是 `adb.exe`(53616)**。
  即 app 的请求路径是 `模拟器内 WebView → 设备 localhost → adb reverse → Windows → wslrelay → WSL 127.0.0.1:4095`。
  WSL 侧看这两条 socket 属 **uid 0**（relay 进程），所以「WSL 里 `ss -tnp` 查不到客户端进程」不代表没人连。
- **另一路测试在本轮期间是活跃的**：`GET /api/session` 显示 session
  `ses_f22fcb8fcffeKgCGsvJv5gNPDi`（标题 `E2ECHK v18 smoke test`，location=`/home/xiufeigo/v11diff`）
  在 17:18–17:29 之间被**连续追加消息**（模型切换 → prompt `E2ECHK v18 smoke` → `E2ECHK. Reply with exactly: BP`
  → 评审面板 comment 注入 → 切 `muse-spark-1.3-contributor-free` → prompt `E2ECHK muse test one`）。
  日志里 17:26 / 17:27 都有它的请求。
  → **本轮拿不到「后端完全没人用」的状态**；表 1 里标了每一轮的隔离程度，读者按标注理解结论强度。
- **app 到底挂没挂 SSE**：4095（run=`9fbedd66`）在日志里只出现过 **8 次** `/api/event` 请求完成记录
  （09:05:00、09:05:31、09:11:36、09:15:10、09:15:26、09:17:25、09:29:10、09:36:15 UTC），
  前 6 次集中在 v18 那轮；**说明 `/api/event` 是长连接、很少重建**。
  相比之下 app 的**会话日志流 `/api/experimental/session/{id}/log` 每 36.0~36.1 s 必重建一次**
  （连续 12 个样本：09:31:34.684 → 09:32:10.737 → 09:32:46.762 → … → 09:38:11.150），
  这条流是本轮期间 app「确实活着并在持续拉流」的硬证据。
  ⚠️ 因此「第 1、2 组在 4095 上跑时 app 的 SSE 到底在不在」**无法 100% 断定**
  （服务端只记「请求结束」，不记「当前订阅者」）；这正是我又在**隔离实例 4096** 上重做一遍的原因。

### 1.3 诊断工具（本轮自己写的，留在 `/tmp/wedge/`，后续轮可直接复用）

| 文件 | 作用 |
|---|---|
| `/tmp/wedge/ocwedge.py` | 主探针：`plain N` / `sse N` 两种模式；建会话→prompt→轮询回包；同时后台采样 `/proc/<pid>/stat` 的 state 与 CPU ticks，并以 `GET /api/info` 做 0.5 s 一次的健康探测，输出 `samples_<TAG>.csv` |
| `/tmp/wedge/monitor.sh <pid> <port> <out>` | 纯外部监视器（不依赖被测进程内的采样线程）：0.5 s 一次记 `state / ticks / probe_code / ms` |
| `/tmp/wedge/forensic.sh <pid> <out>` | 自旋取证快照（进程状态、逐线程 ticks/wchan、`/proc/<pid>/stack`、`ss`、日志尾部） |
| `/tmp/wedge/start_serve.sh <port> <log> [workdir]` | 可靠启动器（§5 定版命令用的就是它） |
| `/tmp/wedge/del_loop.py` | 建会话+prompt+DELETE churn（并发压测的 C 路） |
| `/tmp/wedge/hammer.sh <port> <n>` | N 路并发 prompt |
| `/tmp/wedge/restart_test.sh` | 打断 in-flight prompt → 重启 → 立刻再 prompt |
| `/tmp/wedge/dup_test.sh` | 同端口重复启动（抢端口） |
| `/tmp/wedge/cleanup.py <port>` | 按日志里的 session id 删净测试会话 |

全部只操作 `127.0.0.1` + `/api/*`，无外部网络、无破坏性操作。

---

## 2. 三组复现结论（＋附加 4 组）

所有 run 统一用 `opencode-go/space-bunny-free`（free 档，cost 0，不花钱），
prompt 固定 `Reply with exactly: OK`，回包判定 = `GET /api/session/{id}/message?order=asc`
出现 `type=assistant` 且 `time.completed` 存在。

### 表 1：全部 10 组结果

| # | 组 | 端口 | 隔离程度 | 客户端形态 | 次数 | 结果 | 进程 CPU | 健康探测 | 自旋 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | smoke | 4095 | app 挂着 | curl | 1 | 1/1 OK（17.11 s） | 9.0% | 37 样本，max 20 ms | 无 |
| 2 | **独占（plain）** | 4095 | app 挂着 + 另一路在发 prompt | curl | 5 | **5/5 OK**（13.08 / 11.07 / 14.12 / 11.11 / 11.07 s） | 9.7% | 131 样本，max 23 ms，慢/失败 **0** | 无 |
| 3 | **SSE 挂载** | 4095 | 同上 | curl + `curl -N /api/event` | 3 | **3/3 OK**（10.09 / 12.08 / 11.08 s） | 8.8% | 73 样本，max 24 ms，慢/失败 **0** | 无 |
| 4 | 独占（plain） | **4096 隔离** | **0 个客户端** | curl | 5 | 5/5 OK（11.15 / 11.10 / 11.10 / 11.10 / 10.09 s） | 33.2%¹ | 119 样本，max 37 ms | 无 |
| 5 | SSE 挂载 | **4096 隔离** | 0 个客户端 + 我的 SSE | curl + `/api/event` | 3 | 3/3 OK（20.18 / 11.17 / 17.14 s） | 6.3% | 103 样本，max 17 ms | 无 |
| 6 | 并发 A+B | 4096 隔离 | 2 路同时 prompt | 2×curl | 3+3 | **6/6 OK**（14~17 s） | 10.2% / 9.8% | 108 样本，max 43 ms，慢/失败 **0** | 无 |
| 7 | 并发 C | 4096 隔离 | 0.4 s 一次 churn | create+prompt+**DELETE** | **116** | **全部 DELETE 204** | 27.1% | 114 样本，max 59 ms，慢/失败 **0** | 无 |
| 8 | 并发 D | 4096 隔离 | 6 路同时 prompt | 6×curl | 6 | **6/6 OK**（32~55 s，变慢但无错） | 9.3% | 111 样本，max 35 ms，慢/失败 **0** | 无 |
| 9 | 抢端口 | 4096 隔离 | 同端口再起一个 | `serve --port 4096` | 1 | 新进程 `ServeError … Is port 4096 in use?` 后退出；**原进程不受影响**（`/api/info` 仍 200） | — | — | 无 |
| 10 | **重启窗口** | 4096 隔离 | 打断 in-flight prompt | prompt 中 `kill -TERM` → 重启 → 立刻 3 轮 | 3 | **3/3 OK**（13.77 / 12.10 / 13.10 s）；被打断那轮客户端 `Connection refused` 轮询 150 s 后 TIMEOUT（= app 那句「server restarted while you were working」的场景） | 46.4%（新进程预热） | 83 样本，max 316 ms（启动窗口），慢/失败 **0** | 无 |

¹ 第 4 组 CPU 33.2% 是唯一偏高的一次（其它 6~10%）；空闲复测三个实例都是 **~1%**，
`iso_sse3` 同实例同负载只有 6.3% → **不是自旋，是一次性开销**（该实例刚起、且与 4095 共用 DB）。
**CPU 百分比不能单独当自旋判据**，必须配合「探测延迟」和「state=R 是否持续」一起看（§3）。

### 2.1 逐组判读

**（1）独占组（表 1 #2、#4）——阴性。**
隔离实例（0 客户端）上 5/5 正常、CPU 平稳、探测最大 37 ms。
→ **「curl 建会话 + prompt」本身不会诱发自旋。**

**（2）SSE 组（表 1 #3、#5）——阴性，假设被否证。**
隔离实例上先挂 `GET /api/event`（确认收到 `: heartbeat`，0 s / 15 s / 30 s / 45 s 各一次，
服务端 `Stream.tick("15 seconds")` 心跳正常），再发 3 轮 prompt：3/3 正常、CPU 6.3%、探测最大 17 ms。
→ **「SSE 流挂载时才自旋」不成立**：无 SSE 与有 SSE 的表现没有区别。
另外第 3 组在 4095 上还叠了 app 自己的连接，同样没有自旋。

**（3）并发组（表 1 #6、#7、#8）——阴性。**
- 2 路并发 prompt：6/6 正常，回包时间从单路 ~11 s 涨到 ~16 s（合理排队），无错误。
- **116 次 create+prompt+DELETE**（0.4 s 间隔、DELETE 全部 204）：这是最接近「多轮并跑 + 会话被删」的场景，
  依然 0 慢探测、0 自旋。**DELETE 在压力下也不曾无响应**（历史报告里自旋时 DELETE 也挂，这条对照成立）。
- 6 路并发：6/6 正常，最慢 55 s。**没有出现 429/配额类错误**（免费档这轮没被限流）。

**（4）抢端口（表 1 #9）——阴性，但解释了一条历史日志。**
`/tmp/oc4095.log` 与 `/tmp/oc4095_v18_final.log` 里的
`ServeError … Failed to start server. Is port 4095 in use?` 就是这个：**重启尝试撞上了还活着的旧进程，
新进程干净退出、旧进程毫发无损**（`EXITED_AT=… rc=0`）。它不是自旋，也不留残骸。

**（5）重启窗口（表 1 #10）——阴性。**
这是 v18 那轮 4/4 复现时的现场条件（两路测试互相重启）。
`kill -TERM` 时旧进程**干净退出**（不需要 `SIGKILL`）；新进程 4 s 内 `/api/info` 200；
随后 3 轮 prompt 全正常；被打断的会话在客户端侧表现为连接被拒（app 的
「server restarted while you were working」就是它）。**没有诱发自旋。**

### 2.2 本轮复现失败的边界（必须如实说明）

- 我这轮**没有拿到「后端完全无人使用」**的窗口（另一路测试 17:18–17:29 在驱动 app）。
  但表 1 #4/#5/#6/#7/#8/#9/#10 都在**隔离实例**上跑，客户端只有我自己 —— 隔离条件是成立的，
  只是「隔离实例」与「app 正在用的 4095」是两个进程（同二进制、同 DB、同 cwd）。
- 因此严格表述是：**在「隔离实例 + curl 客户端」下，三种条件都不自旋**；
  「app 驱动 4095」这条路径本轮只跑到 #1/#2/#3（且当时另一路也在发 prompt），也**没自旋**。
- 若后续要判定「自旋是否只由 app 的某种请求组合触发」，需要**排他窗口**（见 §4）。

---

## 3. 自旋时取证：配方 + 基线快照

### 3.1 本轮的困难：自旋**没有留下日志指纹**

把 28 MB 日志按分钟统计（09-25T08:00 → 09-26T09:30，共 59801 行），
≥60 s 的「空档」有 **189 个**，逐一看都是**空闲期**（每 ~2 分钟一条 `provider.updated` 的 watcher tick 把它填上），
**不是自旋**。反过来推：**自旋期间如果 JS 死循环不打日志，日志空档与空闲期长得一模一样**
→ **「日志断档」不能当自旋判据**，必须看进程级证据。
（日志里 09:03:32 与 09:04:04 各有一次 `cli starting … args="[\"serve\",\"--port\",\"4095\"]"`，
就是那两次重启尝试；级别统计：最近 20 万行 `ERROR 33 / WARN 1959 / INFO 57566`。）

### 3.2 健康基线快照（用来对比「自旋时哪里不一样」）

```
$ ps -o pid,ppid,stat,etimes,pcpu,nlwp,args -p 195733
 195733  195715 Ssl  1732  4.5  72  /home/xiufeigo/.opencode/bin/opencode serve --port 4095
$ grep -E 'State|Threads|VmRSS' /proc/195733/status
 State: S (sleeping)   Threads: 72   VmRSS: 546456 kB
主线程 wchan = do_epoll_wait        # 事件循环在等 IO
其余 ~70 线程 wchan = futex_do_wait # 线程池在睡
$ sudo cat /proc/195733/stack
 [<0>] do_epoll_wait+0x499/0x4d0
 [<0>] do_compat_epoll_pwait.part.0+0xf/0x70
 [<0>] __x64_sys_epoll_pwait2+0xb2/0xe0
 [<0>] entry_SYSCALL_64_after_hwframe+0x76/0x7e
```

**判据（关键）**：自旋 = `state=R` **持续**（不是瞬时）、单核 ~100%、**主线程不在 `do_epoll_wait`**、
探测 `GET /api/info` 超时。所以最省事的三角定位是：

```bash
# 1) 是否在跑（不是瞬时 R，要连续采样）
for i in $(seq 1 10); do awk '{print $3, $14+$15}' /proc/<PID>/stat; sleep 0.5; done
# 2) 哪个线程在烧（tid 与 /proc/<PID>/stat 第 1 字段相同者 = 主线程/JS 线程）
for t in /proc/<PID>/task/*; do awk -v t="$(basename $t)" '{print t, $3, $14, $15}' $t/stat; done | sort -k4 -nr | head
# 3) 内核栈：自旋时主线程应当「没有可读栈」或明显不是 epoll_wait
sudo cat /proc/<PID>/stack
# 4) 用户态：Bun 是 stripped 的，只能判「主线程忙/闲」，看不出 JS 函数
sudo gdb -p <PID> -batch -ex 'set pagination off' -ex 'bt 8'
# 5) 一次性快照（上面几步 + ss + 日志尾部）
/tmp/wedge/forensic.sh <PID> /tmp/wedge/forensic_<PID>.txt
```

工具可用性（本机实测）：`strace` / `perf` / `bpftrace` / `py-spy` **都没装**；`gdb` 在 `/usr/sbin/gdb`；
`ptrace_scope=1` → **`gdb -p` 需要 sudo**（本机有免密 sudo，`sudo -n true` 通过）；
`/proc/<pid>/stack` 也需要 sudo。
`gdb` 实测只能给出 `0x0000000001af6d86 in ?? ()` + `#1 0x0` —— **Bun 无符号，别指望 gdb 指出 JS 帧**；
它的价值仅在于「主线程是在 epoll_wait 还是在一个用户态地址」。

**本轮没抓到自旋现场**（一次都没复现），所以上面是**配方 + 健康基线**，不是现场取证结果。
若下一轮抓到，建议额外做两件本轮做不到的事：
① `sudo gdb -p` 时加 `-ex 'thread apply all bt 5'` 看是否有非主线程异常忙；
② 若怀疑 DB 锁，`ls -l /proc/<PID>/fd | grep opencode.db` + `lsof` 看谁持有，
并在自旋窗口内 `sqlite3 opencode.db 'pragma wal_checkpoint;'` 是否被阻塞。

---

## 4. 触发条件与规避

### 4.1 本轮能确定的触发条件

**没有找到**。在「隔离实例 + curl」下，以下全部**不触发**：
建会话/prompt ×5、SSE 挂载中 prompt ×3、2 路/6 路并发 prompt、
116 次 create+prompt+DELETE churn、同端口抢端口、打断 in-flight prompt 后重启再 prompt。

### 4.2 规避法（无论根因是什么，都成立）

1. **排他使用后端**：同一时刻只允许一路在发 prompt / 重启 4095。
   判据不是「有没有人在跑」，而是：
   ```bash
   # a) 谁在连（Windows 侧才能看到 adb/python 客户端 PID）
   netstat -ano | grep ':4095' | grep ESTABLISHED      # 只看 adb.exe → 是 app 在挂，不是另一路在发 prompt
   # b) 最近 3 分钟有没有新 prompt（最可靠）
   curl -s -u opencode:opencode http://127.0.0.1:4095/api/session | \
     python3 -c "import sys,json,time;d=json.load(sys.stdin)['data'];n=int(time.time()*1000);\
     print([ (s['id'],(s.get('title') or '')[:20]) for s in d if n-s['time']['updated']<180000])"
   ```
   **注意**：`adb.exe` 持有到 4095 的 ESTABLISHED 长连接是**常态**（app 用 `adb reverse` 挂着），
   **不等于**有人在发 prompt；反过来，`ss -tnp` 在 WSL 里看不到这些连接的客户端进程（uid 0 relay），
   别据此断言「没人连」。
2. **重启窗口要独占**：重启会打断 in-flight prompt（客户端表现 = 连接被拒 + app 弹
   「server restarted while you were working」）。重启前先确认没有正在跑的轮次。
3. **起完实例必须回读健康**：`/api/info` = 200 且进程在（§5.3）。
   启动命令可能**静默失败**（见 §5.1），只看「命令返回了」会误判成功。
4. **别用「`/` 或 `/health` 返回 200」当健康信号**（§6）。
5. 测试会话用完即删：`/tmp/wedge/cleanup.py 4095`（按日志里的 `ses_*` 删，幂等）。

---

## 5. 定版运维命令（启动 / 重启 / 健康检查）

### 5.1 为什么原来的写法「有时活有时死」——两个独立机制，都已实测复现

**机制 A：Git Bash(MSYS2) 会改写路径参数。**
从 Git Bash 调 Windows 程序时，MSYS 把「看起来像路径」的参数**改写成 Windows 路径**：
`/` → `C:/Program Files/Git`，`/tmp` → `C:/Users/<user>/AppData/Local/Temp`。
实测证据（把脚本输出重定向到文件后回读）：

```
# 直接执行（无 & ）：
$ wsl.exe -d Arch -- bash -lc '/home/xiufeigo/.opencode/bin/opencode --version > /tmp/wedge/b5.txt 2>&1'
$ cat /tmp/wedge/b5.txt
bash: line 1: C:/Program: No such file or directory          # ← 被改写且含空格，命令在空格处断开

# 把启动脚本当 wsl.exe 的直接命令：
$ wsl.exe -d Arch -- /tmp/wedge/start_serve.sh 4099 /tmp/oc4099.log
/usr/bin/bash: line 1: C:/Users/xiufe/AppData/Local/Temp/wedge/start_serve.sh: No such file or directory
```

- 在**后台任务里**（`… &`）这个错误**不会显示**：重定向目标也被改写，日志文件根本不落在 `/tmp`，
  于是现场表现就是「命令返回了、进程没起来、日志文件不存在」——**静默失败**。
- 同一段脚本里的 `$!` 经 `bash -lc` 传进去会变成空串（实测 `[dollar-bang=]`），
  所以 `echo "pid=$!"` 打印空值**不能**当作「没启动成功」的判据，反之亦然。
- **改写是否发生取决于脚本里路径的书写位置/引号**（实测：路径写在双引号里就不改写，
  裸写就改写）→ 这就是「同一命令有时好有时坏」的直接来源。

**机制 B：wsl.exe 会话退出会带走「还没启动完」的子进程。**
用 `sleep` 做过对照（同一次 wsl 会话里同时起 4 个，12 s 后回读）：

| 起法 | 12 s 后 |
|---|---|
| `setsid nohup sleep 600 &`（fd 未重定向） | **ALIVE** |
| `setsid nohup sleep 601 >/dev/null 2>&1 </dev/null &` | **ALIVE** |
| `nohup sleep 602 >/dev/null 2>&1 </dev/null &` | **ALIVE** |
| `sleep 603 >/dev/null 2>&1 </dev/null &`（裸） | **DEAD** |

→ `nohup` 或 `setsid` **二者有其一即可**；都没有就死。
但**光有 `setsid nohup` 还不够**，还要看会话退出时机：

| 起法 | 结果 |
|---|---|
| `wsl.exe -d Arch -- /tmp/wedge/start_serve.sh 4099 /tmp/oc4099.log`（脚本=wsl 的直接命令，秒退） | 进程**死**，`/tmp/oc4099.log` **0 字节**（连 `server listening` 都没来得及打） |
| `MSYS_NO_PATHCONV=1 wsl.exe -d Arch -- bash -c '… setsid nohup … & sleep 10'`（会话多活 10 s） | 进程**活**，日志 `server listening`，`/api/info` = 200 |
| `wsl.exe -d Arch -- bash -s <<'EOF' … setsid nohup … & … EOF`（会话自然多活几秒） | 进程**活**（4096/4097/4099 三个实例都这样起来并长期存活） |

→ 结论：**启动器必须在子进程「起完」之前保持 wsl 会话存活几秒**；秒退会让 WSL 的会话回收
把尚在初始化（~2–4 s 才打印 `server listening`）的子进程一起带走 —— 即使已经 `setsid nohup`。
一旦进程真正起来，之后任意 wsl 会话结束都不影响它（4095/4096/4097 跨了几十个会话都活着）。

### 5.2 定版启动命令（推荐用 A，最稳）

**A. 从 Git Bash / 任意 Windows shell 调一个 WSL 内的启动脚本（脚本内容用 stdin 送进去，绕开参数改写）**

```bash
# 一次性安装启动器（内容写在 WSL 侧，不经过 Windows 参数解析）
wsl.exe -d Arch -- bash -s <<'EOF'
mkdir -p /tmp/wedge
cat > /tmp/wedge/start_serve.sh <<'SEOF'
#!/bin/bash
# usage: start_serve.sh <port> <logfile> [workdir]
port=$1; log=$2; wd=${3:-/mnt/c/sensorsdata/main/program/opencode-android-shell}
export OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=opencode
cd "$wd" || exit 1
: > "$log"
setsid nohup /home/xiufeigo/.opencode/bin/opencode serve --port "$port" >> "$log" 2>&1 < /dev/null &
echo "launched pid=$! port=$port log=$log cwd=$wd"
SEOF
chmod +x /tmp/wedge/start_serve.sh
EOF

# 每次启动/重启：会话内保留 6 秒再退出（对应机制 B）
wsl.exe -d Arch -- bash -s <<'EOF'
/tmp/wedge/start_serve.sh 4095 /tmp/oc4095.log
sleep 6
pgrep -af 'opencode serve --port 4095'
EOF
```

**B. 若坚持「一行命令 + `bash -lc`」，必须加 `MSYS_NO_PATHCONV=1` 并且让会话多活几秒**

```bash
MSYS_NO_PATHCONV=1 wsl.exe -d Arch -- bash -lc \
  'export OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=opencode; \
   cd /mnt/c/sensorsdata/main/program/opencode-android-shell; \
   setsid nohup /home/xiufeigo/.opencode/bin/opencode serve --port 4095 >> /tmp/oc4095.log 2>&1 < /dev/null & \
   sleep 6'
```

**C. 从 PowerShell（无 MSYS，无改写问题；仍需 `sleep`/`Start-Sleep` 保证会话不秒退）**

```powershell
Start-Process -WindowStyle Hidden -Wait:$false wsl.exe `
  -ArgumentList '-d','Arch','--','bash','-s' -RedirectStandardInput <脚本文件>
# 或最简单：先 Start-Sleep 再返回
Start-Process -WindowStyle Hidden wsl.exe -ArgumentList `
  '-d','Arch','--','bash','-c','setsid nohup /home/xiufeigo/.opencode/bin/opencode serve --port 4095 >> /tmp/oc4095.log 2>&1 </dev/null & sleep 6'
```

**不推荐**：`wsl.exe -d Arch -- bash -lc '<含裸绝对路径的脚本>'`（Git Bash 下）、
`wsl.exe -d Arch -- /tmp/xxx.sh`（秒退）、以及任何**不加 `nohup`/`setsid`** 的后台起法。

### 5.3 起完必须回读（缺这一步就等于没验证）

```bash
wsl.exe -d Arch -- bash -s <<'EOF'
pgrep -af 'opencode serve --port 4095'                 # 必须看到进程
cat /tmp/oc4095.log                                    # 必须看到 server listening on http://127.0.0.1:4095
sleep 15                                               # provider 插件加载要 10~20 s
curl -s --noproxy '*' -u opencode:opencode http://127.0.0.1:4095/api/info   # 必须 200，且 pid 与 pgrep 一致
EOF
```

- **cwd 必须是 app 仓库目录** `/mnt/c/sensorsdata/main/program/opencode-android-shell`
  （决定 server 的默认 location；`GET /api/info` 不体现，但 `GET /api/provider` 的
  `location.directory` 就是它）。
- 启动瞬间 `GET /api/provider` 可能是 0 个 provider（models.dev 插件还在加载），**不是故障**。
- 启动后**不要**给 4095 加 `OPENCODE_API_KEY`（会连带打坏 Zen 的 `public` 免费档，
  见 `research/env-provider-key-loading.md` §6）。

---

## 6. API 基路径与探测口径（历史误判的来源）

实测（`curl -u opencode:opencode`，4095）：

| 路径 | 状态码 | Content-Type | 说明 |
|---|---|---|---|
| `/api/info` | **200** | `application/json` | **真 API**，健康检查用这个 |
| `/api/info`（不带鉴权） | **401** | — | 鉴权是 HTTP Basic `opencode:opencode` |
| `/api/event` | 200 | `text/event-stream` | SSE 事件流（`data: {…server.connected}` 立刻发，之后 15 s 一次 `: heartbeat`） |
| `/api/session`、`/api/session/{id}/prompt`、`/api/session/{id}/message` … | 200 | json | 业务 API 全在 `/api/*` |
| `/` | 200 | **`text/html`** | **SPA fallback**（5986 字节） |
| `/info`、`/event`、`/health`、`/nonexistent-xyz` | 200 | **`text/html`** | **全部是同一个 SPA fallback** |
| `/api/health`、`/api/global/event`、`/api` | 404 | — | 不存在 |
| `/openapi.json` | 200 | json（251 KB） | API 描述文档（**注意 `/api/openapi.json` 是 404**） |

**坑**：裸 `/*` 一律 200 `text/html`，所以「`curl -o /dev/null -w %{http_code} http://127.0.0.1:4095/` 得到 200」
**只能说明进程还在应答，不能说明 API 正常**；而 `/health` 这种「看起来该存在」的路径也会返回 200 HTML。
判健康只用 **`/api/info`**（且要校验 `pid` 与 `pgrep` 一致，避免连到别的实例）。
`/openapi.json` 是查接口定义的权威来源（路由前缀 `/api`、SSE 路径 `/api/event` 都能在上面核到）。

---

## 7. `--service` 与 4095 的关系（任务点 3 的答案）

| | `serve --port 4095`（app 用的） | `serve --service`（CLI/TUI 用的） |
|---|---|---|
| mode | `default` | `service` |
| cwd | 启动时的 cwd（本机 = app 仓库） | 强制 `process.chdir(global.home)` = `/home/xiufeigo` |
| 端口 | 固定 4095 | `ServiceConfig`（本机 49374），会「选举」 |
| 是否写 `service.json` | **从不**（`server-process.ts`：只有 `serviceOptions !== undefined` 才注册） | **写**，带租约 |
| 生命周期 | 长期常驻，与 app 的 `adb reverse` 绑定 | **按需拉起、PID 会变**；`ServiceRegistration.register` 每 **5 s** 重读 `service.json`，一旦发现注册被替换（id/version/url/pid/password 任一不符）就**自我 shutdown** |
| 谁连它 | app（WebView → 原生 loopback 代理 → adb reverse → 4095） | `opencode run` / `opencode auth` / TUI / ACP（走 `Service.ensure`，`cli/src/services/server-connection.ts`） |
| 共享资源 | `~/.local/share/opencode/opencode.db`、`~/.local/share/opencode/log/opencode.log` | 同上 |

**本轮实测状态**：`--service` **当前没在跑**（`service.json` 里 pid 194816、url `http://127.0.0.1:49374`，
该 pid 不存在、49374 无监听 = **陈旧注册**），`opencode service status` → `stopped`；
下一个 CLI 命令会重新选举/拉起一个（PID 会变，这就是「PID 变动」的原因）。

**两条推论（对排查很重要）**：
1. **`opencode run …` 连的不是 4095**，而是选举出来的 `--service`（或 `--standalone` 起的私有 server）。
   所以「CLI 5 秒正常 vs app 自旋」这个对照**同时变了两个变量**：客户端协议（有无 SSE）
   **和**服务端进程 —— **不能**用它推出「SSE 导致自旋」。要单变量对照，必须像本轮 §2 那样
   在**同一个进程**上「挂/不挂 SSE」做 A/B。
2. 两者共用一个 SQLite DB 和同一个日志文件 → 排障时按 `run=<id>` 过滤日志，
   否则会把 `--service`/隔离实例的请求算到 4095 头上（本轮就踩过：`/api/event` 的完成记录里混了我自己的订阅）。

---

## 8. 源码级线索（**线索，非结论**）+ 给上游的最小复现设想

> ⚠️ 前提：跑着的是 **2.0.18**，`_upstream` checkout 是 **2.0.11**，
> 且**本轮一次自旋都没复现**。以下只能作为下一轮定位的**候选靶点**，不能当 bug 结论。

1. **SSE 发布路径不可能阻塞**（这条可以基本排除 SSE 是「卡死源」）：
   `_upstream/packages/server/src/event-feed.ts` 给每个订阅者一个
   `Queue.dropping(4096)`，发布用 `Queue.offerUnsafe`（满则**丢弃订阅者**，永不阻塞发布方）；
   订阅是 per-request 的 `Effect.acquireRelease`。心跳 `Stream.tick("15 seconds")`
   （`server/src/handlers/event.ts:22`）。→ **订阅者再多也不会把发布线程拖住**，
   这与本轮「SSE 挂载无异常」的实验结果一致。
2. **可候选的无延时死循环**：`_upstream/packages/core/src/session/runner/llm.ts` 有 3 个 `while (true)`
   （73 / 187 / 212 行）。其中 `runStep`（212 行起）有**两条不带任何延时的 `continue`**：
   - 循环顶部：`if (compaction.required({...})) { const result = yield* compaction.compact(...); if (result.status !== "completed") return fail; … continue }`
     —— 若 `compact()` 返回 `completed` 但上下文**仍**满足 `compaction.required()`，就是**无界热循环**；
   - `SessionStep.Outcome` 的 `Compacted` 分支：`recoverOverflow = false; continue`（同样无延时）。
   这类循环一旦成立，JS 单线程被占满 → **HTTP 全部无响应**（含 DELETE），且**不打日志**（与 §3.1 观察一致）；
   「数分钟后自愈」则可能是上下文/会话状态终于变化后跳出。
3. **重试路径是有限的，可以排除「重试风暴」**：`core/src/session/runner/retry.ts`
   `Schedule.min([exponential 2s, spaced 10s])` + `Schedule.recurs(10)` ≈ 最多 84 s，
   且 `wait()` 一定 `Effect.sleep(decision.delay)` → **重试不会把 CPU 打满**（只会慢）。
4. **给上游的最小复现（设想，本轮未达成）**：
   ```
   环境：opencode 2.0.18，serve --port <free>，独立 XDG_DATA_HOME（避免共享 DB 干扰）
   步骤：POST /api/session {model} → POST /api/session/{id}/prompt {text:"Reply with exactly: OK"}
         ×N，同时用 /proc/<pid>/stat 采样 state 与 CPU
   观察：state=R 持续 + 单核 100% + GET /api/info 超时 + 主线程不在 do_epoll_wait
   附：触发时的 /proc/<pid>/stack、逐线程 ticks、日志尾部、上下文大小（tokens）与会话消息数
   ```
   **必须先复现再报**；本轮 32 轮 prompt + 8 组压力条件全部阴性，
   按现状提交上游会被当作「无法复现」。
   下一轮建议优先试的**新增变量**（本轮没覆盖）：
   - **上下文接近/超过 compaction 阈值**的长会话（对应 §8.2 的候选循环）——
     本轮每次都是全新空会话（9 KB 上下文），**离 compaction 很远**，这是最明显的覆盖缺口；
   - 上游**瞬时失败**（429/配额/地域 403）与 prompt 的组合 —— 注意
     `research/p0-region-block-muse-spark.md` 记录的 `muse-spark` 地域 403 本轮**已不再复现**
     （app 的 E2ECHK 会话 17:27 用 `muse-spark-1.3-contributor-free` 拿到了真 reasoning），
     所以地域块不能再当现成的错误源；
   - app 侧「重启后自动重发 prompt」的重复请求模式。

---

## 9. 未采用的路 / 未动项 / 遗留物

| 项 | 说明 |
|---|---|
| 未重启 4095 | 4095（PID 195733）全程未动，仍是原进程、原启动方式；本轮所有启动/重启实验都在 4096–4099 上做 |
| 未杀 `--service` | 它本来就没在跑（陈旧 `service.json`），未做任何清理，也没主动拉起 |
| 未改 app 仓库代码 / `_upstream/` | 只新增本文档 |
| 未用 git / adb / 模拟器命令 | 只用 `tasklist`/`netstat -ano`/`Get-Process` 只读观察；`adb.exe` 只是被观察到持有 socket |
| 隔离实例为什么用同一 DB | 换 `XDG_DATA_HOME` 会丢凭据（DB 是 v2 唯一凭据源），改用共享 DB 才能用 `opencode-go/space-bunny-free` 发真请求；代价是与 4095 共用 DB/日志，已在 §2 标注 |
| 未试「长上下文/接近 compaction」 | 本轮 32 轮全是空会话小上下文（~9 KB）—— **最大覆盖缺口，见 §8.4** |
| 未试地域 403 / 429 作为「瞬时失败源」 | 免费档本轮没被限流；`muse-spark` 地域块已消失 |
| 遗留物（WSL `/tmp`，不占仓库） | `/tmp/wedge/` 下的 `ocwedge.py` `monitor.sh` `forensic.sh` `start_serve.sh` `del_loop.py` `hammer.sh` `conc.sh` `restart_test.sh` `dup_test.sh` `cleanup.py` 及 `*.log` / `samples_*.csv` / `mon_*.txt`；日志 `/tmp/oc4096.log` `/tmp/oc4097.log` `/tmp/oc4098.log` `/tmp/oc4099*.log` |
| 清理结果 | 本轮建的全部测试 session 已删净（148 个候选 → 32 个实删 + 116 个已被 churn 自删，另补删 2 个日志外泄漏的）；`GET /api/session` 里近 1 小时新建的只剩另一路测试的 `ses_f22fcb8fcffeKgCGsvJv5gNPDi`（**未动**） |
| 收尾状态 | 只剩 4095 一个实例；`GET /api/info` = `{"version":"2.0.18","pid":195733,"urls":["http://127.0.0.1:4095"]}` |
