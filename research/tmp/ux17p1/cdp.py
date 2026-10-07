#!/usr/bin/env python3
"""Minimal CDP client for UX16-P3 smoke verification (read-mostly)."""
import json, sys, urllib.request, time, os

def ws_url():
    with urllib.request.urlopen('http://127.0.0.1:6817/json/list', timeout=8) as r:
        targets = json.load(r)
    for t in targets:
        if t.get('type') == 'page' and t.get('webSocketDebuggerUrl'):
            return t['webSocketDebuggerUrl'], t
    raise SystemExit('no page target')

def ws_connect(url):
    import socket, base64, os, struct
    # strip ws://
    rest = url.split('://',1)[1]
    hostport, path = rest.split('/',1)
    path = '/' + path
    host, port = hostport.split(':')
    s = socket.create_connection((host, int(port)), timeout=30)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (f"GET {path} HTTP/1.1\r\nHost: {hostport}\r\nUpgrade: websocket\r\n"
           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
    s.sendall(req.encode())
    buf = b''
    while b'\r\n\r\n' not in buf:
        chunk = s.recv(4096)
        if not chunk: raise SystemExit('ws handshake closed')
        buf += chunk
    if b'101' not in buf.split(b'\r\n')[0]:
        raise SystemExit('ws handshake failed: ' + buf.split(b'\r\n')[0].decode(errors='replace'))
    return s

class WS:
    def __init__(self, sock):
        self.s = sock; self.buf = b''
    def send(self, obj):
        data = json.dumps(obj).encode()
        hdr = bytearray([0x81])
        n = len(data)
        mask = os.urandom(4)
        if n < 126: hdr.append(0x80 | n)
        elif n < 65536: hdr.append(0x80 | 126); hdr += n.to_bytes(2,'big')
        else: hdr.append(0x80 | 127); hdr += n.to_bytes(8,'big')
        hdr += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.s.sendall(bytes(hdr) + masked)
    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.s.recv(65536)
            if not chunk: raise SystemExit('ws closed')
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out
    def recv(self):
        while True:
            b0, b1 = self._read(2)
            opcode = b0 & 0x0f
            ln = b1 & 0x7f
            if ln == 126: ln = int.from_bytes(self._read(2),'big')
            elif ln == 127: ln = int.from_bytes(self._read(8),'big')
            payload = self._read(ln)
            if opcode == 1:
                return json.loads(payload)
            if opcode == 8:
                raise SystemExit('ws closed by peer')

def evaluate(js, timeout=25):
    url, tgt = ws_url()
    s = WS(ws_connect(url))
    s.send({"id":1,"method":"Runtime.evaluate","params":{
        "expression": js, "returnByValue": True, "awaitPromise": True}})
    deadline = time.time() + timeout
    while time.time() < deadline:
        msg = s.recv()
        if msg.get('id') == 1:
            s.s.close()
            r = msg.get('result', {})
            if 'exceptionDetails' in r:
                return {'__error': json.dumps(r['exceptionDetails'])[:600]}
            return r.get('result', {}).get('value')
    s.s.close()
    return {'__error': 'timeout'}

def raw(js, timeout=25):
    """Evaluate JS; objects come back as JSON strings, Promises are awaited.

    Keeps the original contract (JSON.stringify'd object) while still letting
    the expression itself return a Promise (awaitPromise=True handles it).
    """
    marker = "__RAW__"
    wrapped = (
        "Promise.resolve((function(){" + js + "})()).then(function(v){"
        "if(v && typeof v.then === 'function'){ return v.then(function(x){return JSON.stringify(x);}); }"
        "return JSON.stringify(v); })"
    )
    out = evaluate(wrapped, timeout)
    return out

if __name__ == '__main__':
    js = sys.stdin.read()
    out = evaluate(js)
    print(json.dumps(out, ensure_ascii=False, indent=1) if not isinstance(out, str) else out)