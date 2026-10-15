"""The citizens' looks, made of small parts so no two are alike: skin, hair and beards, clothes and job gear in the
player-skin layout (64x64), and the parts of the face (eyes, irises, brows, noses, mouths and marks) on one atlas.

The game puts each citizen together from their parts (dev.eiriksb.stad.client.CitizenSkins), all listed in
parts.json. In every part, pure greys are tinted (skin, hair, eyes, or the clothes' colour; MID is the grey that comes
out as the tint itself) and anything coloured is drawn as it is. Only wide (4-pixel) arms are drawn; the game narrows
them for slim citizens. The face isn't on the skin: the game draws each citizen's own face sheet over the head
(their nose and marks, their eyes looking each way and blinking, their brows, their mouth in every shape it makes when
speaking), built from face_parts.png.

    python3 citizens.py preview.png    a crowd of random citizens on one sheet (front and back)
    python3 citizens.py parts          writes parts.json (paint.py does too)
"""
import math
import random
import sys
import json
import os
import zlib

from pixels import Art, Layer, rgb, shade, mix

ASSETS = "mod/src/main/resources/assets/stad/textures/"
OUT = ASSETS + "entity/citizen/"

MID = 180  # the grey a tint comes out as
SKIN_GREY = (MID, MID, MID, 255)

ALL = []


def art(fn):
    ALL.append(fn)
    return fn


def seed(text):
    return zlib.crc32(text.encode())


def grey(v, a=255):
    v = max(0, min(255, int(round(v))))
    return (v, v, v, a)


# ---- the skin layout --------------------------------------------------------------------------------------------

# each part: where its box is in the skin (u, v) and its size (w, h, d); the outer layer is another box over it
PARTS = {
    "head": (0, 0, 8, 8, 8), "hat": (32, 0, 8, 8, 8),
    "body": (16, 16, 8, 12, 4), "jacket": (16, 32, 8, 12, 4),
    "rarm": (40, 16, 4, 12, 4), "rsleeve": (40, 32, 4, 12, 4),
    "larm": (32, 48, 4, 12, 4), "lsleeve": (48, 48, 4, 12, 4),
    "rleg": (0, 16, 4, 12, 4), "rpants": (0, 32, 4, 12, 4),
    "lleg": (16, 48, 4, 12, 4), "lpants": (0, 48, 4, 12, 4),
}
OUTER = {"head": "hat", "body": "jacket", "rarm": "rsleeve", "larm": "lsleeve", "rleg": "rpants", "lleg": "lpants"}
ARMS, LEGS = ("rarm", "larm"), ("rleg", "lleg")
SIDES = ("right", "front", "left", "back")
# how much lighter or darker each face is, so the shapes read
FACE_LIGHT = {"top": 0.10, "front": 0.0, "right": -0.07, "left": -0.07, "back": -0.13, "bottom": -0.22}


def faces(part):
    """Each face of a part: name -> (x, y, w, h) in the skin."""
    u, v, w, h, d = PARTS[part]
    return {"top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d), "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h), "back": (u + d + w + d, v + d, w, h)}


def around(part, face, x):
    """Where a column of a side face is, going round the part (right, front, left, back), from the right front edge."""
    u, v, w, h, d = PARTS[part]
    return {"right": x, "front": d + x, "left": d + w + x, "back": d + w + d + x}[face]


class Skin:
    """A 64x64 layer in the skin layout, painted a part at a time."""

    def __init__(self, layer, seed=0):
        self.l = layer
        self.r = random.Random(seed)

    def paint(self, part, fn, shading=True, grain=0.035):
        """Calls fn(face, x, y, w, h) for every pixel of every face; it returns a colour, or None to leave it."""
        for face, (fx, fy, fw, fh) in faces(part).items():
            for y in range(fh):
                for x in range(fw):
                    c = fn(face, x, y, fw, fh)
                    if c is None:
                        continue
                    if shading and c[3] > 0:
                        f = FACE_LIGHT[face] + (self.r.random() - 0.5) * 2 * grain
                        if face in SIDES:
                            f -= 0.10 * (y / max(1, fh - 1)) ** 2 * (0.6 if part in ("head", "hat") else 1)
                            if face in ("front", "back") and fw > 2 and x in (0, fw - 1) and part not in ("head", "hat"):
                                f -= 0.05  # rounder: the edges a little darker
                            if part in ("rleg", "rpants") and face == "front" and x == fw - 1 or part in ("lleg", "lpants") and face == "front" and x == 0:
                                f -= 0.06  # between the legs
                        c = shade(c, f)
                    self.l.set(fx + x, fy + y, c)

    def sides(self, part, rows, color, shading=True, grain=0.035):
        """Fills the side faces' rows (a range), and the top or bottom when the rows reach them."""
        rows = list(rows)
        h = PARTS[part][3]
        def fn(face, x, y, w, fh):
            if face in SIDES:
                return color if y in rows else None
            if face == "top":
                return color if 0 in rows else None
            return color if h - 1 in rows else None
        self.paint(part, fn, shading, grain)

    def all(self, part, color, shading=True, grain=0.035):
        self.sides(part, range(PARTS[part][3]), color, shading, grain)


# ---- the body ---------------------------------------------------------------------------------------------------

@art
def body():
    """Bare skin in greys (tinted to the citizen's tone), the face left plain: its nose, eyes, brows and mouth are drawn over it."""
    a = Art("body", 64, 64, OUT + "body.png")
    s = Skin(a.layer("skin"), 1)
    for part in ("head", "body", "rarm", "larm", "rleg", "lleg"):
        s.all(part, SKIN_GREY, grain=0.012)
    fx, fy, _, _ = faces("head")["front"]
    t = a.layer("chin and ears")
    for x in range(1, 7):
        t.set(fx + x, fy + 7, grey(MID * 0.94))
    for side in ("right", "left"):  # ears
        sx, sy, _, _ = faces("head")[side]
        t.set(sx + 3, sy + 4, grey(MID * 0.88))
        t.set(sx + 4, sy + 4, grey(MID * 0.92))
        t.set(sx + 3, sy + 5, grey(MID * 0.84))
        t.set(sx + 4, sy + 5, grey(MID * 0.88))
    return a


# ---- hair -------------------------------------------------------------------------------------------------------

def hair_art(name, draw):
    a = Art("hair_" + name, 64, 64, OUT + "hair/" + name + ".png")
    base = Skin(a.layer("hair"), seed(name))
    outer = Skin(a.layer("volume"), seed(name + "/volume"))
    draw(base, outer, random.Random(len(name) * 7 + ord(name[0])))
    return a


def strands(r, x, y, light=0.0):
    """A hair grey with strands: lighter and darker streaks down the hair."""
    v = MID * (1 + light) + (math.sin(x * 2.1 + r.random() * 1.2) * 0.07 + (r.random() - 0.5) * 0.08) * MID
    return grey(v)


