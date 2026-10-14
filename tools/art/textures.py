"""Every texture Stad draws itself: the GUI theme (panels, buttons, tabs, icons), the blocks and the items.

Each function returns an Art (tools/art/pixels.py) with named layers. tools/art/paint.py runs these inside Krita
(kritarunner) to make layered .kra files in art/krita and PNGs in the mod's assets; `python3 textures.py sheet.png`
draws them all on one sheet to look at.
"""
import math
import random
import sys

from pixels import Art, Layer, rgb, shade, mix, outline, light, grain, noise

ASSETS = "mod/src/main/resources/assets/stad/textures/"
SPRITES = ASSETS + "gui/sprites/"

# the theme: dark walnut with brass trim
INK = rgb("#0d0906")
BRASS_HI = rgb("#f0d27a")
BRASS = rgb("#c9a24a")
BRASS_LO = rgb("#7d5f22")
WALNUT = rgb("#4a2f1d")
WALNUT_HI = rgb("#6b4a2e")
WALNUT_LO = rgb("#2e1d12")
FELT = rgb("#211813")
PARCHMENT = rgb("#e8d9b0")

ALL = []


def art(fn):
    ALL.append(fn)
    return fn


def nine(w, h, border):
    return {"gui": {"scaling": {"type": "nine_slice", "width": w, "height": h, "border": border}}}


# ---- the GUI ------------------------------------------------------------------------------------------------------

@art
def panel():
    """The screens' background: a walnut frame with a brass edge and rivets round dark felt."""
    a = Art("panel", 32, 32, SPRITES + "panel.png", nine(32, 32, 6))
    r = random.Random(1)
    felt = a.layer("felt")
    noise(felt, 5, 5, 27, 27, FELT, r, 0.05)
    wood = a.layer("frame")
    for y in range(32):
        for x in range(32):
            if 1 <= x <= 30 and 1 <= y <= 30 and not (5 <= x <= 26 and 5 <= y <= 26):
                wood.set(x, y, WALNUT)
    grain(wood, 2, 2, 30, 5, WALNUT, r)
    grain(wood, 2, 27, 30, 30, WALNUT, r)
    grain(wood, 2, 5, 5, 27, WALNUT, r, vertical=True)
    grain(wood, 27, 5, 30, 27, WALNUT, r, vertical=True)
    trim = a.layer("brass")
    for i in range(1, 31):
        trim.set(i, 1, BRASS)
        trim.set(1, i, BRASS)
        trim.set(i, 30, BRASS_LO)
        trim.set(30, i, BRASS_LO)
    trim.set(1, 1, BRASS_HI)
    for i in range(5, 27):  # the felt sits below the frame: a shadow along its top and left
        trim.set(i, 5, shade(FELT, -0.45))
        trim.set(5, i, shade(FELT, -0.45))
        trim.set(i, 26, shade(FELT, 0.12))
        trim.set(26, i, shade(FELT, 0.12))
    for cx, cy in ((3, 3), (27, 3), (3, 27), (27, 27)):
        trim.set(cx, cy, BRASS_HI)
        trim.set(cx + 1, cy, BRASS)
        trim.set(cx, cy + 1, BRASS)
        trim.set(cx + 1, cy + 1, BRASS_LO)
    edge = a.layer("edge")
    edge.frame(0, 0, 32, 32, INK)
    for c in ((0, 0), (31, 0), (0, 31), (31, 31)):
        edge.px[c[1]][c[0]] = None
    return a


@art
def inset():
    """A recessed well inside a panel, for lists and the 3D view's frame."""
    a = Art("inset", 16, 16, SPRITES + "inset.png", nine(16, 16, 2))
    r = random.Random(2)
    l = a.layer("well")
    noise(l, 0, 0, 16, 16, shade(FELT, -0.25), r, 0.03)
    e = a.layer("edge")
    for i in range(16):
        e.set(i, 0, INK)
        e.set(0, i, INK)
        e.set(i, 1, shade(FELT, -0.55))
        e.set(1, i, shade(FELT, -0.55))
        e.set(i, 15, shade(WALNUT, 0.15))
        e.set(15, i, shade(WALNUT, 0.15))
    return a


def plank_button(name, wood, rim_hi, rim_lo, seed, out):
    a = Art(name, 64, 20, out, nine(64, 20, 4))
    r = random.Random(seed)
    face = a.layer("wood")
    grain(face, 2, 2, 62, 18, wood, r, 0.08, -0.10)
    for x in range(2, 62):
        face.set(x, 2, shade(wood, 0.18))
        face.set(x, 17, shade(wood, -0.25))
    rim = a.layer("brass")
    for x in range(1, 63):
        rim.set(x, 1, rim_hi)
        rim.set(x, 18, rim_lo)
    for y in range(1, 19):
        rim.set(1, y, rim_hi)
        rim.set(62, y, rim_lo)
    edge = a.layer("edge")
    edge.frame(0, 0, 64, 20, INK)
    for c in ((0, 0), (63, 0), (0, 19), (63, 19)):
        edge.px[c[1]][c[0]] = None
    return a


