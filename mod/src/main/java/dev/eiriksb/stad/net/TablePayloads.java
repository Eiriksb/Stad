package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import dev.eiriksb.stad.architect.Grid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** The architect's table's messages (PLAN.md §6.8). */
public final class TablePayloads {
    private TablePayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, name));
    }

    /** A style the table can generate in: a palette's materials ({@link dev.eiriksb.stad.planner.Materials} names). */
    public record Style(String name, String walls, String frame, String roof, String floor) {
        static void write(FriendlyByteBuf b, Style s) {
            b.writeUtf(s.name);
            b.writeUtf(s.walls);
            b.writeUtf(s.frame);
            b.writeUtf(s.roof);
            b.writeUtf(s.floor);
        }

        static Style read(FriendlyByteBuf b) {
            return new Style(b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf(), b.readUtf());
        }
    }

    /** The design: what it puts where (site keys and block state ids), its markers, and what it was generated from. */
    public record Design(String name, String type, String spec, String yard, String door, int floorY, int[] keys, int[] states, int[] markerKeys,
                         List<String> markerNames) {
        static void write(FriendlyByteBuf b, Design d) {
            b.writeUtf(d.name);
            b.writeUtf(d.type);
            b.writeUtf(d.spec, 4096);
            b.writeUtf(d.yard, 1024);
            b.writeUtf(d.door);
            b.writeVarInt(d.floorY + 1);
            b.writeVarIntArray(d.keys);
            b.writeVarIntArray(d.states);
            b.writeVarIntArray(d.markerKeys);
            b.writeCollection(d.markerNames, FriendlyByteBuf::writeUtf);
        }

        static Design read(FriendlyByteBuf b) {
            return new Design(b.readUtf(), b.readUtf(), b.readUtf(4096), b.readUtf(1024), b.readUtf(), b.readVarInt() - 1, b.readVarIntArray(),
                    b.readVarIntArray(), b.readVarIntArray(), b.readList(FriendlyByteBuf::readUtf));
        }
    }

    /**
     * Server to client: open the table's screen.
     *
     * @param editor  who else is using it ("" when nobody), when this player may only watch
     * @param terrain the site and the land round it (null when there's no site yet)
     * @param corners the corners marked with the scan wand (a then b), for a new site; empty when none
     * @param stock   items the town has: ids and counts
     */
    public record Open(BlockPos table, String colony, boolean canEdit, String editor, List<String> types, List<Style> styles,
                       BlockPos min, int sx, int sy, int sz, Grid terrain, Design design, List<String> stockItems, int[] stockCounts,
                       String biome, List<BlockPos> corners, List<String> near, String message) implements CustomPacketPayload {
        public static final Type<Open> TYPE = id("table_open");
        public static final StreamCodec<FriendlyByteBuf, Open> CODEC = StreamCodec.of((b, p) -> {
            b.writeBlockPos(p.table);
            b.writeUtf(p.colony);
            b.writeBoolean(p.canEdit);
            b.writeUtf(p.editor);
            b.writeCollection(p.types, FriendlyByteBuf::writeUtf);
            b.writeCollection(p.styles, Style::write);
            b.writeBoolean(p.terrain != null);
            if (p.terrain != null) {
                b.writeBlockPos(p.min);
                b.writeVarInt(p.sx);
                b.writeVarInt(p.sy);
                b.writeVarInt(p.sz);
                p.terrain.write(b);
            }
            Design.write(b, p.design);
            b.writeCollection(p.stockItems, FriendlyByteBuf::writeUtf);
            b.writeVarIntArray(p.stockCounts);
            b.writeUtf(p.biome);
            b.writeCollection(p.corners, (buf, pos) -> buf.writeBlockPos(pos));
            b.writeCollection(p.near, FriendlyByteBuf::writeUtf);
            b.writeUtf(p.message);
        }, b -> {
            BlockPos table = b.readBlockPos();
            String colony = b.readUtf();
            boolean canEdit = b.readBoolean();
            String editor = b.readUtf();
            List<String> types = b.readList(FriendlyByteBuf::readUtf);
            List<Style> styles = b.readList(Style::read);
            BlockPos min = null;
            int sx = 0, sy = 0, sz = 0;
            Grid terrain = null;
            if (b.readBoolean()) {
                min = b.readBlockPos();
                sx = b.readVarInt();
                sy = b.readVarInt();
                sz = b.readVarInt();
                terrain = Grid.read(b);
            }
            return new Open(table, colony, canEdit, editor, types, styles, min, sx, sy, sz, terrain, Design.read(b),
                    b.readList(FriendlyByteBuf::readUtf), b.readVarIntArray(), b.readUtf(), b.readList(buf -> buf.readBlockPos()),
                    b.readList(FriendlyByteBuf::readUtf), b.readUtf());
        });

        public boolean hasSite() {
            return terrain != null;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the design changed on the server (generated, described, flattened, or an edit was turned down).
     * {@code keep}: it follows from the player's own edit (a road drawn, and paved), so their undo stays.
     */
    public record Update(BlockPos table, Design design, String message, boolean error, boolean keep) implements CustomPacketPayload {
        public Update(BlockPos table, Design design, String message, boolean error) {
            this(table, design, message, error, false);
        }

        public static final Type<Update> TYPE = id("table_update");
        public static final StreamCodec<FriendlyByteBuf, Update> CODEC = StreamCodec.of((b, p) -> {
            b.writeBlockPos(p.table);
            b.writeBoolean(p.design != null);
            if (p.design != null) {
                Design.write(b, p.design);
            }
            b.writeUtf(p.message);
            b.writeBoolean(p.error);
            b.writeBoolean(p.keep);
        }, b -> new Update(b.readBlockPos(), b.readBoolean() ? Design.read(b) : null, b.readUtf(), b.readBoolean(), b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: hand edits (site keys and block state ids; air clears) and markers ("" removes one). */
    public record Edit(BlockPos table, int[] keys, int[] states, int[] markerKeys, List<String> markerNames) implements CustomPacketPayload {
        /** the most blocks in one message (a bigger edit goes in several) */
        public static final int MAX = 2000;
        public static final Type<Edit> TYPE = id("table_edit");
        public static final StreamCodec<FriendlyByteBuf, Edit> CODEC = StreamCodec.of((b, p) -> {
            b.writeBlockPos(p.table);
            b.writeVarIntArray(p.keys);
            b.writeVarIntArray(p.states);
            b.writeVarIntArray(p.markerKeys);
            b.writeCollection(p.markerNames, FriendlyByteBuf::writeUtf);
        }, b -> new Edit(b.readBlockPos(), b.readVarIntArray(MAX), b.readVarIntArray(MAX), b.readVarIntArray(MAX),
                b.readList(FriendlyByteBuf::readUtf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: where the site is in the drafting world while the player designs there (its lowest corner and
     * size), and the building's name; a size of 0 when they're done.
     */
    public record Drafting(BlockPos min, int sx, int sy, int sz, String name) implements CustomPacketPayload {
        public static final Drafting NONE = new Drafting(BlockPos.ZERO, 0, 0, 0, "");
        public static final Type<Drafting> TYPE = id("table_drafting");
        public static final StreamCodec<FriendlyByteBuf, Drafting> CODEC = StreamCodec.of((b, p) -> {
            b.writeBlockPos(p.min);
            b.writeVarInt(p.sx);
            b.writeVarInt(p.sy);
            b.writeVarInt(p.sz);
            b.writeUtf(p.name);
        }, b -> new Drafting(b.readBlockPos(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: everything else the screen does. */
    public record Action(BlockPos table, Kind kind, String text) implements CustomPacketPayload {
        public enum Kind { SITE, GENERATE, DESCRIBE, FLATTEN, START_OVER, RENAME, TYPE, SAVE, PLAN, CLOSE, FREE_BUILD, ROUTE }

        public static final Type<Action> TYPE = id("table_action");
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.of((b, p) -> {
            b.writeBlockPos(p.table);
            b.writeEnum(p.kind);
            b.writeUtf(p.text, 4096);
        }, b -> new Action(b.readBlockPos(), b.readEnum(Kind.class), b.readUtf(4096)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
