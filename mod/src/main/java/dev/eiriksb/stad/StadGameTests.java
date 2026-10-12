package dev.eiriksb.stad;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

    private static CompoundTag looks(boolean female, int skin) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Female", female);
        tag.putInt("Skin", skin);
        return tag;
    }
}