@art
def button():
    return plank_button("button", WALNUT_HI, BRASS, BRASS_LO, 3, SPRITES + "button.png")


@art
def button_highlighted():
    return plank_button("button_highlighted", shade(WALNUT_HI, 0.12), BRASS_HI, BRASS, 3, SPRITES + "button_highlighted.png")


@art
def button_disabled():
    return plank_button("button_disabled", rgb("#3b322c"), rgb("#6a5f52"), rgb("#3d352e"), 3, SPRITES + "button_disabled.png")


def tab_sprite(name, wood, rim, selected, out):
    a = Art(name, 32, 20, out, nine(32, 20, {"left": 4, "top": 4, "right": 4, "bottom": 1}))
    r = random.Random(4)
    face = a.layer("wood")
    grain(face, 2, 2, 30, 20, wood, r, 0.07, -0.08)
    rim_l = a.layer("brass")
    for x in range(2, 30):
        rim_l.set(x, 1, rim)
    for y in range(2, 20):
        rim_l.set(1, y, rim)
        rim_l.set(30, y, shade(rim, -0.35))
    edge = a.layer("edge")
    for x in range(2, 30):
        edge.set(x, 0, INK)
    for y in range(2, 20):
        edge.set(0, y, INK)
        edge.set(31, y, INK)
    edge.set(1, 1, INK)
    edge.set(30, 1, INK)
    if not selected:
        for x in range(0, 32):
            edge.set(x, 19, INK)
    return a


@art
def tab():
    return tab_sprite("tab", WALNUT_LO, BRASS_LO, False, SPRITES + "tab.png")


@art
def tab_highlighted():
    return tab_sprite("tab_highlighted", WALNUT, BRASS, False, SPRITES + "tab_highlighted.png")


@art
def tab_selected():
    return tab_sprite("tab_selected", WALNUT, BRASS_HI, True, SPRITES + "tab_selected.png")


@art
def header():
    """A brass plate for a screen's title (dark lettering on it)."""
    a = Art("header", 48, 16, SPRITES + "header.png", nine(48, 16, 5))
    plate = a.layer("brass")
    for y in range(1, 15):
        c = mix(BRASS_HI, BRASS_LO, (y - 1) / 13)
        for x in range(1, 47):
            plate.set(x, y, c)
    for y in range(1, 15):
        plate.set(1, y, shade(plate.get(1, y), 0.2))
        plate.set(46, y, shade(plate.get(46, y), -0.2))
    for cx in (3, 44):
        plate.set(cx, 7, shade(BRASS_LO, -0.3))
        plate.set(cx, 8, shade(BRASS_LO, -0.1))
    edge = a.layer("edge")
    edge.frame(0, 0, 48, 16, INK)
    for c in ((0, 0), (47, 0), (0, 15), (47, 15)):
        edge.px[c[1]][c[0]] = None
    return a


@art
def divider():
    a = Art("divider", 32, 5, SPRITES + "divider.png", nine(32, 5, {"left": 6, "top": 0, "right": 6, "bottom": 0}))
    l = a.layer("line")
    for x in range(3, 29):
        l.set(x, 2, BRASS_LO)
        l.set(x, 1, shade(BRASS_LO, -0.4))
    for ox in (2, 29):  # a little diamond at each end
        l.set(ox, 0, BRASS_LO)
        l.set(ox - 1, 1, BRASS_LO)
        l.set(ox, 1, BRASS_HI)
        l.set(ox + 1, 1, BRASS_LO)
        l.set(ox - 1, 2, BRASS)
        l.set(ox, 2, BRASS)
        l.set(ox + 1, 2, BRASS)
        l.set(ox, 3, BRASS_LO)
    return a


@art
def scroller():
    a = Art("scroller", 6, 16, SPRITES + "scroller.png", nine(6, 16, 2))
    l = a.layer("knob")
    for y in range(16):
        for x in range(6):
            l.set(x, y, BRASS)
    for y in range(16):
        l.set(0, y, BRASS_HI)
        l.set(5, y, BRASS_LO)
    for x in range(6):
        l.set(x, 0, BRASS_HI)
        l.set(x, 15, BRASS_LO)
    for y in (6, 8, 10):
        l.set(2, y, BRASS_LO)
        l.set(3, y, BRASS_LO)
    return a


@art
def scroller_track():
    a = Art("scroller_track", 6, 16, SPRITES + "scroller_track.png", nine(6, 16, 1))
    l = a.layer("track")
    l.rect(0, 0, 6, 16, shade(FELT, -0.35))
    for y in range(16):
        l.set(0, y, INK)
        l.set(5, y, shade(WALNUT, 0.1))
    return a


@art
def slot():
    """An item slot."""
    a = Art("slot", 18, 18, SPRITES + "slot.png")
    l = a.layer("slot")
    l.rect(0, 0, 18, 18, shade(FELT, -0.2))
    for i in range(18):
        l.set(i, 0, INK)
        l.set(0, i, INK)
        l.set(i, 17, shade(WALNUT, 0.25))
        l.set(17, i, shade(WALNUT, 0.25))
    return a


