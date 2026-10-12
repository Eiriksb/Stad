package dev.eiriksb.stad;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.theywilltalk.api.Memory;
import dev.eiriksb.theywilltalk.api.MemoryApi;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Relation;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** In-game tests: {@code ./gradlew :mod:runGameTestServer}. */
@GameTestHolder(Stad.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StadGameTests {
    private StadGameTests() {}

    @GameTest(template = "empty")
    public static void theyWillTalkIsRunning(GameTestHelper helper) {
        helper.assertTrue(TheyWillTalkApi.get().running(), "They Will Talk isn't running in this world");
        helper.assertTrue(VillagerKind.byId("stad") == Stad.CITIZEN, "the citizen kind isn't registered with They Will Talk");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void citizensCanTalk(GameTestHelper helper) {
        CitizenEntity citizen = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 0, 1, 0);
        helper.assertTrue(TheyWillTalkApi.get().canTalk(citizen), "They Will Talk can't talk to a citizen");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void citizensKeepTheirLooks(GameTestHelper helper) {
        CitizenEntity citizen = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 0, 1, 0);
        citizen.readAdditionalSaveData(looks(true, 5));
        CompoundTag saved = new CompoundTag();
        citizen.addAdditionalSaveData(saved);
        CitizenEntity copy = helper.spawnWithNoFreeWill(StadRegistries.CITIZEN.get(), 0, 1, 0);
        copy.readAdditionalSaveData(saved);
        helper.assertTrue(copy.isFemale() && copy.skin() == 5, "looks weren't saved: female=" + copy.isFemale() + " skin=" + copy.skin());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void citizensRememberRecallAndGossip(GameTestHelper helper) {
        MemoryApi memory = TheyWillTalkApi.get().memory();
        UUID astrid = UUID.randomUUID();
        UUID bjorn = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        Subject aboutPlayer = Subject.player(player);
        memory.remember(NewMemory.of(astrid, aboutPlayer, "Eirik promised to fix the roof of my bakery").withKind("promise").withImportance(7)).join();
        memory.remember(NewMemory.of(astrid, aboutPlayer, "Eirik talked about the weather").withImportance(2)).join();
        memory.remember(NewMemory.of(astrid, Subject.NONE, "The harvest was poor this year").withImportance(5)).join();

        List<Memory> recalled = memory.recall(astrid, "Did anyone ever look at the bakery roof?", 2).join();
        helper.assertTrue(!recalled.isEmpty() && recalled.getFirst().text().contains("roof"), "recall missed the roof: " + recalled);
        helper.assertTrue(memory.about(astrid, aboutPlayer, 10).join().size() == 2, "memories about the player");

        int told = memory.tell(astrid, bjorn, aboutPlayer, 5, 3).join();
        helper.assertTrue(told == 1, "passed on " + told + " memories, expected only the promise");
        Memory heard = memory.about(bjorn, aboutPlayer, 5).join().getFirst();
        helper.assertTrue(heard.hearsay() && heard.importance() == 6 && heard.text().contains("roof"), "hearsay copy: " + heard);
        helper.assertTrue(memory.tell(astrid, bjorn, aboutPlayer, 5, 3).join() == 0, "told the same thing twice");

        memory.adjustRelation(astrid, bjorn, 20, 1);
        memory.setRelationKind(astrid, bjorn, "friend");
        Relation rel = memory.relation(astrid, bjorn).join();
        helper.assertTrue(rel.affinity() == 20 && rel.familiarity() == 1 && "friend".equals(rel.kind()), "relation: " + rel);
        helper.assertTrue(memory.relation(bjorn, astrid).join().familiarity() == 0, "relations go one way");
        helper.succeed();
    }

    private static CompoundTag looks(boolean female, int skin) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Female", female);
        tag.putInt("Skin", skin);
        return tag;
    }
}
