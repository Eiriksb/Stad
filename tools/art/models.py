"""Builds Stad's block models in Blockbench, from the shapes below: each becomes a Blockbench project
(art/blockbench/<name>.bbmodel, to open and change by hand), a Java block model in the mod's assets, and a preview
render (art/blockbench/<name>.png).

Blockbench has to be running with remote debugging on (tools/art/models.sh starts it):
    blockbench --remote-debugging-port=9333
Textures come from the mod's assets, so paint them first (paint.sh).
"""
import base64
import json
import os
import sys

from cdp import Cdp

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TEX = os.path.join(REPO, "mod/src/main/resources/assets/stad/textures/block/")
MODELS = os.path.join(REPO, "mod/src/main/resources/assets/stad/models/block/")
SOURCES = os.path.join(REPO, "art/blockbench/")

ALL_FACES = ("north", "east", "south", "west", "up", "down")


def cube(name, frm, to, tex, uv=None, faces=ALL_FACES, rotation=None):
    """A box from {frm} to {to} (0..16), every face textured with {tex} at {uv} (by face, or one for all; default: the
    box's own size, as Blockbench would). {rotation}: (axis, angle, origin)."""
    return {"name": name, "from": frm, "to": to, "tex": tex, "uv": uv, "faces": faces, "rotation": rotation}


def auto_uv(face, f, t):
    x0, y0, z0 = f
    x1, y1, z1 = t
    if face in ("north", "south"):
        return [x0, 16 - y1, x1, 16 - y0]
    if face in ("east", "west"):
        return [z0, 16 - y1, z1, 16 - y0]
    return [x0, z0, x1, z1]


# ---- the models ---------------------------------------------------------------------------------------------------

TILT = ("x", 22.5, [8, 13, 8])  # the drafting board leans back: its north edge up


def architect_table():
    wood, paper, bits, roll = "architect_table_wood", "architect_table_paper", "architect_table_bits", "architect_table_roll"
    c = []
    for x, z, top in ((1, 13, 11), (13, 13, 11), (1, 1, 15), (13, 1, 15)):  # the back legs reach up to the board's high edge
        c.append(cube("leg", [x, 0, z], [x + 2, top, z + 2], wood, uv={"north": [0, 0, 2, top], "south": [2, 0, 4, top], "east": [4, 0, 6, top],
                                                                        "west": [6, 0, 8, top], "up": [8, 0, 10, 2], "down": [8, 2, 10, 4]}))
    c.append(cube("stretcher west", [1.5, 3, 3], [2.5, 4.5, 13], wood))
    c.append(cube("stretcher east", [13.5, 3, 3], [14.5, 4.5, 13], wood))
    c.append(cube("foot bar", [2.5, 3.5, 7.5], [13.5, 4.5, 8.5], wood))
    c.append(cube("board", [0.5, 13, 0.5], [15.5, 14, 15.5], wood, rotation=TILT))
    c.append(cube("drawing", [2, 14, 2], [14, 14.1, 13], paper, uv={"up": [0, 0, 16, 16]}, faces=("up",), rotation=TILT))
    c.append(cube("ledge", [1, 14, 14.5], [15, 15, 15.5], wood, rotation=TILT))
    c.append(cube("roll", [3, 14, 13], [11, 15.5, 14.5], roll, uv={"north": [0, 4, 16, 12], "south": [0, 4, 16, 12], "up": [0, 2, 16, 14],
                                                                   "down": [0, 2, 16, 14], "east": [8, 8, 16, 16], "west": [8, 8, 16, 16]}, rotation=TILT))
    c.append(cube("ink pot", [11.5, 14, 3.5], [13.5, 15.5, 5.5], bits, uv={f: [9, 1, 13, 5] for f in ALL_FACES}, rotation=TILT))
    c.append(cube("ink cap", [12, 15.5, 4], [13, 16, 5], bits, uv={f: [1, 1, 3, 3] for f in ALL_FACES}, rotation=TILT))
    c.append(cube("ruler", [2, 14.1, 4], [3, 14.4, 12], bits, uv={f: [0, 8, 8, 10] for f in ALL_FACES}, rotation=TILT))
    return c


def town_hall():
    side, top, book, bottom, bits = "town_hall_side", "town_hall_top", "town_hall_book", "town_hall_bottom", "architect_table_bits"
    c = []
    c.append(cube("desk", [1, 0, 1], [15, 13, 15], side, uv={"north": [1, 3, 15, 16], "south": [1, 3, 15, 16], "east": [1, 3, 15, 16],
                                                            "west": [1, 3, 15, 16], "up": [1, 1, 15, 15], "down": [1, 1, 15, 15]}))
    c[-1]["tex_by_face"] = {"up": top, "down": bottom}
    c.append(cube("top", [0, 13, 0], [16, 14.5, 16], top, uv={"north": [0, 0, 16, 1.5], "south": [0, 0, 16, 1.5], "east": [0, 0, 16, 1.5],
                                                             "west": [0, 0, 16, 1.5], "up": [0, 0, 16, 16], "down": [0, 0, 16, 16]}))
    c.append(cube("ledger", [2.5, 14.5, 3.5], [12.5, 15.25, 12.5], book, uv={"up": [0, 0, 16, 16], "down": [0, 0, 16, 2], "north": [0, 0, 16, 1],
                                                                          "south": [0, 15, 16, 16], "east": [0, 0, 16, 1], "west": [0, 0, 16, 1]}))
    c.append(cube("ink pot", [13, 14.5, 12.5], [15, 16.5, 14.5], bits, uv={f: [9, 1, 13, 5] for f in ALL_FACES}))
    c.append(cube("quill", [13.75, 16.5, 12], [14.25, 20, 12.5], bits, uv={f: [12, 9, 13, 15] for f in ALL_FACES}, rotation=("x", -22.5, [14, 16.5, 13.5])))
    return c