def cap_of_hair(base, outer, r, back_rows, side_rows, fringe, volume=True, side_cut=None):
    """The common part: the top, the back down to back_rows, the sides down to side_rows (not over the ears), and a
    fringe: the front's top rows, column by column (a list of 8 depths)."""
    def fn(face, x, y, w, h):
        if face == "top":
            return strands(r, x, y, 0.08)
        if face == "back":
            return strands(r, x, y) if y < back_rows else None
        if face in ("right", "left"):
            ear = 3 <= x <= 4 and 4 <= y <= 5
            limit = side_rows if side_cut is None else side_cut(face, x)
            return strands(r, x, y) if y < limit and not ear else None
        if face == "front":
            return strands(r, x, y, 0.03) if y < fringe[x] else None
        return None
    base.paint("head", fn, grain=0.02)
    if volume:
        def vol(face, x, y, w, h):
            if face == "top":
                return strands(r, x, y, 0.12) if r.random() < 0.85 else None
            if face == "back":
                return strands(r, x, y, 0.04) if y < back_rows - 1 and r.random() < 0.7 else None
            if face in ("right", "left"):
                return strands(r, x, y, 0.04) if y < min(side_rows, 3) - 1 and r.random() < 0.6 else None
            if face == "front":
                return strands(r, x, y, 0.08) if y < fringe[x] - 1 and r.random() < 0.5 else None
            return None
        outer.paint("hat", vol, grain=0.02)


HAIRS_MEN = []
HAIRS_WOMEN = []


def hair(women=False):
    def wrap(fn):
        (HAIRS_WOMEN if women else HAIRS_MEN).append(fn.__name__)
        ALL.append(lambda: hair_art(fn.__name__, fn))
        return fn
    return wrap


@hair()
def short(base, outer, r):
    cap_of_hair(base, outer, r, 4, 3, [2, 2, 1, 1, 1, 2, 2, 2])


@hair()
def crop(base, outer, r):
    cap_of_hair(base, outer, r, 3, 2, [1, 1, 1, 1, 1, 1, 1, 1], volume=False)


@hair()
def swept(base, outer, r):
    cap_of_hair(base, outer, r, 4, 3, [3, 3, 2, 2, 1, 1, 1, 2])


@hair()
def messy(base, outer, r):
    cap_of_hair(base, outer, r, 5, 4, [2, 3, 2, 2, 3, 2, 2, 3])
    def tufts(face, x, y, w, h):
        if face in ("top", "right", "left", "back") and y < 2 and r.random() < 0.5:
            return strands(r, x, y, 0.1)
        return None
    outer.paint("hat", tufts, grain=0.02)


@hair()
def receding(base, outer, r):
    cap_of_hair(base, outer, r, 4, 3, [1, 0, 0, 0, 0, 0, 0, 1], volume=False,
                side_cut=lambda face, x: 3)
    # thin on top: some of the top left bare
    fx, fy, _, _ = faces("head")["top"]
    for y in range(4, 8):
        for x in range(2, 6):
            if r.random() < 0.7:
                base.l.px[fy + y][fx + x] = None


@hair()
def tied(base, outer, r):
    cap_of_hair(base, outer, r, 6, 3, [2, 2, 1, 1, 1, 1, 2, 2])
    def tail(face, x, y, w, h):  # a short tail down the back of the neck
        return strands(r, x, y) if face == "back" and 3 <= x <= 4 and y < 4 else None
    base.paint("body", tail, grain=0.02)


@hair(women=True)
def long(base, outer, r):
    cap_of_hair(base, outer, r, 8, 8, [2, 2, 1, 1, 1, 1, 2, 2], side_cut=lambda face, x: 8 if x in (0, 1, 6, 7) else 6)
    def down(face, x, y, w, h):  # down the back, over the shirt
        return strands(r, x, y) if face == "back" and y < 7 - (1 if x in (0, 7) else 0) else None
    outer.paint("jacket", down, grain=0.02)


@hair(women=True)
def bob(base, outer, r):
    cap_of_hair(base, outer, r, 7, 7, [2, 3, 2, 1, 1, 2, 3, 2], side_cut=lambda face, x: 7 if x in (0, 1, 6, 7) else 5)


@hair(women=True)
def bun(base, outer, r):
    cap_of_hair(base, outer, r, 5, 3, [2, 2, 1, 1, 1, 1, 2, 2])
    fx, fy, _, _ = faces("hat")["back"]
    for y in range(0, 3):
        for x in range(2, 6):
            if not (y == 0 and x in (2, 5)):
                outer.l.set(fx + x, fy + y, strands(r, x, y, 0.1))
    tx, ty, _, _ = faces("hat")["top"]
    for x in range(2, 6):
        outer.l.set(tx + x, ty + 7, strands(r, x, 7, 0.15))


@hair(women=True)
def braid(base, outer, r):
    cap_of_hair(base, outer, r, 8, 6, [2, 2, 1, 1, 1, 1, 2, 2])
    def plait(face, x, y, w, h):
        if face == "back" and 3 <= x <= 4 and y < 10:
            return grey(MID * (1.1 if (x + y) % 2 == 0 else 0.85))
        return None
    outer.paint("jacket", plait, shading=False)


@hair(women=True)
def ponytail(base, outer, r):
    cap_of_hair(base, outer, r, 6, 4, [2, 2, 2, 1, 1, 1, 1, 2])
    def tail(face, x, y, w, h):
        return strands(r, x, y, 0.05) if face == "back" and 2 <= x <= 5 and y < 6 - (1 if x in (2, 5) else 0) else None
    outer.paint("jacket", tail, grain=0.02)


@hair(women=True)
def pixie(base, outer, r):
    cap_of_hair(base, outer, r, 4, 3, [3, 2, 2, 1, 1, 1, 1, 1])


# ---- beards -----------------------------------------------------------------------------------------------------

BEARDS = []
BEARD_ARTS = {}


def beard(fn):
    BEARDS.append(fn.__name__)
    def make():
        a = Art("beard_" + fn.__name__, 64, 64, OUT + "beard/" + fn.__name__ + ".png")
        fn(Skin(a.layer("beard"), 3), random.Random(5))
        return a
    ALL.append(make)
    BEARD_ARTS[fn.__name__] = make
    return fn


@beard
def stubble(s, r):
    def fn(face, x, y, w, h):
        if face == "front" and y >= 6 and r.random() < 0.55:
            return grey(MID * 0.85, 120)
        if face in ("right", "left") and y >= 6 and (x <= 2 if face == "right" else x >= 5) and r.random() < 0.4:
            return grey(MID * 0.85, 110)
        return None
    s.paint("head", fn, shading=False)


@beard
def full(s, r):
    def fn(face, x, y, w, h):
        if face == "front" and (y == 7 or y == 6 and x not in (3, 4) or y == 5 and x in (0, 7)):
            return strands(r, x, y)
        if face in ("right", "left") and y >= 5 and (x <= 3 if face == "right" else x >= 4):
            return strands(r, x, y)
        if face == "bottom" and y <= 3:
            return strands(r, x, y, -0.1)
        return None
    s.paint("head", fn, grain=0.02)


