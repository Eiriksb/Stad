package dev.eiriksb.stad.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A town: founded with a town hall, it claims the land around it and has members (players) and citizens. */
public final class Colony {
    private static final int ORDER_LOG = 200;

    public final int id;
    private String name;
    public final ResourceKey<Level> dimension;
    public final BlockPos center;
    public final int radius;
    public final long founded;
    private UUID owner;
    private final Map<UUID, Rank> ranks = new LinkedHashMap<>();
    private final Map<UUID, String> playerNames = new LinkedHashMap<>();
    private final Map<UUID, CitizenRecord> citizens = new LinkedHashMap<>();
    private final Deque<OrderRecord> orders = new ArrayDeque<>();
    private final Map<Integer, Building> buildings = new LinkedHashMap<>();
    private int nextBuildingId = 1;
    private final Map<Integer, Request> requests = new LinkedHashMap<>();
    private int nextRequestId = 1;
    private final Map<UUID, Standing> standing = new LinkedHashMap<>();
    /** the last day the leadership was reviewed (see {@link dev.eiriksb.stad.agent.Leadership}) */
    public long lastReviewDay = -1;
    /** the day the workers are on strike, if they are */
    public long strikeDay = -1;
    /** its stance towards other towns, and its raid settings */
    public final dev.eiriksb.stad.war.Politics politics = new dev.eiriksb.stad.war.Politics();
    /** game time of the last newcomer, for {@link Arrivals} */
    long lastArrival;
    private Runnable onChange = () -> {};

    Colony(int id, String name, ResourceKey<Level> dimension, BlockPos center, int radius, long founded, UUID owner, String ownerName) {
        this.id = id;
        this.name = name;
        this.dimension = dimension;
        this.center = center;
        this.radius = radius;
        this.founded = founded;
        this.owner = owner;
        ranks.put(owner, Rank.OWNER);
        playerNames.put(owner, ownerName);
    }

    void onChange(Runnable r) {
        onChange = r;
    }

    public void changed() {
        onChange.run();
    }

    public String name() {
        return name;
    }

    public void rename(String n) {
        name = n;
        changed();
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return playerNames.getOrDefault(owner, "?");
    }

    public Rank rank(UUID player) {
        return ranks.getOrDefault(player, Rank.VISITOR);
    }

    /** Sets a player's rank (VISITOR removes them). There is always exactly one owner: making someone owner hands it over. */
    public void setRank(UUID player, String playerName, Rank rank) {
        if (rank == Rank.OWNER) {
            ranks.put(owner, Rank.OFFICER);
            owner = player;
        } else if (player.equals(owner)) {
            return; // the owner hands over by making someone else owner
        }
        if (rank == Rank.VISITOR) {
            ranks.remove(player);
        } else {
            ranks.put(player, rank);
        }
        playerNames.put(player, playerName);
        changed();
    }

    public Map<UUID, Rank> ranks() {
        return Map.copyOf(ranks);
    }

    public String playerName(UUID player) {
        return playerNames.getOrDefault(player, "?");
    }

    public boolean contains(ResourceKey<Level> dim, BlockPos pos) {
        return dim.equals(dimension) && horizontalDistSqr(pos) <= (long) radius * radius;
    }

    long horizontalDistSqr(BlockPos pos) {
        long dx = pos.getX() - center.getX();
        long dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz;
    }

    // ---- citizens -----------------------------------------------------------------------------------------------

    public Collection<CitizenRecord> citizens() {
        return List.copyOf(citizens.values());
    }

    public Optional<CitizenRecord> citizen(UUID uuid) {
        return Optional.ofNullable(citizens.get(uuid));
    }

    public boolean hasCitizen(UUID uuid) {
        return citizens.containsKey(uuid);
    }

    public int population() {
        return citizens.size();
    }

    CitizenRecord addCitizen(UUID uuid, String name, long time) {
        CitizenRecord r = citizens.computeIfAbsent(uuid, u -> new CitizenRecord(u, name, time));
        changed();
        return r;
    }

    public void removeCitizen(UUID uuid) {
        if (citizens.remove(uuid) != null) {
            changed();
        }
    }