@art
def slot_selected():
    a = Art("slot_selected", 18, 18, SPRITES + "slot_selected.png")
    l = a.layer("slot")
    l.rect(1, 1, 17, 17, shade(FELT, -0.05))
    l.frame(0, 0, 18, 18, BRASS_HI)
    l.frame(1, 1, 17, 17, BRASS_LO)
    return a


# ---- icons ----------------------------------------------------------------------------------------------------------

def icon(name, draw, size=16, folder="icon/"):
    a = Art(name, size, size, SPRITES + folder + name + ".png")
    body = a.layer("body")
    draw(body)
    light(a, body)
    outline(a, rgb("#140d09"))
    return a


IRON, IRON_LO = rgb("#b9bfc6"), rgb("#7a8089")
HANDLE = rgb("#8a5a32")
GOLD = rgb("#e8b83a")


def _hammer(l):
    l.line(3, 13, 9, 7, 2.2, HANDLE)
    l.poly([(7.5, 4.5), (11.5, 0.5), (15.5, 4.5), (11.5, 8.5)], IRON)
    l.poly([(9.8, 6.8), (13.8, 2.8), (15.5, 4.5), (11.5, 8.5)], IRON_LO)


def _wheat(l):
    """A sheaf: the ears in a fan on top, the stalks tied in the middle and spread at the foot."""
    l.map(1, 1, [
        "...Y..Y..Y....",
        "..YyYYyYYyY...",
        ".YyYyYyYyYyY..",
        ".yYyYyYyYyYy..",
        "..yYyYyYyYy...",
        "...yYyYyYy....",
        "....sssss.....",
        ".....sss......",
        ".....RRR......",
        ".....sss......",
        "....sssss.....",
        "...ss.s.ss....",
        "..ss..s..ss...",
    ], {"Y": rgb("#f0d060"), "y": rgb("#c99a30"), "s": rgb("#b8a048"), "R": rgb("#b04a3a")})


def _axe(l):
    l.line(4, 14, 10, 3, 2.0, HANDLE)
    l.poly([(9.5, 1.5), (14.5, 0.5), (15.5, 6.5), (11.5, 6.5), (10.5, 4.5)], IRON)
    l.poly([(13.5, 0.7), (14.5, 0.5), (15.5, 6.5), (14.2, 6.5)], rgb("#e6eaee"))


def _crate(l):
    wood, lo = rgb("#b07a44"), rgb("#7f5430")
    l.rect(2, 4, 14, 14, wood)
    l.rect(2, 4, 14, 6, shade(wood, 0.15))
    for x in range(2, 14):
        l.set(x, 9, lo)
    l.rect(2, 4, 3, 14, lo)
    l.rect(13, 4, 14, 14, lo)
    l.line(3, 13, 12, 6, 1, lo)


def _shield(l):
    blue, gold = rgb("#3a5fa8"), GOLD
    l.poly([(2.5, 1.5), (13.5, 1.5), (13.5, 8), (8, 14.5), (2.5, 8)], gold)
    l.poly([(4, 3), (12, 3), (12, 8), (8, 12.5), (4, 8)], blue)
    l.rect(7, 3, 9, 12, gold)
    l.rect(4, 6, 12, 8, gold)


def _pot(l):
    iron, rim = rgb("#4b4f57"), rgb("#7c828c")
    l.ellipse(8, 10.5, 6, 4.5, iron)
    l.rect(2, 6, 14, 8, rim)
    l.rect(0, 7, 2, 9, iron)
    l.rect(14, 7, 16, 9, iron)
    steam = rgb("#e3e7ea", 220)
    for (x, y) in ((5, 4), (6, 3), (5, 2), (10, 4), (11, 3), (10, 2), (11, 1)):
        l.set(x, y, steam)


def _fish(l):
    body, belly, fin = rgb("#5f9ccf"), rgb("#c9e3f2"), rgb("#3c6f9e")
    l.ellipse(6.5, 8.5, 5.5, 3.5, body)
    l.ellipse(6.5, 10, 4.5, 1.5, belly)
    l.poly([(11, 8.5), (15.5, 4.5), (15.5, 12.5)], fin)
    l.poly([(5, 5), (8, 3.5), (9, 5.5)], fin)
    l.set(3, 7, rgb("#141414"))


def _sheep(l):
    wool, face, leg = rgb("#eeeae2"), rgb("#4a403a"), rgb("#3a322e")
    for (cx, cy, r) in ((6, 7, 3), (9, 6, 3), (11, 8, 3), (7, 9, 3), (10, 10, 2.5)):
        l.ellipse(cx, cy, r, r * 0.9, wool)
    l.rect(5, 11, 6, 15, leg)
    l.rect(11, 11, 12, 15, leg)
    l.ellipse(3, 7, 2.2, 2.6, face)
    l.set(2, 6, rgb("#e8e0d0"))


def _pickaxe(l):
    l.line(4, 14, 10, 6, 2.0, HANDLE)
    l.poly([(1.5, 5.5), (5, 2.5), (8, 1.5), (11, 1.5), (14, 2.5), (15.5, 5), (12.5, 3.8), (8, 3.5), (4, 4.5)], IRON)


