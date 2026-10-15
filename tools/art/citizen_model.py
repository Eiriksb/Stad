"""The citizen in Blockbench: the player model they're drawn with (box UV, 64x64, the outer layer half a pixel out),
as a project to open and paint by hand (art/blockbench/citizen.bbmodel, with a sample citizen's skin and face at
twice the resolution), and a line-up of random citizens rendered in it (art/blockbench/citizens.png).

Blockbench has to be running with remote debugging on:
    blockbench --remote-debugging-port=9333
    python3 tools/art/citizen_model.py [count]
"""
import base64
import io
import json
import os
import random
import sys

import citizens
from cdp import Cdp

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "art/blockbench/")

# bone: (pivot, [(name, from, size, uv offset, inflate)])
BONES = {
    "head": ([0, 24, 0], [("head", [-4, 24, -4], [8, 8, 8], [0, 0], 0), ("hat", [-4, 24, -4], [8, 8, 8], [32, 0], 0.5)]),
    "body": ([0, 24, 0], [("body", [-4, 12, -2], [8, 12, 4], [16, 16], 0), ("jacket", [-4, 12, -2], [8, 12, 4], [16, 32], 0.25)]),
    "right_arm": ([-5, 22, 0], [("right_arm", [-8, 12, -2], [4, 12, 4], [40, 16], 0), ("right_sleeve", [-8, 12, -2], [4, 12, 4], [40, 32], 0.25)]),
    "left_arm": ([5, 22, 0], [("left_arm", [4, 12, -2], [4, 12, 4], [32, 48], 0), ("left_sleeve", [4, 12, -2], [4, 12, 4], [48, 48], 0.25)]),
    "right_leg": ([-1.9, 12, 0], [("right_leg", [-4, 0, -2], [4, 12, 4], [0, 16], 0), ("right_pants", [-4, 0, -2], [4, 12, 4], [0, 32], 0.25)]),
    "left_leg": ([1.9, 12, 0], [("left_leg", [0, 0, -2], [4, 12, 4], [16, 48], 0), ("left_pants", [0, 0, -2], [4, 12, 4], [0, 48], 0.25)]),
}


def data_url(img):
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def build_js(texture):
    return """(async () => {
  if (Project) { Project.close(true); }
  newProject(Formats.modded_entity);
  Project.name = 'citizen';
  Project.texture_width = 64;
  Project.texture_height = 64;
  const tex = new Texture({name: 'citizen.png'}).fromDataURL(%(tex)s).add(false);
  await new Promise(r => setTimeout(r, 300));
  for (const [bone, [pivot, cubes]] of Object.entries(%(bones)s)) {
    const g = new Group({name: bone, origin: pivot}).init();
    for (const [name, from, size, uv, inflate] of cubes) {
      const c = new Cube({name, from, to: [from[0] + size[0], from[1] + size[1], from[2] + size[2]], box_uv: true,
                          uv_offset: uv, inflate, origin: pivot}).addTo(g).init();
      c.applyTexture(tex, true);
    }
  }
  Canvas.updateAll();
  return true;
})()""" % {"tex": json.dumps(texture), "bones": json.dumps(BONES)}


def swap_js(texture):
    return """(async () => {
  const t = Texture.all[0];
  t.fromDataURL(%s);
  await new Promise(r => setTimeout(r, 250));
  Canvas.updateAll();
  return true;
})()""" % json.dumps(texture)


SHOT_JS = """(async () => {
  const p = Preview.selected;
  p.camera.position.set(0, 22, -46);
  p.controls.target.set(0, 16, 0);
  p.camera.lookAt(0, 16, 0);
  await new Promise(r => setTimeout(r, 300));
  p.render();
  return p.canvas.toDataURL('image/png');
})()"""


def png(data):
    from PIL import Image
    return Image.open(io.BytesIO(base64.b64decode(data.split(",", 1)[1]))).convert("RGBA")


def main(count):
    from PIL import Image
    cdp = Cdp(int(os.environ.get("BB_PORT", "9333")))
    r = random.Random(11)
    p = citizens.parts()
    looks = [citizens.random_look(r, i % 2 == 1, p) for i in range(count)]
    for i, look in enumerate(looks):  # one of each job first, so the line-up shows them
        jobs = list(p["jobs"])
        look["job"] = jobs[i % len(jobs)] if i < len(jobs) else look["job"]
    first = citizens.textured(looks[0], "smile")
    cdp.eval(build_js(data_url(first)))
    bb = cdp.eval("Codecs.project.compile({raw: false})")
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "citizen.bbmodel"), "w") as f:
        f.write(bb if isinstance(bb, str) else json.dumps(bb))
    shots = []
    for look in looks:
        cdp.eval(swap_js(data_url(citizens.textured(look, r.choice(["rest", "smile", "open", "wide"])))))
        shots.append(png(cdp.eval(SHOT_JS)))
    # crop each render to the figure and line them up
    from PIL import ImageChops
    crops = []
    for s in shots:
        # the background is a soft gradient: compare each row with its own edge colour
        diff = Image.new("L", s.size)
        px, dp = s.load(), diff.load()
        for y in range(s.height):
            edge = px[2, y]
            for x in range(s.width):
                c = px[x, y]
                dp[x, y] = 255 if sum(abs(c[i] - edge[i]) for i in range(3)) > 40 else 0
        box = diff.getbbox() or (0, 0, s.width, s.height)
        half = (box[3] - box[1]) * 3 // 10  # the floor grid reaches the edges: keep the middle, as wide as the figure is
        c = s.crop((s.width // 2 - half, box[1], s.width // 2 + half, box[3]))
        crops.append(c.resize((max(1, c.width * 320 // c.height), 320), Image.LANCZOS))
    h = max(c.height for c in crops)
    cols = min(10, len(crops))
    rows = (len(crops) + cols - 1) // cols
    cw = max(c.width for c in crops) + 12
    sheet = Image.new("RGBA", (cols * cw, rows * (h + 12)), (58, 66, 58, 255))
    for i, c in enumerate(crops):
        sheet.alpha_composite(c, ((i % cols) * cw + (cw - c.width) // 2, (i // cols) * (h + 12) + 6 + h - c.height))
    sheet.save(os.path.join(OUT, "citizens.png"))
    print("built citizen.bbmodel and", len(crops), "citizens in citizens.png")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 20)
