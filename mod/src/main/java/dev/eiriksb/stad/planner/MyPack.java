package dev.eiriksb.stad.planner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.stad.building.Blueprint;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.pack.Roles;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "My pack": designs players have kept and buildings they've scanned, saved as an ordinary building pack in the
 * world's data packs ({@code datapacks/stad_my_pack}), so they show up in the Building Plans, survive restarts, and can
 * be shared as a zip like any data pack. Server thread.
 */
public final class MyPack {
    public static final String FOLDER = "stad_my_pack";
    public static final String NAMESPACE = "stad_my";
    public static final String PACK = "my_pack";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MyPack() {}

    public static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(FOLDER);
    }

    /** Whether a building is one of My pack's (which can be edited and deleted in place). */
    public static boolean contains(ResourceLocation key) {
        return key.getNamespace().equals(NAMESPACE);
    }

    /** Saves a design drawn up with a builder; returns the building's key ("stad_my:my_pack/stone_cottage"). */
    public static ResourceLocation save(MinecraftServer server, Design design, String author) throws IOException {
        String description = capitalise(design.describe()) + ".";
        return save(server, design.slug(), design.name(), design.type(), description, design.blueprint(), design.spec().toJson(),
                List.of(), List.of("custom", design.spec().type()), author);
    }

    /** A free id for a new building called {@code name} ("house", "house_2"...). */
    public static String freeId(MinecraftServer server, String name) {
        String base = slug(name);
        Path dir = root(server).resolve("data").resolve(NAMESPACE).resolve("stad/buildings").resolve(PACK);
        String id = base;
        for (int i = 2; Files.exists(dir.resolve(id + ".json")) || BuildingPacks.get(key(id)).isPresent(); i++) {
            id = base + "_" + i;
        }
        return id;
    }

    public static ResourceLocation key(String id) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, PACK + "/" + id);
    }

    /**
     * Writes a building (its structure, with markers, and its manifest) into My pack and makes it usable straight
     * away. An existing building with the same id is replaced. Returns its key.
     */
    public static ResourceLocation save(MinecraftServer server, String id, String name, ResourceLocation type, String description,
                                        Blueprint blueprint, String specJson, List<Roles.Fix> fixes, List<String> tags,
                                        String author) throws IOException {
        Path root = root(server);
        Path data = root.resolve("data").resolve(NAMESPACE);
        Files.createDirectories(data.resolve("stad/buildings").resolve(PACK));
        Files.createDirectories(data.resolve("stad/packs"));
        Files.createDirectories(data.resolve("structure").resolve(PACK));
        Path mcmeta = root.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
            pack.addProperty("description", "Stad: buildings designed with your builders and scanned by you");
            JsonObject o = new JsonObject();
            o.add("pack", pack);
            Files.writeString(mcmeta, GSON.toJson(o));
        }
        Path packInfo = data.resolve("stad/packs").resolve(PACK + ".json");
        if (!Files.exists(packInfo)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "My pack");
            o.addProperty("description", "Buildings designed with your builders, and ones you scanned.");
            o.addProperty("author", author);
            Files.writeString(packInfo, GSON.toJson(o));
        }
        NbtIo.writeCompressed(blueprint.toStructure(), data.resolve("structure").resolve(PACK).resolve(id + ".nbt"));
        ResourceLocation key = key(id);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("type", type.toString());
        manifest.addProperty("name", name);
        manifest.addProperty("description", description);
        manifest.addProperty("structure", key.toString());
        JsonArray tagArray = new JsonArray();
        tags.forEach(tagArray::add);
        manifest.add("tags", tagArray);
        if (specJson != null) {
            manifest.add("spec", JsonParser.parseString(specJson));
        }
        if (!fixes.isEmpty()) {
            JsonArray roles = new JsonArray();
            fixes.forEach(f -> roles.add(f.toJson()));
            manifest.add("roles", roles);
        }
        Files.writeString(data.resolve("stad/buildings").resolve(PACK).resolve(id + ".json"), GSON.toJson(manifest));
        BuildingPacks.addSaved(new BuildingDef(key, PACK, type, name, description, tags, blueprint, specJson, fixes));
        return key;
    }

    /** Deletes one of My pack's buildings; returns whether it was there. */
    public static boolean delete(MinecraftServer server, ResourceLocation key) throws IOException {
        if (!contains(key)) {
            return false;
        }
        String id = key.getPath().substring(key.getPath().indexOf('/') + 1);
        Path data = root(server).resolve("data").resolve(NAMESPACE);
        boolean had = Files.deleteIfExists(data.resolve("stad/buildings").resolve(PACK).resolve(id + ".json"));
        Files.deleteIfExists(data.resolve("structure").resolve(PACK).resolve(id + ".nbt"));
        boolean known = BuildingPacks.get(key).isPresent();
        BuildingPacks.removeSaved(key);
        return had || known;
    }

    /**
     * Zips My pack into {@code <game dir>/stad/exports/<name>.zip}: a data pack anyone can drop into a world's
     * datapacks folder, or into {@code stadpacks/} to have it in every world. Returns the zip.
     */
    public static Path export(MinecraftServer server, String name) throws IOException {
        Path root = root(server);
        if (!Files.exists(root.resolve("pack.mcmeta"))) {
            throw new IOException("My pack is empty: save or scan a building first");
        }
        Path out = server.getServerDirectory().resolve("stad").resolve("exports");
        Files.createDirectories(out);
        Path zip = out.resolve(slug(name) + ".zip");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
            for (Path f : files) {
                z.putNextEntry(new ZipEntry(root.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, z);
                z.closeEntry();
            }
        }
        return zip;
    }

    /** The ids of My pack's buildings on disk. */
    public static List<String> ids(MinecraftServer server) throws IOException {
        Path dir = root(server).resolve("data").resolve(NAMESPACE).resolve("stad/buildings").resolve(PACK);
        List<String> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted()
                        .forEach(n -> out.add(n.substring(0, n.length() - 5)));
            }
        }
        return out;
    }

    /** A short file-safe id from a name: "Stone Cottage" -> "stone_cottage". */
    public static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "building" : s.length() > 32 ? s.substring(0, 32) : s;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
