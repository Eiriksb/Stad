"""Runs inside Krita: paints every texture in textures.py as a layered Krita document (art/krita/...kra) and exports
it as a PNG into the mod's assets (with its .mcmeta when it has one).

    tools/art/paint.sh            (or: cd <repo> && PYTHONPATH=tools/art kritarunner -s paint <repo> [name...])

Open a .kra in Krita to repaint by hand; note that running this again paints over it.
"""
import json
import os

from krita import InfoObject, Krita

import textures


def bgra(rows):
    out = bytearray()
    for row in rows:
        for c in row:
            if c is None:
                out += b"\0\0\0\0"
            else:
                out += bytes((c[2], c[1], c[0], c[3]))
    return bytes(out)


def paint(k, art, repo):
    doc = k.createDocument(art.w, art.h, art.name, "RGBA", "U8", "", 72.0)
    doc.setBatchmode(True)
    root = doc.rootNode()
    for n in list(root.childNodes()):
        n.remove()
    below = None
    for layer in art.layers:
        node = doc.createNode(layer.name, "paintlayer")
        root.addChildNode(node, below)
        node.setPixelData(bgra(layer.px), 0, 0, art.w, art.h)
        below = node
    doc.refreshProjection()
    rel = art.out[len(textures.ASSETS):-len(".png")]
    kra = os.path.join(repo, "art", "krita", rel + ".kra")
    os.makedirs(os.path.dirname(kra), exist_ok=True)
    doc.saveAs(kra)
    png = os.path.join(repo, art.out)
    os.makedirs(os.path.dirname(png), exist_ok=True)
    info = InfoObject()
    info.setProperty("alpha", True)
    info.setProperty("compression", 9)
    info.setProperty("indexed", False)
    info.setProperty("saveSRGBProfile", False)
    doc.exportImage(png, info)
    if art.mcmeta:
        with open(png + ".mcmeta", "w") as f:
            json.dump(art.mcmeta, f, indent=2)
            f.write("\n")
    doc.close()
    print("painted", rel)


def __main__(args):
    repo = args[0]
    only = set(args[1:])
    k = Krita.instance()
    for fn in textures.ALL:
        art = fn()
        if not only or art.name in only:
            paint(k, art, repo)
