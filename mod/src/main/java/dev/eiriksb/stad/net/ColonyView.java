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
public record ColonyView(int id, String name, String owner, BlockPos center, int radius, String growth, int beds, Rank viewerRank, String standing,
                         List<Member> members, List<Citizen> citizens, List<String> buildings, List<String> needs, List<String> recentOrders,
                         boolean open, boolean poaching, String war, List<Step> steps, List<Job> jobs, List<Topic> research) {
    public record Member(String name, Rank rank) {}

    /** a citizen, and what they're doing (or what's stopping them: {@code problem}) */
    public record Citizen(java.util.UUID uuid, String name, String job, boolean home, int hunger, int rest, int happiness, String status, boolean problem) {}

    public record Step(String text, boolean done) {}

    /** a job: what it does and needs, what's missing for it here ("" for nothing), and who has it */
    public record Job(String job, String does, String needs, String problem, List<String> workers) {}

    /** A research topic: what it gives and takes, what it needs first ("" for nothing), how far along, and its state. */
    public record Topic(String id, String name, String gives, String cost, String needs, int percent, State state) {}

    public enum State { CURRENT, AVAILABLE, LOCKED, DONE }

    public static ColonyView of(Colony c, ServerLevel level, ServerPlayer viewer) {
        List<Member> members = new ArrayList<>();
        c.ranks().forEach((uuid, rank) -> members.add(new Member(c.playerName(uuid), rank)));
        members.sort((a, b) -> b.rank().compareTo(a.rank()));
        List<Citizen> citizens = new ArrayList<>();
        for (CitizenRecord r : c.citizens()) {
            dev.eiriksb.stad.citizen.Status st = level.getEntity(r.uuid) instanceof dev.eiriksb.stad.citizen.CitizenEntity e ? e.status()
                    : new dev.eiriksb.stad.citizen.Status("Away (not near anyone)", false);
            citizens.add(new Citizen(r.uuid, r.name, r.job == null ? "" : r.job, r.home != null, r.hunger, r.rest, r.happiness, st.text(), st.problem()));
        }
        List<String> orders = new ArrayList<>();
        for (OrderRecord o : c.orders().subList(0, Math.min(5, c.orders().size()))) {
            orders.add(o.playerName() + ": " + o.kind().name().toLowerCase() + " " + o.citizenName() + " - " + o.outcome().name().toLowerCase()
                    + (o.reason() == null || o.reason().isEmpty() ? "" : " (" + o.reason() + ")"));
        }
        List<String> buildings = new ArrayList<>();
        for (dev.eiriksb.stad.colony.Building b : c.buildings()) {
            String line = "#" + b.id + " " + b.name + ": " + dev.eiriksb.stad.command.StadCommands.status(b);
            if (b.done() && dev.eiriksb.stad.colony.Workplaces.isWorkplace(b)) {
                List<String> who = dev.eiriksb.stad.colony.Workplaces.workers(c, b).stream().map(r -> r.name.split(" ")[0]).toList();
                line += " · " + who.size() + "/" + dev.eiriksb.stad.colony.Workplaces.slots(b) + " workers" + (who.isEmpty() ? "" : " (" + String.join(", ", who) + ")");
            }
            buildings.add(line);
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
        List<Step> steps = dev.eiriksb.stad.colony.Guide.steps(level, c).stream().map(x -> new Step(x.text(), x.done())).toList();
        List<Job> jobs = dev.eiriksb.stad.colony.Guide.jobs(level, c).stream()
                .map(j -> new Job(j.job(), j.does(), j.needs(), j.problem() == null ? "" : j.problem(), j.workers())).toList();
        int wanted = dev.eiriksb.stad.colony.Growth.wanted(c), per = dev.eiriksb.stad.colony.Growth.perHome(c);
        String growth = per == 0 ? "" : c.radius >= dev.eiriksb.stad.colony.Growth.max(c) ? "The land has grown as far as it can."
                : wanted > c.radius && dev.eiriksb.stad.colony.Growth.room(c, dev.eiriksb.stad.colony.ColonyManager.get(level.getServer()).all()) <= c.radius
                ? "The land can't grow any further: another town's land is in the way."
                : "The land grows " + per + " blocks with each new home (up to " + dev.eiriksb.stad.colony.Growth.max(c) + ").";
        return new ColonyView(c.id, c.name(), c.ownerName(), c.center, c.radius, growth, Homes.beds(level, c).size(), c.rank(viewer.getUUID()),
                standing.toString(), members, citizens, buildings, needs, orders, c.politics.open(), c.politics.poaching, String.join("; ", wars),
                steps, jobs, research(c));
    }

    private static List<Topic> research(Colony c) {
        List<Topic> out = new java.util.ArrayList<>();
        for (dev.eiriksb.stad.colony.Research.Topic t : dev.eiriksb.stad.colony.Research.TOPICS) {
            boolean done = dev.eiriksb.stad.colony.Research.has(c, t.id());
            State state = done ? State.DONE : t.id().equals(c.research.current) ? State.CURRENT
                    : dev.eiriksb.stad.colony.Research.available(c, t) ? State.AVAILABLE : State.LOCKED;
            String needs = String.join(" and ", t.requires().stream().filter(r -> !c.research.done.contains(r))
                    .map(r -> dev.eiriksb.stad.colony.Research.topic(r).map(dev.eiriksb.stad.colony.Research.Topic::name).orElse(r)).toList());
            int percent = state == State.CURRENT ? 100 * c.research.progress / Math.max(1, t.work()) : 0;
            out.add(new Topic(t.id(), t.name(), dev.eiriksb.stad.colony.Research.gives(t), dev.eiriksb.stad.colony.Research.describe(t.cost())
                    + (state == State.CURRENT && c.research.paid ? " (paid)" : ""), needs, percent, state));
        }
        return out;
    }

    public static void send(ServerPlayer player, Colony colony, ServerLevel level) {
        send(player, colony, level, "", false);
    }

    public static void send(ServerPlayer player, Colony colony, ServerLevel level, String notice, boolean error) {
        PacketDistributor.sendToPlayer(player, new ColonyInfoPayload(of(colony, level, player), notice, error));
    }

    void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUtf(name);
        buf.writeUtf(owner);
        buf.writeBlockPos(center);
        buf.writeVarInt(radius);
        buf.writeUtf(growth);
        buf.writeVarInt(beds);
        buf.writeEnum(viewerRank);
        buf.writeUtf(standing);
        buf.writeCollection(members, (b, m) -> {
            b.writeUtf(m.name());
            b.writeEnum(m.rank());
        });
        buf.writeCollection(citizens, (b, c) -> {
            b.writeUUID(c.uuid());
            b.writeUtf(c.name());
            b.writeUtf(c.job());
            b.writeBoolean(c.home());
            b.writeVarInt(c.hunger());
            b.writeVarInt(c.rest());
            b.writeVarInt(c.happiness());
            b.writeUtf(c.status());
            b.writeBoolean(c.problem());
        });
        buf.writeCollection(buildings, FriendlyByteBuf::writeUtf);
        buf.writeCollection(needs, FriendlyByteBuf::writeUtf);
        buf.writeCollection(recentOrders, FriendlyByteBuf::writeUtf);
        buf.writeBoolean(open);
        buf.writeBoolean(poaching);
        buf.writeUtf(war);
        buf.writeCollection(steps, (b, x) -> {
            b.writeUtf(x.text());
            b.writeBoolean(x.done());
        });
        buf.writeCollection(jobs, (b, j) -> {
            b.writeUtf(j.job());
            b.writeUtf(j.does());
            b.writeUtf(j.needs());
            b.writeUtf(j.problem());
            b.writeCollection(j.workers(), FriendlyByteBuf::writeUtf);
        });
        buf.writeCollection(research, (b, t) -> {
            b.writeUtf(t.id());
            b.writeUtf(t.name());
            b.writeUtf(t.gives());
            b.writeUtf(t.cost());
            b.writeUtf(t.needs());
            b.writeVarInt(t.percent());
            b.writeEnum(t.state());
        });
    }

    static ColonyView read(FriendlyByteBuf buf) {
        return new ColonyView(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readBlockPos(), buf.readVarInt(), buf.readUtf(), buf.readVarInt(),
                buf.readEnum(Rank.class),
                buf.readUtf(),
                buf.readList(b -> new Member(b.readUtf(), b.readEnum(Rank.class))),
                buf.readList(b -> new Citizen(b.readUUID(), b.readUtf(), b.readUtf(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                        b.readUtf(), b.readBoolean())),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readBoolean(), buf.readBoolean(), buf.readUtf(),
                buf.readList(b -> new Step(b.readUtf(), b.readBoolean())),
                buf.readList(b -> new Job(b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readList(FriendlyByteBuf::readUtf))),
                buf.readList(b -> new Topic(b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readVarInt(), b.readEnum(State.class))));
    }
}
