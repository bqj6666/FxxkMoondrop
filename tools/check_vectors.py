#!/usr/bin/env python3
"""矢量图标几何自检（无需构建、无需设备）。

用途：新增或替换 drawable 矢量图标后跑一次，抓「字形本身尺寸/重心不对」这类
编译器抓不到、只在屏幕上表现为「图标很小/偏心/糊成一团」的问题。

背景：曾从 Material 误取到旧版小尺寸字形，ic_sensors 在 24dp 画布内只占
7.2x12.9、重心偏到 x=11.3 —— 看代码完全正常，只有算几何才暴露。

判定：把每个 <vector> 的路径点归一化到 24x24 画布，计入 <group> 的
scale/translate/pivot、strokeWidth 外扩与圆弧外接框，得到有效包围盒；
宽或高 < 13、或中心偏离画布中心 > 3 即列为可疑。

注意：窄长字形（电池、蓝牙、闪电、</>、沙漏）是正常设计，也会被列出，
需人工判断，不要一律当 bug 改。

用法：python3 tools/check_vectors.py [drawable 目录]
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
ARITY = {'m': 2, 'l': 2, 'h': 1, 'v': 1, 'c': 6, 's': 4,
         'q': 4, 't': 2, 'a': 7, 'z': 0}


def segments(path_data):
    """解析 pathData，返回 (点列表, 圆弧外接框列表)；相对坐标按命令逐一累加。"""
    toks = re.findall(r'([MmLlHhVvCcSsQqTtAaZz])|(-?\d*\.?\d+(?:e-?\d+)?)', path_data)
    seq = [(t, None) if t else (None, float(n)) for t, n in toks]
    pts, arcs = [], []
    cx = cy = sx = sy = 0.0
    cmd, buf = None, []

    def flush(c0, b):
        nonlocal cx, cy, sx, sy
        rel = c0.islower()
        c = c0.lower()
        a = ARITY.get(c, 0)
        if a == 0:
            cx, cy = sx, sy
            return
        for k in range(0, len(b) - a + 1, a):
            g = b[k:k + a]
            if c == 'm':
                cx, cy = (cx + g[0], cy + g[1]) if rel else (g[0], g[1])
                sx, sy = cx, cy
                pts.append((cx, cy))
                c = 'l'
            elif c == 'l':
                cx, cy = (cx + g[0], cy + g[1]) if rel else (g[0], g[1])
                pts.append((cx, cy))
            elif c == 'h':
                cx = cx + g[0] if rel else g[0]
                pts.append((cx, cy))
            elif c == 'v':
                cy = cy + g[0] if rel else g[0]
                pts.append((cx, cy))
            elif c in ('c', 's', 'q', 't'):
                for j in range(0, a, 2):
                    pts.append((cx + g[j], cy + g[j + 1]) if rel else (g[j], g[j + 1]))
                cx, cy = pts[-1]
            elif c == 'a':
                rx, ry, ex, ey = g[0], g[1], g[5], g[6]
                nx, ny = (cx + ex, cy + ey) if rel else (ex, ey)
                arcs.append((nx, ny, abs(rx), abs(ry)))
                pts.append((nx, ny))
                cx, cy = nx, ny

    for t, n in seq:
        if t is not None:
            if buf and cmd:
                flush(cmd, buf)
            buf, cmd = [], t
        else:
            buf.append(n)
    if buf and cmd:
        flush(cmd, buf)
    return pts, arcs


def walk(node, tr):
    """展开 <group>，累积 (scaleX, scaleY, translateX, translateY)。"""
    out = []
    for ch in node:
        tag = ch.tag.split('}')[-1]
        if tag == 'group':
            sx = float(ch.get(A + 'scaleX', 1))
            sy = float(ch.get(A + 'scaleY', 1))
            tx = float(ch.get(A + 'translateX', 0))
            ty = float(ch.get(A + 'translateY', 0))
            px = float(ch.get(A + 'pivotX', 0))
            py = float(ch.get(A + 'pivotY', 0))
            out += walk(ch, (tr[0] * sx, tr[1] * sy,
                             tr[0] * (tx + px - px * sx) + tr[2],
                             tr[1] * (ty + py - py * sy) + tr[3]))
        elif tag == 'path':
            out.append((ch.get(A + 'pathData'), tr,
                        float(ch.get(A + 'strokeWidth', 0))))
    return out


def measure(path):
    """返回归一化到 24x24 的 (宽, 高, 中心x, 中心y)；非矢量图返回 None。"""
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return None
    if root.tag.split('}')[-1] != 'vector':
        return None
    vw = float(root.get(A + 'viewportWidth', 24))
    vh = float(root.get(A + 'viewportHeight', 24))
    xs, ys = [], []
    for pd, tr, sw in walk(root, (1, 1, 0, 0)):
        if not pd:
            continue
        pts, arcs = segments(pd)
        h = sw / 2.0
        for x, y in pts:
            x, y = x * tr[0] + tr[2], y * tr[1] + tr[3]
            xs += [x - h * abs(tr[0]), x + h * abs(tr[0])]
            ys += [y - h * abs(tr[1]), y + h * abs(tr[1])]
        for ax, ay, rx, ry in arcs:
            for sxn in (-1, 1):
                for syn in (-1, 1):
                    xs.append((ax + sxn * rx) * tr[0] + tr[2])
                    ys.append((ay + syn * ry) * tr[1] + tr[3])
    if not xs:
        return None
    xs = [v / vw * 24 for v in xs]
    ys = [v / vh * 24 for v in ys]
    return (max(xs) - min(xs), max(ys) - min(ys),
            (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2)


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/drawable"
    rows = []
    for f in sorted(glob.glob(os.path.join(d, "*.xml"))):
        m = measure(f)
        if m:
            rows.append((os.path.basename(f),) + m)
    flagged = [r for r in rows
               if r[1] < 13 or r[2] < 13 or abs(r[3] - 12) > 3 or abs(r[4] - 12) > 3]
    print("checked=%d  flagged=%d" % (len(rows), len(flagged)))
    for n, w, h, cx, cy in flagged:
        print("  %-34s w=%-5.1f h=%-5.1f center=(%.1f,%.1f)" % (n, w, h, cx, cy))
    print("提示：窄长字形（电池/蓝牙/闪电/</>/沙漏）属正常设计，需人工判断。")
    return 0


if __name__ == '__main__':
    sys.exit(main())
