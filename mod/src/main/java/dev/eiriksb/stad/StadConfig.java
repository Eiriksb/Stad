package dev.eiriksb.stad;

import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code config/stad-common.toml}. */
public final class StadConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CITIZENS_TALK = B
            .comment("Whether citizens talk (through They Will Talk). Off: they still live and work, silently.")
            .define("citizensTalk", true);

    public static final ModConfigSpec SPEC = B.build();

    private StadConfig() {}
}
