package dev.eiriksb.stad.planner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * "My pack": designs players have kept, saved as an ordinary building pack in the world's data packs
 * ({@code datapacks/stad_my_pack}), so they show up in the Building Plans, survive restarts, and can be copied to
 * other worlds like any data pack. Server thread.
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

    /** Saves {@code design}; returns the building's key ("stad_my:my_pack/stone_cottage"). */
    public static ResourceLocation save(MinecraftServer server, Design design, String author) throws IOException {
        Path root = root(server);
        Path data = root.resolve("data").resolve(NAMESPACE);
        String id = design.slug();
        Files.createDirectories(data.resolve("stad/buildings").resolve(PACK));
        Files.createDirectories(data.resolve("stad/packs"));
        Files.createDirectories(data.resolve("structure").resolve(PACK));
        Path mcmeta = root.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            JsonObject pack = new JsonObject();
            pack.addProperty("pack_format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
            pack.addProperty("description", "Stad: buildings designed with your builders");
            JsonObject o = new JsonObject();
            o.add("pack", pack);
            Files.writeString(mcmeta, GSON.toJson(o));
        }
        Path packInfo = data.resolve("stad/packs").resolve(PACK + ".json");
        if (!Files.exists(packInfo)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "My pack");
            o.addProperty("description", "Buildings designed with your builders.");
            o.addProperty("author", author);
            Files.writeString(packInfo, GSON.toJson(o));
        }
        NbtIo.writeCompressed(design.blueprint().toStructure(), data.resolve("structure").resolve(PACK).resolve(id + ".nbt"));
        ResourceLocation structure = ResourceLocation.fromNamespaceAndPath(NAMESPACE, PACK + "/" + id);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("type", design.type().toString());
        manifest.addProperty("name", design.name());
        manifest.addProperty("description", capitalise(design.describe()) + ".");
        manifest.addProperty("structure", structure.toString());
        JsonArray tags = new JsonArray();
        tags.add("custom");
        tags.add(design.spec().type());
        manifest.add("tags", tags);
        manifest.add("spec", JsonParser.parseString(design.spec().toJson()));
        Files.writeString(data.resolve("stad/buildings").resolve(PACK).resolve(id + ".json"), GSON.toJson(manifest));
        BuildingDef def = new BuildingDef(structure, PACK, design.type(), design.name(), capitalise(design.describe()) + ".",
                List.of("custom", design.spec().type()), design.blueprint(), design.spec().toJson());
        BuildingPacks.addSaved(def);
        return structure;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
