package dev.eiriksb.stad.building;

import dev.eiriksb.stad.pack.Roles;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A building in a pack, as its manifest ({@code data/<ns>/stad/buildings/<pack>/<id>.json}) describes it.
 *
 * @param key  "stad:medieval/cottage": namespace, pack and building
 * @param type what it's for: "stad:home", "stad:builder_hut", "stad:warehouse", ...
 * @param spec the {@link dev.eiriksb.stad.planner.BuildSpec} (JSON) it was generated from, for designs saved with a
 *             builder; null for buildings made by hand
 * @param fixes the pack author's corrections to the guessed block roles ({@code "roles"} in the manifest)
 */
public record BuildingDef(ResourceLocation key, String pack, ResourceLocation type, String name, String description,
                          List<String> tags, Blueprint blueprint, String spec, List<Roles.Fix> fixes) {
    public BuildingDef {
        tags = List.copyOf(tags);
        fixes = List.copyOf(fixes);
    }

    public BuildingDef(ResourceLocation key, String pack, ResourceLocation type, String name, String description,
                       List<String> tags, Blueprint blueprint, String spec) {
        this(key, pack, type, name, description, tags, blueprint, spec, List.of());
    }

    /** Every block's role, with the author's corrections. */
    public Roles.Analysis roles() {
        return Roles.analyse(blueprint, fixes);
    }

    public static final ResourceLocation HOME = ResourceLocation.fromNamespaceAndPath("stad", "home");
    public static final ResourceLocation BUILDER_HUT = ResourceLocation.fromNamespaceAndPath("stad", "builder_hut");
    public static final ResourceLocation WAREHOUSE = ResourceLocation.fromNamespaceAndPath("stad", "warehouse");
    /** where guards live: guards patrol between barracks and watchtowers */
    public static final ResourceLocation BARRACKS = ResourceLocation.fromNamespaceAndPath("stad", "barracks");
    public static final ResourceLocation WATCHTOWER = ResourceLocation.fromNamespaceAndPath("stad", "watchtower");
    public static final ResourceLocation MINE = ResourceLocation.fromNamespaceAndPath("stad", "mine");
    public static final ResourceLocation KITCHEN = ResourceLocation.fromNamespaceAndPath("stad", "kitchen");
    public static final ResourceLocation FARMHOUSE = ResourceLocation.fromNamespaceAndPath("stad", "farmhouse");
    public static final ResourceLocation LUMBER_CAMP = ResourceLocation.fromNamespaceAndPath("stad", "lumber_camp");
    public static final ResourceLocation FISHERY = ResourceLocation.fromNamespaceAndPath("stad", "fishery");
    public static final ResourceLocation BARN = ResourceLocation.fromNamespaceAndPath("stad", "barn");
    public static final ResourceLocation SMITHY = ResourceLocation.fromNamespaceAndPath("stad", "smithy");
    public static final ResourceLocation INFIRMARY = ResourceLocation.fromNamespaceAndPath("stad", "infirmary");
    public static final ResourceLocation MARKET = ResourceLocation.fromNamespaceAndPath("stad", "market");
    public static final ResourceLocation SCHOOL = ResourceLocation.fromNamespaceAndPath("stad", "school");
    /** a field ({@link Fields}): farmers make it, builders have nothing to do there */
    public static final ResourceLocation FIELD = ResourceLocation.fromNamespaceAndPath("stad", "field");
}
