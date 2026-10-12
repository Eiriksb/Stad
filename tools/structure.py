#!/usr/bin/env python3
"""Writes a structure template for game tests: an empty box, optionally with a floor.

    structure.py <out.nbt> [<x> <y> <z> [<floor block>]]
    structure.py empty.nbt                        # 1x1x1 of air
    structure.py room.nbt 9 4 9 minecraft:stone   # 9x4x9, stone floor at y=0
"""
import gzip, struct, sys

def name(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b

def tag(kind, key, payload):
    return bytes([kind]) + name(key) + payload

def compound(*items):
    return b"".join(items) + b"\x00"

def int_list(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)

def compound_list(items):
    return bytes([10]) + struct.pack(">i", len(items)) + b"".join(items)

DATA_VERSION_1_21_1 = 3955
out = sys.argv[1]
sx, sy, sz = (int(a) for a in sys.argv[2:5]) if len(sys.argv) >= 5 else (1, 1, 1)
floor = sys.argv[5] if len(sys.argv) >= 6 else None
palette = ["minecraft:air"] + ([floor] if floor else [])
blocks = []
for y in range(sy):
    for z in range(sz):
        for x in range(sx):
            state = 1 if floor and y == 0 else 0
            blocks.append(compound(tag(9, "pos", int_list([x, y, z])), tag(3, "state", struct.pack(">i", state))))
root = tag(10, "", compound(
    tag(3, "DataVersion", struct.pack(">i", DATA_VERSION_1_21_1)),
    tag(9, "size", int_list([sx, sy, sz])),
    tag(9, "palette", compound_list([compound(tag(8, "Name", name(p))) for p in palette])),
    tag(9, "blocks", compound_list(blocks)),
    tag(9, "entities", bytes([0]) + struct.pack(">i", 0)),
))
with gzip.open(out, "wb") as f:
    f.write(root)
