package dev.eiriksb.stad.net;

import dev.eiriksb.stad.Stad;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Server to client: open the chat screen for a citizen (right-clicking them).
 *
 * @param about   short lines about them: job and town, age, home, what they think of the player, today's plan
 * @param actions the buttons the player may use
 * @param history the last lines between them, oldest first
 * @param prompt  a suggestion for the text box ("Could you design a ..." for the builder), or ""
 */
public record CitizenScreenPayload(UUID citizen, String name, List<String> about, int happiness, int hunger, int rest,
                                   List<Action> actions, List<Line> history, boolean canTalk, String prompt) implements CustomPacketPayload {
    /** A button: runs {@code command} (a /stad command), or puts {@code text} in the text box when {@code prefill}. */
    public record Action(String label, String command, boolean prefill, int color) {}

    public record Line(boolean fromPlayer, String speaker, String text) {}

    public static final Type<CitizenScreenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Stad.MOD_ID, "citizen_screen"));

    static void writeLine(FriendlyByteBuf b, Line l) {
        b.writeBoolean(l.fromPlayer());
        b.writeUtf(l.speaker());
        b.writeUtf(l.text(), 4096);
    }

    static Line readLine(FriendlyByteBuf b) {
        return new Line(b.readBoolean(), b.readUtf(), b.readUtf(4096));
    }

    public static final StreamCodec<FriendlyByteBuf, CitizenScreenPayload> CODEC = StreamCodec.of((buf, p) -> {
        UUIDUtil.STREAM_CODEC.encode(buf, p.citizen);
        buf.writeUtf(p.name);
        buf.writeCollection(p.about, FriendlyByteBuf::writeUtf);
        buf.writeVarInt(p.happiness);
        buf.writeVarInt(p.hunger);
        buf.writeVarInt(p.rest);
        buf.writeCollection(p.actions, (b, a) -> {
            b.writeUtf(a.label());
            b.writeUtf(a.command());
            b.writeBoolean(a.prefill());
            b.writeInt(a.color());
        });
        buf.writeCollection(p.history, CitizenScreenPayload::writeLine);
        buf.writeBoolean(p.canTalk);
        buf.writeUtf(p.prompt);
    }, buf -> new CitizenScreenPayload(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt(), buf.readList(b -> new Action(b.readUtf(), b.readUtf(), b.readBoolean(), b.readInt())),
            buf.readList(CitizenScreenPayload::readLine), buf.readBoolean(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
