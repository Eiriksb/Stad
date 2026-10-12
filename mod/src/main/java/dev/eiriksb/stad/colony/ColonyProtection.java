package dev.eiriksb.stad.colony;

import dev.eiriksb.stad.StadRegistries;
import dev.eiriksb.stad.war.War;
import dev.eiriksb.stad.war.Wars;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Optional;

/**
 * A town's land is its own (PLAN.md §6.7): only its people may build, dig or open its chests and beds there, and only
 * its founder may take the town hall down. In a war raid the attackers can break doors, fences, walls and gates (to
 * get in), and nothing else; beds, storage and the town hall stay locked. Operators in creative mode may do anything.
 */
public final class ColonyProtection {
    private ColonyProtection() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ColonyProtection::onBreak);
        NeoForge.EVENT_BUS.addListener(ColonyProtection::onPlace);
        NeoForge.EVENT_BUS.addListener(ColonyProtection::onUse);
    }

    public enum Verdict { ALLOWED, RAID_BREAKABLE, DENIED }

    private static boolean admin(Player p) {
        return p.isCreative() && p.hasPermissions(2);
    }

    /** Whether a raider may break this in a raid: ways in, not homes or stores. */
    public static boolean breakableInRaid(BlockState s) {
        return s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.FENCES)
                || s.is(BlockTags.WALLS);
    }

    /** Whether {@code player} attacks {@code colony} in a raid going on now. */
    static boolean raiding(ServerPlayer player, Colony colony) {
        Optional<War> war = Wars.raidOf(colony.id).filter(w -> w.defender == colony.id);
        return war.flatMap(w -> ColonyManager.get(player.server).byId(w.attacker)).map(a -> a.rank(player.getUUID()).atLeast(Rank.MEMBER))
                .orElse(false);
    }

    /** What {@code player} may do to the block at {@code pos}: break it, or (for raiders) only ways in. */
    public static Verdict canBreak(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        Optional<Colony> found = ColonyManager.get(level.getServer()).at(level.dimension(), pos);
        if (found.isEmpty() || admin(player)) {
            return Verdict.ALLOWED;
        }
        Colony c = found.get();
        if (state.is(StadRegistries.TOWN_HALL.get()) && c.center.equals(pos)) {
            return c.owner().equals(player.getUUID()) ? Verdict.ALLOWED : Verdict.DENIED;
        }
        if (c.rank(player.getUUID()).atLeast(Rank.MEMBER)) {
            return Verdict.ALLOWED;
        }
        return raiding(player, c) && breakableInRaid(state) ? Verdict.RAID_BREAKABLE : Verdict.DENIED;
    }

    private static void deny(Player p, String msg) {
        p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.RED), true);
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer p)) {
            return;
        }
        if (canBreak(p, level, event.getPos(), event.getState()) == Verdict.DENIED) {
            event.setCanceled(true);
            Colony c = ColonyManager.get(level.getServer()).at(level.dimension(), event.getPos()).orElseThrow();
            deny(p, event.getState().is(StadRegistries.TOWN_HALL.get()) ? "Only " + c.ownerName() + " can take down the town hall of " + c.name() + "."
                    : raiding(p, c) ? "In a raid you can only break doors, fences, walls and gates." : "This is " + c.name() + "'s land.");
        }
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer p) || admin(p)) {
            return;
        }
        ColonyManager.get(level.getServer()).at(level.dimension(), event.getPos()).ifPresent(c -> {
            if (!c.rank(p.getUUID()).atLeast(Rank.MEMBER)) {
                event.setCanceled(true);
                deny(p, "This is " + c.name() + "'s land.");
            }
        });
    }

    /** Chests, barrels, furnaces and beds are locked to outsiders (doors and the town hall's own screen aren't). */
    private static void onUse(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer p) || admin(p)) {
            return;
        }
        BlockState s = level.getBlockState(event.getPos());
        boolean locked = s.getBlock() instanceof BedBlock
                || (level.getBlockEntity(event.getPos()) instanceof net.minecraft.world.Container && !s.is(StadRegistries.TOWN_HALL.get()));
        if (!locked) {
            return;
        }
        ColonyManager.get(level.getServer()).at(level.dimension(), event.getPos()).ifPresent(c -> {
            if (!c.rank(p.getUUID()).atLeast(Rank.MEMBER)) {
                event.setCanceled(true);
                deny(p, "That belongs to " + c.name() + ".");
            }
        });
    }
}
