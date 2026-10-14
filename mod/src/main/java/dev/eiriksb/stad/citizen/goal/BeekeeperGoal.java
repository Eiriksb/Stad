package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.architect.Yard;
import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Smithing;
import dev.eiriksb.stad.citizen.Tools;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The beekeeper (PLAN.md §6.12) keeps the apiary: takes the honey from full hives (into glass bottles from the stores,
 * or as honeycomb when there are none), brings bees to empty hives (wild ones from round about; when there are none, a
 * swarm settles in now and then, drawn by the flowers), and sows the apiary's flowers (wild flowers they pick).
 */
public class BeekeeperGoal extends WorkGoal {
    /** bees a hive should have */
    static final int BEES = 2;

    enum Kind { HARVEST, BEES, SOW }

    record Task(Kind kind, BlockPos pos) {}

    private Task task;
    private int planIn;
    private boolean bottle;
    /** when a swarm last settled in each hive (a day number) */
    private final Map<BlockPos, Long> settled = new HashMap<>();

    public BeekeeperGoal(CitizenEntity citizen) {
        super(citizen, Jobs.BEEKEEPER);
    }

    @Override
    protected ColonyStorage deliverTo(ServerLevel level, Colony colony) {
        return ColonyStorage.deliveries(level, colony);
    }

    @Override
    protected boolean keep(ItemStack s) {
        return s.is(Items.GLASS_BOTTLE);
    }

    /** The apiary's hives, as they stand. */
    private List<BlockPos> hives(ServerLevel level, Colony colony) {
        List<BlockPos> out = new ArrayList<>();
        workplace(colony).flatMap(Building::placement).ifPresent(p -> p.steps().forEach(s -> {
            if (s.state().getBlock() instanceof BeehiveBlock && level.getBlockState(s.pos()).getBlock() instanceof BeehiveBlock) {
                out.add(s.pos());
            }
        }));
        return out;
    }

    /** The apiary's flower spots with no flower on them yet, that a flower would grow on. */
    private List<BlockPos> bare(ServerLevel level, Colony colony) {
        List<BlockPos> out = new ArrayList<>();
        workplace(colony).flatMap(Building::placement).ifPresent(p -> p.markers("stad:flower").forEach(m -> {
            if (level.getBlockState(m).isAir() && level.getBlockState(m.below()).is(BlockTags.DIRT)) {
                out.add(m);
            }
        }));
        return out;
    }

    private int bees(ServerLevel level, BlockPos hive) {
        int in = level.getBlockEntity(hive) instanceof BeehiveBlockEntity be ? be.getOccupantCount() : 0;
        int out = level.getEntitiesOfClass(Bee.class, new AABB(hive).inflate(48), b -> hive.equals(b.getHivePos())).size();
        return in + out;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (task != null) {
            return true;
        }
        if (--planIn > 0) {
            return false;
        }
        planIn = 60;
        task = plan(level, colony);
        cooldown = task == null ? 0 : pace(task.kind() == Kind.SOW ? 20 : 50);
        return task != null;
    }

    Task plan(ServerLevel level, Colony colony) {
        List<BlockPos> hives = hives(level, colony);
        for (BlockPos h : hives) {
            if (level.getBlockState(h).getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS) {
                return new Task(Kind.HARVEST, h);
            }
        }
        long day = level.getDayTime() / 24000;
        for (BlockPos h : hives) {
            if (bees(level, h) < BEES && (wildBee(level, h) != null || settled.getOrDefault(h, -1L) != day && flowersNear(level, h) >= 2)) {
                return new Task(Kind.BEES, h);
            }
        }
        List<BlockPos> bare = bare(level, colony);
        return bare.isEmpty() ? null : new Task(Kind.SOW, bare.getFirst());
    }

    private Bee wildBee(ServerLevel level, BlockPos hive) {
        return level.getEntitiesOfClass(Bee.class, new AABB(hive).inflate(48), b -> !b.hasHive() && !b.isAngry()).stream().findFirst().orElse(null);
    }

    private static int flowersNear(ServerLevel level, BlockPos hive) {
        return (int) BlockPos.betweenClosedStream(hive.offset(-5, -2, -5), hive.offset(5, 2, 5)).filter(p -> level.getBlockState(p).is(BlockTags.FLOWERS)).count();
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        if (workplace(colony).isEmpty()) {
            return "No apiary to keep: lay one out at the architect's table";
        }
        return hives(level, colony).isEmpty() ? "The apiary has no beehives yet" : null;
    }

