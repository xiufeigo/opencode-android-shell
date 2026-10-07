"""逐帧量终端里的行距/字高，用来客观判定"键盘开关时字体是否被缩放"。

做法：在终端区域内取一列（或几列）像素，按行求"有字像素"的亮度，得到行剖面，
再对剖面做自相关求主周期 = 文本行距（device px）。行距突变 = 字形被缩放/被拉伸。
"""

import glob
import os
import sys

from PIL import Image

FRAME_GLOB = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ["TEMP"], "imeframes", "f*.png")
# 终端内容区（device px）：避开标签栏与键盘，取画布中部一条竖带
X0, X1 = 60, 700  # 横向取样范围（有文字的列）
Y0, Y1 = 430, 1450  # 纵向取样范围（终端内容）


def row_profile(img):
    """每行"文字像素占比"（暗色主题下用亮度方差近似）。"""
    px = img.convert("L").crop((X0, Y0, X1, Y1))
    w, h = px.size
    data = list(px.getdata())
    prof = []
    for y in range(h):
        row = data[y * w : (y + 1) * w]
        # 文字像素 = 偏离背景（背景是众数）较远的像素
        prof.append(sum(1 for v in row if v > 90))
    return prof


def pitch(prof):
    """自相关主周期（= 行距，device px）。"""
    n = len(prof)
    mean = sum(prof) / n
    sig = [v - mean for v in prof]
    best, best_lag = 0.0, 0
    for lag in range(12, 80):
        s = sum(sig[i] * sig[i + lag] for i in range(n - lag))
        if s > best:
            best, best_lag = s, lag
    return best_lag


def text_rows(prof, thresh):
    """粗略数一下有文字的连续行块数量（看内容是否在变）。"""
    cnt, inrun = 0, False
    for v in prof:
        if v > thresh and not inrun:
            cnt += 1
            inrun = True
        elif v <= thresh:
            inrun = False
    return cnt


files = sorted(glob.glob(FRAME_GLOB))
print(f"frames={len(files)}")
prev = None
for f in files:
    img = Image.open(f)
    prof = row_profile(img)
    thr = max(prof) * 0.15 if max(prof) else 0
    p = pitch(prof)
    rows = text_rows(prof, thr)
    marker = ""
    if prev is not None and abs(p - prev) >= 3:
        marker = f"  <== 行距跳变 {prev} -> {p}"
    prev = p
    print(f"{os.path.basename(f)}  size={img.size}  pitch={p:>3}px  文字行块={rows:>3}{marker}")