    /** Who sleeps in the bed whose head is at {@code pos}, if anyone. */
    public Optional<CitizenRecord> sleeperAt(BlockPos pos) {
        return citizens.values().stream().filter(c -> pos.equals(c.home)).findFirst();
    }

    // ---- buildings ----------------------------------------------------------------------------------------------

    /** In the order they were planned (builders take them in that order). */
    public List<Building> buildings() {
        return List.copyOf(buildings.values());
    }

    public Optional<Building> building(int id) {
        return Optional.ofNullable(buildings.get(id));
    }

    /** Plans a building; {@link dev.eiriksb.stad.building.Planning} checks where it may go first. */
    public Building addBuilding(net.minecraft.resources.ResourceLocation key, String name, BlockPos origin,
                                net.minecraft.world.level.block.Rotation rotation, long time) {
        return addBuilding(key, name, origin, rotation, time, null);
    }

    public Building addBuilding(net.minecraft.resources.ResourceLocation key, String name, BlockPos origin,
                                net.minecraft.world.level.block.Rotation rotation, long time, dev.eiriksb.stad.planner.Design design) {
        Building b = new Building(nextBuildingId++, key, name, origin.immutable(), rotation, time, design);
        buildings.put(b.id, b);
        changed();
        return b;
    }

    public void removeBuilding(int id) {
        if (buildings.remove(id) != null) {
            changed();
        }
    }

    /** The building {@code builder} works on: the one they've started, else repairs, else the first nobody has taken. */
    public Optional<Building> workFor(UUID builder) {
        Optional<Building> mine = buildings.values().stream().filter(b -> !b.done() && builder.equals(b.builder)).findFirst();
        if (mine.isPresent()) {
            return mine;
        }
        Optional<Building> free = buildings.values().stream().filter(b -> !b.done() && b.builder == null && b.isRepair()).findFirst()
                .or(() -> buildings.values().stream().filter(b -> !b.done() && b.builder == null).findFirst());
        free.ifPresent(b -> {
            b.builder = builder;
            changed();
        });
        return free;
    }

    // ---- requests -----------------------------------------------------------------------------------------------

    /** Oldest first. */
    public List<Request> requests() {
        return List.copyOf(requests.values());
    }

    public Optional<Request> request(String what, BlockPos dest) {
        return requests.values().stream().filter(r -> r.what.equals(what) && r.dest.equals(dest)).findFirst();
    }

    /** Asks for {@code count} of {@code what} at {@code dest} (or updates the count of the same request). */
    public Request need(String what, int count, BlockPos dest, String destName, String reason, long time) {
        Optional<Request> existing = request(what, dest);
        if (existing.isPresent()) {
            if (existing.get().count != count) {
                existing.get().count = count;
                changed();
            }
            return existing.get();
        }
        Request r = new Request(nextRequestId++, what, count, dest.immutable(), destName, reason, time);
        requests.put(r.id, r);
        changed();
        return r;
    }

    public void removeRequest(int id) {
        if (requests.remove(id) != null) {
            changed();
        }
    }

    // ---- standing -----------------------------------------------------------------------------------------------

    /** How the town sees a player as a leader. */
    public Standing standing(UUID player) {
        return standing.computeIfAbsent(player, p -> new Standing());
    }

    public void changeStanding(UUID player, String playerName, int delta, String cause, long time) {
        if (delta == 0) {
            return;
        }
        standing(player).change(delta, cause, time);
        playerNames.putIfAbsent(player, playerName);
        changed();
    }

    /** Something that's down to whoever leads the town (the founder and officers). */
    public void changeLeadersStanding(int delta, String cause, long time) {
        ranks.forEach((uuid, rank) -> {
            if (rank.atLeast(Rank.OFFICER)) {
                standing(uuid).change(delta, cause, time);
            }
        });
        changed();
    }

    public void setStanding(UUID player, int value) {
        standing(player).set(value);
        changed();
    }

    public boolean onStrike(long day) {
        return strikeDay == day;
    }

    // ---- orders -------------------------------------------------------------------------------------------------

