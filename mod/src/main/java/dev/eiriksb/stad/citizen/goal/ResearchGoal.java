package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.building.ColonyStorage;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.Tools;
import dev.eiriksb.stad.colony.Announce;
import dev.eiriksb.stad.colony.Building;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.colony.Research;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The researcher (PLAN.md §6.14) works at the university's lectern on what the town's officers chose (the town hall's
 * Research tab): first the topic's books and materials are fetched from the stores, then a piece of study every half
 * minute or so until it's done, when the town is told what it can build now.
 */
public class ResearchGoal extends WorkGoal {
    /** ticks a piece of study takes, before skill, workplace and the rest */
    static final int STUDY = 600;

    private boolean studying;

    public ResearchGoal(CitizenEntity citizen) {
        super(citizen, Jobs.RESEARCHER);
    }

    private Optional<BlockPos> lectern(Colony colony) {
        return workplace(colony).flatMap(Building::placement)
                .flatMap(p -> p.steps().stream().filter(s -> s.state().is(Blocks.LECTERN)).map(s -> s.pos()).findFirst());
    }

    private static Optional<Research.Topic> topic(Colony colony) {
        return colony.research.current == null ? Optional.empty() : Research.topic(colony.research.current);
    }

    private ColonyStorage stores(ServerLevel level, Colony colony) {
        List<ColonyStorage.Box> all = new ArrayList<>();
        Tools.stores(level, colony, citizen, workplace(colony)).forEach(s -> all.addAll(s.boxes()));
        return ColonyStorage.of(all);
    }

    /** What the topic takes that the stores are short of. */
    private Map<Item, Integer> short_(ServerLevel level, Colony colony, Research.Topic t) {
        ColonyStorage stores = stores(level, colony);
        Map<Item, Integer> out = new java.util.LinkedHashMap<>();
        t.cost().forEach((item, n) -> {
            int have = stores.count(item);
            if (have < n) {
                out.put(item, n - have);
            }
        });
        return out;
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        if (lectern(colony).isEmpty()) {
            return false;
        }
        Optional<Research.Topic> t = topic(colony);
        return t.isPresent() && (colony.research.paid || short_(level, colony, t.get()).isEmpty());
    }

    @Override
    protected String blocked(ServerLevel level, Colony colony) {
        if (workplace(colony).isEmpty() || lectern(colony).isEmpty()) {
            return "No university to work at: build one (a lectern and bookshelves)";
        }
        Optional<Research.Topic> t = topic(colony);
        if (t.isEmpty()) {
            return "Nothing to research: choose a topic in the town hall's Research tab";
        }
        Map<Item, Integer> missing = short_(level, colony, t.get());
        return missing.isEmpty() ? null : "To research " + t.get().name() + " the town needs " + Research.describe(missing)
                + " more, in the town hall or a warehouse";
    }

    @Override
    protected String doingNow() {
        return citizen.colony().flatMap(ResearchGoal::topic).map(t -> "Researching " + t.name()).orElse("At the university");
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        Optional<Research.Topic> topic = topic(colony);
        Optional<BlockPos> at = lectern(colony);
        if (topic.isEmpty() || at.isEmpty()) {
            return;
        }
        Research.Topic t = topic.get();
        if (!colony.research.paid) { // the books and materials, from the stores
            ColonyStorage stores = stores(level, colony);
            Item first = t.cost().keySet().iterator().next();
            ColonyStorage.Box box = stores.find(s -> s.is(first));
            if (box == null || !approach.tick(citizen, box.pos(), 2.8, 0.6)) {
                return;
            }
            if (!short_(level, colony, t).isEmpty()) {
                return;
            }
            t.cost().forEach((item, n) -> stores.take(item, n));
            colony.research.paid = true;
            colony.changed();
            citizen.swing(InteractionHand.MAIN_HAND);
            approach.reset();
            return;
        }
        BlockPos p = at.get();
        if (!approach.tick(citizen, p, 2.5, 0.6)) {
            studying = false;
            return;
        }
        citizen.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5);
        if (!studying) {
            studying = true;
            cooldown = pace(STUDY);
        }
        if (--cooldown > 0) {
            if (cooldown % 40 == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                level.sendParticles(ParticleTypes.ENCHANT, p.getX() + 0.5, p.getY() + 1.3, p.getZ() + 0.5, 6, 0.4, 0.3, 0.4, 0.4);
                level.playSound(null, p, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.6f, 1f);
            }
            return;
        }
        studying = false;
        worked(1);
        colony.research.progress++;
        if (colony.research.progress >= t.work()) {
            colony.research.done.add(t.id());
            colony.research.current = null;
            colony.research.progress = 0;
            colony.research.paid = false;
            dev.eiriksb.stad.colony.Achievements.feat(colony, dev.eiriksb.stad.colony.Achievements.RESEARCH);
            Announce.members(level.getServer(), colony, Component.literal("The university has finished researching " + t.name() + ". "
                    + Research.gives(t) + ".").withStyle(ChatFormatting.GREEN));
            level.playSound(null, p, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.7f, 1f);
        }
        colony.changed();
    }
}
