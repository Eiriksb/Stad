package dev.eiriksb.stad.building;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A building in a pack, as its manifest ({@code data/<ns>/stad/buildings/<pack>/<id>.json}) describes it.
 *
 * @param key  "stad:medieval/cottage": namespace, pack and building
 * @param type what it's for: "stad:home", "stad:builder_hut", "stad:warehouse", ...
 */
public record BuildingDef(ResourceLocation key, String pack, ResourceLocation type, String name, String description,
                          List<String> tags, Blueprint blueprint) {
    public static final ResourceLocation HOME = ResourceLocation.fromNamespaceAndPath("stad", "home");
    public static final ResourceLocation BUILDER_HUT = ResourceLocation.fromNamespaceAndPath("stad", "builder_hut");
    public static final ResourceLocation WAREHOUSE = ResourceLocation.fromNamespaceAndPath("stad", "warehouse");
}
