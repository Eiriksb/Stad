package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A building in a town: planned, being built, waiting for materials, or done. */
public final class Building {
    public enum Status { PLANNED, BUILDING, WAITING, DONE }

    public final int id;
    public final ResourceLocation key;
    public final String name;
    public final BlockPos origin;
    public final Rotation rotation;
    public final long planned;
    /** drawn up with a builder (blocks and all), instead of a pack building; null for pack buildings */
    public final dev.eiriksb.stad.planner.Design design;
    public Status status = Status.PLANNED;
    /** index of the next step in {@link Placement#steps()} */
    public int progress;
    /** the builder working on it, if any */
    public UUID builder;

    /** what's still missing when it's waiting (not saved) */
    public Map<Item, Integer> missing = new LinkedHashMap<>();
    /** game time the builder last asked for materials (not saved) */
    public long lastAsked = -100_000;
    /** game time until which its outline is shown (not saved) */
    public long outlineUntil;
    private Placement placement;

    Building(int id, ResourceLocation key, String name, BlockPos origin, Rotation rotation, long planned,
             dev.eiriksb.stad.planner.Design design) {
        this.id = id;
        this.design = design;
        this.key = key;
        this.name = name;
        this.origin = origin;
        this.rotation = rotation;
        this.planned = planned;
    }

    public Optional<BuildingDef> def() {
        return BuildingPacks.get(key);
    }

    /** What it's for ("stad:home", "stad:warehouse"...); null when its pack isn't loaded any more. */
    public ResourceLocation type() {
        return design != null ? design.type() : def().map(BuildingDef::type).orElse(null);
    }

    public boolean is(ResourceLocation type) {
        return type.equals(type());
    }

    /** Where every block goes; empty when its pack isn't loaded any more. */
    public Optional<Placement> placement() {
        if (placement == null) {
            placement = design != null ? Placement.of(design.blueprint(), origin, rotation)
                    : def().map(d -> Placement.of(d.blueprint(), origin, rotation)).orElse(null);
        }
        return Optional.ofNullable(placement);
    }

    public boolean done() {
        return status == Status.DONE;
    }

    /** 0..1 */
    public double fractionDone() {
        if (done()) {
            return 1;
        }
        return placement().map(p -> p.steps().isEmpty() ? 1.0 : (double) progress / p.steps().size()).orElse(0.0);
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Id", id);
        t.putString("Key", key.toString());
        t.putString("Name", name);
        t.put("Origin", NbtUtils.writeBlockPos(origin));
        t.putString("Rotation", rotation.name());
        t.putLong("Planned", planned);
        t.putString("Status", status.name());
        t.putInt("Progress", progress);
        if (builder != null) {
            t.putUUID("Builder", builder);
        }
        if (design != null) {
            t.put("Design", design.save());
        }
        return t;
    }

    static Building load(CompoundTag t) {
        Building b = new Building(t.getInt("Id"), ResourceLocation.parse(t.getString("Key")), t.getString("Name"),
                NbtUtils.readBlockPos(t, "Origin").orElse(BlockPos.ZERO), Rotation.valueOf(t.getString("Rotation")), t.getLong("Planned"),
                t.contains("Design") ? dev.eiriksb.stad.planner.Design.load(t.getCompound("Design")) : null);
        b.status = Status.valueOf(t.getString("Status"));
        b.progress = t.getInt("Progress");
        b.builder = t.hasUUID("Builder") ? t.getUUID("Builder") : null;
        return b;
    }
}
