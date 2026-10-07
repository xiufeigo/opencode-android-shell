import sys, io, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
p = r"webapp\src\main\assets\www\index.html"
raw = open(p, "rb").read()
lines = raw.split(b"\n")
for n in (int(x) for x in sys.argv[1:]):
    b = lines[n - 1]
    print(f"--- line {n} ({len(b)} bytes) ---")
    # print as a compact hex + ascii rendering
    s = b.decode("utf-8", errors="replace")
    out = []
    i = 0
    while i < len(b):
        c = b[i]
        if c < 0x80:
            out.append(chr(c)); i += 1
            continue
        # try to find the length of the (possibly broken) sequence
        nxt = b[i + 1] if i + 1 < len(b) else 0
        if 0xC2 <= c <= 0xDF: L = 2
        elif 0xE0 <= c <= 0xEF: L = 3
        elif 0xF0 <= c <= 0xF4: L = 4
        else: L = 1
        valid = all(0x80 <= b[i + k] <= 0xBF for k in range(1, L) if i + k < len(b)) and i + L <= len(b)
        hexs = " ".join(f"{b[i+k]:02X}" for k in range(L) if i + k < len(b))
        ch = b[i:i + L].decode("utf-8", errors="replace")
        out.append(f"[{hexs}]{ch}" + ("" if valid else "!"))
        i += L
    print("".join(out))