def _anvil(l):
    dark, top = rgb("#4c5059"), rgb("#6d737d")
    l.poly([(1, 4), (4, 4), (5, 3.5), (14.5, 3.5), (14.5, 6.5), (12, 6.5), (10, 8.5), (10, 10.5), (12.5, 12.5), (12.5, 14.5),
            (3.5, 14.5), (3.5, 12.5), (6, 10.5), (6, 8.5), (4, 6.5), (2, 5.5)], dark)
    l.rect(5, 3, 15, 5, top)


def _potion(l):
    glass, red, cork = rgb("#cfe6f0", 200), rgb("#d9384a"), rgb("#a87745")
    l.ellipse(8, 10.5, 5, 4.5, glass)
    l.ellipse(8, 11, 4, 3.5, red)
    l.rect(6, 3, 10, 7, glass)
    l.rect(6, 1, 10, 3, cork)
    l.set(6, 9, rgb("#ffffff"))
    l.set(6, 10, rgb("#ffd0d6"))


def _emerald(l):
    green, hi, lo = rgb("#41c764"), rgb("#a6f2b8"), rgb("#1f8a3c")
    l.poly([(8, 1), (13.5, 5), (13.5, 11), (8, 15), (2.5, 11), (2.5, 5)], green)
    l.poly([(8, 3.5), (11, 6), (11, 10), (8, 12.5), (5, 10), (5, 6)], lo)
    l.poly([(8, 3.5), (11, 6), (8, 7), (5, 6)], hi)


def _book(l):
    cover, page, ink = rgb("#7a3b2a"), rgb("#efe3c2"), rgb("#9a8a70")
    l.poly([(0.5, 4), (8, 5.5), (15.5, 4), (15.5, 13), (8, 14.5), (0.5, 13)], cover)
    l.poly([(1.5, 3), (7.5, 4.5), (7.5, 13), (1.5, 11.5)], page)
    l.poly([(8.5, 4.5), (14.5, 3), (14.5, 11.5), (8.5, 13)], page)
    for y in (6, 8, 10):
        for x in range(3, 7):
            l.set(x, y, ink)
        for x in range(10, 14):
            l.set(x, y, ink)


def _saw(l):
    """The carpenter's hand saw: a toothed blade with a wooden grip."""
    blade, tooth = IRON, IRON_LO
    l.poly([(1.5, 9.5), (11, 2), (13, 4.5), (3.5, 13)], blade)
    for i in range(5):  # teeth along the lower edge
        x, y = 3 + i * 2, 12 - i * 2
        l.set(x, y + 1, tooth)
        l.set(x + 1, y, tooth)
    l.poly([(10.5, 1.5), (13, 0.5), (15.5, 3), (14.5, 5.5), (12.5, 4.5)], HANDLE)
    l.set(13, 3, rgb("#5a3a1e"))


def _trowel(l):
    """The mason's bricks and trowel."""
    brick, mortar = rgb("#9c9a94"), rgb("#6e6b66")
    l.rect(1, 8, 13, 15, brick)
    for x in range(1, 13):
        l.set(x, 11, mortar)
    for (x, y0, y1) in ((5, 8, 11), (9, 8, 11), (3, 11, 15), (7, 11, 15), (11, 11, 15)):
        for y in range(y0, y1):
            l.set(x, y, mortar)
    l.poly([(7, 6.5), (11.5, 1.5), (15.5, 5.5), (10.5, 9)], IRON)
    l.line(10, 6, 13.5, 2.5, 1, IRON_LO)
    l.line(6.5, 7, 4.5, 9, 1.4, HANDLE)


def _yarn(l):
    """The weaver's ball of red wool, a loose end trailing."""
    wool, dark, hi = rgb("#c8463c"), rgb("#8f2a24"), rgb("#e88a7c")
    l.ellipse(8, 8, 6, 6, wool)
    for (a, b, c, d) in ((3, 6, 12, 3), (3, 10, 13, 6), (5, 13, 13, 9), (6, 3, 4, 12)):
        l.line(a, b, c, d, 1, dark)
    l.set(6, 5, hi)
    l.set(7, 4, hi)
    l.line(13, 12, 15.5, 15.5, 1, wool)


def _bee(l):
    """A bee: striped body, wings up."""
    yellow, black, wing = rgb("#f2c230"), rgb("#2a2018"), rgb("#dfeef5", 220)
    l.ellipse(5.5, 4.5, 3, 2.5, wing)
    l.ellipse(10, 4, 3, 2.5, wing)
    l.ellipse(8, 9.5, 6, 4, yellow)
    for x in (6, 9):
        l.rect(x, 6, x + 2, 14, black)
    l.ellipse(2.5, 9.5, 1.8, 2, black)
    l.set(14, 9, black)
    l.set(15, 10, black)


