"""Purist Player: render the launcher icon design (same geometry as res/drawable/ic_launcher_*.xml) to the
legacy mipmap PNGs (API 24-25) and the Android TV banner. Run once after changing the design:
    python -I tools/icon/make_icon_pngs.py [preview.png]
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res'))

BG_TOP, BG_BOTTOM = (0x1E, 0x20, 0x27), (0x0A, 0x0B, 0x0E)
BARS = [(0xF2, 0xF2, 0xF2), (0xF2, 0xE6, 0x00), (0x00, 0xD8, 0xE6), (0x00, 0xD2, 0x3C),
        (0xE6, 0x00, 0xC8), (0xE6, 0x14, 0x1E), (0x14, 0x32, 0xE6)]
# Rounded triangle from ic_launcher_foreground.xml (108-unit viewport), quadratic corners flattened.
TRI = [(37, 31), (37, 77), ('q', (37, 82), (41.32, 79.48)), (80.68, 56.52), ('q', (85, 54), (80.68, 51.48)),
       (41.32, 28.52), ('q', (37, 26), (37, 31))]
SS = 4  # supersampling


def triangle_points(scale, ox=0.0, oy=0.0):
    pts, last = [], None
    for p in TRI:
        if p[0] == 'q':
            c, e = p[1], p[2]
            for i in range(1, 9):
                t = i / 8
                x = (1 - t) ** 2 * last[0] + 2 * (1 - t) * t * c[0] + t * t * e[0]
                y = (1 - t) ** 2 * last[1] + 2 * (1 - t) * t * c[1] + t * t * e[1]
                pts.append(((x - ox) * scale, (y - oy) * scale))
            last = e
        else:
            pts.append(((p[0] - ox) * scale, (p[1] - oy) * scale))
            last = p
    return pts


def gradient(size):
    w, h = size
    img = Image.new('RGB', size)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)))
    return img


def glyph(size_px, view_from=18.0, view_size=72.0):
    """Colour-bar triangle on transparent canvas; the 108-unit viewport window [view_from, view_from+view_size]."""
    s = size_px * SS
    scale = s / view_size
    bars = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(bars)
    x0 = 37.0
    for i, col in enumerate(BARS):
        xa = (x0 + i * 48 / 7 - view_from) * scale
        xb = (x0 + (i + 1) * 48 / 7 - view_from) * scale + 1
        d.rectangle([xa, 0, xb, s], fill=col + (255,))
    mask = Image.new('L', (s, s), 0)
    ImageDraw.Draw(mask).polygon(triangle_points(scale, view_from, view_from), fill=255)
    out = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    out.paste(bars, (0, 0), mask)
    return out.resize((size_px, size_px), Image.LANCZOS)


def legacy_icon(size_px):
    s = size_px * SS
    bg = gradient((s, s)).convert('RGBA')
    mask = Image.new('L', (s, s), 0)
    inset = round(s * 0.04)
    ImageDraw.Draw(mask).rounded_rectangle([inset, inset, s - inset, s - inset], radius=round(s * 0.22), fill=255)
    icon = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    icon.paste(bg, (0, 0), mask)
    icon = icon.resize((size_px, size_px), Image.LANCZOS)
    icon.alpha_composite(glyph(size_px))
    return icon


def banner():
    w, h = 320, 180
    img = gradient((w * SS, h * SS)).resize((w, h), Image.LANCZOS).convert('RGBA')
    g = glyph(140, view_from=24, view_size=60)
    img.alpha_composite(g, (4, 20))
    try:
        font = ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf', 34)
    except OSError:
        font = ImageFont.load_default()
    d = ImageDraw.Draw(img)
    d.text((168, 52), 'Purist', font=font, fill=(255, 255, 255, 255))
    d.text((168, 92), 'Player', font=font, fill=(255, 255, 255, 255))
    return img


def main():
    sizes = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for dens, px in sizes.items():
        path = os.path.join(RES, 'mipmap-' + dens, 'ic_launcher.png')
        legacy_icon(px).save(path, optimize=True)
        print('wrote', path)
    path = os.path.join(RES, 'mipmap-xhdpi', 'banner.png')
    banner().save(path, optimize=True)
    print('wrote', path)

    if len(sys.argv) > 1:  # preview: adaptive icon under squircle and circle masks, plus the banner
        n = 384
        layer = gradient((n * 108 // 72, n * 108 // 72)).convert('RGBA')
        layer.alpha_composite(glyph(n * 108 // 72, view_from=0, view_size=108))
        visible = layer.crop((n // 4, n // 4, n // 4 + n, n // 4 + n))
        sq = Image.new('L', (n, n), 0)
        ImageDraw.Draw(sq).rounded_rectangle([0, 0, n, n], radius=round(n * 0.3), fill=255)
        ci = Image.new('L', (n, n), 0)
        ImageDraw.Draw(ci).ellipse([0, 0, n, n], fill=255)
        prev = Image.new('RGBA', (n * 2 + 400, n + 40), (235, 235, 235, 255))
        a = Image.new('RGBA', (n, n), (0, 0, 0, 0)); a.paste(visible, (0, 0), sq)
        b = Image.new('RGBA', (n, n), (0, 0, 0, 0)); b.paste(visible, (0, 0), ci)
        prev.alpha_composite(a, (10, 20)); prev.alpha_composite(b, (n + 20, 20))
        prev.alpha_composite(banner().resize((360, 202)), (2 * n + 30, 20 + (n - 202) // 2))
        prev.convert('RGB').save(sys.argv[1])
        print('preview', sys.argv[1])


if __name__ == '__main__':
    main()
