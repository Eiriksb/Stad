package dev.eiriksb.stad.citizen.goal;

import dev.eiriksb.stad.citizen.CitizenEntity;
import dev.eiriksb.stad.colony.Colony;
import dev.eiriksb.stad.colony.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BellBlock;

/**
 * The merchant (PLAN.md §6.9) keeps the market by day: stands at it, watches for customers, and rings its bell now and
 * then when someone's about. The trading itself is {@link dev.eiriksb.stad.colony.Trading}.
 */
public class MerchantGoal extends WorkGoal {
    private int bellIn = 200;

    public MerchantGoal(CitizenEntity citizen) {
        super(citizen, Jobs.MERCHANT);
    }

    @Override
    protected boolean hasWork(ServerLevel level, Colony colony) {
        return true; // the market's open all day
    }

    @Override
    protected String doingNow() {
        return "Minding the market (sneak and right-click to trade)";
    }

    @Override
    protected void work(ServerLevel level, Colony colony) {
        BlockPos stand = workplace(colony).map(dev.eiriksb.stad.colony.Workplaces::spot).orElse(colony.center.south(3));
        if (!approach.tick(citizen, stand, 2.0, 0.6)) {
            return;
        }
        Player customer = level.getNearestPlayer(citizen, 10);
        if (customer != null) {
            citizen.getLookControl().setLookAt(customer);
        }
        if (--bellIn <= 0) {
            bellIn = 1200 + citizen.getRandom().nextInt(1200);
            if (customer != null) {
                ring(level, colony);
            }
        }
    }

    private void ring(ServerLevel level, Colony colony) {
        workplace(colony).flatMap(dev.eiriksb.stad.colony.Building::placement).flatMap(p -> p.steps().stream()
                .filter(s -> s.state().getBlock() instanceof BellBlock).map(s -> s.pos()).findFirst()).ifPresent(pos -> {
            if (level.getBlockState(pos).getBlock() instanceof BellBlock bell) {
                bell.attemptToRing(citizen, level, pos, Direction.NORTH);
            }
        });
    }
}
