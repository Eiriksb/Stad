#!/usr/bin/env python3
"""Isometric pictures of structure .nbt files: iso.py out.png a.nbt b.nbt ... (front, z = 0, faces the viewer).

Needs Pillow. Colours are rough, by block name."""
import sys
from PIL import Image, ImageDraw
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from nbtview import load

COLORS = [
    ("glass", (170, 210, 235)), ("moss_block", (89, 109, 45)), ("structure_block", (255, 0, 255)), ("dark_oak_log", (60, 46, 26)), ("spruce_log", (58, 37, 16)), ("oak_log", (109, 85, 50)),
    ("birch_log", (216, 215, 210)), ("_log", (90, 70, 40)), ("dark_oak", (66, 43, 20)), ("spruce", (114, 84, 48)),
    ("birch", (196, 179, 123)), ("jungle", (160, 115, 80)), ("acacia", (168, 90, 50)), ("cherry", (226, 178, 172)),
    ("mangrove", (117, 54, 48)), ("oak", (162, 130, 78)), ("mossy_stone_brick", (115, 121, 105)), ("stone_brick", (122, 121, 122)),
    ("deepslate", (70, 70, 75)), ("mossy_cobble", (110, 118, 94)), ("cobble", (110, 110, 110)), ("red_sandstone", (181, 98, 31)),
    ("sandstone", (216, 203, 155)), ("brick", (150, 74, 58)), ("mud_brick", (137, 103, 79)), ("andesite", (132, 134, 133)),
    ("granite", (154, 106, 89)), ("diorite", (188, 188, 188)), ("blackstone", (48, 42, 49)), ("tuff", (98, 104, 94)),
    ("quartz", (235, 229, 222)), ("prismarine", (99, 156, 151)), ("nether_brick", (44, 21, 26)), ("stone", (125, 125, 125)),
    ("door", (130, 100, 60)), ("_bed", (170, 40, 40)), ("chest", (160, 110, 40)), ("barrel", (130, 95, 55)),
    ("lantern", (250, 200, 90)), ("campfire", (240, 120, 30)), ("torch", (250, 220, 120)), ("fence", (120, 95, 60)),
    ("carpet", (230, 230, 230)), ("bookshelf", (120, 80, 50)), ("ladder", (150, 120, 70)),
]

def color(name):
    for k, c in COLORS:
        if k in name:
            return c
    return (200, 0, 200)

def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)

def render(path, s=10):
    size, blocks = load(path)
    W, H, D = size
    img = Image.new("RGB", ((W + D) * s + 20, (W + D) * s // 2 + H * s + 30), (235, 240, 245))
    dr = ImageDraw.Draw(img)
    ox, oy = D * s + 10, H * s + 10
    def proj(x, y, z):  # camera at -x,-z looking at the front (z = 0)
        xp, zp = W - 1 - x, D - 1 - z
        return ox + (xp - zp) * s, oy + (xp + zp) * s // 2 - y * s
    order = sorted(blocks, key=lambda p: (-(p[0] + p[2]), p[1]))
    for (x, y, z) in order:
        name, props = blocks[(x, y, z)]
        if name in ("air", "wall_torch", "torch"):
            continue
        c = color(name)
        h = 0.5 if "slab" in name and props.get("type") != "double" else 1.0
        if "fence" in name or "lantern" in name or "carpet" in name:
            h = 0.3 if "carpet" in name else 1.0
        # corners of the unit cube at (x, y, z); visible faces: top, north (-z), west (-x)
        px, py = proj(x, y, z)
        top = [proj(x + 1, y + h, z + 1), proj(x, y + h, z + 1), proj(x, y + h, z), proj(x + 1, y + h, z)]
        north = [proj(x, y, z), proj(x + 1, y, z), proj(x + 1, y + h, z), proj(x, y + h, z)]
        west = [proj(x, y, z + 1), proj(x, y, z), proj(x, y + h, z), proj(x, y + h, z + 1)]
        dr.polygon(west, fill=shade(c, 0.75), outline=shade(c, 0.6))
        dr.polygon(north, fill=shade(c, 0.9), outline=shade(c, 0.7))
        dr.polygon(top, fill=shade(c, 1.1), outline=shade(c, 0.85))
    return img

if __name__ == "__main__":
    out = sys.argv[1]
    imgs = [(p.rsplit("/", 1)[-1], render(p)) for p in sys.argv[2:]]
    cols = 3
    cw = max(i.width for _, i in imgs); ch = max(i.height for _, i in imgs) + 16
    rows = (len(imgs) + cols - 1) // cols
    sheet = Image.new("RGB", (cw * cols, ch * rows), (255, 255, 255))
    d = ImageDraw.Draw(sheet)
    for i, (n, im) in enumerate(imgs):
        x, y = (i % cols) * cw, (i // cols) * ch
        sheet.paste(im, (x, y + 16)); d.text((x + 6, y + 2), n, fill=(0, 0, 0))
    sheet.save(out)
    print(out, sheet.size)
