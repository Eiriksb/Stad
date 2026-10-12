#!/usr/bin/env python3
"""Generates the bundled "Medieval" building pack: vanilla structure templates (.nbt) plus their building manifests.

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
    """blocks: {(x, y, z): (name, {props})}; everything else in the box is air."""
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
                name, props = blocks.get((x, y, z), ("minecraft:air", {}))
                entries.append(_compound(_tag(9, "pos", _int_list([x, y, z])), _tag(3, "state", struct.pack(">i", state_id(name, props)))))
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

BUILDINGS = {
    "builder_hut": ("stad:builder_hut", "Builder's Hut", "Where the town's builder lives and works.", builder_hut, ["small", "workplace"]),
    "cottage": ("stad:home", "Cottage", "A cozy home with two beds.", cottage, ["small", "home"]),
    "warehouse": ("stad:warehouse", "Warehouse", "Chests and barrels for the town's goods.", warehouse, ["storage"]),
}

def main():
    res = sys.argv[1]
    for key, (btype, name, description, make, tags) in BUILDINGS.items():
        size, blocks = make()
        write_structure(os.path.join(res, "data/stad/structure/medieval", key + ".nbt"), size, blocks)
        manifest = {"type": btype, "name": name, "description": description, "structure": "stad:medieval/" + key, "tags": tags}
        with open(os.path.join(res, "data/stad/stad/buildings/medieval", key + ".json"), "w") as f:
            json.dump(manifest, f, indent=2)
            f.write("\n")
        print(f"{key}: {size[0]}x{size[1]}x{size[2]}, {len(blocks)} blocks")
    with open(os.path.join(res, "data/stad/stad/packs/medieval.json"), "w") as f:
        json.dump({"name": "Medieval", "description": "Timber, cobblestone and hip roofs.", "author": "Stad"}, f, indent=2)
        f.write("\n")

if __name__ == "__main__":
    main()
