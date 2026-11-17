"""Build launcher icons from a full-bleed illustration.

usage: python -I make_icons.py <source.png> <app/src/main/res> <preview.png>

Adaptive icon (API 26+): the picture is the background layer. Launchers show only the
central 72 of the 108 dp canvas, so the picture is scaled to cover a little more than that
and the outer margin (seen only during parallax effects) is filled with a blurred copy.
Legacy icons (API 21-25): rounded-square and round PNGs.
"""
import sys
from PIL import Image, ImageDraw, ImageFilter

src_path, res_dir, preview_path = sys.argv[1], sys.argv[2], sys.argv[3]
src = Image.open(src_path).convert("RGB")
w, h = src.size

# Square crop centred on the face (slightly left of and above the picture's centre).
side = min(w, h)
cx, cy = int(w * 0.49), int(h * 0.5)
left = max(0, min(w - side, cx - side // 2))
top = max(0, min(h - side, cy - side // 2))
square = src.crop((left, top, left + side, top + side))

VISIBLE = 80 / 108  # picture covers 80 dp of the 108 dp canvas; the mask shows 72 dp of it


def adaptive_background(px):
    canvas = square.resize((px, px), Image.LANCZOS).filter(ImageFilter.GaussianBlur(px / 14))
    inner = round(px * VISIBLE)
    picture = square.resize((inner, inner), Image.LANCZOS)
    offset = (px - inner) // 2
    # Feather the picture's edge into the blurred margin so no hard frame can ever show.
    mask = Image.new("L", (inner, inner), 0)
    feather = max(2, inner // 24)
    ImageDraw.Draw(mask).rectangle((feather, feather, inner - feather, inner - feather), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    canvas.paste(picture, (offset, offset), mask)
    return canvas


def rounded(px, radius_ratio):
    scale = 4  # supersample for a smooth edge
    big = square.resize((px * scale, px * scale), Image.LANCZOS)
    mask = Image.new("L", (px * scale, px * scale), 0)
    r = int(px * scale * radius_ratio)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, px * scale - 1, px * scale - 1), radius=r, fill=255)
    out = Image.new("RGBA", (px * scale, px * scale), (0, 0, 0, 0))
    out.paste(big, (0, 0), mask)
    return out.resize((px, px), Image.LANCZOS)


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
import os

for name, factor in DENSITIES.items():
    folder = os.path.join(res_dir, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    adaptive_background(round(108 * factor)).save(os.path.join(folder, "ic_launcher_background.png"), optimize=True)
    rounded(round(48 * factor), 0.22).save(os.path.join(folder, "ic_launcher.png"), optimize=True)
    rounded(round(48 * factor), 0.5).save(os.path.join(folder, "ic_launcher_round.png"), optimize=True)

# Preview: how launchers will mask the adaptive icon (circle, squircle, rounded square), plus legacy.
bg = adaptive_background(432)
view = bg.crop((72, 72, 360, 360))  # the central 72 dp at xxxhdpi = 288 px


def masked(shape):
    s = 4
    m = Image.new("L", (288 * s, 288 * s), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse((0, 0, 288 * s - 1, 288 * s - 1), fill=255)
    elif shape == "squircle":
        d.rounded_rectangle((0, 0, 288 * s - 1, 288 * s - 1), radius=int(288 * s * 0.38), fill=255)
    else:
        d.rounded_rectangle((0, 0, 288 * s - 1, 288 * s - 1), radius=int(288 * s * 0.2), fill=255)
    m = m.resize((288, 288), Image.LANCZOS)
    out = Image.new("RGBA", (288, 288), (0, 0, 0, 0))
    out.paste(view, (0, 0), m)
    return out


sheet = Image.new("RGB", (4 * 320 + 20, 480), (24, 24, 28))
for i, shape in enumerate(("circle", "squircle", "rounded")):
    sheet.paste(masked(shape), (20 + i * 320, 20), masked(shape))
legacy = rounded(192, 0.22)
sheet.paste(legacy, (20 + 3 * 320 + 48, 68), legacy)
# Home-screen size check: 48 dp at xxhdpi-ish (132 px), as it appears among other icons.
for i, shape in enumerate(("circle", "squircle", "rounded")):
    small = masked(shape).resize((132, 132), Image.LANCZOS)
    sheet.paste(small, (20 + i * 320 + 78, 330), small)
sheet.save(preview_path)
print("ok", square.size)
