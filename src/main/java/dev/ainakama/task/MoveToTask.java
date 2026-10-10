package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** 指定した場所へ歩いていく。近づけなくなったら失敗。 */
final class MoveToTask implements Task {
	private static final double ARRIVE_DISTANCE = 2.5;
	private static final int REPATH_TICKS = 20;
	private static final int STUCK_TICKS = 200;

	private final BlockPos target;
	private final String label;
	private final Walker walker = new Walker(STUCK_TICKS);

	MoveToTask(BlockPos target, String label) {
		this.target = target;
		this.label = label;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		double dist = Math.sqrt(nakama.distanceToSqr(Vec3.atCenterOf(target)));
		if (dist <= ARRIVE_DISTANCE) {
			nakama.getNavigation().stop();
			return DONE;
		}
		if (walker.step(nakama, target, 1, REPATH_TICKS, dist)) {
			return RUNNING;
		}
		// 歩いて行けないとき（川の向こう・崖の上など）は、ペットがそばに移るのと同じように移動する
		var spot = level.isLoaded(target) ? Terrain.standableNear(level, target) : java.util.Optional.<BlockPos>empty();
		if (spot.isEmpty()) {
			return new Result.Failed(label + " にたどり着けない（遠すぎるか、立てる場所がない）");
		}
		BlockPos p = spot.get();
		nakama.getNavigation().stop();
		nakama.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, nakama.getYRot(), nakama.getXRot());
		return DONE;
	}

	@Override
	public String describe() {
		return label + " へ移動";
	}
}
