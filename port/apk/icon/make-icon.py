#!/usr/bin/env python3
# Builds the adaptive launcher icon's foreground (app/src/main/res/mipmap-*/dd_icon_fg.png) and prints the
# background colour for res/values/dd_icon.xml, from ddp-icon.png (the artwork with its own frame).
# An adaptive icon is a 108 dp canvas of which launchers show only the middle ~72 dp, cut to their shape:
# the art inside the frame is scaled so the whole logo stays in the 66 dp safe circle, and it fades into
# its own edge colours, continued out to the canvas edge. Needs Pillow.
# Usage: make-icon.py [RES_DIR]   (default: ../app/src/main/res next to this script)
import os
import sys
from PIL import Image, ImageChops, ImageFilter, ImageStat

HERE = os.path.dirname(os.path.abspath(__file__))
# in fractions of the (square) source: the logo's centre, the half-size of the square inside the frame,
# and the logo's farthest point from its centre (the bottom corners of the two D's)
CX, CY, HALF, LOGO_R = 0.5048, 0.4984, 0.4406, 0.4513
SAFE_R = 33          # dp: radius every launcher mask keeps visible
FADE = (0.93, 0.99)  # superellipse radius (1 = crop edge) over which the art fades into the fill
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def fade_mask(n):
    # 255 inside FADE[0], 0 beyond FADE[1], on a superellipse (|x|^4 + |y|^4): it follows the square
    # inside the frame but stays clear of the frame's rounded corners
    m = Image.new('L', (n, n))
    px = m.load()
    for y in range(n):
        for x in range(n):
            u, v = (2 * x + 1) / n - 1, (2 * y + 1) / n - 1
            d = (u ** 4 + v ** 4) ** 0.25
            t = min(max((FADE[1] - d) / (FADE[1] - FADE[0]), 0), 1)
            px[x, y] = round(255 * t * t * (3 - 2 * t))
    return m


def edge_fill(art, a, n):
    # the n×n canvas around an a×a art: the art's coarse colours with its edge cells repeated outwards,
    # smoothed, so masks that show past the art see its dark rim continue instead of a blurred copy
    k = 8
    cells = art.resize((k, k), Image.BOX)
    pad = -(-(n * k // a - k) // 2) + 1
    grid = Image.new('RGB', (k + 2 * pad,) * 2)
    for y in range(grid.height):
        for x in range(grid.width):
            grid.putpixel((x, y), cells.getpixel((min(max(x - pad, 0), k - 1), min(max(y - pad, 0), k - 1))))
    size = round(grid.width * a / k)
    big = grid.resize((size, size), Image.BICUBIC).filter(ImageFilter.GaussianBlur(a / k / 2))
    o = (size - n) // 2
    return big.crop((o, o, o + n, o + n))


def main():
    res = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', 'app', 'src', 'main', 'res')
    src = Image.open(os.path.join(HERE, 'ddp-icon.png')).convert('RGB')
    s = src.width
    box = [round(s * v) for v in (CX - HALF, CY - HALF, CX + HALF, CY + HALF)]
    art = src.crop(box)
    art_dp = 2 * HALF * SAFE_R / LOGO_R   # the art's size on the 108 dp canvas
    for name, scale in DENSITIES.items():
        n = round(108 * scale)
        a = round(art_dp * scale)
        out = edge_fill(art, a, n)
        out.paste(art.resize((a, a), Image.LANCZOS), ((n - a) // 2,) * 2, fade_mask(a))
        out.save(os.path.join(res, 'mipmap-' + name, 'dd_icon_fg.png'), optimize=True)
    # the background layer only shows where a launcher draws past the foreground: the art's edge colour
    edge = ImageChops.subtract(Image.new('L', art.size, 255), fade_mask(art.width))
    print('#%02X%02X%02X' % tuple(round(c) for c in ImageStat.Stat(art, edge).mean))


if __name__ == '__main__':
    main()
