import json, sys, urllib.request, base64, time

BASE = "http://127.0.0.1:4095/api"
AUTH = base64.b64encode(b"opencode:opencode").decode()


def get(path):
    req = urllib.request.Request(BASE + path, headers={"Authorization": "Basic " + AUTH})
    with urllib.request.urlopen(req, timeout=20) as r:
        d = json.loads(r.read().decode("utf-8"))
    # Every endpoint wraps its payload in {"data": ...}.
    return d["data"] if isinstance(d, dict) and "data" in d else d


def _row(s):
    return (s.get("id"), s.get("title"),
            (s.get("location") or {}).get("directory", ""),
            s.get("time", {}).get("created", 0))


def _fmt(t):
    return time.strftime("%m-%d %H:%M:%S", time.localtime(t / 1000)) if t else "?"


def mine(prefix="E2ECHK5558"):
    """Sessions on the shared backend whose title carries my device prefix.

    Only the first page is scanned: the endpoint answers a bare `/session` with
    the most-recently-updated sessions, and mine are the newest by construction.
    Passing `?cursor=` back the server rejects with HTTP 400, so no pagination.
    """
    rows = get("/session")
    if isinstance(rows, dict):
        rows = rows["data"]
    return [s for s in rows if (s.get("title") or "").startswith(prefix)]


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "list"
    if cmd == "list":
        for s in mine(sys.argv[2] if len(sys.argv) > 2 else "E2ECHK5558"):
            i, t, d, c = _row(s)
            print(i, "|", t, "|", d[-42:], "|", _fmt(c))
    elif cmd == "recent":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 25
        for s in get("/session")[:n]:
            i, t, d, c = _row(s)
            print(i, "|", repr(t), "|", d[-40:], "|", _fmt(c))
    elif cmd == "del":
        for s in mine():
            req = urllib.request.Request(BASE + "/session/" + s["id"], method="DELETE",
                                         headers={"Authorization": "Basic " + AUTH})
            urllib.request.urlopen(req, timeout=20).read()
            print("deleted", s["id"], s.get("title"))
