"""Pixel-art drawing for Stad's textures: layers of RGBA pixels drawn with simple shapes, outlined and shaded.

Pure Python (no Krita); tools/art/paint.py runs inside Krita and turns each Art into a layered .kra and a PNG.
"""
import math
import random


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(4))


def shade(c, f):
    """Lighter (f > 0) or darker (f < 0), keeping alpha."""
    if f >= 0:
        return mix(c, (255, 255, 255, c[3]), f)
    return mix(c, (0, 0, 0, c[3]), -f)


class Layer:
    def __init__(self, name, w, h):
        self.name, self.w, self.h = name, w, h
        self.px = [[None] * w for _ in range(h)]

    def set(self, x, y, c):
        x, y = int(x), int(y)
        if 0 <= x < self.w and 0 <= y < self.h and c is not None:
            self.px[y][x] = c

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[y][x]
        return None

    def rect(self, x0, y0, x1, y1, c):
        """Fills x0..x1-1, y0..y1-1."""
        for y in range(int(y0), int(y1)):
            for x in range(int(x0), int(x1)):
                self.set(x, y, c)

    def frame(self, x0, y0, x1, y1, c):
        for x in range(x0, x1):
            self.set(x, y0, c)
            self.set(x, y1 - 1, c)
        for y in range(y0, y1):
            self.set(x0, y, c)
            self.set(x1 - 1, y, c)

    def poly(self, pts, c):
        """Fills a polygon (pixel centres inside it)."""
        ys = [p[1] for p in pts]
        for y in range(int(math.floor(min(ys))), int(math.ceil(max(ys))) + 1):
            cy = y + 0.5
            xs = []
            for i in range(len(pts)):
                (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % len(pts)]
                if (y0 <= cy < y1) or (y1 <= cy < y0):
                    xs.append(x0 + (cy - y0) * (x1 - x0) / (y1 - y0))
            xs.sort()
            for a, b in zip(xs[0::2], xs[1::2]):
                for x in range(int(math.ceil(a - 0.5)), int(math.floor(b - 0.5)) + 1):
                    self.set(x, y, c)

    def line(self, x0, y0, x1, y1, width, c):
        """A thick line between pixel centres."""
        dx, dy = x1 - x0, y1 - y0
        n = math.hypot(dx, dy) or 1
        ox, oy = -dy / n * width / 2, dx / n * width / 2
        self.poly([(x0 + ox + .5, y0 + oy + .5), (x1 + ox + .5, y1 + oy + .5), (x1 - ox + .5, y1 - oy + .5), (x0 - ox + .5, y0 - oy + .5)], c)

    def ellipse(self, cx, cy, rx, ry, c):
        for y in range(self.h):
            for x in range(self.w):
                if ((x + .5 - cx) / rx) ** 2 + ((y + .5 - cy) / ry) ** 2 <= 1:
                    self.set(x, y, c)

    def map(self, x0, y0, rows, palette):
        """Draws a character map: each character a palette colour, '.' and ' ' left alone."""
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch in palette:
                    self.set(x0 + i, y0 + j, palette[ch])

    def mask(self):
        return [[self.px[y][x] is not None for x in range(self.w)] for y in range(self.h)]


class Art:
    """A texture: its size, where its PNG goes in the mod, and its layers bottom to top."""

    def __init__(self, name, w, h, out, mcmeta=None):
        self.name, self.w, self.h, self.out, self.mcmeta = name, w, h, out, mcmeta
        self.layers = []

    def layer(self, name):
        l = Layer(name, self.w, self.h)
        self.layers.append(l)
        return l

    def flat(self):
        out = [[(0, 0, 0, 0)] * self.w for _ in range(self.h)]
        for l in self.layers:
            for y in range(self.h):
                row = out[y] = list(out[y])
                for x in range(self.w):
                    c = l.px[y][x]
                    if c is None:
                        continue
                    a = c[3] / 255
                    b = row[x]
                    ba = b[3] / 255
                    oa = a + ba * (1 - a)
                    if oa == 0:
                        continue
                    row[x] = tuple(int(round((c[i] * a + b[i] * ba * (1 - a)) / oa)) for i in range(3)) + (int(round(oa * 255)),)
        return out


def outline(art, color, name="outline"):
    """A layer with a 1-pixel line round everything drawn so far (not diagonals)."""
    solid = [[False] * art.w for _ in range(art.h)]
    for l in art.layers:
        for y in range(art.h):
            for x in range(art.w):
                if l.px[y][x] is not None and l.px[y][x][3] > 0:
                    solid[y][x] = True
    o = Layer(name, art.w, art.h)
    for y in range(art.h):
        for x in range(art.w):
            if solid[y][x]:
                continue
            if any(0 <= x + dx < art.w and 0 <= y + dy < art.h and solid[y + dy][x + dx] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                o.px[y][x] = color
    art.layers.insert(0, o)
    return o


def light(art, layer, up=0.28, down=-0.30, name="shading"):
    """A layer that lightens the top-left edge and darkens the bottom-right edge of each colour region in {layer}."""
    s = Layer(name, art.w, art.h)
    for y in range(art.h):
        for x in range(art.w):
            c = layer.px[y][x]
            if c is None:
                continue
            def differs(dx, dy):
                n = layer.get(x + dx, y + dy)
                return n is None or n != c
            if differs(0, -1) or differs(-1, 0):
                s.px[y][x] = shade(c, up)
            elif differs(0, 1) or differs(1, 0):
                s.px[y][x] = shade(c, down)
    art.layers.append(s)
    return s


def grain(layer, x0, y0, x1, y1, base, rnd, streak=0.10, dark=-0.12, vertical=False):
    """Wood grain: streaks of a lighter and a darker tone along the boards."""
    for y in range(y0, y1):
        for x in range(x0, x1):
            a, b = (y, x) if vertical else (x, y)
            v = math.sin(a * 0.9 + b * 2.7 + rnd.random() * 0.8) + rnd.random() * 0.7
            c = shade(base, streak) if v > 1.05 else shade(base, dark) if v < -0.75 else base
            layer.set(x, y, c)


def noise(layer, x0, y0, x1, y1, base, rnd, amount=0.06):
    for y in range(y0, y1):
        for x in range(x0, x1):
            layer.set(x, y, shade(base, (rnd.random() - 0.5) * 2 * amount))
