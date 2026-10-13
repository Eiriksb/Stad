package dev.eiriksb.stad;

import dev.eiriksb.stad.agent.Social;
import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.citizen.CitizenScreens;
import dev.eiriksb.stad.citizen.CitizenVoice;
import dev.eiriksb.stad.citizen.Genes;
import dev.eiriksb.stad.colony.CitizenRecord;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.ColonyManager;
import dev.eiriksb.stad.colony.Jobs;
import dev.eiriksb.stad.net.CitizenScreenPayload;
import dev.eiriksb.stad.twt.StadAdapter;
import dev.eiriksb.theywilltalk.api.ChatLine;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/** In-game tests for voices and the chat screen (M9). */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VoiceGameTests {
    private VoiceGameTests() {}

    private static final int G = 2;

    @GameTest(template = "empty", batch = "voice")
    public static void genesArePassedOn(GameTestHelper helper) {
        RandomSource r = RandomSource.create(42);
        for (int i = 0; i < 50; i++) {
            Genes g = Genes.random(r);
            helper.assertTrue(g.pitch() >= 0.85 && g.pitch() <= 1.15 && g.pace() >= 0.88 && g.pace() <= 1.12, "in range: " + g);
            Genes a = Genes.random(r), b = Genes.random(r), child = Genes.child(a, b, r);
            helper.assertTrue(List.of(a.timbre(), b.timbre()).contains(child.timbre()) && List.of(a.accent(), b.accent()).contains(child.accent()),
                    "a parent's timbre and accent: " + child);
            helper.assertTrue(Math.abs(child.pitch() - (a.pitch() + b.pitch()) / 2) < 0.16, "between the parents: " + child);
        }
        Genes g = new Genes(0.9, 1.1, 3, 5);
        helper.assertTrue(g.equals(Genes.load(g.save())), "saved");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "voice")
    public static void voicesComeFromGenesAndAgeAndStayPut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CitizenEntity c = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 1, G, 1);
        c.setGenes(new Genes(0.88, 0.92, 4, 2));
        c.setAgeYears(34);
        String design = CitizenVoice.design(c);
        helper.assertTrue(design.contains(Genes.TIMBRES.get(4)) && design.contains(Genes.ACCENTS.get(2)) && design.contains("speaks slowly")
                && design.contains("thirties"), "from genes and age: " + design);
        helper.assertTrue(design.contains(c.isFemale() ? "low" : "deep"), "low-pitched: " + design);
        helper.assertTrue(design.equals(CitizenVoice.design(c)), "the same every time");
        c.setAgeYears(38);
        helper.assertTrue(design.equals(CitizenVoice.design(c)), "the same within a decade (a new description is a new voice)");
        c.setAgeYears(67);
        helper.assertTrue(CitizenVoice.design(c).contains("elderly") && CitizenVoice.pitch(c) < c.genes().pitch(), "old: " + CitizenVoice.design(c));
        // kept with the citizen
        CompoundTag tag = new CompoundTag();
        c.saveWithoutId(tag);
        CitizenEntity copy = StadRegistries.CITIZEN.get().create(level);
        copy.load(tag);
        helper.assertTrue(copy.genes().equals(c.genes()) && copy.ageYears() == 67, "saved with them");
        // a child's voice changes as they grow, and only then
        CitizenEntity kid = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 2, G, 2);
        kid.setChild(true);
        String little = CitizenVoice.design(kid);
        helper.assertTrue(CitizenVoice.age(kid) < 10 && little.contains("little") && CitizenVoice.pitch(kid) > kid.genes().pitch(), "a child: " + little);
        kid.setBorn(level.getGameTime() - (long) (StadConfig.CHILD_DAYS.get() * 24000L * 0.85));
        helper.assertTrue(CitizenVoice.ageGroup(CitizenVoice.age(kid)).equals("teen") && CitizenVoice.design(kid).contains("teenage"),
                "a teenager: " + CitizenVoice.design(kid));
        kid.setChild(false);
        helper.assertTrue(kid.ageYears() == 18 && CitizenVoice.design(kid).contains("young"), "grown up: " + CitizenVoice.design(kid));
        // They Will Talk gets it with the rest of what it knows about them
        VillagerFacts f = new VillagerFacts();
        new StadAdapter().collect(c, null, f);
        helper.assertTrue(CitizenVoice.design(c).equals(f.voiceDesign) && f.voicePitch != null && f.voiceSpeed != null && "adult".equals(f.ageGroup),
                "facts: " + f.voiceDesign);
        helper.succeed();
    }

    @GameTest(template = "lot", batch = "voice_family", timeoutTicks = 100)
    public static void childrenSoundLikeTheirParents(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        helper.setBlock(new BlockPos(2, G, 2), StadRegistries.TOWN_HALL.get());
        Colony colony = ColonyManager.get(level.getServer()).found(level, helper.absolutePos(new BlockPos(2, G, 2)), owner.getUUID(),
                owner.getGameProfile().getName(), 12).colony();
        try {
            CitizenEntity mum = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 5, G, 5);
            CitizenEntity dad = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 6, G, 5);
            mum.setGenes(new Genes(1.1, 1.0, 1, 1));
            dad.setGenes(new Genes(0.9, 1.0, 7, 6));
            ColonyManager.get(level.getServer()).join(colony, mum);
            ColonyManager.get(level.getServer()).join(colony, dad);
            CitizenRecord a = colony.citizen(mum.getUUID()).orElseThrow(), b = colony.citizen(dad.getUUID()).orElseThrow();
            helper.assertTrue(dad.genes().equals(b.genes), "kept in the town's records too");
            CitizenEntity baby = Social.haveChild(level, colony, a, b);
            helper.assertTrue(baby != null && Set.of(1, 7).contains(baby.genes().timbre()) && Set.of(1, 6).contains(baby.genes().accent()),
                    "the family voice: " + (baby == null ? null : baby.genes()));
            // the chat screen, as the town's founder sees it
            colony.citizen(dad.getUUID()).orElseThrow().job = Jobs.BUILDER;
            CitizenScreenPayload p = CitizenScreens.payload(owner, dad, 30, List.of(new ChatLine(1, true, "Tester", "Hello!", null),
                    new ChatLine(2, false, dad.getName().getString(), "Good day!", "happy")));
            helper.assertTrue(p.actions().getFirst().prefill() && p.actions().stream().anyMatch(x -> x.command().contains("citizen job")),
                    "plan a building, jobs: " + p.actions());
            helper.assertTrue(p.about().stream().anyMatch(s -> s.startsWith("Builder of " + colony.name())) && p.about().contains("They like you"),
                    "about: " + p.about());
            helper.assertTrue(p.history().size() == 2 && p.history().getFirst().fromPlayer() && !p.prompt().isEmpty(), "history " + p.history());
            CitizenScreenPayload kid = CitizenScreens.payload(owner, baby, 0, List.of());
            helper.assertTrue(kid.actions().stream().noneMatch(x -> x.command().contains("citizen job")), "children don't get jobs");
        } finally {
            ColonyManager.get(level.getServer()).dissolve(level.getServer(), colony);
            level.getServer().getPlayerList().remove(owner);
        }
        helper.succeed();
    }
}
