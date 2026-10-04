// F3 / N-2 设备探针：在宿主页面里真调 getUserMedia，断言能否拿到摄像头流。
// 用法：adb -s emulator-6050 shell am start ... 之后通过 evaluateJavascript 注入。
// 本文件不参与构建，仅作为 research/env/f3/ 下的取证脚本。
(function () {
  window.__f3cam = { steps: [], err: null };
  function log(s) { window.__f3cam.steps.push(String(s)); }
  try {
    log("secureContext=" + window.isSecureContext);
    log("hasGetUserMedia=" + !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia));
    navigator.mediaDevices.getUserMedia({ video: true, audio: false })
      .then(function (stream) {
        var track = stream.getVideoTracks()[0];
        log("gUM=OK tracks=" + stream.getTracks().length);
        log("track=" + (track ? track.label : "none") + " readyState=" + (track ? track.readyState : "n/a"));
        var v = document.createElement("video");
        v.id = "f3cam";
        v.setAttribute("playsinline", "");
        v.muted = true;
        v.autoplay = true;
        v.style.position = "fixed";
        v.style.left = "0"; v.style.top = "0";
        v.style.width = "320px"; v.style.height = "240px";
        v.style.zIndex = "99999"; v.style.background = "#0f0";
        v.srcObject = stream;
        document.body.appendChild(v);
        return v.play().then(function () { log("play=OK vw=" + v.videoWidth + " vh=" + v.videoHeight); },
                             function (e) { log("play=ERR " + e); });
      })
      .catch(function (e) { window.__f3cam.err = String(e && e.name) + ": " + String(e && e.message); log("gUM=ERR " + window.__f3cam.err); });
  } catch (e) {
    window.__f3cam.err = "sync " + String(e);
  }
  return "probe-injected";
})();