def _gather(l):
    """The gatherer's spade stuck in a heap of earth, red berries beside it."""
    earth, dark, berry, leaf = rgb("#6e4526"), rgb("#4a2e19"), rgb("#d0302a"), rgb("#4f8a30")
    l.ellipse(7, 13.5, 6.5, 3, earth)
    l.line(2, 14.5, 11, 14.5, 1, dark)
    l.line(9, 1.5, 7.5, 8, 1.4, HANDLE)
    l.line(7.5, 1.5, 10.5, 1.5, 1.2, HANDLE)
    l.poly([(5, 8), (10, 8), (9.5, 11.5), (7.5, 13), (5.5, 11.5)], IRON)
    l.line(6, 9, 6, 11, 1, IRON_LO)
    l.line(13, 9.5, 14.5, 8, 1, leaf)
    for (x, y) in ((13, 11.5), (14.5, 13), (12.5, 13.5)):
        l.ellipse(x, y, 1.2, 1.2, berry)


def _person(l):
    skin, shirt = rgb("#d9a77e"), rgb("#5f7f4a")
    l.ellipse(8, 4.5, 3, 3, skin)
    l.poly([(3, 15), (3.5, 10), (5.5, 8.5), (10.5, 8.5), (12.5, 10), (13, 15)], shirt)


def _people(l):
    skin, a_c, b_c = rgb("#d9a77e"), rgb("#7a5a9a"), rgb("#4f7fa8")
    l.ellipse(11, 4, 2.5, 2.5, skin)
    l.poly([(7, 15), (7.5, 10), (9, 7.5), (13, 7.5), (14.5, 10), (15, 15)], a_c)
    l.ellipse(5, 5.5, 2.5, 2.5, skin)
    l.poly([(1, 15.5), (1.5, 11), (3, 9), (7, 9), (8.5, 11), (9, 15.5)], b_c)


def _house(l):
    wall, roof, door = rgb("#d8c39a"), rgb("#a8463a"), rgb("#6b4428")
    l.rect(3, 7, 13, 15, wall)
    l.poly([(0.5, 8), (8, 1), (15.5, 8)], roof)
    l.rect(7, 10, 10, 15, door)
    l.rect(4, 9, 6, 11, rgb("#8fc3e6"))


def _bell(l):
    gold, lo = GOLD, rgb("#a8781e")
    l.poly([(4, 13), (5, 6), (6.5, 3.5), (9.5, 3.5), (11, 6), (12, 13)], gold)
    l.rect(2, 12, 14, 14, lo)
    l.rect(7, 1, 9, 3, lo)
    l.rect(7, 14, 9, 16, lo)


def _question(l):
    c = rgb("#b8b0a4")
    l.map(4, 2, [
        ".XXXXX.",
        "XX...XX",
        "XX...XX",
        "....XX.",
        "...XX..",
        "...XX..",
        ".......",
        "...XX..",
        "...XX..",
    ], {"X": c})


def _hoe_field(l):
    soil, crop = rgb("#6b4428"), rgb("#7cc456")
    for y in range(9, 15):
        for x in range(1, 15):
            l.set(x, y, shade(soil, -0.15 if (y % 2) else 0.0))
    l.rect(1, 11, 15, 12, rgb("#3f76c4"))
    for x in (3, 6, 9, 12):
        l.line(x, 8, x, 4, 1, crop)
        l.set(x - 1, 5, crop)
        l.set(x + 1, 6, crop)


def _fence(l):
    wood = rgb("#b07a44")
    for x in (2, 7, 12):
        l.rect(x, 3, x + 2, 15, wood)
    l.rect(1, 6, 15, 8, shade(wood, -0.1))
    l.rect(1, 10, 15, 12, shade(wood, -0.1))


def _sapling(l):
    leaf, stem = rgb("#5aa83c"), rgb("#7a5230")
    l.rect(7, 9, 9, 15, stem)
    l.ellipse(8, 6, 4.5, 4, leaf)
    l.ellipse(5, 8, 2.5, 2, leaf)
    l.ellipse(11, 8, 2.5, 2, leaf)
    l.rect(4, 14, 12, 15, rgb("#6b4428"))


def _wall(l):
    stone, dark, door = rgb("#9a958e"), rgb("#6e6a64"), rgb("#6b4428")
    l.rect(1, 5, 15, 15, stone)
    for x in (1, 5, 9, 13):  # battlements
        l.rect(x, 2, x + 2, 5, stone)
    for y in (8, 11):
        for x in range(1, 15):
            l.set(x, y, dark)
    for x in (4, 8, 12):
        l.set(x, 6, dark)
        l.set(x, 7, dark)
    l.rect(6, 10, 10, 15, door)
    l.rect(6, 9, 10, 10, door)
    l.set(6, 9, stone)
    l.set(9, 9, stone)


def _stall(l):
    """A market stall: a striped awning with a scalloped edge, on two posts, over a counter with produce on it."""
    l.map(0, 1, [
        "..RRWWRRWWRRWW..",
        ".RRWWRRWWRRWWRR.",
        "RRWWRRWWRRWWRRWW",
        "RRWWRRWWRRWWRRWW",
        "R.W.R.W.R.W.R.W.",
        "..P..........P..",
        "..P..........P..",
        "..P.aa.gg.oo.P..",
        ".CCCCCCCCCCCCCC.",
        ".DDDDDDDDDDDDDD.",
        ".DdDDDDdDDDDdDD.",
        ".DDDDDDDDDDDDDD.",
        ".PP..........PP.",
    ], {"R": rgb("#c8463c"), "W": rgb("#efe6d6"), "P": rgb("#6b4428"), "C": rgb("#c99a5e"), "D": rgb("#8a5a32"),
        "d": rgb("#6b4428"), "a": rgb("#d8433a"), "g": rgb("#6fb44a"), "o": rgb("#e8a03a")})


