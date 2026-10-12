package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.StadConfig;
import dev.eiriksb.stad.citizen.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Every colony on the server, saved with the world ({@code data/stad_colonies.dat}). Server thread only. */
public final class ColonyManager extends SavedData {
    private static final SavedData.Factory<ColonyManager> FACTORY = new SavedData.Factory<>(ColonyManager::new, ColonyManager::load);

    private final Map<Integer, Colony> colonies = new LinkedHashMap<>();
    private int nextId = 1;

    public static ColonyManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "stad_colonies");
    }

    /** A new colony, or why there can't be one here. */
    public record Founding(Colony colony, String problem) {}

    public Founding found(ServerLevel level, BlockPos center, UUID owner, String ownerName) {
        return found(level, center, owner, ownerName, StadConfig.CLAIM_RADIUS.get());
    }

    public Founding found(ServerLevel level, BlockPos center, UUID owner, String ownerName, int radius) {
        int max = StadConfig.MAX_COLONIES_PER_PLAYER.get();
        if (max > 0 && ownedBy(owner).size() >= max) {
            return new Founding(null, "You already lead " + ownedBy(owner).getFirst().name() + ".");
        }
        for (Colony c : colonies.values()) {
            long min = c.radius + radius;
            if (c.dimension.equals(level.dimension()) && c.horizontalDistSqr(center) < min * min) {
                return new Founding(null, "This land is too close to " + c.name() + ".");
            }
        }
        String name = TownNames.random(level.random, colonies.values().stream().map(Colony::name).toList());
        long now = level.getGameTime();
        Colony c = new Colony(nextId++, name, level.dimension(), center.immutable(), radius, now, owner, ownerName);
        // The first newcomer comes about a minute after founding, the rest at the normal pace.
        c.lastArrival = now - Arrivals.intervalTicks() + 1200;
        c.onChange(this::setDirty);
        colonies.put(c.id, c);
        setDirty();
        return new Founding(c, null);
    }

    /** Ends a colony. Its citizens stay in the world as newcomers without a town. */
    public void dissolve(MinecraftServer server, Colony colony) {
        colonies.remove(colony.id);
        for (CitizenRecord r : colony.citizens()) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(r.uuid) instanceof CitizenEntity c) {
                    c.leftColony();
                }
            }
        }
        setDirty();
    }

    public Optional<Colony> byId(int id) {
        return Optional.ofNullable(colonies.get(id));
    }

    public Optional<Colony> at(ResourceKey<Level> dimension, BlockPos pos) {
        return colonies.values().stream().filter(c -> c.contains(dimension, pos)).findFirst();
    }

    public Optional<Colony> ofCitizen(UUID citizen) {
        return colonies.values().stream().filter(c -> c.hasCitizen(citizen)).findFirst();
    }

    public List<Colony> ownedBy(UUID player) {
        return colonies.values().stream().filter(c -> c.owner().equals(player)).toList();
    }

    public Collection<Colony> all() {
        return List.copyOf(colonies.values());
    }

    /** {@code citizen} moves into {@code colony}. */
    public void join(Colony colony, CitizenEntity citizen) {
        ofCitizen(citizen.getUUID()).ifPresent(old -> old.removeCitizen(citizen.getUUID()));
        colony.addCitizen(citizen.getUUID(), citizen.getName().getString(), citizen.level().getGameTime());
        citizen.joinedColony(colony);
    }

    /** {@code citizen} leaves their colony. */
    public void leave(Colony colony, CitizenEntity citizen) {
        colony.buildings().stream().filter(b -> citizen.getUUID().equals(b.builder) && !b.done()).forEach(b -> b.builder = null);
        colony.removeCitizen(citizen.getUUID());
        citizen.leftColony();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        ListTag list = new ListTag();
        colonies.values().forEach(c -> list.add(c.save()));
        tag.put("Colonies", list);
        return tag;
    }

    private static ColonyManager load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonyManager m = new ColonyManager();
        m.nextId = Math.max(1, tag.getInt("NextId"));
        for (Tag t : tag.getList("Colonies", Tag.TAG_COMPOUND)) {
            Colony c = Colony.load((CompoundTag) t);
            c.onChange(m::setDirty);
            m.colonies.put(c.id, c);
        }
        return m;
    }
}
