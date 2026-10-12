package dev.eiriksb.stad.building;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.pack.Roles;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The building packs from data packs (the mod's own "Medieval" pack is one): every
 * {@code data/<ns>/stad/buildings/<pack>/<id>.json} with the structure template it names. Reloaded with /reload.
 */
public final class BuildingPacks extends SimpleJsonResourceReloadListener {
    private static volatile Map<ResourceLocation, BuildingDef> buildings = Map.of();
    /** designs saved since the last reload (their data pack files are read on the next one) */
    private static final Map<ResourceLocation, BuildingDef> saved = new java.util.concurrent.ConcurrentHashMap<>();
    /** buildings deleted since the last reload */
    private static final java.util.Set<ResourceLocation> removed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BuildingPacks() {
        super(new Gson(), "stad/buildings");
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new BuildingPacks()));
    }

    public static Collection<BuildingDef> all() {
        if (saved.isEmpty() && removed.isEmpty()) {
            return buildings.values();
        }
        Map<ResourceLocation, BuildingDef> all = new LinkedHashMap<>(buildings);
        all.putAll(saved);
        all.keySet().removeAll(removed);
        return all.values();
    }

    public static Optional<BuildingDef> get(ResourceLocation key) {
        if (key == null || removed.contains(key)) {
            return Optional.empty();
        }
        BuildingDef d = saved.get(key);
        return Optional.ofNullable(d != null ? d : buildings.get(key));
    }

    /** A building just saved into a data pack: usable now, before the pack is (re)loaded. */
    public static void addSaved(BuildingDef def) {
        removed.remove(def.key());
        saved.put(def.key(), def);
    }

    /** A building just deleted from a data pack: gone now, before the pack is reloaded. */
    public static void removeSaved(ResourceLocation key) {
        saved.remove(key);
        removed.add(key);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, BuildingDef> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> f : files.entrySet()) {
            ResourceLocation key = f.getKey();
            try {
                JsonObject o = f.getValue().getAsJsonObject();
                ResourceLocation structure = ResourceLocation.parse(GsonHelper.getAsString(o, "structure"));
                Blueprint blueprint = readStructure(resources, structure);
                String path = key.getPath();
                String pack = path.contains("/") ? path.substring(0, path.indexOf('/')) : "default";
                List<String> tags = new ArrayList<>();
                GsonHelper.getAsJsonArray(o, "tags", new com.google.gson.JsonArray()).forEach(t -> tags.add(t.getAsString()));
                List<Roles.Fix> fixes = new ArrayList<>();
                GsonHelper.getAsJsonArray(o, "roles", new com.google.gson.JsonArray())
                        .forEach(r -> Roles.Fix.fromJson(r.getAsJsonObject()).ifPresent(fixes::add));
                loaded.put(key, new BuildingDef(key, pack, ResourceLocation.parse(GsonHelper.getAsString(o, "type")),
                        GsonHelper.getAsString(o, "name", path), GsonHelper.getAsString(o, "description", ""), List.copyOf(tags), blueprint,
                        o.has("spec") ? o.get("spec").toString() : null, fixes));
            } catch (Exception e) {
                Stad.LOGGER.warn("Building {} could not be loaded: {}", key, e.toString());
            }
        }
        buildings = Map.copyOf(loaded);
        saved.keySet().removeAll(loaded.keySet());
        removed.clear();
        Stad.LOGGER.info("Loaded {} buildings from {} packs", loaded.size(), loaded.values().stream().map(BuildingDef::pack).distinct().count());
    }

    private static Blueprint readStructure(ResourceManager resources, ResourceLocation id) throws Exception {
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "structure/" + id.getPath() + ".nbt");
        Resource r = resources.getResource(file).orElseThrow(() -> new IllegalArgumentException("no structure " + file));
        try (InputStream in = r.open()) {
            return Blueprint.read(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), BuiltInRegistries.BLOCK.asLookup());
        }
    }
}