MODELS_BY_NAME = {"architect_table": architect_table, "town_hall": town_hall}
PARTICLE = {"architect_table": "architect_table_wood", "town_hall": "town_hall_side"}


# ---- Blockbench ---------------------------------------------------------------------------------------------------

def js_for(name, cubes):
    textures = sorted({c["tex"] for c in cubes} | {t for c in cubes for t in c.get("tex_by_face", {}).values()})
    elements = []
    for c in cubes:
        faces = {}
        for f in ALL_FACES:
            if f not in c["faces"]:
                continue
            uv = c["uv"].get(f) if isinstance(c["uv"], dict) else c["uv"]
            if uv is None:
                uv = auto_uv(f, c["from"], c["to"])
            faces[f] = {"uv": uv, "tex": c.get("tex_by_face", {}).get(f, c["tex"])}
        e = {"name": c["name"], "from": c["from"], "to": c["to"], "faces": faces}
        if c["rotation"]:
            axis, angle, origin = c["rotation"]
            e["rotation"] = [angle if axis == "x" else 0, angle if axis == "y" else 0, angle if axis == "z" else 0]
            e["origin"] = origin
        elements.append(e)
    return """(async () => {
  if (Project) { Project.close(true); }
  newProject(Formats.java_block);
  Project.name = %(name)s;
  Project.ambientocclusion = true;
  const tex = {};
  for (const [t, data] of Object.entries(%(textures)s)) {
    // from the file's bytes, not its path: Blockbench would show a cached copy after a repaint
    const x = new Texture({name: t + '.png', namespace: 'stad', folder: 'block'}).fromDataURL(data).add(false);
    x.path = %(texdir)s + t + '.png';
    x.namespace = 'stad';
    x.folder = 'block';
    tex[t] = x;
  }
  await new Promise(r => setTimeout(r, 400));
  for (const e of %(elements)s) {
    const faces = {};
    for (const [f, d] of Object.entries(e.faces)) {
      faces[f] = {uv: d.uv, texture: tex[d.tex].uuid};
    }
    for (const f of ['north', 'east', 'south', 'west', 'up', 'down']) {
      if (!faces[f]) faces[f] = {texture: null};
    }
    const c = new Cube({name: e.name, from: e.from, to: e.to, faces: faces, rotation: e.rotation || [0, 0, 0],
                        origin: e.origin || [8, 8, 8], autouv: 0, box_uv: false}).init();
  }
  Canvas.updateAll();
  const bb = Codecs.project.compile({raw: false});
  const java = Codecs.java_block.compile({raw: false});
  Preview.selected.camera.position.set(26, 24, 30);
  Preview.selected.controls.target.set(0, 4, 0);
  Preview.selected.camera.lookAt(0, 4, 0);
  await new Promise(r => setTimeout(r, 400));
  Preview.selected.render();
  const shot = Preview.selected.canvas.toDataURL('image/png');
  return {bb, java, shot};
})()""" % {"name": json.dumps(name), "textures": json.dumps({t: "data:image/png;base64," + base64.b64encode(open(TEX + t + ".png", "rb").read()).decode()
                                                       for t in textures}), "texdir": json.dumps(TEX), "elements": json.dumps(elements)}


DISPLAY_PARENT = "minecraft:block/block"  # the hand, GUI and ground transforms of an ordinary block


def build(c, name):
    r = c.eval(js_for(name, MODELS_BY_NAME[name]()))
    os.makedirs(SOURCES, exist_ok=True)
    bb = r["bb"] if isinstance(r["bb"], str) else json.dumps(r["bb"])
    with open(os.path.join(SOURCES, name + ".bbmodel"), "w") as f:
        f.write(bb)
    java = json.loads(r["java"]) if isinstance(r["java"], str) else r["java"]
    java.pop("credit", None)
    java = {"parent": DISPLAY_PARENT, **java}
    java["textures"] = {k: v if ":" in v else "stad:block/" + v.split("/")[-1] for k, v in java.get("textures", {}).items()}
    java.pop("format_version", None)
    java["textures"]["particle"] = "stad:block/" + PARTICLE[name]
    with open(os.path.join(MODELS, name + ".json"), "w") as f:
        json.dump(java, f, indent=2)
        f.write("\n")
    with open(os.path.join(SOURCES, name + ".png"), "wb") as f:
        f.write(base64.b64decode(r["shot"].split(",", 1)[1]))
    print("built", name, len(java.get("elements", [])), "elements")


if __name__ == "__main__":
    cdp = Cdp(int(os.environ.get("BB_PORT", "9333")))
    for n in sys.argv[1:] or MODELS_BY_NAME:
        build(cdp, n)
