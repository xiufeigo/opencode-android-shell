  // ======================================================================
  // UX8 · 回合电源传感器（D1 常亮的**驱动** + P6 后台断流取证）
  //
  // 为什么需要它：UX6 删回环代理后宿主看不见流量，D1 只剩"页面桥"一条路，
  // 而上游官方 UI 从不调 setKeepScreenOn ⇒ 常亮从未置起（FIX_D1 的教训：
  // "观测到 0 命中"不等于"策略在位"）。上游全仓也没有 wakeLock/keepScreenOn
  // 机制可复用（_upstream/packages/app/src 零命中）⇒ 按规格在注入层自建。
  //
  // 传感器只干两件事，全部是**观测 + 调既有桥**，不碰凭据、不改上游行为：
  //  1) 回合开始：fetch 拦截到 POST /api/session/{id}/prompt|message|message_async
  //     ⇒ 乐观置位（与上游 composer/submit.ts:100-103 的乐观 busy 同一思路）。
  //  2) 回合进行/结束：给 /api/event 的 SSE 响应体套一根透明 TransformStream
  //     tee，逐行解析 "data: {json}" 帧，按**线上实测验证过**的
  //     session.execution.started / .succeeded / .failed / .interrupted
  //     （上游自己判 running/idle 也是这四个：packages/client/src/solid/data.ts
  //     :1009-1011 与 :1025-1028）维护 busy 会话集合 ⇒ 驱动 oc.setKeepScreenOn。
  //     ⚠ 不用 "session.status"+"type":"idle" 作主判据：真后端 4095 实测整轮
  //     无该帧（research/web/ux8/wire_event.txt）——旧标记只留作兜底。
  //
  // 本文件由 addDocumentStartJavaScript 注入，早于页面脚本 ⇒ 上游随后
  // 拿到的 window.fetch 就是我们的包装（含 platform.fetch 引用同一全局）。
  //
  // 多会话：busy 按键 sessionID 计数，任一会话在跑 ⇒ 常亮；全部收口 ⇒ 释放。
  // 已知边界（与旧代理时代同取舍）：后台期间回合结束时 SSE 已被掐断，事件丢
  // 失 ⇒ 靠宿主 RoundActivityTracker 的 90s 空闲兜底 + 30min 硬上限收口。
  // ======================================================================
  function installRoundPowerSensor() {
    if (window.__ocRoundPowerSensor) return;
    window.__ocRoundPowerSensor = { installed: true };

    var NativeFetch = window.fetch;
    if (typeof NativeFetch !== "function") return;

    var busy = {};       // sessionID -> true；"__post__" = 发送瞬间的乐观占位
    var keepOn = null;   // 上次推给宿主的值（去抖，避免每帧刷桥）
    var streamSeq = 0;   // SSE 流编号（取证日志用）

    function log(msg) {
      try {
        console.log("[oc-pow] " + msg);
      } catch (e) {}
    }

    function pushKeep(on) {
      if (keepOn === on) return;
      keepOn = on;
      try {
        oc.setKeepScreenOn(on); // 既有桥：前台校验/超时兜底/落 flag 全在宿主侧
      } catch (e) {}
      var keys = [];
      for (var k in busy) keys.push(k);
      log("keepScreenOn -> " + on + " busy=[" + keys.join(",") + "]");
    }

    function setBusy(id, on) {
      if (!id) id = "__unknown__";
      var had = !!busy[id];
      if (on === had) return;
      if (on) busy[id] = true;
      else delete busy[id];
      var any = false;
      for (var k2 in busy) {
        any = true;
        break;
      }
      pushKeep(any);
    }

    var ROUND_POST_RE = /\/api\/session\/[^/?#]+\/(?:prompt|message|message_async)\/?$/;
    var EVENT_PATH_RE = /\/api\/event\/?$/;

    // 按行解析 SSE 帧（线上格式已验证："data: {json}"，心跳为 ": heartbeat"）。
    function scanEventLines(lines) {
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.indexOf("data:") !== 0) continue;
        var payload = line.substring(5).replace(/^\s+/, "");
        if (
          payload.indexOf("session.execution") < 0 &&
          payload.indexOf("session.status") < 0 &&
          payload.indexOf("session.idle") < 0
        ) continue;
        var evt = null;
        try {
          evt = JSON.parse(payload);
        } catch (e) {
          continue; // 帧被读切两半时 JSON 不完整，等下一行（leftover 机制保证行完整）
        }
        if (!evt || !evt.type) continue;
        var sid = evt.data && evt.data.sessionID;
        var t = evt.type;
        if (t === "session.execution.started") {
          log("round start " + t);
          setBusy(sid, true);
          setBusy("__post__", false); // 真信号接管，撤乐观占位
        } else if (
          t === "session.execution.succeeded" ||
          t === "session.execution.failed" ||
          t === "session.execution.interrupted"
        ) {
          log("round end " + t);
          setBusy(sid, false);
        } else if (t === "session.status") {
          // 兜底：部分后端发 session.status（schema session-status-event.ts:9-32）
          var st = evt.data && evt.data.status && evt.data.status.type;
          if (st === "busy") {
            setBusy(sid, true);
            setBusy("__post__", false);
          } else if (st === "idle") {
            setBusy(sid, false);
          }
        } else if (t === "session.idle") {
          setBusy(sid, false); // 已废弃事件，旧后端兜底
        }
      }
    }

    // 取绝对 URL 的 origin（纯诊断用；失败时退化成 "?"，绝不抛）。
    function originOf(url) {
      try {
        return new URL(url, window.location.href).origin;
      } catch (e) {
        return "?";
      }
    }

    // 给 /api/event 响应体套透明 tee：字节原样透传，顺便扫帧 + 记录开合。
    function teeEventStream(resp, path, absUrl) {
      var body = resp.body;
      if (!body || body.__ocPowed) return;
      var id = ++streamSeq;
      var origin = originOf(absUrl || path);
      var status = resp.status;
      // P7：日志补 origin 与 status。
      // 动机（P6 §B.4 原文）：EVENT_PATH_RE 是**纯 pathname 匹配、不查 status**，
      // 所以页面 origin(4173) 被 LocalUiOriginServer 按红线 3 打成 404 + Connection: close
      // 的空转流，也一样会打出 `event stream OPEN`。P5 据此误判成"4095 真流被重连风暴
      // 截断"，P6 也差点跟着走偏 —— 缺的就是 origin 和 status 这两个字段。
      log("event stream OPEN #" + id + " " + origin + path + " status=" + status);
      if (typeof status === "number" && (status < 200 || status >= 300)) {
        // 单独再打一行非 2xx：一条 grep 即可区分「4173-404 空转」与「4095 真实流」。
        log("event stream NON2XX #" + id + " " + origin + " status=" + status);
      }
      var decoder = new TextDecoder();
      var leftover = "";
      var closed = false;
      function markClosed(reason) {
        if (closed) return;
        closed = true;
        log("event stream CLOSE #" + id + " " + origin + " " + reason);
      }
      var ts = new TransformStream({
        transform: function (chunk, ctl) {
          try {
            var text = decoder.decode(chunk, { stream: true });
            if (text) {
              var buf = leftover + text;
              var lines = buf.split(/\r?\n/);
              leftover = lines.pop(); // 行可能被读切两半，留到下一块拼回
              scanEventLines(lines);
            }
          } catch (e) {}
          ctl.enqueue(chunk);
        },
        flush: function () {
          markClosed("eof");
        },
      });
      var piped;
      try {
        piped = body.pipeThrough(ts);
      } catch (e) {
        return;
      }
      piped.__ocPowed = true;
      // 上游 abort() 走 reader.cancel，flush 不一定跑到 ⇒ 包一层 getReader 记 CLOSE。
      try {
        var origGetReader = piped.getReader.bind(piped);
        piped.getReader = function () {
          var r = origGetReader();
          if (r && typeof r.cancel === "function") {
            var origCancel = r.cancel.bind(r);
            r.cancel = function (reason) {
              markClosed("cancel");
              return origCancel(reason);
            };
          }
          return r;
        };
        Object.defineProperty(resp, "body", { value: piped, configurable: true });
      } catch (e) {}
    }

    window.fetch = function (input, init) {
      var url = "";
      var method = "GET";
      try {
        url = typeof input === "string" ? input : ((input && input.url) || "");
        method = (init && init.method) || (input && input.method) || "GET";
      } catch (e) {}
      var path = "";
      try {
        path = new URL(url, window.location.href).pathname;
      } catch (e) {
        path = url;
      }

      if (/^POST$/i.test(method) && ROUND_POST_RE.test(path)) {
        setBusy("__post__", true); // 乐观：点发送的瞬间 flag 就置起，不等首个事件
        log("round POST " + path);
      }

      return NativeFetch.apply(window, arguments).then(function (resp) {
        try {
          if (resp && EVENT_PATH_RE.test(path)) teeEventStream(resp, path, url);
        } catch (e) {}
        return resp;
      });
    };

    log("installed");
  }
  installRoundPowerSensor();

