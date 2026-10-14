package dev.eiriksb.stad.item;

import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Construction;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import dev.eiriksb.stad.net.PlansPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Use it on the ground in your town to choose a building from the packs and plan it there for the builders. */
public class BuildingPlansItem extends Item {
    public BuildingPlansItem(Properties props) {
        super(props);
    }

    private static final List<String> GROUPS = List.of("Homes", "Workplaces", "Town", "Farms");

    private static int groupOrder(String g) {
        return GROUPS.indexOf(g);
    }

    /** What kind of building it is, for the plans' tabs. */
    static String group(BuildingDef d) {
        net.minecraft.resources.ResourceLocation t = d.type();
        if (t.equals(BuildingDef.STALLS) || t.equals(BuildingDef.ROAD)) {
            return "Town";
        }
        if (dev.eiriksb.stad.architect.Yard.is(t)) {
            return "Farms";
        }
        if (t.equals(BuildingDef.HOME)) {
            return "Homes";
        }
        if (t.equals(BuildingDef.WAREHOUSE) || t.equals(BuildingDef.MARKET) || t.equals(BuildingDef.SCHOOL) || t.equals(BuildingDef.INFIRMARY)
                || t.equals(BuildingDef.BARRACKS) || t.equals(BuildingDef.WATCHTOWER)) {
            return "Town";
        }
        return dev.eiriksb.stad.colony.Workplaces.jobFor(t).isPresent() ? "Workplaces" : "Town";
    }

    /** What it's for, in a line: "A home with 2 beds", "Where the farmer works". */
    static String purpose(BuildingDef d) {
        net.minecraft.resources.ResourceLocation t = d.type();
        long beds = d.blueprint().entries().stream().filter(e -> e.state().getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && e.state().getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD).count();
        String sleeps = beds == 0 ? "" : " Sleeps " + beds + ".";
        if (t.equals(BuildingDef.FIELD)) {
            return "Where the farmers grow wheat (and whatever seeds the town has). Design your own at the architect's table.";
        }
        if (t.equals(BuildingDef.PEN)) {
            return "A fenced pen: the shepherd brings animals in through the gate.";
        }
        if (t.equals(BuildingDef.GROVE)) {
            return "Saplings for the lumberjack to plant and fell.";
        }
        if (t.equals(BuildingDef.ROAD)) {
            return "A road: citizens walk faster on it. Draw your own at the architect's table.";
        }
        if (t.equals(BuildingDef.STALLS)) {
            return "An open-air market: a merchant keeps each stall.";
        }
        if (t.equals(BuildingDef.APIARY)) {
            return "Beehives among flowers: the beekeeper takes the honey and honeycomb.";
        }
        if (t.equals(BuildingDef.HOME)) {
            return "A home." + sleeps;
        }
        if (t.equals(BuildingDef.WAREHOUSE)) {
            return "Where workers bring what they make, and couriers work from." + sleeps;
        }
        Optional<String> job = dev.eiriksb.stad.colony.Workplaces.jobFor(t);
        return job.map(j -> "Where the " + j + (j.equals("guard") ? "s live and" : "") + " work" + (j.equals("guard") ? "" : "s")
                + ": they work faster and are happier there.").orElse("A " + dev.eiriksb.stad.building.Requirements.word(t) + ".") + sleeps
                + " " + d.pack().substring(0, 1).toUpperCase() + d.pack().substring(1) + " pack.";
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        Optional<Colony> colony = ColonyManager.get(player.server).at(player.level().dimension(), ctx.getClickedPos());
        if (colony.isEmpty()) {
            player.displayClientMessage(Component.literal("Plan buildings on your town's land.").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }
        if (!colony.get().rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            player.displayClientMessage(Component.literal("Only officers of " + colony.get().name() + " can plan buildings.")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        List<PlansPayload.Option> options = new ArrayList<>();
        BuildingPacks.all().stream().sorted(Comparator.comparing((BuildingDef d) -> groupOrder(group(d))).thenComparing(BuildingDef::pack)
                .thenComparing(BuildingDef::name)).forEach(d -> {
            var size = d.blueprint().size();
            String materials = d.type().equals(BuildingDef.FIELD) ? "Nothing: the farmers make it."
                    : Construction.describe(Placement.of(d.blueprint(), ctx.getClickedPos(), net.minecraft.world.level.block.Rotation.NONE)
                    .materials(), 6);
            options.add(new PlansPayload.Option(d.key().toString(), d.name(), d.description(), size.getX(), size.getY(), size.getZ(), materials,
                    group(d), purpose(d)));
        });
        PacketDistributor.sendToPlayer(player, new PlansPayload(colony.get().id, ctx.getClickedPos(),
                Placement.facingViewer(player.getDirection()), options,
                dev.eiriksb.stad.pack.Palettes.all().stream().map(dev.eiriksb.stad.pack.Palette::name).toList()));
        return InteractionResult.CONSUME;
    }
}
