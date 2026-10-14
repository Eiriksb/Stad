package dev.eiriksb.stad;

import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code config/stad-client.toml}: what each player sees. */
public final class StadClientConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SHOW_GUIDE = B
            .comment("Whether the getting-started guide (your town's next step) shows in the corner of the screen. Its key (U by default) turns it on and off.")
            .define("showGuide", true);

    public static final ModConfigSpec SPEC = B.build();

    private StadClientConfig() {}
}
