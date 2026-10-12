#!/usr/bin/env python3
"""Reads a structure .nbt and prints each layer as characters (or renders an isometric PNG with --png out.png)."""
import gzip, struct, sys

def read_nbt(path):
    data = gzip.open(path).read()
    pos = 0
    def u(fmt, n):
        nonlocal pos
        v = struct.unpack_from(fmt, data, pos); pos += n; return v[0]
    def string():
        n = u(">H", 2); nonlocal pos
        s = data[pos:pos+n].decode("utf-8"); pos += n; return s
    def payload(t):
        nonlocal pos
        if t == 1: return u(">b", 1)
        if t == 2: return u(">h", 2)
        if t == 3: return u(">i", 4)
        if t == 4: return u(">q", 8)
        if t == 5: return u(">f", 4)
        if t == 6: return u(">d", 8)
        if t == 7:
            n = u(">i", 4); b = data[pos:pos+n]; pos += n; return b
        if t == 8: return string()
        if t == 9:
            et = u(">b", 1); n = u(">i", 4); return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = u(">b", 1)
                if tt == 0: return out
                k = string(); out[k] = payload(tt)
        if t == 11:
            n = u(">i", 4); return [u(">i", 4) for _ in range(n)]
        if t == 12:
            n = u(">i", 4); return [u(">q", 8) for _ in range(n)]
        raise ValueError(t)
    t = u(">b", 1); string(); return payload(t)

def load(path):
    root = read_nbt(path)
    size = root["size"]
    pal = root["palette"]
    blocks = {}
    for b in root["blocks"]:
        st = pal[b["state"]]
        blocks[tuple(b["pos"])] = (st["Name"].replace("minecraft:", ""), st.get("Properties", {}))
    return size, blocks

def char(name, props):
    if name == "air": return "."
    if "stairs" in name:
        return {"north": "^", "south": "v", "east": ">", "west": "<"}.get(props.get("facing"), "S")
    if "slab" in name: return "_"
    if name.endswith("_log"): return "#"
    if "glass" in name: return "o"
    if "door" in name: return "D"
    if name.endswith("_bed"): return "B" if props.get("part") == "head" else "b"
    if name == "ladder": return "H"
    if name == "chest": return "C"
    if name == "barrel": return "R"
    if "lantern" in name: return "*"
    if "torch" in name: return "i"
    if "fence" in name: return "|"
    if "carpet" in name: return "~"
    if name in ("crafting_table", "smoker", "furnace", "stonecutter", "lectern", "bookshelf"): return "F"
    if name == "campfire": return "^"
    return "X"

if __name__ == "__main__":
    size, blocks = load(sys.argv[1])
    print("size", size)
    for y in range(size[1]):
        print(f"--- y={y}")
        for z in range(size[2]):
            print("".join(char(*blocks[(x, y, z)]) if (x, y, z) in blocks else " " for x in range(size[0])))