def _road(l):
    """A road winding through grass, seen from above, with a lamp post beside it."""
    l.map(0, 0, [
        "gGGGGGGGeppeGGGg",
        "GGGGGGGGeppeGGGG",
        "GGGGGGGeppeGGGGG",
        "GGGGGGeppeGGGGGG",
        "GGGGGeppeGGGGLGG",
        "GGGGeppeGGGGGlGG",
        "GGGeppeGGGGGGlGG",
        "GGGeppeGGGGGGlGG",
        "GGGGeppeGGGGGGGG",
        "GGGGGeppeGGGGGGG",
        "GGGGGGeppeGGGGGG",
        "GGGGGGGeppeGGGGG",
        "GGGGGGGGeppeGGGG",
        "GGGGGGGGeppeGGGG",
        "GGGGGGGeppeGGGGG",
        "gGGGGGGeppeGGGGg",
    ], {"G": rgb("#5f9a3c"), "g": rgb("#4f8a30"), "p": rgb("#c4a466"), "e": rgb("#8f7444"), "L": rgb("#f0d27a"),
        "l": rgb("#3a2a1c")})


ICONS = {
    "job/builder": _hammer, "job/farmer": _wheat, "job/lumberjack": _axe, "job/courier": _crate, "job/guard": _shield,
    "job/cook": _pot, "job/fisher": _fish, "job/shepherd": _sheep, "job/miner": _pickaxe, "job/smith": _anvil,
    "job/healer": _potion, "job/merchant": _emerald, "job/teacher": _book, "job/none": _question,
    "job/carpenter": _saw, "job/mason": _trowel, "job/weaver": _yarn, "job/beekeeper": _bee, "job/gatherer": _gather,
    "person": _person, "people": _people, "house": _house, "bell": _bell, "field": _hoe_field, "fence": _fence, "sapling": _sapling, "wall": _wall,
    "stall": _stall, "road": _road,
}


def _make_icon(key, fn):
    def make():
        return icon(key.split("/")[-1], fn, folder="icon/" + ("job/" if key.startswith("job/") else ""))
    make.__name__ = "icon_" + key.replace("/", "_")
    return make


for _k, _f in ICONS.items():
    art(_make_icon(_k, _f))


SMALL = {  # 9 by 9, drawn inline with text
    "check": (["........#", ".......##", "......##.", "#....##..", "##..##...", ".####....", "..##.....", ".........", "........."],
              rgb("#6fd16a")),
    "cross": (["##.....##", ".##...##.", "..##.##..", "...###...", "..##.##..", ".##...##.", "##.....##", ".........", "........."],
              rgb("#e2574c")),
    "warn": (["....#....", "...###...", "...#.#...", "..##.##..", "..##.##..", ".#######.", ".###.###.", "#########", "........."],
             rgb("#f0c040")),
    "heart": ([".##...##.", "####.####", "#########", "#########", ".#######.", "..#####..", "...###...", "....#....", "........."],
              rgb("#e2574c")),
    "food": (["....###..", "...#####.", "..######.", "..######.", "...####..", "..b.##...", ".b.......", "bb.......", "b........"],
             rgb("#c98a4a")),
    "moon": (["...####..", "..##.....", ".##......", ".##......", ".##......", ".##......", "..##.....", "...####..", "........."],
             rgb("#a9b8e8")),
    "star": (["....#....", "....#....", "...###...", "#########", ".#######.", "..#####..", "..##.##..", ".##...##.", "........."],
             rgb("#f0d27a")),
}


def _make_small(name, rows, color):
    def make():
        a = Art(name, 9, 9, SPRITES + "icon/" + name + ".png")
        body = a.layer("body")
        body.map(0, 0, rows, {"#": color, "b": rgb("#efe8da")})
        light(a, body, 0.25, -0.2)
        return a
    make.__name__ = "small_" + name
    return make


for _k, (_rows, _c) in SMALL.items():
    art(_make_small(_k, _rows, _c))


# ---- blocks -----------------------------------------------------------------------------------------------------

BLOCKS = ASSETS + "block/"


@art
def architect_table_wood():
    """Dark oak boards, for the drafting table's legs, frame and board."""
    a = Art("architect_table_wood", 16, 16, BLOCKS + "architect_table_wood.png")
    r = random.Random(11)
    base = rgb("#5a3b23")
    w = a.layer("boards")
    for plank in range(4):
        tone = shade(base, (r.random() - 0.5) * 0.12)
        grain(w, 0, plank * 4, 16, plank * 4 + 4, tone, r, 0.10, -0.12)
    s = a.layer("seams")
    for plank in range(4):
        for x in range(16):
            s.set(x, plank * 4 + 3, shade(base, -0.38))
        j = r.randint(2, 13)
        for y in range(plank * 4, plank * 4 + 3):
            s.set(j, y, shade(base, -0.30))
    return a


