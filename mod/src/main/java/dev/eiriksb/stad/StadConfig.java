package dev.eiriksb.stad;

import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code config/stad-common.toml}. */
public final class StadConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CITIZENS_TALK = B
            .comment("Whether citizens talk (through They Will Talk). Off: they still live and work, silently.")
            .define("citizensTalk", true);

    static {
        B.push("colonies");
    }

    public static final ModConfigSpec.IntValue CLAIM_RADIUS = B
            .comment("How far (in blocks) a town hall's land reaches.")
            .defineInRange("claimRadius", 48, 16, 256);

    public static final ModConfigSpec.IntValue MAX_COLONIES_PER_PLAYER = B
            .comment("How many towns one player may found (0: no limit).")
            .defineInRange("maxColoniesPerPlayer", 1, 0, 100);

    public static final ModConfigSpec.IntValue ARRIVAL_MINUTES = B
            .comment("Minutes between newcomers moving into a town that has a free bed.")
            .defineInRange("arrivalMinutes", 5, 1, 1440);

    static {
        B.pop();
        B.push("work");
    }

    public static final ModConfigSpec.IntValue BUILDER_TICKS_PER_BLOCK = B
            .comment("How many ticks a builder takes per block (20 ticks = 1 second).")
            .defineInRange("builderTicksPerBlock", 10, 1, 200);

    static {
        B.pop();
        B.push("needs");
    }

    public static final ModConfigSpec.DoubleValue HUNGER_DAYS = B
            .comment("Minecraft days a citizen can go on a full stomach before they're starving.")
            .defineInRange("hungerDays", 1.5, 0.1, 100.0);

    static {
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private StadConfig() {}
}