    public void log(OrderRecord r) {
        orders.addFirst(r);
        while (orders.size() > ORDER_LOG) {
            orders.removeLast();
        }
        changed();
    }

    /** Newest first. */
    public List<OrderRecord> orders() {
        return List.copyOf(orders);
    }

    // ---- saving -------------------------------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Id", id);
        t.putString("Name", name);
        t.putString("Dimension", dimension.location().toString());
        t.put("Center", NbtUtils.writeBlockPos(center));
        t.putInt("Radius", radius);
        t.putLong("Founded", founded);
        t.putUUID("Owner", owner);
        t.putLong("LastArrival", lastArrival);
        ListTag members = new ListTag();
        ranks.forEach((uuid, rank) -> {
            CompoundTag m = new CompoundTag();
            m.putUUID("Uuid", uuid);
            m.putString("Name", playerNames.getOrDefault(uuid, "?"));
            m.putString("Rank", rank.id());
            members.add(m);
        });
        t.put("Members", members);
        ListTag cs = new ListTag();
        citizens.values().forEach(c -> cs.add(c.save()));
        t.put("Citizens", cs);
        ListTag os = new ListTag();
        orders.forEach(o -> os.add(o.save()));
        t.put("Orders", os);
        t.putInt("NextBuildingId", nextBuildingId);
        ListTag bs = new ListTag();
        buildings.values().forEach(b -> bs.add(b.save()));
        t.put("Buildings", bs);
        t.putInt("NextRequestId", nextRequestId);
        ListTag rs = new ListTag();
        requests.values().forEach(r -> rs.add(r.save()));
        t.put("Requests", rs);
        ListTag st = new ListTag();
        standing.forEach((uuid, s) -> {
            CompoundTag e = s.save();
            e.putUUID("Player", uuid);
            st.add(e);
        });
        t.put("Standing", st);
        t.putLong("LastReviewDay", lastReviewDay);
        t.putLong("StrikeDay", strikeDay);
        t.put("Politics", politics.save());
        return t;
    }

    public static Colony load(CompoundTag t) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("Dimension")));
        UUID owner = t.getUUID("Owner");
        Colony c = new Colony(t.getInt("Id"), t.getString("Name"), dim, NbtUtils.readBlockPos(t, "Center").orElse(BlockPos.ZERO),
                t.getInt("Radius"), t.getLong("Founded"), owner, "?");
        c.lastArrival = t.getLong("LastArrival");
        c.ranks.clear();
        for (Tag tag : t.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) tag;
            c.ranks.put(m.getUUID("Uuid"), Rank.byId(m.getString("Rank")));
            c.playerNames.put(m.getUUID("Uuid"), m.getString("Name"));
        }
        c.ranks.put(owner, Rank.OWNER);
        for (Tag tag : t.getList("Citizens", Tag.TAG_COMPOUND)) {
            CitizenRecord r = CitizenRecord.load((CompoundTag) tag);
            c.citizens.put(r.uuid, r);
        }
        for (Tag tag : t.getList("Orders", Tag.TAG_COMPOUND)) {
            c.orders.addLast(OrderRecord.load((CompoundTag) tag));
        }
        c.nextBuildingId = Math.max(1, t.getInt("NextBuildingId"));
        for (Tag tag : t.getList("Buildings", Tag.TAG_COMPOUND)) {
            Building b = Building.load((CompoundTag) tag);
            c.buildings.put(b.id, b);
        }
        c.nextRequestId = Math.max(1, t.getInt("NextRequestId"));
        for (Tag tag : t.getList("Requests", Tag.TAG_COMPOUND)) {
            Request r = Request.load((CompoundTag) tag);
            c.requests.put(r.id, r);
        }
        for (Tag tag : t.getList("Standing", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) tag;
            c.standing.put(e.getUUID("Player"), Standing.load(e));
        }
        c.lastReviewDay = t.contains("LastReviewDay") ? t.getLong("LastReviewDay") : -1;
        c.strikeDay = t.contains("StrikeDay") ? t.getLong("StrikeDay") : -1;
        c.politics.load(t.getCompound("Politics"));
        return c;
    }
}