@art
def architect_table_paper():
    """A blueprint: blue paper, a grid, and the plan of a house in white."""
    a = Art("architect_table_paper", 16, 16, BLOCKS + "architect_table_paper.png")
    blue = rgb("#2a5d9e")
    p = a.layer("paper")
    p.rect(0, 0, 16, 16, blue)
    g = a.layer("grid")
    for i in range(0, 16, 3):
        for j in range(16):
            g.set(i, j, shade(blue, 0.10))
            g.set(j, i, shade(blue, 0.10))
    d = a.layer("drawing")
    chalk = rgb("#e1edf9")
    d.frame(3, 5, 13, 13, chalk)  # the walls
    d.rect(7, 12, 9, 13, blue)  # the door
    for x in range(3, 13):  # the roof's ridge, seen from above
        d.set(x, 9, shade(chalk, -0.25))
    d.set(5, 7, chalk)
    d.set(10, 7, chalk)
    d.line(2, 3, 13, 3, 1, shade(chalk, -0.15))  # a measuring line
    d.set(2, 2, chalk)
    d.set(13, 2, chalk)
    e = a.layer("edge")
    e.frame(0, 0, 16, 16, rgb("#9fc0e6"))
    return a


@art
def architect_table_bits():
    """The bits on the table, in quarters: brass (top left), ink glass (top right), a ruler (bottom left), the end of a roll (bottom right)."""
    a = Art("architect_table_bits", 16, 16, BLOCKS + "architect_table_bits.png")
    r = random.Random(12)
    b = a.layer("brass")
    for y in range(8):
        for x in range(8):
            b.set(x, y, mix(BRASS_HI, BRASS_LO, (x + y) / 14))
    g = a.layer("ink")
    for y in range(8):
        for x in range(8, 16):
            g.set(x, y, rgb("#1b1f2e") if y > 2 else rgb("#3a4466"))
    g.set(10, 3, rgb("#8a9ac8"))
    g.set(10, 4, rgb("#5a6a98"))
    ru = a.layer("ruler")
    for y in range(8, 16):
        for x in range(8):
            ru.set(x, y, rgb("#d9b878"))
    for x in range(0, 8, 2):
        ru.set(x, 8, rgb("#5a3b23"))
        ru.set(x, 9, rgb("#5a3b23") if x % 4 == 0 else rgb("#d9b878"))
    roll = a.layer("roll end")
    cream = rgb("#efe2bd")
    for y in range(8, 16):
        for x in range(8, 16):
            dx, dy = x - 11.5, y - 11.5
            d = math.hypot(dx, dy)
            roll.set(x, y, shade(cream, -0.25) if int(d * 1.6 + math.atan2(dy, dx)) % 2 else cream)
    return a