@beard
def goatee(s, r):
    def fn(face, x, y, w, h):
        if face == "front" and y == 7 and 2 <= x <= 5:
            return strands(r, x, y)
        if face == "bottom" and y <= 1 and 2 <= x <= 5:
            return strands(r, x, y, -0.1)
        return None
    s.paint("head", fn, grain=0.02)


@beard
def moustache(s, r):
    # it sits over the top lip: the mouth is drawn below it (face.png's mouths start half a pixel lower)
    def fn(face, x, y, w, h):
        return strands(r, x, y) if face == "front" and y == 6 and 2 <= x <= 5 else None
    s.paint("head", fn)


# ---- clothes ----------------------------------------------------------------------------------------------------

DYE = grey(MID)  # dyed: the citizen's colour for that piece


class Outfit:
    """Clothes being drawn on a skin: DYE (and its shades: greys) takes the citizen's colour, the rest is as drawn."""

    def __init__(self, skin, seed):
        self.fixed = self.dye = skin
        self.r = random.Random(seed)

    def on(self, color):
        return self.fixed

    # what goes on

    def shirt(self, color, sleeves=12, collar=None, tuck=10):
        """A shirt over the body down to row {tuck}, sleeves {sleeves} rows long (0: none), and a collar colour."""
        s = self.on(color)
        s.sides("body", range(tuck), color)
        for arm in ARMS:
            if sleeves:
                s.sides(arm, range(sleeves), color)
                if sleeves < 12:  # a hem
                    s.sides(arm, [sleeves - 1], shade(color, -0.12) if color is not DYE else grey(MID * 0.86))
        if collar:
            c = self.on(collar)
            def neck(face, x, y, w, h):
                if face == "front" and y == 0 and 2 <= x <= 5 or face == "front" and y == 1 and x in (3, 4):
                    return collar
                if face == "top":
                    return collar if 1 <= x <= 6 and y in (1, 2) else None
                return None
            c.paint("body", neck)

    def vneck(self, skin_rows=2):
        """Cuts a V at the neck (the body's skin shows through)."""
        for s in (self.fixed, self.dye):
            fx, fy, _, _ = faces("body")["front"]
            for y in range(skin_rows):
                for x in range(3 - y, 5 + y) if y < 1 else range(3, 5):
                    s.l.px[fy + y][fx + x] = None

    def trousers(self, color, waist=10):
        s = self.on(color)
        for leg in LEGS:
            s.all(leg, color)
        s.sides("body", range(waist, 12), color)

    def boots(self, color, height=3):
        s = self.on(color)
        for leg in LEGS:
            s.sides(leg, range(12 - height, 12), color)
            s.sides(leg, [12 - height], shade(color, 0.14) if color is not DYE else grey(MID * 1.1))

    def shoes(self, color):
        self.boots(color, 1)

    def belt(self, color, row=9, buckle=None):
        s = self.on(color)
        s.sides("body", [row], color)
        if buckle:
            fx, fy, _, _ = faces("body")["front"]
            self.on(buckle).l.set(fx + 3, fy + row, buckle)
            self.on(buckle).l.set(fx + 4, fy + row, shade(buckle, -0.2))

    def gloves(self, color, rows=3):
        s = self.on(color)
        for arm in ARMS:
            s.sides(arm, range(12 - rows, 12), color)

    def apron(self, color, top=2, legs=7, strap=True):
        """An apron on the outer layer: the front of the body from row {top}, and on down the legs' fronts."""
        s = self.on(color)
        def front(face, x, y, w, h):
            if face != "front":
                return None
            if y >= top:
                return color
            return color if strap and x in (1, 6) else None
        s.paint("jacket", front)
        for leg, side in (("rpants", "front"), ("lpants", "front")):
            def fn(face, x, y, w, h, side=side):
                return color if face == side and y < legs else None
            s.paint(leg, fn)
        # ties round the waist
        s.sides("jacket", [top + 6], shade(color, -0.15) if color is not DYE else grey(MID * 0.8))

    def overalls(self, color):
        self.trousers(color, waist=6)
        s = self.on(color)
        def bib(face, x, y, w, h):
            if face == "front" and 3 <= y < 6 and 1 <= x <= 6:
                return color
            if face in ("front", "back") and y < 3 and x in (1, 6):
                return color
            return None
        s.paint("body", bib)
        fx, fy, _, _ = faces("body")["front"]
        for x in (1, 6):
            s.l.set(fx + x, fy + 3, rgb("#d8c27a"))

    def robe(self, color, belt=None):
        """A long robe: the body and on down the legs (the outer layer, so it hangs over them) to the shoes."""
        s = self.on(color)
        s.sides("body", range(12), color)
        for leg in ("rpants", "lpants"):
            s.sides(leg, range(11), color)
        if belt:
            self.belt(belt, 8)

    def vest(self, color, open_front=True):
        s = self.on(color)
        def fn(face, x, y, w, h):
            if face == "front":
                return color if (x <= 2 or x >= 5 or not open_front) and y < 10 else None
            if face in ("right", "left", "back"):
                return color if y < 10 else None
            return color if face == "top" else None
        s.paint("jacket", fn)

    def tabard(self, color, trim=None):
        s = self.on(color)
        def fn(face, x, y, w, h):
            if face in ("front", "back"):
                if 1 <= x <= 6:
                    if trim and (x in (1, 6) or y == 11):
                        return trim
                    return color
            return None
        s.paint("jacket", fn)
        for leg in ("rpants", "lpants"):
            def drop(face, x, y, w, h, leg=leg):
                inner = (face == "front" and (x >= 2 if leg == "rpants" else x <= 1))
                return (trim or color) if inner and y < 4 else None
            s.paint(leg, drop)

    def satchel(self, strap, bag):
        s = self.fixed
        fx, fy, _, _ = faces("jacket")["front"]
        bx, by, _, _ = faces("jacket")["back"]
        for i in range(8):
            s.l.set(fx + 7 - i, fy + i + 1, strap)
            s.l.set(bx + i, by + i + 1, strap)
        rx, ry, _, _ = faces("rpants")["right"]
        for y in range(0, 3):
            for x in range(4):
                s.l.set(rx + x, ry + y, shade(bag, -0.1 if y == 2 else 0))

    def hat_band(self, color, rows=2, top=True, back_rows=None):
        """Headwear on the outer layer: the crown and a band of rows round the head."""
        s = self.on(color)
        back_rows = rows if back_rows is None else back_rows
        def fn(face, x, y, w, h):
            if face == "top":
                return color if top else None
            if face == "back":
                return color if y < back_rows else None
            if face in SIDES:
                return color if y < rows else None
            return None
        s.paint("hat", fn)

    def glasses(self, color):
        fx, fy, _, _ = faces("hat")["front"]
        for x in (0, 3, 4, 7):
            self.fixed.l.set(fx + x, fy + 4, color)
        for x in range(0, 8):
            if x not in (3, 4):
                pass
        self.fixed.l.set(fx + 3, fy + 4, color)
        self.fixed.l.set(fx + 4, fy + 4, color)
        for x in (1, 2, 5, 6):
            self.fixed.l.set(fx + x, fy + 3, color)
        for side in ("right", "left"):
            sx, sy, _, _ = faces("hat")[side]
            for x in range(1, 7):
                self.fixed.l.set(sx + x, sy + 3, color)

    def smudges(self, color, n, parts=("body", "rleg", "lleg", "rarm", "larm")):
        """Dirt or soot, see-through, over whatever's worn there."""
        for _ in range(n):
            part = self.r.choice(parts)
            fx, fy, fw, fh = faces(part)[self.r.choice(("front", "front", "right", "left"))]
            self.fixed.l.set(fx + self.r.randrange(fw), fy + self.r.randrange(fh), color[:3] + (110,))

    def emblem(self, rows, palette, part="jacket", face="front", x0=2, y0=2):
        fx, fy, _, _ = faces(part)[face]
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch in palette:
                    self.fixed.l.set(fx + x0 + i, fy + y0 + j, palette[ch])




