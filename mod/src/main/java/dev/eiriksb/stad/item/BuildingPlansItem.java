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
        BuildingPacks.all().stream().sorted(Comparator.comparing(BuildingDef::pack).thenComparing(BuildingDef::name)).forEach(d -> {
            var size = d.blueprint().size();
            String materials = Construction.describe(Placement.of(d.blueprint(), ctx.getClickedPos(), net.minecraft.world.level.block.Rotation.NONE)
                    .materials(), 6);
            options.add(new PlansPayload.Option(d.key().toString(), d.name(), d.description(), size.getX(), size.getY(), size.getZ(), materials));
        });
        PacketDistributor.sendToPlayer(player, new PlansPayload(colony.get().id, ctx.getClickedPos(),
                Placement.facingViewer(player.getDirection()), options));
        return InteractionResult.CONSUME;
    }
}
