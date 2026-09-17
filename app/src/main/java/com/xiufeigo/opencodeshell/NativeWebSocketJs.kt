package com.xiufeigo.opencodeshell

/**
 * Patches `window.WebSocket` so loopback PTY sockets go through
 * [OpenCodeAndroidBridge] / OkHttp instead of Chromium's WebSocket stack.
 *
 * `https://appassets.androidplatform.net` → `ws://127.0.0.1` is mixed content
 * on older System WebView builds even with MIXED_CONTENT_ALWAYS_ALLOW.
 * Native OkHttp is not subject to that check; LocalGateway still tunnels.
 */
object NativeWebSocketJs {
    const val INSTALL = """
            (function () {
              if (window.__ocNativeWS) return;
              var Native = window.WebSocket;
              if (!Native) return;
              var sockets = {};
              function loopback(url) {
                try {
                  var u = new URL(String(url), location.href);
                  var host = (u.hostname || '').replace(/^\[|\]$/g, '');
                  var scheme = (u.protocol || '').toLowerCase();
                  if (scheme !== 'ws:' && scheme !== 'wss:') return false;
                  return host === '127.0.0.1' || host === 'localhost' || host === '::1';
                } catch (e) {
                  return /^(ws|wss):\/\/(127\.0\.0\.1|localhost|\[::1\])/i.test(String(url));
                }
              }
              function canNative() {
                try {
                  return !!(window.OpenCodeAndroid && typeof window.OpenCodeAndroid.wsOpen === 'function');
                } catch (e) {
                  return false;
                }
              }
              function b64encode(buf) {
                var u8 = buf instanceof ArrayBuffer ? new Uint8Array(buf)
                  : (ArrayBuffer.isView(buf) ? new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength)
                    : new Uint8Array(0));
                var chunk = 0x8000;
                var s = '';
                for (var i = 0; i < u8.length; i += chunk) {
                  s += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
                }
                return btoa(s);
              }
              function b64decode(b64) {
                var s = atob(b64 || '');
                var u8 = new Uint8Array(s.length);
                for (var i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
                return u8.buffer;
              }
              function fire(target, type, init) {
                var ev;
                try {
                  if (type === 'message') ev = new MessageEvent(type, init || {});
                  else if (type === 'close') ev = new CloseEvent(type, init || {});
                  else ev = new Event(type);
                } catch (e) {
                  ev = { type: type };
                  if (init) {
                    for (var k in init) ev[k] = init[k];
                  }
                }
                try { target.dispatchEvent(ev); } catch (e2) {}
                var handler = target['on' + type];
                if (typeof handler === 'function') {
                  try { handler.call(target, ev); } catch (e3) {}
                }
              }
              function OcWebSocket(url, protocols) {
                if (!(this instanceof OcWebSocket)) return new OcWebSocket(url, protocols);
                this.url = String(url);
                this.protocol = Array.isArray(protocols) ? (protocols[0] || '') : (protocols || '');
                this.extensions = '';
                this.binaryType = 'arraybuffer';
                this.bufferedAmount = 0;
                this.readyState = OcWebSocket.CONNECTING;
                this.onopen = null;
                this.onmessage = null;
                this.onerror = null;
                this.onclose = null;
                this._listeners = { open: [], message: [], error: [], close: [] };
                if (!loopback(this.url) || !canNative()) {
                  return new Native(url, protocols);
                }
                this._id = 'ocws_' + Math.random().toString(36).slice(2) + '_' + Date.now();
                sockets[this._id] = this;
                try {
                  window.OpenCodeAndroid.wsOpen(this._id, this.url);
                } catch (err) {
                  this.readyState = OcWebSocket.CLOSED;
                  fire(this, 'error', {});
                  fire(this, 'close', { code: 1011, reason: String(err && err.message || err), wasClean: false });
                }
              }
              OcWebSocket.CONNECTING = 0;
              OcWebSocket.OPEN = 1;
              OcWebSocket.CLOSING = 2;
              OcWebSocket.CLOSED = 3;
              OcWebSocket.prototype.addEventListener = function (type, fn) {
                if (!fn || !this._listeners[type]) return;
                this._listeners[type].push(fn);
              };
              OcWebSocket.prototype.removeEventListener = function (type, fn) {
                var list = this._listeners[type];
                if (!list) return;
                this._listeners[type] = list.filter(function (x) { return x !== fn; });
              };
              OcWebSocket.prototype.dispatchEvent = function (ev) {
                var list = (this._listeners[(ev && ev.type) || ''] || []).slice();
                for (var i = 0; i < list.length; i++) {
                  try { list[i].call(this, ev); } catch (e) {}
                }
                return true;
              };
              OcWebSocket.prototype.send = function (data) {
                if (this.readyState !== OcWebSocket.OPEN) {
                  throw new Error('InvalidStateError');
                }
                try {
                  if (typeof data === 'string') {
                    window.OpenCodeAndroid.wsSendText(this._id, data);
                  } else {
                    window.OpenCodeAndroid.wsSendBinary(this._id, b64encode(data));
                  }
                } catch (e) {
                  fire(this, 'error', {});
                }
              };
              OcWebSocket.prototype.close = function (code, reason) {
                if (this.readyState === OcWebSocket.CLOSING || this.readyState === OcWebSocket.CLOSED) return;
                this.readyState = OcWebSocket.CLOSING;
                try {
                  window.OpenCodeAndroid.wsClose(this._id, code || 1000, reason || '');
                } catch (e) {}
              };
              window.__ocNativeWS = {
                dispatch: function (msg) {
                  if (!msg || !msg.id) return;
                  var sock = sockets[msg.id];
                  if (!sock) return;
                  if (msg.type === 'open') {
                    sock.readyState = OcWebSocket.OPEN;
                    fire(sock, 'open', {});
                    return;
                  }
                  if (msg.type === 'message') {
                    var payload = msg.binary ? b64decode(msg.data) : msg.data;
                    fire(sock, 'message', { data: payload, origin: sock.url });
                    return;
                  }
                  if (msg.type === 'error') {
                    fire(sock, 'error', {});
                    return;
                  }
                  if (msg.type === 'close') {
                    sock.readyState = OcWebSocket.CLOSED;
                    fire(sock, 'close', {
                      code: msg.code || 1006,
                      reason: msg.reason || '',
                      wasClean: !!msg.wasClean,
                    });
                    delete sockets[msg.id];
                  }
                }
              };
              OcWebSocket.OPEN = 1;
              window.WebSocket = OcWebSocket;
            })();
        """
}
