#!/usr/bin/env python3
"""Test 1: push 600k tokens through E2ECHK-compaction-test session."""
import json
import sys
import time
import urllib.request
import urllib.error
import base64

BASE = "http://127.0.0.1:4095"
AUTH_HEADER = "Basic " + base64.b64encode(b"opencode:opencode").decode()
SESSION = sys.argv[1] if len(sys.argv) > 1 else "ses_f2182ac47ffe1IVJqMIzI0yWQu"


def req(path, method="GET", body=None):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode() if body else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Authorization", AUTH_HEADER)
    r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=180) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


# Session state
code, body = req(f"/api/session/{SESSION}")
print(f"[{code}] session: {body[:200]}")
sess = json.loads(body)['data']
print(f"  title={sess.get('title')} tokens={sess.get('tokens')}")

# Generate chunks. ~200k chars ~= ~50k tokens. Send 16 chunks = ~800k tokens.
chunk_size = 200_000
n_chunks = int(sys.argv[2]) if len(sys.argv) > 2 else 16

base_text = (
    "The quick brown fox jumps over the lazy dog. " * 100 +
    "All work and no play makes Jack a dull boy. " * 100 +
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 100 +
    "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " * 100 +
    "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris. " * 100 +
    "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum. " * 100 +
    "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia. " * 100
)

print(f"chunk size: {chunk_size} chars, n_chunks: {n_chunks}")
print(f"total chars: ~{chunk_size * n_chunks / 1_000_000:.1f}M, ~tokens: {chunk_size * n_chunks // 4 / 1000:.0f}k")

for i in range(n_chunks):
    text = (f"[Chunk {i+1}/{n_chunks}] " * 10) + (base_text * ((chunk_size // len(base_text)) + 1))[:chunk_size]
    text += f"\n\n(End of chunk {i+1}) Reply with single word ACK only."

    code, body_text = req(f"/api/session/{SESSION}/prompt", "POST", {"text": text, "delivery": "queue"})
    print(f"chunk {i+1}: http={code} reply={body_text[:120]!r}")
    if code not in (200, 204):
        print(f"  ERROR: {body_text[:500]}")
        break

print("done sending")