def _at(part, face, x, y):
    fx, fy, _, _ = faces(part)[face]
    return fx + x, fy + y


PIECES = {"top": [], "bottom": [], "shoes": [], "job": []}
MAKERS = {}


def piece(slot, who="all"):
    """A piece of clothing for a slot (top, bottom, shoes) or a job's gear; {who}: "all", "men" or "women" wear it."""
    def wrap(fn):
        PIECES[slot].append((fn.__name__, who))
        def make():
            a = Art(slot + "_" + fn.__name__, 64, 64, OUT + slot + "/" + fn.__name__ + ".png")
            fn(Outfit(Skin(a.layer(fn.__name__), seed(slot + fn.__name__)), seed(fn.__name__)))
            return a
        ALL.append(make)
        MAKERS[(slot, fn.__name__)] = make
        return fn
    return wrap


LEATHER = rgb("#6b4428")
DARK_LEATHER = rgb("#3e2817")
LINEN = rgb("#d9cfb4")
WHITE = rgb("#ece8dc")
IRON = rgb("#a7a9ab")
BRASS = rgb("#c9a24a")


# tops: tinted to the citizen's top colour

@piece("top")
def tunic(o):
    o.shirt(DYE, sleeves=8, collar=LINEN)
    o.belt(LEATHER, 8, BRASS)


@piece("top")
def shirt(o):
    o.shirt(DYE, sleeves=12, collar=grey(MID * 1.15))
    fx, fy, _, _ = faces("body")["front"]
    for y in (3, 6, 9):
        o.fixed.l.set(fx + 3, fy + y, rgb("#e8dcc0"))


@piece("top")
def rolled(o):
    o.shirt(DYE, sleeves=5, collar=grey(MID * 1.1))
    for arm in ARMS:
        o.fixed.sides(arm, [4], grey(MID * 1.12))


@piece("top")
def vest(o):
    o.shirt(LINEN, sleeves=12, collar=WHITE)
    o.vest(DYE)
    fx, fy, _, _ = faces("jacket")["front"]
    for y in (3, 6):
        o.fixed.l.set(fx + 2, fy + y, BRASS)


