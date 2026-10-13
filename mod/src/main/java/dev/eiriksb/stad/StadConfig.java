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

    public enum Leadership { LOYAL, NORMAL, HARSH }

    public static final ModConfigSpec.EnumValue<Leadership> LEADERSHIP = B
            .comment("How much citizens put up with. LOYAL: they grumble but always do as they're told. NORMAL: a bad leader",
                    "gets refused, struck against and left. HARSH: they're quicker to say no.")
            .defineEnum("leadership", Leadership.NORMAL);

    public static final ModConfigSpec.IntValue CHILD_DAYS = B
            .comment("Minecraft days a child takes to grow up.")
            .defineInRange("childDays", 4, 1, 100);

    static {
        B.pop();
        B.push("work");
    }

    public static final ModConfigSpec.IntValue BUILDER_TICKS_PER_BLOCK = B
            .comment("How many ticks a builder takes per block (20 ticks = 1 second).")
            .defineInRange("builderTicksPerBlock", 10, 1, 200);

    static {
        B.pop();
        B.push("packs");
    }

    public static final ModConfigSpec.BooleanValue EVERYONE_EDITS_PACKS = B
            .comment("Whether every player may scan buildings, save designs and edit My pack (the world's own building pack).",
                    "Off: only operators (and the host in singleplayer).")
            .define("everyoneEditsPacks", true);

    static {
        B.pop();
        B.push("raids");
    }

    public enum MobRaids { OFF, EASY, NORMAL, HARD }

    public static final ModConfigSpec.EnumValue<MobRaids> MOB_RAIDS = B
            .comment("Raiders attacking towns at night. OFF: never. The harder, the more of them and the more often.")
            .defineEnum("mobRaids", MobRaids.NORMAL);

    public static final ModConfigSpec.IntValue MOB_RAID_DAYS = B
            .comment("At least this many Minecraft days between mob raids on a town (fewer on HARD, more on EASY).")
            .defineInRange("mobRaidDays", 5, 1, 1000);

    public static final ModConfigSpec.IntValue MOB_RAID_MIN_PEOPLE = B
            .comment("Towns smaller than this are left alone by raiders.")
            .defineInRange("mobRaidMinPeople", 4, 1, 1000);

    public enum PvpRaids { OFF, OPT_IN, FORCED }

    public static final ModConfigSpec.EnumValue<PvpRaids> PVP_RAIDS = B
            .comment("Wars between players' towns. OFF: never. OPT_IN: only between towns that are both open to war (every town",
                    "starts peaceful). FORCED: every town can be attacked, whatever its stance (hardcore servers).")
            .defineEnum("pvpRaids", PvpRaids.OPT_IN);

    public static final ModConfigSpec.IntValue WAR_WARNING_MINUTES = B
            .comment("Real minutes between declaring war and the first raid, so both towns can get ready.")
            .defineInRange("warWarningMinutes", 24 * 60, 0, 60 * 24 * 30);

    public static final ModConfigSpec.IntValue STANCE_COOLDOWN_MINUTES = B
            .comment("Real minutes a town must stay open to war before it can go back to peaceful (no going open for one raid).")
            .defineInRange("stanceCooldownMinutes", 3 * 24 * 60, 0, 60 * 24 * 365);

    public static final ModConfigSpec.IntValue TRUCE_MINUTES = B
            .comment("Real minutes after a war ends before the same two towns can go to war again.")
            .defineInRange("truceMinutes", 3 * 24 * 60, 0, 60 * 24 * 365);

    public static final ModConfigSpec.IntValue RAID_MINUTES = B
            .comment("How long a raid on a player's town can last (real minutes) before the defenders have won.")
            .defineInRange("raidMinutes", 20, 1, 240);

    public static final ModConfigSpec.IntValue CAPTURE_SECONDS = B
            .comment("How long raiders must hold the ground by the town hall, with no defenders near, to take its banner.")
            .defineInRange("captureSeconds", 60, 5, 1200);

    public static final ModConfigSpec.IntValue LOOT_PERCENT = B
            .comment("The share of the losing town's warehouse stock the winners carry off (and at most lootMaxItems items).")
            .defineInRange("lootPercent", 20, 0, 100);

    public static final ModConfigSpec.IntValue LOOT_MAX_ITEMS = B
            .comment("The most items a raid can carry off.")
            .defineInRange("lootMaxItems", 512, 0, 100000);

    public static final ModConfigSpec.BooleanValue OFFLINE_RAIDS = B
            .comment("Whether a town can be raided while none of its officers are online.")
            .define("offlineRaids", false);

    public static final ModConfigSpec.BooleanValue POACHING_DEFAULT = B
            .comment("Whether new towns let rivals lure their unhappy citizens away (each town can change it).")
            .define("poachingDefault", true);

    public static final ModConfigSpec.BooleanValue RAIDER_FACTIONS = B
            .comment("Raiders that fit the town's land: Norse raiders in the cold north, marauders in deserts, badlands and savanna,",
                    "bandits or pillagers elsewhere. Off: always the game's own pillagers.")
            .define("raiderFactions", true);

    public static final ModConfigSpec.IntValue MAX_RAIDERS = B
            .comment("The most raiders in one mob raid, however big the town.")
            .defineInRange("maxRaiders", 16, 2, 64);

    static {
        B.pop();
        B.push("jobs");
    }

    public static final ModConfigSpec.IntValue HERD_SIZE = B
            .comment("The shepherd breeds each kind of farm animal up to this many, and butchers them once there are four more.")
            .defineInRange("herdSize", 6, 2, 64);

    public static final ModConfigSpec.IntValue MINE_DEPTH = B
            .comment("How many layers deep a miner digs a mine before it's done.")
            .defineInRange("mineDepth", 48, 4, 320);

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

    static {
        B.comment("How much of the language model's time citizens get, so big towns don't keep it busy (conversations always",
                "go first).").push("thinking");
    }

    public static final ModConfigSpec.IntValue THINK_RADIUS = B
            .comment("Citizens this close (in blocks) to a player plan their own day each morning with the language model.",
                    "The rest follow their routine, which costs nothing.")
            .defineInRange("thinkRadius", 48, 0, 512);

    public static final ModConfigSpec.IntValue PLANS_PER_DAY = B
            .comment("At most this many citizens of one town plan their day with the language model each Minecraft day (0: no limit).")
            .defineInRange("plansPerDay", 16, 0, 1000);

    public static final ModConfigSpec.IntValue BUSY_QUEUE = B
            .comment("Background thinking waits while this many requests are already waiting for the language model.")
            .defineInRange("busyQueue", 3, 0, 100);

    static {
        B.pop();
        B.comment("Keeping towns running.").push("performance");
    }

    public static final ModConfigSpec.EnumValue<KeepLoaded> KEEP_LOADED = B
            .comment("Keep a town's land loaded so its citizens keep working while players are away from it.",
                    "NEVER: towns only run while a player is near. WHILE_ONLINE: while one of the town's members is online.",
                    "ALWAYS: all the time (costs the most).")
            .defineEnum("keepLoaded", KeepLoaded.WHILE_ONLINE);

    public static final ModConfigSpec.IntValue LOADED_RADIUS = B
            .comment("How far from the town hall (in chunks of 16 blocks) a town's land is kept loaded.")
            .defineInRange("loadedRadius", 3, 1, 8);

    public enum KeepLoaded { NEVER, WHILE_ONLINE, ALWAYS }

    static {
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private StadConfig() {}
}