    @Override
    protected String idle(ServerLevel level, Colony colony) {
        return "Waiting for the bees to make honey";
    }

    @Override
    protected String doingNow() {
        if (task == null) {
            return "Watching the bees";
        }
        return switch (task.kind()) {
            case HARVEST -> "Taking the honey";
            case BEES -> "Bringing bees to a hive";
            case SOW -> "Sowing flowers for the bees";
        };
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        if (task == null) {
            return;
        }
        if (task.kind() == Kind.HARVEST && !bottle && takeBottle(level, colony)) {
            return; // on their way to the stores
        }
        if (!approach.tick(citizen, task.pos(), 2.5, 0.6)) {
            return;
        }
        citizen.getLookControl().setLookAt(task.pos().getX() + 0.5, task.pos().getY() + 0.5, task.pos().getZ() + 0.5);
        if (--cooldown > 0) {
            if (cooldown % 10 == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        switch (task.kind()) {
            case HARVEST -> harvest(level);
            case BEES -> bringBees(level);
            case SOW -> sow(level);
        }
        task = null;
        bottle = false;
        planIn = 0;
        approach.reset();
    }

    /**
     * For the honey they need a glass bottle: one in their pockets, else from the stores. Returns whether they're still
     * fetching it (with none anywhere, they take honeycomb instead).
     */
    private boolean takeBottle(ServerLevel level, Colony colony) {
        if (citizen.inventory().countItem(Items.GLASS_BOTTLE) > 0) {
            bottle = true;
            return false;
        }
        List<ColonyStorage.Box> all = new ArrayList<>();
        Tools.stores(level, colony, workplace(colony)).forEach(s -> all.addAll(s.boxes()));
        ColonyStorage stores = ColonyStorage.of(all);
        ColonyStorage.Box box = stores.find(s -> Smithing.plain(s) && s.is(Items.GLASS_BOTTLE));
        if (box == null) {
            bottle = true; // honeycomb, then
            return false;
        }
        if (!approach.tick(citizen, box.pos(), 2.8, 0.6)) {
            return true;
        }
        stores.takeUpTo(s -> Smithing.plain(s) && s.is(Items.GLASS_BOTTLE), 4).forEach(this::pocket);
        bottle = true;
        cooldown = pace(40);
        approach.reset();
        return false;
    }

    private void harvest(ServerLevel level) {
        BlockState st = level.getBlockState(task.pos());
        if (!(st.getBlock() instanceof BeehiveBlock hive) || st.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) {
            return;
        }
        if (usePocketed(s -> s.is(Items.GLASS_BOTTLE))) {
            pocket(new ItemStack(Items.HONEY_BOTTLE));
            level.playSound(null, task.pos(), SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1f, 1f);
        } else {
            pocket(new ItemStack(Items.HONEYCOMB, 3));
            level.playSound(null, task.pos(), SoundEvents.BEEHIVE_SHEAR, SoundSource.BLOCKS, 1f, 1f);
        }
        hive.resetHoneyLevel(level, st, task.pos()); // smoked out gently: the bees stay calm
        worked(2);
    }

    private void bringBees(ServerLevel level) {
        BlockPos h = task.pos();
        Bee wild = wildBee(level, h);
        if (wild != null) {
            wild.setHivePos(h);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, wild.getX(), wild.getY() + 0.5, wild.getZ(), 4, 0.3, 0.3, 0.3, 0);
        } else if (level.getBlockEntity(h) instanceof BeehiveBlockEntity be && !be.isFull()) {
            Bee swarm = EntityType.BEE.create(level);
            if (swarm != null) { // a swarm settles in, drawn by the flowers
                swarm.moveTo(h.getX() + 0.5, h.getY() + 1, h.getZ() + 0.5);
                be.addOccupant(swarm);
                settled.put(h, level.getDayTime() / 24000);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, h.getX() + 0.5, h.getY() + 1.2, h.getZ() + 0.5, 6, 0.4, 0.3, 0.4, 0);
            }
        }
        worked(1);
    }

    private void sow(ServerLevel level) {
        BlockPos at = task.pos();
        if (!level.getBlockState(at).isAir() || !level.getBlockState(at.below()).is(BlockTags.DIRT)) {
            return;
        }
        BlockState flower = Yard.FLOWERS.get(Math.floorMod(at.getX() * 31 + at.getZ(), Yard.FLOWERS.size())).defaultBlockState();
        level.setBlock(at, flower, 3);
        level.playSound(null, at, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
        worked(1);
    }

    Optional<Task> current() {
        return Optional.ofNullable(task);
    }
}
