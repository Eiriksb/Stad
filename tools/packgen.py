#!/usr/bin/env python3
"""Generates the bundled building packs (Medieval, Fjord, Oasis): vanilla structure templates (.nbt) plus their manifests.

    packgen.py <resources dir>      # mod/src/main/resources

Every building faces north: its door is in the z = 0 wall. Every position in the bounding box that isn't a block is
air, so builders clear the site first.
"""
import gzip, json, os, struct, sys

# ---- NBT ------------------------------------------------------------------------------------------------------------

def _name(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b

def _tag(kind, key, payload):
    return bytes([kind]) + _name(key) + payload

def _compound(*items):
    return b"".join(items) + b"\x00"

def _int_list(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)

def _compound_list(items):
    return bytes([10]) + struct.pack(">i", len(items)) + b"".join(items)

def _state(name, props):
    items = [_tag(8, "Name", _name(name))]
    if props:
        items.append(_tag(10, "Properties", _compound(*[_tag(8, k, _name(str(v))) for k, v in sorted(props.items())])))
    return _compound(*items)

DATA_VERSION_1_21_1 = 3955

def write_structure(path, size, blocks):
    """blocks: {(x, y, z): (name, {props}) or (name, {props}, {nbt strings})}; everything else in the box is air."""
    sx, sy, sz = size
    palette, index, entries = [], {}, []
    def state_id(name, props):
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            palette.append(_state(name, props))
        return index[key]
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                entry = blocks.get((x, y, z), ("minecraft:air", {}))
                name, props = entry[0], entry[1]
                items = [_tag(9, "pos", _int_list([x, y, z])), _tag(3, "state", struct.pack(">i", state_id(name, props)))]
                if len(entry) > 2:
                    items.append(_tag(10, "nbt", _compound(*[_tag(8, k, _name(str(v))) for k, v in sorted(entry[2].items())])))
                entries.append(_compound(*items))
    root = _tag(10, "", _compound(
        _tag(3, "DataVersion", struct.pack(">i", DATA_VERSION_1_21_1)),
        _tag(9, "size", _int_list([sx, sy, sz])),
        _tag(9, "palette", _compound_list(palette)),
        _tag(9, "blocks", _compound_list(entries)),
        _tag(9, "entities", bytes([0]) + struct.pack(">i", 0)),
    ))
    with gzip.GzipFile(path, "wb", mtime=0) as f:
        f.write(root)

# ---- building blocks ------------------------------------------------------------------------------------------------

def house(w, d, wall_h, floor, wall, corner, roof, roof_fill, roof_slab, door_x, windows):
    """A box with log corners, a door in the north wall, glass windows and a hip roof. Returns (size, blocks)."""
    b = {}
    for x in range(w):
        for z in range(d):
            b[(x, 0, z)] = (floor, {})
    for y in range(1, wall_h + 1):
        for x in range(w):
            for z in range(d):
                if x in (0, w - 1) or z in (0, d - 1):
                    is_corner = x in (0, w - 1) and z in (0, d - 1)
                    b[(x, y, z)] = (corner, {"axis": "y"}) if is_corner else (wall, {})
    for (x, y, z) in windows:
        along_x = z in (0, d - 1)
        b[(x, y, z)] = ("minecraft:glass_pane", {"east": "true", "west": "true"} if along_x else {"north": "true", "south": "true"})
    b[(door_x, 1, 0)] = ("minecraft:oak_door", {"facing": "south", "half": "lower", "hinge": "left", "open": "false"})
    b[(door_x, 2, 0)] = ("minecraft:oak_door", {"facing": "south", "half": "upper", "hinge": "left", "open": "false"})
    # hip roof: rings of stairs climbing inwards, each ring's inside filled (the ceiling), slabs on top
    x0, z0, x1, z1, y = 0, 0, w - 1, d - 1, wall_h + 1
    while x1 - x0 >= 2 and z1 - z0 >= 2:
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if z == z0:
                    b[(x, y, z)] = (roof, {"facing": "south", "half": "bottom", "shape": "straight"})
                elif z == z1:
                    b[(x, y, z)] = (roof, {"facing": "north", "half": "bottom", "shape": "straight"})
                elif x == x0:
                    b[(x, y, z)] = (roof, {"facing": "east", "half": "bottom", "shape": "straight"})
                elif x == x1:
                    b[(x, y, z)] = (roof, {"facing": "west", "half": "bottom", "shape": "straight"})
                else:
                    b[(x, y, z)] = (roof_fill, {})
        x0, z0, x1, z1, y = x0 + 1, z0 + 1, x1 - 1, z1 - 1, y + 1
    if x1 >= x0 and z1 >= z0:
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                b[(x, y, z)] = (roof_slab, {"type": "bottom"})
        y += 1
    return (w, y, d), b

def bed(b, color, foot, facing):
    fx, fy, fz = foot
    dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[facing]
    b[(fx, fy, fz)] = (f"minecraft:{color}_bed", {"facing": facing, "part": "foot", "occupied": "false"})
    b[(fx + dx, fy, fz + dz)] = (f"minecraft:{color}_bed", {"facing": facing, "part": "head", "occupied": "false"})

def builder_hut():
    size, b = house(7, 7, 3, "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:spruce_stairs",
                    "minecraft:spruce_planks", "minecraft:spruce_slab", 3, [(0, 2, 3), (6, 2, 3), (3, 2, 6)])
    b[(1, 1, 5)] = ("minecraft:crafting_table", {})
    b[(2, 1, 5)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    bed(b, "brown", (5, 1, 5), "north")
    return size, b

def cottage():
    size, b = house(7, 9, 3, "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:dark_oak_stairs",
                    "minecraft:dark_oak_planks", "minecraft:dark_oak_slab", 3,
                    [(0, 2, 2), (0, 2, 6), (6, 2, 2), (6, 2, 6), (3, 2, 8)])
    bed(b, "red", (1, 1, 6), "south")
    bed(b, "blue", (5, 1, 6), "south")
    b[(3, 1, 7)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    return size, b

def warehouse():
    size, b = house(9, 7, 4, "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:spruce_log", "minecraft:spruce_stairs",
                    "minecraft:spruce_planks", "minecraft:spruce_slab", 4, [(0, 2, 3), (8, 2, 3)])
    for x in (1, 3, 5, 7):
        b[(x, 1, 5)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(7, 1, 1)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(4, 1, 3)] = ("minecraft:lantern", {"hanging": "false"})
    return size, b

def marker(name):
    """A Data structure block carrying one of Stad's markers (Blueprint.MARKERS); builders leave air there."""
    return ("minecraft:structure_block", {"mode": "data"}, {"id": "minecraft:structure_block", "mode": "DATA", "metadata": name})

def mine():
    """An open timber headframe over a 5x5 shaft (the stad:shaft marker in the middle), fenced round with a gap at the front."""
    b = {}
    n = 9
    for x in range(n):
        for z in range(n):
            if x in (0, n - 1) or z in (0, n - 1) or x in (1, n - 2) or z in (1, n - 2):
                b[(x, 0, z)] = ("minecraft:cobblestone", {})
    for x in range(n):
        for z in range(n):
            if (x in (0, n - 1) or z in (0, n - 1)) and not (z == 0 and x == 4):
                b[(x, 1, z)] = ("minecraft:oak_fence", fence_sides(x, z, n, n, gap=(4, 0)))
    for (x, z) in ((0, 0), (n - 1, 0), (0, n - 1), (n - 1, n - 1)):
        for y in range(1, 5):
            b[(x, y, z)] = ("minecraft:oak_log", {"axis": "y"})
    for i in range(n):
        b[(i, 5, 0)] = ("minecraft:oak_log", {"axis": "x"})
        b[(i, 5, n - 1)] = ("minecraft:oak_log", {"axis": "x"})
        b[(0, 5, i)] = ("minecraft:oak_log", {"axis": "z"})
        b[(n - 1, 5, i)] = ("minecraft:oak_log", {"axis": "z"})
        b[(i, 5, 4)] = ("minecraft:oak_planks", {})
    b[(4, 4, 4)] = ("minecraft:lantern", {"hanging": "true"})
    b[(1, 1, 7)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(4, 0, 4)] = marker("stad:shaft")
    return (n, 6, n), b

def fence_sides(x, z, w, d, gap=None):
    """Which sides an edge fence post joins (its neighbours along the edge)."""
    def edge(px, pz):
        return 0 <= px < w and 0 <= pz < d and (px in (0, w - 1) or pz in (0, d - 1)) and (px, pz) != gap
    return {"north": str(edge(x, z - 1) and x in (0, w - 1)).lower(), "south": str(edge(x, z + 1) and x in (0, w - 1)).lower(),
            "west": str(edge(x - 1, z) and z in (0, d - 1)).lower(), "east": str(edge(x + 1, z) and z in (0, d - 1)).lower()}

def kitchen():
    size, b = house(7, 7, 3, "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:spruce_stairs",
                    "minecraft:spruce_planks", "minecraft:spruce_slab", 3, [(0, 2, 3), (6, 2, 3)])
    b[(1, 1, 5)] = ("minecraft:smoker", {"facing": "north", "lit": "false"})
    b[(2, 1, 5)] = ("minecraft:furnace", {"facing": "north", "lit": "false"})
    b[(3, 1, 5)] = ("minecraft:crafting_table", {})
    b[(4, 1, 5)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(5, 1, 5)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    return size, b

# ---- Fjord: laid log walls, stone footings and turf roofs ----------------------------------------------------------

def gable(b, w, d, y0, slope, ridge, gable_wall, overhang=False):
    """A gable roof across x (ridge along z, gable ends in the north and south walls). slope = stairs, or a full block
    for a stepped turf roof. Returns the height above it."""
    x0, x1, y = 0, w - 1, y0
    stairs = slope.endswith("_stairs")
    while x1 - x0 >= 1:
        for z in range(d):
            if stairs:
                b[(x0, y, z)] = (slope, {"facing": "east", "half": "bottom", "shape": "straight"})
                b[(x1, y, z)] = (slope, {"facing": "west", "half": "bottom", "shape": "straight"})
            else:
                b[(x0, y, z)] = (slope, {})
                b[(x1, y, z)] = (slope, {})
        for x in range(x0 + 1, x1):
            for z in (0, d - 1):
                b[(x, y, z)] = (gable_wall, {})
        x0, x1, y = x0 + 1, x1 - 1, y + 1
    if x0 == x1:
        for z in range(d):
            b[(x0, y, z)] = (ridge, {})
        y += 1
    return y

def laft(w, d, wall_h, log, door_x, windows):
    """Walls of logs laid on their side (axis along each wall), corners standing, on a stone footing."""
    b = {}
    for x in range(w):
        for z in range(d):
            b[(x, 0, z)] = ("minecraft:cobblestone" if x in (0, w - 1) or z in (0, d - 1) else "minecraft:spruce_planks", {})
    for y in range(1, wall_h + 1):
        for x in range(w):
            for z in range(d):
                corner = x in (0, w - 1) and z in (0, d - 1)
                if corner:
                    b[(x, y, z)] = (log, {"axis": "y"})
                elif z in (0, d - 1):
                    b[(x, y, z)] = (log, {"axis": "x"})
                elif x in (0, w - 1):
                    b[(x, y, z)] = (log, {"axis": "z"})
    for (x, y, z) in windows:
        along_x = z in (0, d - 1)
        b[(x, y, z)] = ("minecraft:glass_pane", {"east": "true", "west": "true"} if along_x else {"north": "true", "south": "true"})
    b[(door_x, 1, 0)] = ("minecraft:spruce_door", {"facing": "south", "half": "lower", "hinge": "left", "open": "false"})
    b[(door_x, 2, 0)] = ("minecraft:spruce_door", {"facing": "south", "half": "upper", "hinge": "left", "open": "false"})
    return b

def fjord_stue():
    w, d = 7, 9
    b = laft(w, d, 3, "minecraft:spruce_log", 3, [(0, 2, 3), (0, 2, 6), (6, 2, 3), (6, 2, 6)])
    # a turf roof: stepped moss over a spruce ceiling, birch bark at the gable ends
    for x in range(1, w - 1):
        for z in range(1, d - 1):
            b[(x, 4, z)] = ("minecraft:spruce_planks", {})
    top = gable(b, w, d, 4, "minecraft:moss_block", "minecraft:moss_block", "minecraft:spruce_planks")
    bed(b, "red", (1, 1, 6), "south")
    bed(b, "white", (5, 1, 6), "south")
    b[(3, 1, 7)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:campfire", {"facing": "south", "lit": "false", "signal_fire": "false", "waterlogged": "false"})
    b[(5, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    return (w, top, d), b

def fjord_stabbur():
    """The storehouse: a log store with a deep plank roof, its chests and barrels round the walls."""
    w, d = 7, 7
    b = laft(w, d, 3, "minecraft:dark_oak_log", 3, [(3, 2, 6)])
    for x in range(1, w - 1):
        for z in range(1, d - 1):
            b[(x, 4, z)] = ("minecraft:dark_oak_planks", {})
    top = gable(b, w, d, 4, "minecraft:dark_oak_stairs", "minecraft:dark_oak_slab", "minecraft:dark_oak_planks")
    for x in (1, 2, 4, 5):
        b[(x, 1, 5)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(5, 1, 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(5, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    return (w, top, d), b

def fjord_smie():
    """The builder's workshop and home under a turf roof."""
    w, d = 7, 7
    b = laft(w, d, 3, "minecraft:spruce_log", 3, [(0, 2, 3), (6, 2, 3)])
    for x in range(1, w - 1):
        for z in range(1, d - 1):
            b[(x, 4, z)] = ("minecraft:spruce_planks", {})
    top = gable(b, w, d, 4, "minecraft:moss_block", "minecraft:moss_block", "minecraft:spruce_planks")
    b[(1, 1, 5)] = ("minecraft:crafting_table", {})
    b[(2, 1, 5)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    bed(b, "brown", (5, 1, 5), "north")
    return (w, top, d), b

# ---- Oasis: sandstone, flat roofs with a parapet -------------------------------------------------------------------

def flat_house(w, d, wall_h, door_x, windows):
    b = {}
    for x in range(w):
        for z in range(d):
            b[(x, 0, z)] = ("minecraft:smooth_sandstone", {})
    for y in range(1, wall_h + 1):
        for x in range(w):
            for z in range(d):
                if x in (0, w - 1) or z in (0, d - 1):
                    corner = x in (0, w - 1) and z in (0, d - 1)
                    b[(x, y, z)] = ("minecraft:cut_sandstone" if corner else "minecraft:sandstone", {})
    for (x, y, z) in windows:
        along_x = z in (0, d - 1)
        b[(x, y, z)] = ("minecraft:glass_pane", {"east": "true", "west": "true"} if along_x else {"north": "true", "south": "true"})
    b[(door_x, 1, 0)] = ("minecraft:jungle_door", {"facing": "south", "half": "lower", "hinge": "left", "open": "false"})
    b[(door_x, 2, 0)] = ("minecraft:jungle_door", {"facing": "south", "half": "upper", "hinge": "left", "open": "false"})
    roof = wall_h + 1
    for x in range(w):
        for z in range(d):
            b[(x, roof, z)] = ("minecraft:smooth_sandstone", {})
            if x in (0, w - 1) or z in (0, d - 1):
                b[(x, roof + 1, z)] = ("minecraft:sandstone_wall", wall_sides(x, z, w, d))
    # palm-wood beams poking out under the roof
    for z in range(1, d - 1, 2):
        b[(0, wall_h, z)] = ("minecraft:stripped_jungle_log", {"axis": "x"})
        b[(w - 1, wall_h, z)] = ("minecraft:stripped_jungle_log", {"axis": "x"})
    return roof + 2, b

def wall_sides(x, z, w, d):
    def edge(px, pz):
        return 0 <= px < w and 0 <= pz < d and (px in (0, w - 1) or pz in (0, d - 1))
    side = lambda ok: "low" if ok else "none"
    return {"north": side(edge(x, z - 1) and x in (0, w - 1)), "south": side(edge(x, z + 1) and x in (0, w - 1)),
            "west": side(edge(x - 1, z) and z in (0, d - 1)), "east": side(edge(x + 1, z) and z in (0, d - 1)),
            "up": "true", "waterlogged": "false"}

def oasis_house():
    w, d = 7, 9
    h, b = flat_house(w, d, 3, 3, [(0, 2, 2), (0, 2, 6), (6, 2, 2), (6, 2, 6)])
    bed(b, "orange", (1, 1, 6), "south")
    bed(b, "yellow", (5, 1, 6), "south")
    b[(3, 1, 7)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    b[(5, 1, 1)] = ("minecraft:potted_cactus", {})
    return (w, h, d), b

def oasis_storehouse():
    w, d = 9, 7
    h, b = flat_house(w, d, 4, 4, [(0, 3, 3), (8, 3, 3)])
    for x in (1, 3, 5, 7):
        b[(x, 1, 5)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(7, 1, 1)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(4, 1, 3)] = ("minecraft:lantern", {"hanging": "false"})
    return (w, h, d), b

def oasis_workshop():
    w, d = 7, 7
    h, b = flat_house(w, d, 3, 3, [(0, 2, 3), (6, 2, 3)])
    b[(1, 1, 5)] = ("minecraft:crafting_table", {})
    b[(2, 1, 5)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    bed(b, "orange", (5, 1, 5), "north")
    return (w, h, d), b


# ---- workplaces (M13): the same furniture in each pack's own shell -------------------------------------------------

def medieval_shell(w, d, windows):
    return house(w, d, 3, "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:spruce_stairs",
                 "minecraft:spruce_planks", "minecraft:spruce_slab", w // 2, windows)

def fjord_shell(w, d, windows):
    b = laft(w, d, 3, "minecraft:spruce_log", w // 2, windows)
    for x in range(1, w - 1):
        for z in range(1, d - 1):
            b[(x, 4, z)] = ("minecraft:spruce_planks", {})
    top = gable(b, w, d, 4, "minecraft:moss_block", "minecraft:moss_block", "minecraft:spruce_planks")
    return (w, top, d), b

def oasis_shell(w, d, windows):
    h, b = flat_house(w, d, 3, w // 2, windows)
    return (w, h, d), b

def furnish_farmhouse(b, w, d):
    b[(1, 1, d - 2)] = ("minecraft:composter", {"level": "0"})
    b[(2, 1, d - 2)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(w - 2, 1, d - 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def furnish_lumber_camp(b, w, d):
    b[(1, 1, d - 2)] = ("minecraft:crafting_table", {})
    b[(2, 1, d - 2)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(w - 2, 1, d - 2)] = ("minecraft:stonecutter", {"facing": "north"})
    for z in range(1, d - 2):
        b[(w - 2, 1, z)] = ("minecraft:oak_log", {"axis": "z"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def furnish_fishery(b, w, d):
    b[(1, 1, d - 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(2, 1, d - 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(w - 2, 1, d - 2)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def furnish_barn(b, w, d):
    b[(1, 1, d - 2)] = ("minecraft:loom", {"facing": "north"})
    b[(2, 1, d - 2)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    for x in range(w - 3, w - 1):
        b[(x, 1, d - 2)] = ("minecraft:hay_block", {"axis": "y"})
    b[(w - 2, 1, d - 3)] = ("minecraft:hay_block", {"axis": "y"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def with_pen(size, b, fence, gate):
    """A fenced pen behind the building (south), reached through a back door; the pen is 5 deep."""
    w, h, d = size
    door = b[(w // 2, 1, 0)][0]
    b[(w // 2, 1, d - 1)] = (door, {"facing": "north", "half": "lower", "hinge": "left", "open": "false"})
    b[(w // 2, 2, d - 1)] = (door, {"facing": "north", "half": "upper", "hinge": "left", "open": "false"})
    pd = 5
    for x in range(w):
        for z in range(d, d + pd):
            edge = x in (0, w - 1) or z == d + pd - 1
            b[(x, 0, z)] = ("minecraft:grass_block", {"snowy": "false"})
            if edge:
                if (x, z) == (w // 2, d + pd - 1):
                    b[(x, 1, z)] = (gate, {"facing": "south", "open": "false", "in_wall": "false", "powered": "false"})
                else:
                    sides = {"north": "false", "south": "false", "east": "false", "west": "false"}
                    if x in (0, w - 1):
                        sides["north"] = "true"
                        if z < d + pd - 1:
                            sides["south"] = "true"
                    if z == d + pd - 1:
                        if x > 0:
                            sides["west"] = "true"
                        if x < w - 1:
                            sides["east"] = "true"
                    b[(x, 1, z)] = (fence, sides)
    return (w, h, d + pd), b

def workplace(shell, furnish, w, d, windows, pen=None):
    def make():
        size, b = shell(w, d, windows)
        furnish(b, w, d)
        if pen:
            return with_pen(size, b, *pen)
        return size, b
    return make

# ---- the later jobs' workplaces (M14) -----------------------------------------------------------------------------

def furnish_smithy(b, w, d):
    b[(1, 1, d - 2)] = ("minecraft:anvil", {"facing": "east"})
    b[(2, 1, d - 2)] = ("minecraft:blast_furnace", {"facing": "north", "lit": "false"})
    b[(3, 1, d - 2)] = ("minecraft:furnace", {"facing": "north", "lit": "false"})
    b[(w - 2, 1, d - 2)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(w - 2, 1, 1)] = ("minecraft:grindstone", {"face": "floor", "facing": "north"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def furnish_infirmary(b, w, d):
    bed(b, "white", (1, 1, d - 3), "south")
    bed(b, "white", (2, 1, d - 3), "south")
    bed(b, "light_blue", (w - 2, 1, d - 3), "south")
    b[(w // 2, 1, d - 2)] = ("minecraft:brewing_stand", {"has_bottle_0": "false", "has_bottle_1": "false", "has_bottle_2": "false"})
    b[(w // 2 + 1, 1, d - 2)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    b[(w - 2, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def furnish_market(b, w, d):
    b[(w // 2, 1, d - 2)] = ("minecraft:bell", {"attachment": "floor", "facing": "north", "powered": "false"})
    b[(1, 1, d - 2)] = ("minecraft:chest", {"facing": "north", "type": "single"})
    b[(2, 1, d - 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(w - 2, 1, d - 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(w - 3, 1, d - 2)] = ("minecraft:barrel", {"facing": "up", "open": "false"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    b[(w - 2, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def furnish_school(b, w, d, seat="minecraft:oak_stairs"):
    b[(w // 2, 1, d - 2)] = ("minecraft:lectern", {"facing": "north", "has_book": "false", "powered": "false"})
    b[(1, 1, d - 2)] = ("minecraft:bookshelf", {})
    b[(w - 2, 1, d - 2)] = ("minecraft:bookshelf", {})
    for z in (2, 3):
        for x in range(1, w - 1):
            if x != w // 2:
                b[(x, 1, z)] = (seat, {"facing": "north", "half": "bottom", "shape": "straight", "waterlogged": "false"})
    b[(1, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})
    b[(w - 2, 1, 1)] = ("minecraft:lantern", {"hanging": "false"})

def school_with(seat):
    return lambda b, w, d: furnish_school(b, w, d, seat)

SIDE_WINDOWS = lambda w, d: [(0, 2, d // 2), (w - 1, 2, d // 2)]

PACKS = {
    "medieval": ({"name": "Medieval", "description": "Timber, cobblestone and hip roofs.", "author": "Stad"}, {
        "builder_hut": ("stad:builder_hut", "Builder's Hut", "Where the town's builder lives and works.", builder_hut, ["small", "workplace"]),
        "cottage": ("stad:home", "Cottage", "A cozy home with two beds.", cottage, ["small", "home"]),
        "warehouse": ("stad:warehouse", "Warehouse", "Chests and barrels for the town's goods.", warehouse, ["storage"]),
        "kitchen": ("stad:kitchen", "Kitchen", "A smoker and a furnace for the town's cook.", kitchen, ["small", "workplace"]),
        "mine": ("stad:mine", "Mine", "A headframe over a shaft the town's miner digs deeper, layer by layer.", mine, ["workplace"]),
        "farmhouse": ("stad:farmhouse", "Farmhouse", "Where the farmers keep their tools and take in the harvest.",
                      workplace(medieval_shell, furnish_farmhouse, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "lumber_camp": ("stad:lumber_camp", "Lumber Camp", "A woodcutters' hut with a log pile and a stonecutter.",
                        workplace(medieval_shell, furnish_lumber_camp, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "fishery": ("stad:fishery", "Fishery", "Barrels for the catch; build it by the water.",
                    workplace(medieval_shell, furnish_fishery, 7, 5, SIDE_WINDOWS(7, 5)), ["small", "workplace"]),
        "barn": ("stad:barn", "Barn", "Hay, a loom and a fenced pen behind it for the shepherd's animals.",
                 workplace(medieval_shell, furnish_barn, 9, 7, SIDE_WINDOWS(9, 7), ("minecraft:oak_fence", "minecraft:oak_fence_gate")), ["workplace"]),
        "smithy": ("stad:smithy", "Smithy", "An anvil, a blast furnace and a grindstone, where the smith makes and mends the town's tools.",
                   workplace(medieval_shell, furnish_smithy, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "infirmary": ("stad:infirmary", "Infirmary", "Three beds and a brewing stand, where the healer looks after the sick.",
                      workplace(medieval_shell, furnish_infirmary, 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
        "market": ("stad:market", "Market Hall", "The merchant's bell and the barrels of goods for sale.",
                   workplace(medieval_shell, furnish_market, 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
        "school": ("stad:school", "Schoolhouse", "Benches facing the teacher's lectern, between the bookshelves.",
                   workplace(medieval_shell, school_with("minecraft:spruce_stairs"), 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
    }),
    "fjord": ({"name": "Fjord", "description": "Laid log walls on stone footings, under turf roofs.", "author": "Stad"}, {
        "stue": ("stad:home", "Stue", "A log house with a turf roof and two beds.", fjord_stue, ["small", "home"]),
        "stabbur": ("stad:warehouse", "Stabbur", "The log storehouse, chests and barrels round its walls.", fjord_stabbur, ["storage"]),
        "smie": ("stad:builder_hut", "Builder's Smie", "The builder's workshop and bed under a turf roof.", fjord_smie, ["small", "workplace"]),
        "gardshus": ("stad:farmhouse", "Gardshus", "The farm's log house, with a composter and the harvest chest.",
                     workplace(fjord_shell, furnish_farmhouse, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "vedskjul": ("stad:lumber_camp", "Vedskjul", "The woodshed: a log pile, a crafting table and a stonecutter under turf.",
                     workplace(fjord_shell, furnish_lumber_camp, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "naust": ("stad:fishery", "Naust", "The boathouse by the water, its barrels for the catch.",
                  workplace(fjord_shell, furnish_fishery, 7, 5, SIDE_WINDOWS(7, 5)), ["small", "workplace"]),
        "fjos": ("stad:barn", "Fjos", "The byre: hay and a loom inside, a pen behind.",
                 workplace(fjord_shell, furnish_barn, 9, 7, SIDE_WINDOWS(9, 7), ("minecraft:spruce_fence", "minecraft:spruce_fence_gate")), ["workplace"]),
        "esse": ("stad:smithy", "Esse", "The forge under a turf roof: anvil, blast furnace and grindstone.",
                 workplace(fjord_shell, furnish_smithy, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "sjukestue": ("stad:infirmary", "Sjukestue", "The sick-house: beds by the wall and the healer's brewing stand.",
                      workplace(fjord_shell, furnish_infirmary, 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
        "handelsbu": ("stad:market", "Handelsbu", "The trading booth, its bell and barrels of goods.",
                      workplace(fjord_shell, furnish_market, 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
        "skulestue": ("stad:school", "Skulestue", "The schoolroom in a log house: benches and a lectern.",
                      workplace(fjord_shell, school_with("minecraft:spruce_stairs"), 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
    }),
    "oasis": ({"name": "Oasis", "description": "Sandstone houses with flat roofs and parapets, palm-wood beams.", "author": "Stad"}, {
        "house": ("stad:home", "Sandstone House", "A cool sandstone house with two beds.", oasis_house, ["small", "home"]),
        "storehouse": ("stad:warehouse", "Storehouse", "Thick walls keep the town's goods cool.", oasis_storehouse, ["storage"]),
        "workshop": ("stad:builder_hut", "Builder's Workshop", "Where the builder works and sleeps.", oasis_workshop, ["small", "workplace"]),
        "farmhouse": ("stad:farmhouse", "Oasis Farmhouse", "Cool walls for the harvest, a composter for the fields.",
                      workplace(oasis_shell, furnish_farmhouse, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "woodyard": ("stad:lumber_camp", "Woodyard", "Where palm and acacia are cut and stacked.",
                     workplace(oasis_shell, furnish_lumber_camp, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "fishery": ("stad:fishery", "Riverside Fishery", "Barrels for the catch, by the river.",
                    workplace(oasis_shell, furnish_fishery, 7, 5, SIDE_WINDOWS(7, 5)), ["small", "workplace"]),
        "stable": ("stad:barn", "Stable", "Hay and a loom in the shade, a pen behind.",
                   workplace(oasis_shell, furnish_barn, 9, 7, SIDE_WINDOWS(9, 7), ("minecraft:jungle_fence", "minecraft:jungle_fence_gate")), ["workplace"]),
        "forge": ("stad:smithy", "Desert Forge", "Thick sandstone walls round the anvil and the blast furnace.",
                  workplace(oasis_shell, furnish_smithy, 7, 7, SIDE_WINDOWS(7, 7)), ["small", "workplace"]),
        "house_of_healing": ("stad:infirmary", "House of Healing", "Cool rooms with beds for the sick, and the healer's brewing stand.",
                             workplace(oasis_shell, furnish_infirmary, 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
        "souk": ("stad:market", "Souk", "The covered market: a bell to call buyers, barrels of goods.",
                 workplace(oasis_shell, furnish_market, 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
        "school": ("stad:school", "Oasis School", "Sandstone benches facing the lectern.",
                   workplace(oasis_shell, school_with("minecraft:sandstone_stairs"), 9, 7, SIDE_WINDOWS(9, 7)), ["workplace"]),
    }),
}

def main():
    res = sys.argv[1]
    for pack, (info, buildings) in PACKS.items():
        os.makedirs(os.path.join(res, "data/stad/structure", pack), exist_ok=True)
        os.makedirs(os.path.join(res, "data/stad/stad/buildings", pack), exist_ok=True)
        for key, (btype, name, description, make, tags) in buildings.items():
            size, blocks = make()
            write_structure(os.path.join(res, "data/stad/structure", pack, key + ".nbt"), size, blocks)
            manifest = {"type": btype, "name": name, "description": description, "structure": f"stad:{pack}/{key}", "tags": tags}
            with open(os.path.join(res, "data/stad/stad/buildings", pack, key + ".json"), "w") as f:
                json.dump(manifest, f, indent=2)
                f.write("\n")
            print(f"{pack}/{key}: {size[0]}x{size[1]}x{size[2]}, {len(blocks)} blocks")
        with open(os.path.join(res, "data/stad/stad/packs", pack + ".json"), "w") as f:
            json.dump(info, f, indent=2)
            f.write("\n")

if __name__ == "__main__":
    main()
