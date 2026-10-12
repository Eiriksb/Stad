package dev.eiriksb.stad.pack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.eiriksb.stad.Stad;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The styles from data packs ({@code data/<ns>/stad/palettes/<id>.json}); the mod brings a few. Reloaded with /reload. */
public final class Palettes extends SimpleJsonResourceReloadListener {
    private static volatile Map<ResourceLocation, Palette> palettes = Map.of();

    private Palettes() {
        super(new Gson(), "stad/palettes");
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new Palettes()));
    }

    public static Collection<Palette> all() {
        return palettes.values();
    }

    /** By id ("stad:nordic"), path ("nordic") or name ("Nordic"). */
    public static Optional<Palette> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        return palettes.values().stream().filter(p -> p.id().toString().equals(n) || p.id().getPath().equals(n)
                || p.name().toLowerCase(Locale.ROOT).equals(n)).findFirst();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, Palette> loaded = new LinkedHashMap<>();
        files.forEach((id, json) -> {
            try {
                loaded.put(id, Palette.fromJson(id, json.getAsJsonObject()));
            } catch (Exception e) {
                Stad.LOGGER.warn("Palette {} could not be loaded: {}", id, e.toString());
            }
        });
        palettes = Map.copyOf(loaded);
        Stad.LOGGER.info("Loaded {} palettes", loaded.size());
    }

    /** For tests and tools: sets the palettes directly. */
    public static void set(Collection<Palette> all) {
        Map<ResourceLocation, Palette> m = new LinkedHashMap<>();
        all.forEach(p -> m.put(p.id(), p));
        palettes = Map.copyOf(m);
    }
}
