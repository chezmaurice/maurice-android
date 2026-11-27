#!/usr/bin/env python3
"""Tile screenshots into one image per screen, for looking at them at once.

    scripts/contact-sheet.py maurice/shared/build/screenshots chat
writes build/sheets/chat.png from every chat--*.png, one row per frame.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

src, screen = Path(sys.argv[1]), sys.argv[2]
files = sorted(src.glob(f"{screen}--*.png"))
rows = {}
for f in files:
    _, frame, look = f.stem.split("--")
    rows.setdefault(frame, []).append((look, Image.open(f)))
scale, pad, label = 0.35, 16, 28
cells = [[(look, im.resize((int(im.width * scale), int(im.height * scale)))) for look, im in r] for r in rows.values()]
width = max(sum(im.width + pad for _, im in r) for r in cells) + pad
height = sum(max(im.height for _, im in r) + pad + label for r in cells) + pad
sheet = Image.new("RGB", (width, height), "#808080")
draw = ImageDraw.Draw(sheet)
y = pad
for frame, r in zip(rows, cells):
    x = pad
    for look, im in r:
        draw.text((x, y), f"{frame} · {look}", fill="white")
        sheet.paste(im, (x, y + label))
        x += im.width + pad
    y += max(im.height for _, im in r) + pad + label
out = Path("build/sheets") / f"{screen}.png"
out.parent.mkdir(parents=True, exist_ok=True)
sheet.save(out)
print(out, sheet.size)
