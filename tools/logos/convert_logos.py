"""Purist: convert the user's format logos (black artwork on white) into white-on-transparent badge PNGs.

Run once after changing a source logo, then commit the outputs:
    python -I tools/logos/convert_logos.py

Steps per logo: colour-to-alpha against white (keeps anti-aliasing), neutral (grey/black) pixels -> white,
coloured pixels (HDR10+ stripes) keep their colour, trim, scale to a common height, add a soft shadow so the
white logo stays legible over bright scenes.
"""
import os

from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'src')
OUT = os.path.normpath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res', 'drawable-nodpi'))

LOGOS = {
    'badge_dv.png': 'DVLogo.png',
    'badge_hdr10plus.png': 'HDR10PlusLogo.jpg',
    'badge_hdr10.png': 'HDR10Logo.jpg',
    'badge_hdr.png': 'HDRLogo.jpg',
    'badge_sdr.png': 'SDRLogo.jpg',
}

TARGET_HEIGHT = 240      # px of the logo artwork (displayed at ~28-44 dp, so this is >= 2x on xxhdpi)
NEUTRAL_SATURATION = 60  # max-min channel spread below which a pixel counts as grey/black
ALPHA_FLOOR = 14         # JPEG noise near the white background -> fully transparent

# Per-logo tuning. HDR10's source text is a light-grey-to-black gradient and its frame is brownish:
# treat the frame as neutral (-> white) and lift the pale grey so the whole word reads solid.
TUNING = {
    'badge_hdr10.png': {'neutral': 140, 'alpha_gamma': 0.45},
}


def to_white_on_transparent(img, neutral=NEUTRAL_SATURATION, alpha_gamma=1.0):
    img = img.convert('RGB')
    w, h = img.size
    src = img.load()
    out = Image.new('RGBA', (w, h))
    dst = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = src[x, y]
            a = 255 - min(r, g, b)
            if a < ALPHA_FLOOR:
                dst[x, y] = (255, 255, 255, 0)
                continue
            af = a / 255.0
            # un-premultiply against white: c = af*c' + (1-af)*255
            cr = max(0, min(255, round((r - 255 * (1 - af)) / af)))
            cg = max(0, min(255, round((g - 255 * (1 - af)) / af)))
            cb = max(0, min(255, round((b - 255 * (1 - af)) / af)))
            if max(cr, cg, cb) - min(cr, cg, cb) < neutral:
                cr = cg = cb = 255
                if alpha_gamma != 1.0:
                    a = round(255 * (a / 255.0) ** alpha_gamma)
            dst[x, y] = (cr, cg, cb, a)
    return out


def trim(img):
    bbox = img.getchannel('A').point(lambda v: 255 if v > 16 else 0).getbbox()
    return img.crop(bbox) if bbox else img


def add_shadow(img):
    w, h = img.size
    pad = max(4, round(h * 0.06))
    canvas = Image.new('RGBA', (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    alpha = Image.new('L', canvas.size, 0)
    alpha.paste(img.getchannel('A'), (pad, pad + max(1, pad // 4)))
    alpha = alpha.filter(ImageFilter.GaussianBlur(pad / 2)).point(lambda v: round(v * 0.6))
    shadow = Image.new('RGBA', canvas.size, (0, 0, 0, 255))
    shadow.putalpha(alpha)
    canvas = Image.alpha_composite(canvas, shadow)
    canvas.alpha_composite(img, (pad, pad))
    return canvas


def main():
    os.makedirs(OUT, exist_ok=True)
    for out_name, src_name in LOGOS.items():
        img = Image.open(os.path.join(SRC, src_name))
        img = trim(to_white_on_transparent(img, **TUNING.get(out_name, {})))
        scale = TARGET_HEIGHT / img.height
        img = img.resize((max(1, round(img.width * scale)), TARGET_HEIGHT), Image.LANCZOS)
        img = add_shadow(img)
        path = os.path.join(OUT, out_name)
        img.save(path, optimize=True)
        print('%-22s %4dx%-4d %6d bytes' % (out_name, img.width, img.height, os.path.getsize(path)))


if __name__ == '__main__':
    main()
