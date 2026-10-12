package dev.eiriksb.stad;

import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
}
