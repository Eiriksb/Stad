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

/**
 * Use it on the ground in your town to choose a building from the packs and plan it there for the builders. Sneak and
 * use it on two blocks to mark out an area (opposite corners); using it inside the area then plans for it: a building
 * from the packs goes inside it, or one is drawn up to fill it (a field, a pen, a house as big as it'll hold...).
 */
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
        return group(d.type());
    }

    static String group(net.minecraft.resources.ResourceLocation t) {
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
                || t.equals(BuildingDef.UNIVERSITY)
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
        if (t.equals(BuildingDef.UNIVERSITY)) {
            return "Where the researchers work out how to build the rest of the town's buildings, and better ways of working.";
        }
        Optional<String> job = dev.eiriksb.stad.colony.Workplaces.jobFor(t);
        return job.map(j -> "Where the " + j + (j.equals("guard") ? "s live and" : "") + " work" + (j.equals("guard") ? "" : "s")
                + ": they work faster and are happier there.").orElse("A " + dev.eiriksb.stad.building.Requirements.word(t) + ".") + sleeps
                + " " + d.pack().substring(0, 1).toUpperCase() + d.pack().substring(1) + " pack.";
    }

    public static PlanArea area(net.minecraft.world.item.ItemStack stack) {
        return stack.get(dev.eiriksb.stad.StadRegistries.PLAN_AREA);
    }

    /** Forgets the area on whichever plans the player holds that have it (it's been planned in). */
    public static void used(ServerPlayer player, PlanArea area) {
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BuildingPlansItem && area.equals(area(stack))) {
                stack.remove(dev.eiriksb.stad.StadRegistries.PLAN_AREA);
            }
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        net.minecraft.world.item.ItemStack stack = ctx.getItemInHand();
        net.minecraft.core.BlockPos pos = ctx.getClickedPos();
        if (player.isSecondaryUseActive()) {
            corner(player, stack, pos);
            return InteractionResult.CONSUME;
        }
        PlanArea area = area(stack);
        boolean inArea = area != null && area.complete() && area.covers(pos.getX(), pos.getZ());
        Optional<Colony> colony = ColonyManager.get(player.server).at(player.level().dimension(), pos);
        if (colony.isEmpty()) {
            player.displayClientMessage(Component.literal("Plan buildings on your town's land.").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }
        if (!colony.get().rank(player.getUUID()).atLeast(Rank.OFFICER)) {
            player.displayClientMessage(Component.literal("Only officers of " + colony.get().name() + " can plan buildings.")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        if (inArea) {
            String problem = dev.eiriksb.stad.architect.AreaFit.problem(player.serverLevel(), colony.get(), area);
            if (problem != null) {
                player.displayClientMessage(Component.literal(problem + " Sneak and right-click to mark it again.").withStyle(ChatFormatting.RED), true);
                return InteractionResult.CONSUME;
            }
        }
        open(player, colony.get(), inArea ? area.middle(pos.getY()) : pos, inArea ? area : null);
        return InteractionResult.CONSUME;
    }

    /** Sneak and use it in the air: forget the area. */
    @Override
    public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive() && area(stack) != null) {
            if (!level.isClientSide) {
                stack.remove(dev.eiriksb.stad.StadRegistries.PLAN_AREA);
                player.displayClientMessage(Component.literal("Area cleared.").withStyle(ChatFormatting.GRAY), true);
            }
            return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    /** The first corner, or the opposite one (and a third click starts again). */
    private static void corner(ServerPlayer player, net.minecraft.world.item.ItemStack stack, net.minecraft.core.BlockPos pos) {
        PlanArea old = area(stack);
        if (old == null || old.complete()) {
            stack.set(dev.eiriksb.stad.StadRegistries.PLAN_AREA, new PlanArea(pos.immutable(), Optional.empty()));
            player.displayClientMessage(Component.literal("First corner at " + pos.toShortString() + ". Sneak and right-click the opposite corner.")
                    .withStyle(ChatFormatting.AQUA), true);
            return;
        }
        PlanArea area = PlanArea.of(old.first(), pos);
        stack.set(dev.eiriksb.stad.StadRegistries.PLAN_AREA, area);
        Optional<Colony> colony = ColonyManager.get(player.server).at(player.level().dimension(), pos);
        String problem = colony.isEmpty() ? "The area has to be on your town's land."
                : dev.eiriksb.stad.architect.AreaFit.problem(player.serverLevel(), colony.get(), area);
        player.displayClientMessage(problem != null ? Component.literal(problem).withStyle(ChatFormatting.RED)
                : Component.literal("Marked out " + area.size() + ". Right-click inside it to plan what goes there.")
                .withStyle(ChatFormatting.AQUA), true);
    }

    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext ctx, List<Component> lines,
                                net.minecraft.world.item.TooltipFlag flag) {
        lines.add(Component.literal("Right-click the ground in your town to plan a building.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Sneak and right-click two corners to mark out an area,").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("then right-click inside it to build there, or right-click").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("an architect's table to design something for it.").withStyle(ChatFormatting.GRAY));
        PlanArea area = area(stack);
        if (area != null) {
            lines.add(Component.literal(area.complete() ? "Area: " + area.size() + " (sneak and right-click the air to clear it)"
                    : "First corner marked").withStyle(ChatFormatting.AQUA));
        }
    }

    /** Opens the plans: for a spot, or with {@code area}, for the area (centred in it, {@code at} the floor's height). */
    private static void open(ServerPlayer player, Colony colony, net.minecraft.core.BlockPos at, PlanArea area) {
        List<PlansPayload.Option> options = new ArrayList<>();
        if (area != null) {
            for (net.minecraft.resources.ResourceLocation type : dev.eiriksb.stad.architect.AreaFit.types()) {
                String name = dev.eiriksb.stad.architect.AreaFit.name(type);
                boolean land = dev.eiriksb.stad.architect.Yard.is(type);
                options.add(new PlansPayload.Option(dev.eiriksb.stad.architect.AreaFit.key(type), name + " to fit",
                        (land ? "Laid out over the whole area, " : "Drawn up as big as the area holds, ") + "the way the architect's table generates one."
                                + " To design it yourself instead, right-click an architect's table with the plans: the area becomes its site.",
                        area.sizeX(), 0, area.sizeZ(), "Worked out when you preview it.", group(type),
                        land ? "Laid out over the whole " + area.size() + " area." : "As big as the " + area.size() + " area holds.",
                        dev.eiriksb.stad.colony.Research.needed(colony, type).map(t -> t.name()).orElse(""), PlansPayload.FILLS));
            }
        }
        BuildingPacks.all().stream().sorted(Comparator.comparing((BuildingDef d) -> groupOrder(group(d))).thenComparing(BuildingDef::pack)
                .thenComparing(BuildingDef::name)).forEach(d -> {
            var size = d.blueprint().size();
            String materials = d.type().equals(BuildingDef.FIELD) ? "Nothing: the farmers make it."
                    : Construction.describe(Placement.of(d.blueprint(), at, net.minecraft.world.level.block.Rotation.NONE)
                    .materials(), 6);
            String fit = "";
            if (area != null) {
                boolean straight = size.getX() <= area.sizeX() && size.getZ() <= area.sizeZ();
                boolean turned = size.getZ() <= area.sizeX() && size.getX() <= area.sizeZ();
                fit = straight && turned ? PlansPayload.FITS : straight ? PlansPayload.STRAIGHT : turned ? PlansPayload.TURNED
                        : "Too big for the area: it's " + size.getX() + " × " + size.getZ() + ".";
            }
            options.add(new PlansPayload.Option(d.key().toString(), d.name(), d.description(), size.getX(), size.getY(), size.getZ(), materials,
                    group(d), purpose(d), dev.eiriksb.stad.colony.Research.needed(colony, d.type()).map(t -> t.name()).orElse(""), fit));
        });
        options.sort(Comparator.comparing((PlansPayload.Option o) -> groupOrder(o.group())));
        PacketDistributor.sendToPlayer(player, new PlansPayload(colony.id, at, Placement.facingViewer(player.getDirection()), options,
                dev.eiriksb.stad.pack.Palettes.all().stream().map(dev.eiriksb.stad.pack.Palette::name).toList(), area));
    }
}