@piece("top")
def sweater(o):
    o.shirt(DYE, sleeves=12, tuck=11)
    def knit(face, x, y, w, h):
        return grey(MID * (1.08 if (x + (y // 2)) % 3 == 0 else 0.96)) if face in SIDES and y < 11 else None
    o.fixed.paint("body", knit, shading=True, grain=0.0)
    o.fixed.sides("body", [10], grey(MID * 0.85))
    for arm in ARMS:
        o.fixed.sides(arm, [11], grey(MID * 0.85))


@piece("top", "women")
def blouse(o):
    o.shirt(DYE, sleeves=9, tuck=10)
    o.vneck()
    for arm in ARMS:
        o.fixed.sides(arm, [8], WHITE)
    o.belt(grey(MID * 0.7), 8)


@piece("top", "women")
def bodice(o):
    o.shirt(WHITE, sleeves=6, tuck=10)
    def laced(face, x, y, w, h):
        if face in SIDES and 2 <= y < 10:
            return rgb("#e2d6b8") if face == "front" and x in (3, 4) and y % 2 == 0 else DYE
        return None
    o.fixed.paint("jacket", laced)


# bottoms: tinted to the citizen's bottom colour

@piece("bottom")
def trousers(o):
    o.trousers(DYE)


@piece("bottom", "men")
def breeches(o):
    o.trousers(DYE)
    for leg in LEGS:
        o.fixed.sides(leg, range(8, 12), rgb("#e4dccb"))  # stockings below the knee
        o.fixed.sides(leg, [7], grey(MID * 0.8))


@piece("bottom", "women")
def skirt(o):
    o.trousers(DYE, waist=10)
    for leg in ("rpants", "lpants"):
        o.fixed.sides(leg, range(0, 8), DYE)
        o.fixed.sides(leg, [7], grey(MID * 0.82))


@piece("bottom", "women")
def long_skirt(o):
    o.trousers(DYE, waist=10)
    for leg in ("rpants", "lpants"):
        o.fixed.sides(leg, range(0, 11), DYE)
        o.fixed.sides(leg, [10], grey(MID * 0.82))


# shoes: as they are

@piece("shoes")
def shoes(o):
    o.shoes(rgb("#4a3424"))


@piece("shoes")
def boots(o):
    o.boots(rgb("#3b2a1e"), 3)


@piece("shoes")
def tall_boots(o):
    o.boots(rgb("#2c211a"), 5)


@piece("shoes")
def tan_boots(o):
    o.boots(rgb("#8a6440"), 3)


@piece("shoes")
def clogs(o):
    o.shoes(rgb("#b08850"))


# a job's gear, over the rest (JOBS: which of the citizen's own pieces it covers)

JOBS = {}


def job(name, covers=()):
    def wrap(fn):
        JOBS[name] = list(covers)
        return piece("job")(fn)
    return wrap


@job("farmer", covers=("bottom",))
def farmer(o):
    o.overalls(rgb("#3f5677"))
    o.hat_band(rgb("#d9b65c"), rows=2)
    for side in SIDES:
        fx, fy, fw, _ = faces("hat")[side]
        for x in range(fw):
            o.fixed.l.set(fx + x, fy + 1, rgb("#8a3b2a"))
    o.smudges(rgb("#5a4126"), 6, ("rleg", "lleg"))


@job("builder")
def builder(o):
    s = o.fixed
    o.belt(LEATHER, 9)
    fx, fy, _, _ = faces("jacket")["front"]
    for x, y, c in ((0, 9, IRON), (0, 10, LEATHER), (6, 9, LEATHER), (6, 10, LEATHER), (7, 9, LEATHER), (7, 10, rgb("#c9c3b5"))):
        s.l.set(fx + x, fy + y, c)
    o.hat_band(rgb("#e0b23a"), rows=2, back_rows=1)
    hx, hy, _, _ = faces("hat")["front"]
    for x in range(8):
        s.l.set(hx + x, hy + 1, rgb("#b8891f"))


@job("lumberjack", covers=("top",))
def lumberjack(o):
    red, dark = rgb("#a8322b"), rgb("#4b1c1a")
    def plaid(face, x, y, w, h, part):
        if face == "bottom" or face in SIDES and y >= 10:
            return None
        a = around(part, face, x) if face in SIDES else x
        return dark if (a % 4 == 0 or y % 4 == 1) else red
    for part in ("body", "rarm", "larm"):
        o.fixed.paint(part, lambda f, x, y, w, h, part=part: plaid(f, x, y, w, h, part))
    for arm in ARMS:
        o.fixed.sides(arm, [9], shade(red, -0.25))
    o.belt(DARK_LEATHER, 9, IRON)
    o.hat_band(rgb("#3c5a2a"), rows=2)


@job("miner", covers=("shoes",))
def miner(o):
    o.boots(rgb("#262320"), 4)
    o.gloves(rgb("#4a3322"), 2)
    o.hat_band(rgb("#c8a034"), rows=3, back_rows=2)
    hx, hy, _, _ = faces("hat")["front"]
    o.fixed.l.set(hx + 3, hy + 1, rgb("#fff3b0"))
    o.fixed.l.set(hx + 4, hy + 1, rgb("#ffd86a"))
    for x in range(8):
        o.fixed.l.set(hx + x, hy + 2, rgb("#8c6d1c"))
    o.smudges(rgb("#1d1b19"), 16)


@job("fisher", covers=("top", "shoes"))
def fisher(o):
    yellow = rgb("#e2b51f")
    o.boots(rgb("#2d3b33"), 5)
    o.shirt(yellow, sleeves=12, tuck=12, collar=rgb("#b88d12"))
    fx, fy, _, _ = faces("body")["front"]
    for y in range(1, 12):
        o.fixed.l.set(fx + 4, fy + y, shade(yellow, -0.22))
    o.hat_band(yellow, rows=2, back_rows=3)


@job("shepherd")
def shepherd(o):
    wool = rgb("#e9e2d0")
    o.vest(wool)
    fx, fy, _, _ = faces("jacket")["front"]
    for y in range(10):
        for x in range(8):
            if o.fixed.l.get(fx + x, fy + y) and (x + y * 3) % 5 == 0:
                o.fixed.l.set(fx + x, fy + y, shade(wool, -0.12))
    o.hat_band(rgb("#6b5233"), rows=2)


@job("guard", covers=("top", "shoes"))
def guard(o):
    mail = rgb("#8d9094")
    for part in ("body", "rarm", "larm"):
        rows = 10 if part == "body" else 9
        o.fixed.paint(part, lambda f, x, y, w, h, rows=rows: shade(mail, 0.12 if (x + y) % 2 == 0 else -0.12)
                      if f in SIDES and y < rows or f == "top" else None)
    o.boots(rgb("#2a2622"), 4)
    o.gloves(DARK_LEATHER, 3)
    o.tabard(rgb("#2f4f86"), trim=rgb("#d1ab45"))
    o.emblem(["..y..", ".yyy.", "..y.."], {"y": rgb("#e6c766")}, x0=1, y0=3)
    o.hat_band(IRON, rows=3, back_rows=4)
    hx, hy, _, _ = faces("hat")["front"]
    o.fixed.l.set(hx + 3, hy + 3, IRON)
    o.fixed.l.set(hx + 4, hy + 3, shade(IRON, -0.2))
    for x in range(8):
        o.fixed.l.set(hx + x, hy + 2, shade(IRON, -0.25))


@job("cook")
def cook(o):
    o.apron(rgb("#f4f1ea"), top=3, legs=6)
    o.hat_band(WHITE, rows=3, back_rows=3)
    o.smudges(rgb("#b06a3a"), 4, ("body",))


@job("courier")
def courier(o):
    o.satchel(DARK_LEATHER, LEATHER)
    o.hat_band(rgb("#7a2e2a"), rows=1)


@job("smith")
def smith(o):
    o.apron(DARK_LEATHER, top=1, legs=8)
    o.gloves(rgb("#2a2018"), 4)
    o.smudges(rgb("#151311"), 12)


@job("healer", covers=("top", "bottom"))
def healer(o):
    o.robe(rgb("#dfe3d6"), belt=rgb("#6f8a5a"))
    o.shirt(rgb("#dfe3d6"), sleeves=12, tuck=12)
    o.satchel(rgb("#6f8a5a"), rgb("#7f9b66"))
    o.emblem([".g.", "ggg", ".g."], {"g": rgb("#4f8a3f")}, x0=2, y0=3)


@job("merchant")
def merchant(o):
    o.vest(rgb("#6e2340"))
    fx, fy, _, _ = faces("jacket")["front"]
    for y in (2, 4, 6):
        o.fixed.l.set(fx + 2, fy + y, BRASS)
    o.fixed.l.set(*_at("jacket", "front", 6, 10), rgb("#a07b2a"))


@job("teacher")
def teacher(o):
    o.glasses(rgb("#2b2420"))
    o.fixed.l.set(*_at("jacket", "front", 5, 3), rgb("#7a2e2a"))  # a book in the pocket
    o.fixed.l.set(*_at("jacket", "front", 6, 3), rgb("#7a2e2a"))


@job("carpenter")
def carpenter(o):
    o.apron(rgb("#5c7a4a"), top=3, legs=6)
    o.fixed.l.set(*_at("jacket", "front", 5, 4), rgb("#d8b25a"))
    o.fixed.l.set(*_at("jacket", "front", 5, 3), rgb("#d8b25a"))


@job("mason")
def mason(o):
    o.apron(rgb("#8b8780"), top=4, legs=5)
    for leg in ("rpants", "lpants"):
        o.fixed.sides(leg, [7, 8], rgb("#4b3a2a"))
    o.smudges(rgb("#c8c4bb"), 10)


@job("weaver")
def weaver(o):
    shawl = [rgb("#b8463a"), rgb("#e2b14a"), rgb("#3f6f8a")]
    def fn(face, x, y, w, h):
        if face in SIDES and y < 4 or face == "top":
            a = around("jacket", face, x) if face in SIDES else x
            return shawl[(a + y) % 3]
        return None
    o.fixed.paint("jacket", fn)
    o.hat_band(rgb("#7a3f5a"), rows=2, back_rows=4)


@job("beekeeper", covers=("top", "bottom"))
def beekeeper(o):
    suit = rgb("#efe9d3")
    o.shirt(suit, sleeves=12, tuck=12)
    o.trousers(suit, waist=10)
    o.gloves(rgb("#d7c48a"), 3)
    o.hat_band(rgb("#e7dfc0"), rows=2)
    # the veil, in front of the face: a mesh (every other pixel; the game draws skins cut out, not see-through)
    for side in ("front", "right", "left", "back"):
        sx, sy, _, _ = faces("hat")[side]
        for y in range(2, 8 if side == "front" else 7):
            for x in range(8):
                if (x + y) % 2 == 0:
                    o.fixed.l.set(sx + x, sy + y, (52, 48, 42, 255))


@job("gatherer")
def gatherer(o):
    o.hat_band(rgb("#5d6b3a"), rows=4, back_rows=8)
    bx, by, _, _ = faces("jacket")["back"]
    for y in range(1, 9):
        for x in range(1, 7):
            o.fixed.l.set(bx + x, by + y, shade(rgb("#a07a42"), 0.1 if (x + y) % 2 == 0 else -0.1))
    o.satchel(rgb("#6b4c2a"), rgb("#8c6a3a"))


@job("researcher", covers=("top", "bottom"))
def researcher(o):
    o.robe(rgb("#2f3550"), belt=rgb("#b39545"))
    o.shirt(rgb("#2f3550"), sleeves=12, tuck=12)
    o.glasses(rgb("#c9a24a"))
    o.hat_band(rgb("#232740"), rows=1)


# ---- the parts of the face --------------------------------------------------------------------------------------

# face_parts.png, at twice the skin's resolution (the head's front is 16x16 of these pixels). Each kind of part is a
# row of cells (parts.json says where); the game takes a citizen's ones and builds their face sheet from them.
FACE_W, FACE_H = 128, 64
CELLS = {"eyes": (0, 0, 6, 4), "irises": (0, 4, 3, 3), "brows": (0, 8, 6, 2), "mouths": (0, 12, 8, 4),
         "noses": (0, 32, 16, 16), "marks": (0, 48, 16, 16)}
MOUTH_SHAPES = ["rest", "smile", "frown", "small", "open", "wide", "round", "pressed", "teeth", "grin", "o_small"]
WHITE_EYE = rgb("#f3f0ea")
LASH = (45, 30, 24)

# eye shapes: (width, height, lashes, almond)
EYES = {"plain": (4, 3, "thin", False), "narrow": (4, 2, "thin", False), "almond": (5, 3, "outer", True),
        "soft": (4, 3, "none", True), "wide": (5, 2, "outer", False), "round": (3, 3, "thin", False)}
# 2x3, the same both ways round (the right eye is the left one mirrored)
IRISES = {"plain": [[0.72, 0.72], [0.12, 0.12], [1.1, 1.1]], "dark": [[0.58, 0.58], [0.1, 0.1], [0.9, 0.9]],
          "bright": [[0.85, 0.85], [0.16, 0.16], [1.25, 1.25]], "ringed": [[0.6, 0.6], [0.14, 0.14], [1.3, 1.3]]}
BROWS = {"thin": ["  ...", "     "], "plain": [" ....", "     "], "thick": [".....", " ... "],
         "arched": ["  .. ", " .  ."], "straight": [".... ", "     "], "bushy": [".....", "....."]}
# mouths: (width, full lips)
MOUTH_STYLES = {"small": (4, False), "plain": (5, False), "wide": (6, False), "full": (5, True)}
NOSES = {"button": ([(7, 10), (8, 10)], [(7, 11), (8, 11)]), "long": ([(7, 8), (7, 9), (7, 10)], [(7, 11), (8, 11), (8, 10)]),
         "wide": ([(7, 10), (8, 10)], [(6, 11), (7, 11), (8, 11), (9, 11)]), "pointed": ([(8, 9), (8, 10)], [(7, 11), (8, 11), (9, 11)]),
         "small": ([], [(7, 11), (8, 11)]), "snub": ([(7, 10), (8, 10)], [(6, 11), (9, 11)])}
MARKS = {
    "freckles": [((3, 11), (150, 90, 60, 120)), ((5, 12), (150, 90, 60, 110)), ((4, 10), (150, 90, 60, 100)), ((2, 12), (150, 90, 60, 90)),
                 ((12, 11), (150, 90, 60, 120)), ((10, 12), (150, 90, 60, 110)), ((11, 10), (150, 90, 60, 100)), ((13, 12), (150, 90, 60, 90))],
    "blush": [((x, y), (220, 110, 100, 55)) for x in (2, 3, 4, 11, 12, 13) for y in (11, 12)],
    "mole": [((11, 13), (70, 45, 35, 200))],
    "wrinkles": [((0, 9), (60, 40, 35, 70)), ((15, 9), (60, 40, 35, 70)), ((0, 10), (60, 40, 35, 50)), ((15, 10), (60, 40, 35, 50)),
                 ((3, 13), (60, 40, 35, 60)), ((12, 13), (60, 40, 35, 60)), ((3, 14), (60, 40, 35, 50)), ((12, 14), (60, 40, 35, 50))],
    "scar": [((12, y), (235, 205, 195, 130)) for y in range(5, 11)],
    "dimples": [((3, 14), (80, 50, 40, 70)), ((12, 14), (80, 50, 40, 70))],
}


def mouth_frame(l, ox, oy, w, full, shape):
    """One mouth shape for a style, in an 8x4 cell: L the line and P the lips (skin greys), the inside, teeth and tongue
    as they are."""
    L, P = grey(MID * 0.45), grey(MID * 0.8)
    I, T, G = rgb("#3a1712"), rgb("#f1ece2"), rgb("#a4473f")
    x0 = 4 - w // 2
    def row(y, x_from, x_to, c):
        for x in range(x_from, x_to):
            l.set(ox + x, oy + y, c)
    lo = P if full else None
    if shape == "rest":
        row(1, x0, x0 + w, L)
        if full:
            row(2, x0 + 1, x0 + w - 1, P)
    elif shape == "smile":
        row(1, x0 + 1, x0 + w - 1, L)
        l.set(ox + x0, oy, L)
        l.set(ox + x0 + w - 1, oy, L)
        if full:
            row(2, x0 + 1, x0 + w - 1, P)
    elif shape == "frown":
        row(1, x0 + 1, x0 + w - 1, L)
        l.set(ox + x0, oy + 2, L)
        l.set(ox + x0 + w - 1, oy + 2, L)
    elif shape == "small":
        row(1, 3, 5, L)
        row(2, 3, 5, I)
    elif shape == "open":
        row(0, x0 + 1, x0 + w - 1, T)
        row(1, x0 + 1, x0 + w - 1, I)
        row(2, x0 + 1, x0 + w - 1, I)
        row(2, 3, 5, G)
        row(3, x0 + 1, x0 + w - 1, P if full else L)
    elif shape == "wide":
        row(1, x0, x0 + w, T)
        l.set(ox + x0, oy + 1, L)
        l.set(ox + x0 + w - 1, oy + 1, L)
        row(2, x0 + 1, x0 + w - 1, I)
        if full:
            row(3, x0 + 1, x0 + w - 1, P)
    elif shape == "round":
        row(0, 3, 5, L)
        row(1, 2, 6, L)
        row(1, 3, 5, I)
        row(2, 2, 6, L)
        row(2, 3, 5, I)
        row(3, 3, 5, P if full else L)
    elif shape == "pressed":
        row(1, x0, x0 + w, L)
        row(0, x0 + 1, x0 + w - 1, P)
        row(2, x0 + 1, x0 + w - 1, P)
    elif shape == "teeth":
        row(1, x0 + 1, x0 + w - 1, T)
        row(2, x0 + 1, x0 + w - 1, P)
    elif shape == "grin":
        l.set(ox + x0, oy, L)
        l.set(ox + x0 + w - 1, oy, L)
        row(1, x0 + 1, x0 + w - 1, T)
        l.set(ox + x0, oy + 1, L)
        l.set(ox + x0 + w - 1, oy + 1, L)
        row(2, x0 + 1, x0 + w - 1, I)
        if full:
            row(3, x0 + 2, x0 + w - 2, P)
    elif shape == "o_small":
        row(1, 3, 5, L)
        row(2, 2, 6, L)
        row(2, 3, 5, I)
        row(3, 3, 5, L)


@art
def face_parts():
    a = Art("face_parts", FACE_W, FACE_H, OUT + "face_parts.png")
    eyes = a.layer("eyes")
    cx, cy, cw, ch = CELLS["eyes"]
    for i, (w, h, lashes, almond) in enumerate(EYES.values()):
        ox, x0 = cx + i * cw, (cw - w) // 2
        for y in range(1, h + 1):
            for x in range(x0, x0 + w):
                if almond and (x, y) in ((x0, 1), (x0 + w - 1, h)):
                    continue
                eyes.set(ox + x, cy + y, shade(WHITE_EYE, -0.10) if y == 1 else WHITE_EYE)
        if lashes == "thin":
            for x in range(x0 + 1, x0 + w - 1):
                eyes.set(ox + x, cy, LASH + (120,))
        elif lashes == "outer":
            for x in range(x0, x0 + w):
                eyes.set(ox + x, cy, LASH + (170,))
            eyes.set(ox + x0 - 1, cy, LASH + (140,))  # the outer corner (the viewer's left eye's left)
    irises = a.layer("irises")
    cx, cy, cw, ch = CELLS["irises"]
    for i, rows in enumerate(IRISES.values()):
        for y, r in enumerate(rows):
            for x, v in enumerate(r):
                irises.set(cx + i * cw + x, cy + y, grey(MID * v))
    brows = a.layer("brows")
    cx, cy, cw, ch = CELLS["brows"]
    for i, rows in enumerate(BROWS.values()):
        for y, r in enumerate(rows):
            for x, c in enumerate(r):
                if c == ".":
                    brows.set(cx + i * cw + x, cy + y, grey(MID * (1.0 if y == 0 else 0.85)))
    mouths = a.layer("mouths")
    cx, cy, cw, ch = CELLS["mouths"]
    for s, (w, full) in enumerate(MOUTH_STYLES.values()):
        for m, shape in enumerate(MOUTH_SHAPES):
            mouth_frame(mouths, cx + m * cw, cy + s * ch, w, full, shape)
    noses = a.layer("noses")
    cx, cy, cw, ch = CELLS["noses"]
    for i, (light_px, dark_px) in enumerate(NOSES.values()):
        for x, y in light_px:
            noses.set(cx + i * cw + x, cy + y, grey(MID * 1.08))
        for x, y in dark_px:
            noses.set(cx + i * cw + x, cy + y, grey(MID * 0.86))
    marks = a.layer("marks")
    cx, cy, cw, ch = CELLS["marks"]
    for i, dots in enumerate(MARKS.values()):
        for (x, y), c in dots:
            marks.set(cx + i * cw + x, cy + y, c)
    return a


# ---- parts.json: everything the game picks from -----------------------------------------------------------------

SKIN_TONES = ["#f6d9c6", "#efc6a8", "#e2b08c", "#d39c74", "#bf8660", "#a66d48", "#8b5737", "#71442a", "#5a3420", "#432617"]
HAIR_COLOURS = ["#1e1814", "#3b2619", "#5c3b22", "#82603a", "#a8844f", "#d0b276", "#e4d8b8", "#7b3a1d", "#a64a20", "#2a201b"]
GREY_HAIR = "#a19c95"
EYE_COLOURS = ["#5b3a1e", "#3a2414", "#7a6a30", "#4f7a3a", "#3f6fa8", "#6f7f88", "#5d4a2a"]
CLOTH = ["#9c4a3a", "#b68a3a", "#4f6e3c", "#3c4f78", "#6e5038", "#3e7a78", "#6e4466", "#7a7a76", "#8a6a4a", "#4a4a58",
         "#a0603a", "#5a6a3a", "#c8b48a", "#3a5a4a", "#7a3a3a", "#4a5a7a"]
TROUSER_CLOTH = ["#4f4436", "#3d3a35", "#5a4b3a", "#3b4250", "#5d5141", "#4a3e30", "#6a5a44", "#3a3a3e"]


def parts():
    """What the game picks a citizen's looks from (a citizen's looks are numbers; each is taken modulo its list's
    length, and a blank name means none). Repeating a name makes it likelier."""
    def names(slot, who):
        return [n for n, w in PIECES[slot] if w in ("all", who)]
    return {
        "face_parts": {"cells": CELLS, "eyes": list(EYES), "eye_sizes": {k: [v[0], v[1]] for k, v in EYES.items()},
                       "irises": list(IRISES), "brows": list(BROWS), "mouth_styles": list(MOUTH_STYLES), "mouth_shapes": MOUTH_SHAPES,
                       "noses": list(NOSES), "marks": list(MARKS)},
        "marks": ["", "", "", "", "freckles", "freckles", "blush", "mole", "dimples", "scar"],
        "hair": {"men": HAIRS_MEN, "women": HAIRS_WOMEN},
        "beards": ["", "", "", "", "stubble", "stubble", "full", "goatee", "moustache"],
        "tops": {"men": names("top", "men"), "women": names("top", "women")},
        "bottoms": {"men": names("bottom", "men"), "women": names("bottom", "women") + ["trousers"]},
        "shoes": [n for n, _ in PIECES["shoes"]],
        "jobs": JOBS,
        "colours": {"skin": SKIN_TONES, "hair": HAIR_COLOURS, "grey_hair": GREY_HAIR, "eyes": EYE_COLOURS, "tops": CLOTH,
                    "bottoms": TROUSER_CLOTH},
    }


def write_parts(repo):
    path = os.path.join(repo, OUT, "parts.json")
    with open(path, "w") as f:
        json.dump(parts(), f, indent=1)
        f.write("\n")
    print("wrote", path)


# ---- the preview: citizens put together as the game does --------------------------------------------------------

def image(a):
    from PIL import Image
    im = Image.new("RGBA", (a.w, a.h))
    im.putdata([c for row in a.flat() for c in row])
    return im


def tint(img, color):
    """Tints the pure greys (the game's rule), leaves colours as they are."""
    r, g, b = rgb(color)[:3]
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            c = px[x, y]
            if c[3] and c[0] == c[1] == c[2]:
                px[x, y] = (min(255, c[0] * r // MID), min(255, c[1] * g // MID), min(255, c[2] * b // MID), c[3])
    return out


_cache = {}


def made(key, fn):
    if key not in _cache:
        _cache[key] = image(fn())
    return _cache[key]


def compose(look):
    """A skin as the game puts it together (CitizenSkins.skin)."""
    from PIL import Image
    p = parts()
    out = tint(made("body", body), look["skin"])
    covers = set(p["jobs"].get(look["job"], []))
    for slot in ("bottom", "top", "shoes"):
        if slot not in covers and look[slot]:
            out.alpha_composite(tint(made((slot, look[slot]), MAKERS[(slot, look[slot])]), look.get(slot + "_colour", "#b4b4b4")))
    gear = made(("job", look["job"]), MAKERS[("job", look["job"])]) if ("job", look["job"]) in MAKERS else None
    if gear:
        out.alpha_composite(tint(gear, look["top_colour"]))
    if look["beard"]:
        out.alpha_composite(tint(made("beard" + look["beard"], BEARD_ARTS[look["beard"]]), look["hair_colour"]))
    h = tint(made("hair" + look["hair"], lambda: hair_art(look["hair"], globals()[look["hair"]])), look["hair_colour"])
    if gear:  # headwear goes over the hair's volume
        for y in range(16):
            for x in range(32, 64):
                if gear.getpixel((x, y))[3]:
                    h.putpixel((x, y), (0, 0, 0, 0))
    out.alpha_composite(h)
    if gear:
        out.alpha_composite(tint(gear, look["top_colour"]).crop((32, 0, 64, 16)), (32, 0))
    return out


def face_sheet_front(look, shape="rest"):
    """The face, looking ahead with its eyes open, as the game draws it (16x16)."""
    from PIL import Image
    fp = made("face_parts", face_parts)
    def cell(kind, i, row=0):
        cx, cy, cw, ch = CELLS[kind]
        return fp.crop((cx + i * cw, cy + row * ch, cx + (i + 1) * cw, cy + (row + 1) * ch))
    f = Image.new("RGBA", (16, 16))
    f.alpha_composite(tint(cell("noses", list(NOSES).index(look["nose"])), look["skin"]))
    if look["marks"]:
        f.alpha_composite(cell("marks", list(MARKS).index(look["marks"])))
    ei = list(EYES).index(look["eyes"])
    w, h = EYES[look["eyes"]][:2]
    eye = cell("eyes", ei)
    iris = tint(cell("irises", list(IRISES).index(look["iris"])), look["eye_colour"])
    x0 = (6 - w) // 2 + (w - 2) // 2
    for y in range(3):
        for x in range(2):
            px = iris.getpixel((x, y))
            if px[3] and eye.getpixel((x0 + x, 1 + y))[3] and eye.getpixel((x0 + x, 1 + y))[0] > 150:
                eye.putpixel((x0 + x, 1 + y), px)
    f.alpha_composite(eye, (1, 7))
    f.alpha_composite(eye.transpose(Image.FLIP_LEFT_RIGHT), (9, 7))
    brow = tint(cell("brows", list(BROWS).index(look["brow"])), "#%02x%02x%02x" % mix(rgb(look["hair_colour"]), (0, 0, 0, 255), 0.25)[:3])
    f.alpha_composite(brow, (1, 5))
    f.alpha_composite(brow.transpose(Image.FLIP_LEFT_RIGHT), (9, 5))
    mouth = cell("mouths", MOUTH_SHAPES.index(shape), list(MOUTH_STYLES).index(look["mouth"]))
    f.alpha_composite(tint(mouth, look["skin"]), (4, 12))
    return f


def random_look(r, woman, p):
    def pick(lst):
        return lst[r.randrange(len(lst))]
    jobs = list(p["jobs"]) + [None, None]
    return {"woman": woman, "skin": pick(SKIN_TONES), "hair_colour": pick(HAIR_COLOURS), "eye_colour": pick(EYE_COLOURS),
            "eyes": pick(list(EYES)), "iris": pick(list(IRISES)), "brow": pick(list(BROWS)), "mouth": pick(list(MOUTH_STYLES)),
            "nose": pick(list(NOSES)), "marks": pick(p["marks"]), "hair": pick(p["hair"]["women" if woman else "men"]),
            "beard": "" if woman else pick(p["beards"]), "top": pick(p["tops"]["women" if woman else "men"]), "top_colour": pick(CLOTH),
            "bottom": pick(p["bottoms"]["women" if woman else "men"]), "bottom_colour": pick(TROUSER_CLOTH), "shoes": pick(p["shoes"]),
            "job": pick(jobs)}


def front_view(skin, face, scale=6, back=False):
    from PIL import Image
    fig = Image.new("RGBA", (16, 32))
    side = "back" if back else "front"
    places = [("head", 4, 0), ("body", 4, 8), ("rarm", 0, 8), ("larm", 12, 8), ("rleg", 4, 20), ("lleg", 8, 20)]
    if back:
        places = [("head", 4, 0), ("body", 4, 8), ("rarm", 12, 8), ("larm", 0, 8), ("rleg", 8, 20), ("lleg", 4, 20)]
    for part, x, y in places:
        fx, fy, fw, fh = faces(part)[side]
        fig.alpha_composite(skin.crop((fx, fy, fx + fw, fy + fh)), (x, y))
        ox, oy, ow, oh = faces(OUTER[part])[side]
        if part == "head" and not back:
            continue
        fig.alpha_composite(skin.crop((ox, oy, ox + ow, oy + oh)), (x, y))
    big = fig.resize((32, 64), Image.NEAREST)
    if not back:
        big.alpha_composite(face, (8, 0))
        hx, hy, hw, hh = faces("hat")["front"]
        big.alpha_composite(skin.crop((hx, hy, hx + hw, hy + hh)).resize((16, 16), Image.NEAREST), (8, 0))
    return big.resize((16 * scale, 32 * scale), Image.NEAREST)


def preview(path, n=40):
    from PIL import Image
    r = random.Random(7)
    p = parts()
    cols = 10
    cw, ch = 16 * 6 + 8, 32 * 6 * 2 + 16
    rows = (n + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cw, rows * ch), (70, 80, 70, 255))
    for i in range(n):
        look = random_look(r, i % 2 == 1, p)
        skin = compose(look)
        face = face_sheet_front(look, r.choice(MOUTH_SHAPES))
        x, y = (i % cols) * cw + 4, (i // cols) * ch + 4
        sheet.alpha_composite(front_view(skin, face), (x, y))
        sheet.alpha_composite(front_view(skin, face, back=True), (x, y + 32 * 6 + 8))
    sheet.save(path)
    print(n, "citizens ->", path)


def textured(look, shape="rest"):
    """A citizen's skin at twice its resolution with their face on it (as the game shows them, looking ahead): for
    Blockbench and previews."""
    from PIL import Image
    skin = compose(look)
    big = skin.resize((128, 128), Image.NEAREST)
    fx, fy, _, _ = faces("head")["front"]
    big.alpha_composite(face_sheet_front(look, shape), (fx * 2, fy * 2))
    return big


if __name__ == "__main__":
    if sys.argv[1:2] == ["parts"]:
        write_parts(os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
    else:
        preview(sys.argv[1] if len(sys.argv) > 1 else "citizens.png")
