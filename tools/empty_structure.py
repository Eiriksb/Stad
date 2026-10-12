#!/usr/bin/env python3
"""Writes an empty 1x1x1 structure template (for game tests that only need somewhere to run): empty_structure.py <out.nbt>"""
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
root = tag(10, "", compound(
    tag(3, "DataVersion", struct.pack(">i", DATA_VERSION_1_21_1)),
    tag(9, "size", int_list([1, 1, 1])),
    tag(9, "palette", compound_list([compound(tag(8, "Name", name("minecraft:air")))])),
    tag(9, "blocks", compound_list([compound(tag(9, "pos", int_list([0, 0, 0])), tag(3, "state", struct.pack(">i", 0)))])),
    tag(9, "entities", bytes([0]) + struct.pack(">i", 0)),
))
with gzip.open(sys.argv[1], "wb") as f:
    f.write(root)
