  // ======================================================================
  // N5 / upstream notification policy bridge.
  //
  // Upstream delivers system notifications through the browser Notification
  // API. Android WebView does not implement it, so upstream's web platform
  // takes the early return at
  //   origin/v2 packages/app/src/runtime/platform/web.ts:22
  //     if (!("Notification" in window)) return
  // and NO turn-complete / error notification is ever raised in this host.
  //
  // This shim defines window.Notification so that upstream's own policy
  // executes verbatim in the page -- we do NOT re-implement it here:
  //   - web.ts:24-28      permission gate   -> hasNotificationPermission()
  //   - web.ts:29         visible+focused   -> not raised (in-page)
  //   - coordinator.ts:62 hasFocus()        -> not raised (in-page)
  //   - coordinator.ts:73-93 claim()        -> per-eventID dedup (in-page)
  //   - notification.tsx:227,240 hasOpenTab  -> only sessions with an open
  //                          tab notify (in-page)
  //   - notification.tsx:228,229,256,257     sound/notification user switches
  //
  // The host therefore only DELIVERS. Its own NotifierHost.postTurnFinished
  // stays a pure delivery function with no dedup / mute / focus policy of
  // its own -- the policy lives in one place, upstream's.
  //
  // sessionId: upstream's notify(title, description, onClick) carries no
  // session id, but the notification is only raised while that session has an
  // open tab (notification.tsx:240), so the current route is the right target.
  // Route shape is upstream's: /server/<serverKey>/session/<id>
  //   (shell/routes/routes.tsx:39-40, shell/routes/session.ts:5-7)
  // ======================================================================
  function currentSessionId() {
    try {
      var m = /\/server\/[^/]+\/session\/([^/?#]+)/.exec(window.location.pathname || "");
      return m ? decodeURIComponent(m[1]) : "";
    } catch (e) {
      return "";
    }
  }

  if (!("Notification" in window)) {
    var ShimNotification = function (title, options) {
      var opts = options || {};
      var sid = currentSessionId();
      var closed = false;
      try {
        oc.postMessage(String(title == null ? "" : title), String(opts.body == null ? "" : opts.body), sid);
      } catch (e) {}
      this.onclick = null;
      this.close = function () {
        closed = true;
      };
      this.__isClosed = function () {
        return closed;
      };
    };
    ShimNotification.permission = "default";
    ShimNotification.requestPermission = function () {
      var has = oc.hasNotificationPermission();
      // P9 root-cause fix: write the host truth back into the static property.
      // Previously only the resolved promise value changed; ShimNotification.permission
      // stayed "default" forever. If upstream reads Notification.permission first
      // (calling requestPermission only when it reads "default"), then after the
      // permission dialog was accepted the property is still "default" => never notify.
      ShimNotification.permission = has === 1 ? "granted" : (has < 0 ? "denied" : "default");
      if (has === 1) return Promise.resolve("granted");
      if (has < 0) return Promise.resolve("denied");
      oc.requestNotificationPermission();
      // Web-side cannot observe the async Android grant round-trip; report
      // "default" so upstream asks again on the next turn instead of
      // assuming either outcome.
      return Promise.resolve("default");
    };
    try {
      window.Notification = ShimNotification;
    } catch (e) {}

    // P9 · Host push entry for the permission result.
    // The Android side cannot observe the async grant round-trip from JS, so
    // WebHostActivity's POST_NOTIFICATIONS launcher callback dispatches this
    // custom event via evaluateJavascript:
    //   window.dispatchEvent(new CustomEvent('oc:notifyPerm', {detail:'granted'|'denied'}))
    // Listen for it and refresh the static property, so a gate that reads
    // Notification.permission (instead of calling requestPermission) stops
    // seeing a stale "default" after the user accepted the system dialog.
    try {
      window.addEventListener("oc:notifyPerm", function (ev) {
        var detail = ev && ev.detail;
        if (detail === "granted" || detail === "denied") {
          ShimNotification.permission = detail;
        }
      });
    } catch (e) {}
  }

