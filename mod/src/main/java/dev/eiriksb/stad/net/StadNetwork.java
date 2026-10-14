package dev.eiriksb.stad.net;

import dev.eiriksb.stad.block.TownHallBlock;
import dev.eiriksb.stad.building.BuildingDef;
import dev.eiriksb.stad.building.BuildingPacks;
import dev.eiriksb.stad.building.Outlines;
import dev.eiriksb.stad.building.Placement;
import dev.eiriksb.stad.building.Planning;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Optional;

/** Stad's network messages. */
public final class StadNetwork {
    private StadNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(StadNetwork::payloads);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playToClient(ColonyInfoPayload.TYPE, ColonyInfoPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.openTownHall(p.view(), p.notice(), p.error());
            }
        });
        r.playToServer(ColonyActionPayload.TYPE, ColonyActionPayload.CODEC, StadNetwork::action);
        r.playToClient(PlansPayload.TYPE, PlansPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.openPlans(p);
            }
        });
        r.playToServer(PlanActionPayload.TYPE, PlanActionPayload.CODEC, StadNetwork::planAction);
        r.playToClient(EditorPayload.TYPE, EditorPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.openEditor(p);
            }
        });
        r.playToServer(EditorSavePayload.TYPE, EditorSavePayload.CODEC, StadNetwork::editorSave);
        r.playToClient(CitizenScreenPayload.TYPE, CitizenScreenPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.openCitizen(p);
            }
        });
        r.playBidirectional(CitizenLinePayload.TYPE, CitizenLinePayload.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                dev.eiriksb.stad.citizen.CitizenScreens.say(player, p);
            } else if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.citizenLine(p);
            }
        });
        r.playToClient(TablePayloads.Open.TYPE, TablePayloads.Open.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.openTable(p);
            }
        });
        r.playToClient(TablePayloads.Update.TYPE, TablePayloads.Update.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.updateTable(p);
            }
        });
        r.playToServer(TablePayloads.Edit.TYPE, TablePayloads.Edit.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                dev.eiriksb.stad.architect.Tables.edit(player, p);
            }
        });
        r.playToServer(TablePayloads.Action.TYPE, TablePayloads.Action.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                dev.eiriksb.stad.architect.Tables.action(player, p);
            }
        });
        r.playToClient(DevUiPayload.TYPE, DevUiPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.devUi(p.script());
            }
        });
        r.playToClient(TablePayloads.Drafting.TYPE, TablePayloads.Drafting.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.drafting(p);
            }
        });
        r.playToClient(PlacePreviewPayload.TYPE, PlacePreviewPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.placePreview(p);
            }
        });
        r.playToClient(DraftPreviewPayload.TYPE, DraftPreviewPayload.CODEC, (p, ctx) -> {
            if (FMLEnvironment.dist.isClient()) {
                dev.eiriksb.stad.client.StadClientHooks.draftPreview(p);
            }
        });
    }

    private static void editorSave(EditorSavePayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        try {
            ResourceLocation key = dev.eiriksb.stad.pack.PackEditor.save(player, p.token(), p.name(), p.kind(), p.rows());
            player.sendSystemMessage(Component.literal("Saved " + p.name().strip() + " in My pack (" + key + "). It's in the Building Plans now.")
                    .withStyle(ChatFormatting.GREEN));
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("Couldn't save it: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    private static void planAction(PlanActionPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        Optional<Colony> colony = ColonyManager.get(player.server).byId(p.colony());
        Optional<BuildingDef> def = BuildingPacks.get(ResourceLocation.tryParse(p.key()));
        if (colony.isEmpty() || def.isEmpty() || player.distanceToSqr(p.at().getCenter()) > 64 * 64) {
            return;
        }
        Optional<dev.eiriksb.stad.pack.Palette> palette = def.get().key().equals(dev.eiriksb.stad.building.Fields.KEY) ? Optional.empty()
                : dev.eiriksb.stad.pack.Palettes.get(p.palette());
        dev.eiriksb.stad.planner.Design styled = palette.map(pal -> dev.eiriksb.stad.planner.Design.styled(def.get(), pal)).orElse(null);
        var blueprint = styled != null ? styled.blueprint() : def.get().blueprint();
        String name = styled != null ? styled.name() : def.get().name();
        BlockPos origin = Placement.originFor(blueprint, p.at(), p.rotation());
        if (p.preview()) { // the building itself, to move about and put down by hand (client.BuildingPlacer)
            String problem = Planning.problem(colony.get(), blueprint, origin, p.rotation());
            if (problem == null) {
                problem = Planning.ground(player.serverLevel(), blueprint, origin, p.rotation());
            }
            int[] blocks = new int[0];
            if (!p.check()) {
                java.util.List<dev.eiriksb.stad.building.Blueprint.Entry> entries = blueprint.entries().stream().filter(e -> !e.state().isAir()).toList();
                blocks = new int[entries.size() * 2];
                for (int i = 0; i < entries.size(); i++) {
                    blocks[2 * i] = PlacePreviewPayload.pack(entries.get(i).pos());
                    blocks[2 * i + 1] = net.minecraft.world.level.block.Block.getId(entries.get(i).state());
                }
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new PlacePreviewPayload(p.colony(), p.key(), p.palette(), name, blueprint.size().getX(),
                    blueprint.size().getY(), blueprint.size().getZ(), blocks, p.at(), p.rotation(), problem == null ? "" : problem));
            return;
        }
        Planning.Result result = styled != null ? Planning.plan(player, player.serverLevel(), colony.get(), styled, origin, p.rotation())
                : Planning.plan(player, player.serverLevel(), colony.get(), def.get(), origin, p.rotation());
        player.sendSystemMessage(result.building() == null ? Component.literal(result.problem()).withStyle(ChatFormatting.RED)
                : Component.literal(Planning.plannedMessage(player.serverLevel(), colony.get(), result.building())).withStyle(ChatFormatting.GREEN));
    }

    private static void action(ColonyActionPayload p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        Optional<Colony> found = ColonyManager.get(player.server).byId(p.colony());
        if (found.isEmpty()) {
            return;
        }
        Colony colony = found.get();
        ServerLevel level = player.server.getLevel(colony.dimension);
        if (level == null || player.level() != level || player.distanceToSqr(colony.center.getCenter()) > 64) {
            return; // the screen belongs to a town hall the player is standing at
        }
        switch (p.action()) {
            case RENAME -> {
                String name = p.text().strip();
                if (!colony.rank(player.getUUID()).atLeast(Rank.OFFICER)) {
                    player.displayClientMessage(Component.literal("Only officers can rename the town.").withStyle(ChatFormatting.RED), true);
                } else if (name.length() >= 2 && name.length() <= 32) {
                    colony.rename(name);
                    ColonyView.send(player, colony, level);
                }
            }
            case OPEN_STORAGE -> TownHallBlock.openStorage(player, level, colony.center);
            case STANCE -> {
                if (dev.eiriksb.stad.command.StadCommands.setStance(player, colony, colony.politics.stance == dev.eiriksb.stad.war.Politics.Stance.OPEN
                        ? dev.eiriksb.stad.war.Politics.Stance.PEACEFUL : dev.eiriksb.stad.war.Politics.Stance.OPEN)) {
                    ColonyView.send(player, colony, level);
                }
            }
            case ROADS -> {
                var r = dev.eiriksb.stad.architect.TownRoads.plan(player, level, colony, dev.eiriksb.stad.architect.Yard.parse(
                        "{\"paving\": \"" + p.text().replaceAll("[^a-z_]", "") + "\"}"));
                player.sendSystemMessage(Component.literal(r.message()).withStyle(r.error() ? ChatFormatting.RED : ChatFormatting.GREEN));
                ColonyView.send(player, colony, level, r.message(), r.error());
            }
            case RESEARCH -> {
                String said = dev.eiriksb.stad.colony.Research.choose(colony, player, p.text());
                ColonyView.send(player, colony, level, said, !said.startsWith("The university will"));
            }
            case POACHING -> {
                if (colony.owner().equals(player.getUUID())) {
                    colony.politics.poaching = !colony.politics.poaching;
                    colony.changed();
                    ColonyView.send(player, colony, level);
                }
            }
        }
    }
}
