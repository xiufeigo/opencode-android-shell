"""逐帧量"字高高"与"行数"，客观判定键盘切换过程中终端是否被缩放/拉伸。

指标：
- glyph: 文字连续行块的中位高度（device px）—— 字形被纵向拉伸会等比变大
- runs : 文字行块数量
- bright: 区域平均亮度（判断键盘是否已经进入画面）
"""

import glob
import os
import statistics
import sys

from PIL import Image

GLOB = sys.argv[1]
X0, X1 = 80, 900
Y0, Y1 = 430, 1460


def analyse(img):
    px = img.convert("L").crop((X0, Y0, X1, Y1))
    w, h = px.size
    data = list(px.getdata())
    # 背景亮度取整幅的众数（深浅色主题都适用），文字像素 = 与背景差 > 60 的像素
    hist = {}
    for v in data:
        b = v // 8
        hist[b] = hist.get(b, 0) + 1
    bg = max(hist, key=hist.get) * 8 + 4
    prof = []
    for y in range(h):
        row = data[y * w : (y + 1) * w]
        prof.append(sum(1 for v in row if abs(v - bg) > 60))
    peak = max(prof) if prof else 0
    thr = peak * 0.12
    runs, cur = [], 0
    for v in prof:
        if v > thr:
            cur += 1
        elif cur:
            runs.append(cur)
            cur = 0
    if cur:
        runs.append(cur)
    bright = sum(prof) / h
    return (statistics.median(runs) if runs else 0, len(runs), round(bright, 1))


files = sorted(glob.glob(GLOB))
print(f"frames={len(files)}  (指标: glyph=中位字高px, runs=文字行块数, bright=文字像素/行)")
prev = None
for f in files:
    g, n, b = analyse(Image.open(f))
    mark = ""
    if prev and (abs(g - prev[0]) >= 3 or abs(n - prev[1]) >= 4):
        mark = f"   <== 跳变 glyph {prev[0]}->{g}, runs {prev[1]}->{n}"
    prev = (g, n)
    print(f"{os.path.basename(f)}  glyph={g:>3}  runs={n:>3}  bright={b:>6}{mark}")
