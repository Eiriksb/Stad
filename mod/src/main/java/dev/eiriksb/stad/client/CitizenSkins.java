package dev.eiriksb.stad.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.citizen.Looks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puts citizens together from their parts (tools/art/citizens.py draws them; textures/entity/citizen/parts.json lists
 * them): a skin (the body tinted to their skin tone, their clothes in their colours, their job's gear, beard and hair)
 * and a face sheet with their own nose and marks, their eyes looking each way and blinking, their brows for each
 * expression and their mouth in each shape it makes ({@link CitizenFace} draws from it). In every part pure greys are
 * tinted and colours are kept. Each different citizen is put together once, then kept as a texture until the resources
 * reload or the player leaves. Render thread.
 */
public final class CitizenSkins {
    private CitizenSkins() {}

    private static final String DIR = "textures/entity/citizen/";
    private static final int MID = 180;

    /** where things are on a face sheet (128x16, twice the skin's resolution) */
    public static final int SHEET_W = 128, SHEET_H = 16;
    public static final int EYES_X = 16, EYE_W = 6, EYE_H = 4;
    public static final int BROWS_X = 16, BROWS_Y = 4, BROW_W = 6, BROW_H = 4;
    public static final int MOUTHS_X = 16, MOUTHS_Y = 8, MOUTH_W = 8, MOUTH_H = 4;
    /** eye frames: open and half-shut, each looking down or not and left, ahead or right; then shut */
    public static final int EYE_SHUT = 12;
    /** brow frames */
    public static final int BROW_NEUTRAL = 0, BROW_RAISED = 1, BROW_CROSS = 2, BROW_SAD = 3;
    /** the mouth shapes, in face_parts.png's order */
    public static final List<String> MOUTH_SHAPES = List.of("rest", "smile", "frown", "small", "open", "wide", "round", "pressed", "teeth",
            "grin", "o_small");

    public static int eyeFrame(boolean half, boolean down, int dx) {
        return (half ? 6 : 0) + (down ? 3 : 0) + (dx + 1);
    }

    /** A citizen's textures. */
    public record Textures(ResourceLocation skin, ResourceLocation face) {}

    private static JsonObject parts;
    private static final Map<String, Optional<NativeImage>> images = new HashMap<>();
    private static final Map<String, Textures> made = new HashMap<>();
    private static int next;

    /** The textures for a citizen with these looks (as the client knows them), slim or not, in a job ("" for none). */
    public static Textures get(Looks looks, boolean slim, String job, boolean child) {
        String key = Long.toHexString(looks.born()) + "/" + Long.toHexString(looks.style()) + "/" + slim + "/" + job + "/" + child;
        Textures t = made.get(key);
        if (t == null) {
            try {
                t = make(looks, slim, job, child);
            } catch (RuntimeException e) {
                Stad.LOGGER.warn("Couldn't put a citizen together", e);
                t = new Textures(ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png"), null);
            }
            made.put(key, t);
        }
        return t;
    }

    /** Forgets everything (the resources reloaded, or the player left). */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Textures t : made.values()) {
            if (t.skin().getNamespace().equals(Stad.MOD_ID)) {
                mc.getTextureManager().release(t.skin());
            }
            if (t.face() != null) {
                mc.getTextureManager().release(t.face());
            }
        }
        made.clear();
        images.values().forEach(o -> o.ifPresent(NativeImage::close));
        images.clear();
        parts = null;
    }

    // ---- the parts ------------------------------------------------------------------------------------------------

    private static JsonObject parts() {
        if (parts == null) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, DIR + "parts.json");
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(id)) {
                parts = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                throw new IllegalStateException("No " + id, e);
            }
        }
        return parts;
    }

    private static NativeImage image(String path) {
        return images.computeIfAbsent(path, p -> {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, DIR + p + ".png");
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(id)) {
                return Optional.of(NativeImage.read(in));
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static List<String> list(JsonElement e) {
        List<String> out = new ArrayList<>();
        e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        return out;
    }

    /** The {@code value}'th of a list, going round. */
    private static String pick(List<String> list, int value) {
        return list.isEmpty() ? "" : list.get(Math.floorMod(value, list.size()));
    }

    private static int colour(String hex) {
        return Integer.parseInt(hex.substring(1), 16);
    }

    private static int colour(JsonObject colours, String kind, int value) {
        return colour(pick(list(colours.get(kind)), value));
    }

    // ---- pixels (NativeImage keeps them as ABGR) -----------------------------------------------------------------

    private static int a(int c) {
        return c >>> 24;
    }

    /** {@code c} tinted to {@code rgb} when it's a pure grey. */
    private static int tint(int c, int rgb) {
        int r = c & 0xFF, g = c >> 8 & 0xFF, b = c >> 16 & 0xFF;
        if (r != g || g != b) {
            return c;
        }
        int tr = rgb >> 16 & 0xFF, tg = rgb >> 8 & 0xFF, tb = rgb & 0xFF;
        return c & 0xFF000000 | Math.min(255, b * tb / MID) << 16 | Math.min(255, g * tg / MID) << 8 | Math.min(255, r * tr / MID);
    }

    /** {@code top} over {@code bottom}. */
    private static int over(int top, int bottom) {
        int ta = a(top);
        if (ta == 255 || a(bottom) == 0) {
            return top;
        }
        if (ta == 0) {
            return bottom;
        }
        float t = ta / 255f, ba = a(bottom) / 255f * (1 - t), oa = t + ba;
        int out = Math.round(oa * 255) << 24;
        for (int s = 0; s < 24; s += 8) {
            float v = ((top >> s & 0xFF) * t + (bottom >> s & 0xFF) * ba) / oa;
            out |= Math.min(255, Math.round(v)) << s;
        }
        return out;
    }

    private static int mix(int rgb, int other, float t) {
        int out = 0;
        for (int s = 0; s < 24; s += 8) {
            out |= Math.round((rgb >> s & 0xFF) * (1 - t) + (other >> s & 0xFF) * t) << s;
        }
        return out;
    }

    /** Draws a part over {@code out}, tinted; {@code skip} (or null) says which of its pixels to leave out. */
    private static void layer(NativeImage out, NativeImage part, int rgb, java.util.function.BiPredicate<Integer, Integer> skip) {
        if (part == null) {
            return;
        }
        for (int y = 0; y < Math.min(out.getHeight(), part.getHeight()); y++) {
            for (int x = 0; x < Math.min(out.getWidth(), part.getWidth()); x++) {
                int c = part.getPixelRGBA(x, y);
                if (a(c) == 0 || skip != null && skip.test(x, y)) {
                    continue;
                }
                out.setPixelRGBA(x, y, over(tint(c, rgb), out.getPixelRGBA(x, y)));
            }
        }
    }

    /** Copies a w by h piece of {@code from} to {@code out}, tinted, optionally mirrored. */
    private static void cell(NativeImage out, int ox, int oy, NativeImage from, int fx, int fy, int w, int h, int rgb) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = from.getPixelRGBA(fx + x, fy + y);
                if (a(c) != 0) {
                    out.setPixelRGBA(ox + x, oy + y, over(tint(c, rgb), out.getPixelRGBA(ox + x, oy + y)));
                }
            }
        }
    }

    // ---- putting a citizen together -------------------------------------------------------------------------------

    private static Textures make(Looks l, boolean slim, String job, boolean child) {
        JsonObject p = parts(), colours = p.getAsJsonObject("colours");
        String who = slim ? "women" : "men";
        List<String> tones = list(colours.get("skin"));
        int skin = colour(tones.get(Math.min(tones.size() - 1, l.born(Looks.SKIN) * tones.size() / Looks.MAX)));
        int hair = mix(colour(colours, "hair", l.born(Looks.HAIR_COLOUR)), colour(colours.get("grey_hair").getAsString()),
                l.born(Looks.GREY) / 3f);
        int eyes = colour(colours, "eyes", l.born(Looks.EYE_COLOUR));
        int top = colour(colours, "tops", l.style(Looks.TOP_COLOUR)), bottom = colour(colours, "bottoms", l.style(Looks.BOTTOM_COLOUR));
        JsonArray covers = p.getAsJsonObject("jobs").has(job) ? p.getAsJsonObject("jobs").getAsJsonArray(job) : new JsonArray();
        java.util.Set<String> covered = new java.util.HashSet<>(list(covers));

        NativeImage out = new NativeImage(64, 64, true);
        layer(out, image("body"), skin, null);
        if (!covered.contains("bottom")) {
            layer(out, image("bottom/" + pick(list(p.getAsJsonObject("bottoms").get(who)), l.style(Looks.BOTTOM))), bottom, null);
        }
        if (!covered.contains("top")) {
            layer(out, image("top/" + pick(list(p.getAsJsonObject("tops").get(who)), l.style(Looks.TOP))), top, null);
        }
        if (!covered.contains("shoes")) {
            layer(out, image("shoes/" + pick(list(p.get("shoes")), l.style(Looks.SHOES))), 0xB4B4B4, null);
        }
        NativeImage gear = job.isEmpty() ? null : image("job/" + job);
        layer(out, gear, top, null);
        String beard = slim || child ? "" : pick(list(p.get("beards")), l.style(Looks.BEARD));
        if (!beard.isEmpty()) {
            layer(out, image("beard/" + beard), hair, null);
        }
        // the hair, but not its volume where there's a hat; then the hat over it
        layer(out, image("hair/" + pick(list(p.getAsJsonObject("hair").get(who)), l.style(Looks.HAIR))), hair,
                (x, y) -> gear != null && y < 16 && x >= 32 && a(gear.getPixelRGBA(x, y)) != 0);
        if (gear != null) {
            layer(out, gear, top, (x, y) -> !(y < 16 && x >= 32));
        }
        if (slim) {
            slim(out);
        }

        NativeImage face = face(p.getAsJsonObject("face_parts"), l, skin, hair, eyes);
        Minecraft mc = Minecraft.getInstance();
        int n = next++;
        ResourceLocation skinId = ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "citizen/skin_" + n);
        ResourceLocation faceId = ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "citizen/face_" + n);
        mc.getTextureManager().register(skinId, new DynamicTexture(out));
        mc.getTextureManager().register(faceId, new DynamicTexture(face));
        return new Textures(skinId, faceId);
    }

    /** Narrows the arms from 4 pixels to 3 (the slim model), dropping a column from their fronts, backs, tops and bottoms. */
    static void slim(NativeImage img) {
        for (int[] arm : new int[][]{{40, 16}, {40, 32}, {32, 48}, {48, 48}}) {
            int u = arm[0], v = arm[1];
            int[][] old = new int[16][16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    old[y][x] = img.getPixelRGBA(u + x, v + y);
                    img.setPixelRGBA(u + x, v + y, 0);
                }
            }
            // top and bottom (4x4 each, from x 4 and 8) -> 3x4 from x 4 and 7
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 3; x++) {
                    img.setPixelRGBA(u + 4 + x, v + y, old[y][4 + (x < 1 ? x : x + 1)]);
                    img.setPixelRGBA(u + 7 + x, v + y, old[y][8 + (x < 1 ? x : x + 1)]);
                }
            }
            for (int y = 4; y < 16; y++) {
                for (int x = 0; x < 4; x++) {
                    img.setPixelRGBA(u + x, v + y, old[y][x]);            // outer side
                    img.setPixelRGBA(u + 7 + x, v + y, old[y][8 + x]);    // inner side
                }
                for (int x = 0; x < 3; x++) {
                    img.setPixelRGBA(u + 4 + x, v + y, old[y][4 + (x < 1 ? x : x + 1)]);   // front
                    img.setPixelRGBA(u + 11 + x, v + y, old[y][12 + (x < 1 ? x : x + 1)]); // back
                }
            }
        }
    }

    // ---- the face sheet ----------------------------------------------------------------------------------------------

    private static int[] cellOf(JsonObject fp, String kind) {
        JsonArray c = fp.getAsJsonObject("cells").getAsJsonArray(kind);
        return new int[]{c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt(), c.get(3).getAsInt()};
    }

    private static int indexOf(JsonObject fp, String kind, String name) {
        return Math.max(0, list(fp.get(kind)).indexOf(name));
    }

    private static NativeImage face(JsonObject fp, Looks l, int skin, int hair, int eyes) {
        NativeImage atlas = image("face_parts");
        NativeImage out = new NativeImage(SHEET_W, SHEET_H, true);
        if (atlas == null) {
            return out;
        }
        // the base: nose and marks
        int[] noses = cellOf(fp, "noses");
        cell(out, 0, 0, atlas, noses[0] + l.born(Looks.NOSE) % list(fp.get("noses")).size() * noses[2], noses[1], 16, 16, skin);
        String mark = pick(list(parts().get("marks")), l.born(Looks.MARKS));
        if (!mark.isEmpty()) {
            int[] marks = cellOf(fp, "marks");
            cell(out, 0, 0, atlas, marks[0] + indexOf(fp, "marks", mark) * marks[2], marks[1], 16, 16, skin);
        }
        // the eyes, each way they look
        List<String> shapes = list(fp.get("eyes"));
        String shape = pick(shapes, l.born(Looks.EYES));
        JsonArray size = fp.getAsJsonObject("eye_sizes").getAsJsonArray(shape);
        int ew = size.get(0).getAsInt(), eh = size.get(1).getAsInt();
        int[] ec = cellOf(fp, "eyes"), ic = cellOf(fp, "irises");
        int eyeX = ec[0] + shapes.indexOf(shape) * ec[2], irisX = ic[0] + l.born(Looks.IRIS) % list(fp.get("irises")).size() * ic[2];
        int lid = tint(gray(MID * 97 / 100), skin), lash = 0xFF000000 | 24 << 16 | 30 << 8 | 45;
        int x0 = (EYE_W - ew) / 2;
        // the iris sits in the middle of the white: 2 wide in an even eye, 1 or 3 in an odd one (else it's off to one
        // side, the same side in both eyes as the right one is mirrored, and they look cross-eyed)
        int iw = ew % 2 == 0 ? 2 : ew <= 3 ? 1 : 3;
        for (int f = 0; f <= EYE_SHUT; f++) {
            boolean half = f >= 6 && f < EYE_SHUT, shut = f == EYE_SHUT, down = !shut && f % 6 >= 3;
            int dx = shut ? 0 : f % 3 - 1;
            int ox = EYES_X + f * EYE_W;
            for (int y = 0; y < EYE_H; y++) {
                for (int x = 0; x < EYE_W; x++) {
                    int c = atlas.getPixelRGBA(eyeX + x, ec[1] + y);
                    boolean white = y >= 1 && a(c) == 255 && (c & 0xFF) > 150;
                    if (white) {
                        int ix = x - (x0 + (ew - iw) / 2 + dx), iy = y - 1 - (down ? 1 : 0);
                        if (ix >= 0 && ix < iw && iy >= 0 && iy < 3) {
                            int i = atlas.getPixelRGBA(irisX + Math.min(ix, 1), ic[1] + iy); // its columns are alike
                            if (a(i) != 0) {
                                c = tint(i, eyes);
                            }
                        }
                    }
                    int lastWhite = eh; // the bottom row of the white
                    if (shut || half && y <= 1) {
                        if (y == 0 || white) {
                            c = shut && y == lastWhite || half && y == 1 && white ? lash : white || a(c) != 0 ? lid : c;
                        }
                    }
                    if (a(c) != 0) {
                        out.setPixelRGBA(ox + x, y, c);
                    }
                }
            }
        }
        // the brows, for each expression: shifted a row at either end
        int[] bc = cellOf(fp, "brows");
        int browX = bc[0] + l.born(Looks.BROW) % list(fp.get("brows")).size() * bc[2];
        int brow = mix(hair, 0, 0.25f);
        int[][] lift = {{1, 1}, {0, 0}, {0, 2}, {2, 0}}; // the outer end's row, the inner end's (cross and sad tilt by a pixel)
        for (int e = 0; e < 4; e++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 5; x++) {
                    int c = atlas.getPixelRGBA(browX + x, bc[1] + y);
                    if (a(c) == 0) {
                        continue;
                    }
                    int row = y + (x < 3 ? lift[e][0] : lift[e][1]);
                    if (row < BROW_H) {
                        out.setPixelRGBA(BROWS_X + e * BROW_W + x, BROWS_Y + row, tint(c, brow));
                    }
                }
            }
        }
        // the mouth, in every shape
        int[] mc = cellOf(fp, "mouths");
        int style = l.born(Looks.MOUTH) % list(fp.get("mouth_styles")).size();
        for (int m = 0; m < MOUTH_SHAPES.size(); m++) {
            cell(out, MOUTHS_X + m * MOUTH_W, MOUTHS_Y, atlas, mc[0] + m * mc[2], mc[1] + style * mc[3], MOUTH_W, MOUTH_H, skin);
        }
        return out;
    }

    private static int gray(int v) {
        return 0xFF000000 | v << 16 | v << 8 | v;
    }
}