@art
def architect_table_roll():
    """The side of a rolled-up drawing."""
    a = Art("architect_table_roll", 16, 16, BLOCKS + "architect_table_roll.png")
    cream = rgb("#efe2bd")
    l = a.layer("paper")
    for y in range(16):
        t = abs(y - 7.5) / 8
        for x in range(16):
            l.set(x, y, shade(cream, 0.12 - t * 0.45))
    s = a.layer("edge")
    for y in range(16):
        s.set(15 - (y // 3), y, rgb("#3a6aad"))  # the blue of the drawing showing at the edge
    return a


@art
def town_hall_side():
    """Walnut panelling with a stone plinth and the town's crest: a blue shield with a white tower."""
    a = Art("town_hall_side", 16, 16, BLOCKS + "town_hall_side.png")
    r = random.Random(21)
    frame = a.layer("frame")
    grain(frame, 0, 0, 16, 16, rgb("#4e321e"), r, 0.08, -0.10, vertical=True)
    panel = a.layer("panel")
    grain(panel, 2, 3, 14, 12, rgb("#7a5634"), r, 0.08, -0.10, vertical=True)
    for x in range(2, 14):
        panel.set(x, 3, rgb("#3a2414"))
    for y in range(3, 12):
        panel.set(2, y, rgb("#3a2414"))
        panel.set(13, y, rgb("#93714a"))
    for x in range(2, 14):
        panel.set(x, 11, rgb("#93714a"))
    stone = a.layer("plinth")
    for y in range(13, 16):
        for x in range(16):
            stone.set(x, y, shade(rgb("#8a8580"), (r.random() - 0.5) * 0.12))
    for x in range(16):
        stone.set(x, 13, rgb("#a8a39c"))
        stone.set(x, 12, rgb("#2a1a10"))
    for x in (5, 11):
        stone.set(x, 14, rgb("#5f5a55"))
        stone.set(x, 15, rgb("#5f5a55"))
    crest = a.layer("crest")
    crest.map(4, 3, [
        "GGGGGGGG",
        "GBWBBWBG",
        "GBWWWWBG",
        "GBWWWWBG",
        "GBWDDWBG",
        ".GBWWBG.",
        "..GBBG..",
        "...GG...",
    ], {"G": GOLD, "B": rgb("#2f56a0"), "W": rgb("#f2efe6"), "D": rgb("#5a3b23")})
    return a


@art
def town_hall_top():
    """The desk's top: boards in a brass-cornered frame."""
    a = Art("town_hall_top", 16, 16, BLOCKS + "town_hall_top.png")
    r = random.Random(22)
    t = a.layer("boards")
    for plank in range(4):
        grain(t, 1, 1 + plank * 3 + (1 if plank else 0), 15, 1 + (plank + 1) * 3 + 1, shade(rgb("#7a5634"), (r.random() - .5) * .1), r)
    f = a.layer("frame")
    f.frame(0, 0, 16, 16, rgb("#4e321e"))
    for c in ((0, 0), (15, 0), (0, 15), (15, 15)):
        f.set(c[0], c[1], GOLD)
    return a


@art
def town_hall_book():
    """The town's ledger lying open: two pages of names, a red ribbon, a leather cover."""
    a = Art("town_hall_book", 16, 16, BLOCKS + "town_hall_book.png")
    cover = a.layer("cover")
    cover.rect(0, 0, 16, 16, rgb("#6b2f22"))
    for x in range(16):
        cover.set(x, 0, rgb("#8a4430"))
        cover.set(x, 15, rgb("#4a1e15"))
    pages = a.layer("pages")
    pages.rect(1, 1, 8, 15, rgb("#f0e5c6"))
    pages.rect(8, 1, 15, 15, rgb("#e8dbb7"))
    for y in range(1, 15):
        pages.set(7, y, rgb("#cdbf98"))
        pages.set(8, y, rgb("#d6c8a2"))
    ink = a.layer("writing")
    r = random.Random(23)
    for y in range(3, 14, 2):
        for x0, x1 in ((2, 7), (9, 14)):
            n = r.randint(2, x1 - x0)
            for x in range(x0, x0 + n):
                ink.set(x, y, rgb("#6a5a48"))
    ribbon = a.layer("ribbon")
    for y in range(1, 16):
        ribbon.set(11, y, rgb("#c0303a"))
    ribbon.set(10, 15, rgb("#c0303a"))
    return a


@art
def town_hall_bottom():
    a = Art("town_hall_bottom", 16, 16, BLOCKS + "town_hall_bottom.png")
    r = random.Random(24)
    s = a.layer("stone")
    for y in range(16):
        for x in range(16):
            s.set(x, y, shade(rgb("#7d7873"), (r.random() - 0.5) * 0.14))
    for i in range(16):
        s.set(i, 7, rgb("#5f5a55"))
        s.set(7 if i < 8 else 3, i, rgb("#5f5a55"))
    return a


# ---- items --------------------------------------------------------------------------------------------------------

ITEMS = ASSETS + "item/"


@art
def building_plans():
    """A rolled-up blueprint with a red tie, half open."""
    a = Art("building_plans", 16, 16, ITEMS + "building_plans.png")
    l = a.layer("plans")
    blue, cream = rgb("#2f66ad"), rgb("#efe2bd")
    l.poly([(1.5, 9), (9, 1.5), (14.5, 7), (7, 14.5)], blue)  # the open sheet
    for k in range(3):
        l.line(4 + k * 2, 9 + k * -0 , 9 + k * 2, 4, 1, shade(blue, 0.35))
    l.line(5, 11, 11, 5, 1, rgb("#d5e6f8"))
    l.line(1, 13, 10, 4, 3, cream)  # the roll
    l.line(1, 13, 10, 4, 1, shade(cream, -0.2))
    l.rect(4, 9, 7, 11, rgb("#c0303a"))  # the tie
    light(a, l)
    outline(a, rgb("#140d09"))
    return a


@art
def scan_wand():
    """A surveyor's staff: red and white bands, and a brass sight on top."""
    a = Art("scan_wand", 16, 16, ITEMS + "scan_wand.png")
    l = a.layer("staff")
    for i in range(11):
        x, y = 1 + i, 14 - i
        c = rgb("#d9383a") if (i // 2) % 2 == 0 else rgb("#f1ece2")
        l.set(x, y, c)
        l.set(x + 1, y, shade(c, -0.25))
    l.ellipse(12.5, 3.5, 3, 3, BRASS)
    l.ellipse(12.5, 3.5, 1.6, 1.6, rgb("#8fd0f0"))
    l.set(12, 3, rgb("#ffffff"))
    light(a, l, 0.2, -0.25)
    outline(a, rgb("#140d09"))
    return a


def sheet(path, scale=6):
    from PIL import Image
    arts = [fn() for fn in ALL]
    cols = 8
    cell = max(max(a.w, a.h) for a in arts) * scale + 8
    rows = (len(arts) + cols - 1) // cols
    img = Image.new("RGBA", (cols * cell, rows * cell), (60, 60, 60, 255))
    for i, a in enumerate(arts):
        flat = a.flat()
        im = Image.new("RGBA", (a.w, a.h))
        im.putdata([c for row in flat for c in row])
        im = im.resize((a.w * scale, a.h * scale), Image.NEAREST)
        img.alpha_composite(im, ((i % cols) * cell + 4, (i // cols) * cell + 4))
    img.save(path)
    print(len(arts), "textures ->", path)


if __name__ == "__main__":
    sheet(sys.argv[1] if len(sys.argv) > 1 else "sheet.png")
