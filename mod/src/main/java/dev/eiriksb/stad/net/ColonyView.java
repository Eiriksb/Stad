package dev.eiriksb.stad.net;

import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Homes;
import dev.eiriksb.stad.colony.OrderRecord;
import dev.eiriksb.stad.colony.Rank;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** What the town hall screen shows, as sent to one player. */
public record ColonyView(int id, String name, String owner, BlockPos center, int radius, int beds, Rank viewerRank, String standing,
                         List<Member> members, List<Citizen> citizens, List<String> buildings, List<String> needs, List<String> recentOrders,
                         boolean open, boolean poaching, String war) {
    public record Member(String name, Rank rank) {}

    public record Citizen(String name, String job, boolean home, int hunger, int rest, int happiness) {}

    public static ColonyView of(Colony c, ServerLevel level, ServerPlayer viewer) {
        List<Member> members = new ArrayList<>();
        c.ranks().forEach((uuid, rank) -> members.add(new Member(c.playerName(uuid), rank)));
        members.sort((a, b) -> b.rank().compareTo(a.rank()));
        List<Citizen> citizens = new ArrayList<>();
        for (CitizenRecord r : c.citizens()) {
            citizens.add(new Citizen(r.name, r.job == null ? "" : r.job, r.home != null, r.hunger, r.rest, r.happiness));
        }
        List<String> orders = new ArrayList<>();
        for (OrderRecord o : c.orders().subList(0, Math.min(5, c.orders().size()))) {
            orders.add(o.playerName() + ": " + o.kind().name().toLowerCase() + " " + o.citizenName() + " - " + o.outcome().name().toLowerCase()
                    + (o.reason() == null || o.reason().isEmpty() ? "" : " (" + o.reason() + ")"));
        }
        List<String> buildings = new ArrayList<>();
        for (dev.eiriksb.stad.colony.Building b : c.buildings()) {
            buildings.add("#" + b.id + " " + b.name + ": " + dev.eiriksb.stad.command.StadCommands.status(b));
        }
        List<String> needs = new ArrayList<>();
        for (dev.eiriksb.stad.colony.Request r : c.requests()) {
            needs.add(r.label() + " for " + r.destName + " (" + r.reason + ")");
        }
        var st = c.standing(viewer.getUUID());
        StringBuilder standing = new StringBuilder("You're " + st.word() + " (" + (st.score() > 0 ? "+" : "") + st.score() + ")");
        List<String> why = new ArrayList<>();
        st.worst(2).forEach(e -> why.add(e.delta() + " " + e.cause()));
        st.best(2).forEach(e -> why.add("+" + e.delta() + " " + e.cause()));
        if (!why.isEmpty()) {
            standing.append(": ").append(String.join(", ", why));
        }
        List<String> wars = new ArrayList<>();
        for (dev.eiriksb.stad.war.War w : dev.eiriksb.stad.war.Wars.of(level.getServer(), c.id)) {
            String other = dev.eiriksb.stad.colony.ColonyManager.get(level.getServer()).byId(w.other(c.id)).map(Colony::name).orElse("?");
            wars.add((w.state == dev.eiriksb.stad.war.War.State.RAID ? "Raid with " : "At war with ") + other);
        }
        dev.eiriksb.stad.war.Raids.of(c).ifPresent(r -> wars.add("Raiders from the " + r.from.getName() + "!"));
        return new ColonyView(c.id, c.name(), c.ownerName(), c.center, c.radius, Homes.beds(level, c).size(), c.rank(viewer.getUUID()),
                standing.toString(), members, citizens, buildings, needs, orders, c.politics.open(), c.politics.poaching, String.join("; ", wars));
    }

    public static void send(ServerPlayer player, Colony colony, ServerLevel level) {
        PacketDistributor.sendToPlayer(player, new ColonyInfoPayload(of(colony, level, player)));
    }

    void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(name);
        buf.writeUtf(owner);
        buf.writeBlockPos(center);
        buf.writeVarInt(radius);
        buf.writeVarInt(beds);
        buf.writeEnum(viewerRank);
        buf.writeUtf(standing);
        buf.writeCollection(members, (b, m) -> {
            b.writeUtf(m.name());
            b.writeEnum(m.rank());
        });
        buf.writeCollection(citizens, (b, c) -> {
            b.writeUtf(c.name());
            b.writeUtf(c.job());
            b.writeBoolean(c.home());
            b.writeVarInt(c.hunger());
            b.writeVarInt(c.rest());
            b.writeVarInt(c.happiness());
        });
        buf.writeCollection(buildings, FriendlyByteBuf::writeUtf);
        buf.writeCollection(needs, FriendlyByteBuf::writeUtf);
        buf.writeCollection(recentOrders, FriendlyByteBuf::writeUtf);
        buf.writeBoolean(open);
        buf.writeBoolean(poaching);
        buf.writeUtf(war);
    }

    static ColonyView read(FriendlyByteBuf buf) {
        return new ColonyView(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(),
                buf.readEnum(Rank.class),
                buf.readUtf(),
                buf.readList(b -> new Member(b.readUtf(), b.readEnum(Rank.class))),
                buf.readList(b -> new Citizen(b.readUtf(), b.readUtf(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt())),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readBoolean(), buf.readBoolean(), buf.readUtf());
    }
}
