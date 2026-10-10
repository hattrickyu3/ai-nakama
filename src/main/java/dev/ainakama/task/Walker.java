package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Terrain;
import net.minecraft.core.BlockPos;

/**
 * 目的地へ歩かせ、近づけているかを見張る。
 * stuckTicks のあいだ距離が縮まらなければ「たどり着けない」と判断する。
 */
final class Walker {
	private static final double PROGRESS_EPSILON = 0.5;
	/** これより遠い目的地は、一度に道を探せないので、途中の地点を目指して少しずつ進む。 */
	private static final double LONG_DISTANCE = 40;
	private static final double WAYPOINT_DISTANCE = 28;
	/** これより近いのにたどり着けないときは、移らずにあきらめる（すぐそばの届かない1マスのために飛び回らない）。 */
	private static final double WARP_MIN_DISTANCE = 8;

	private final int stuckTicks;
	private double bestDistance = Double.MAX_VALUE;
	private int sinceProgress;
	private int repathCooldown;
	private BlockPos lastTarget;
	private boolean warped;

	Walker(int stuckTicks) {
		this.stuckTicks = stuckTicks;
	}

	/** 目的地の方向へ WAYPOINT_DISTANCE だけ進んだ、地面の上の地点。 */
	private static BlockPos waypoint(NakamaEntity nakama, BlockPos target, double distance) {
		double f = WAYPOINT_DISTANCE / distance;
		int x = (int) Math.floor(nakama.getX() + (target.getX() + 0.5 - nakama.getX()) * f);
		int z = (int) Math.floor(nakama.getZ() + (target.getZ() + 0.5 - nakama.getZ()) * f);
		return new BlockPos(x, Terrain.surfaceY(nakama.level(), x, z, nakama.blockPosition().getY()), z);
	}

	private static boolean warp(NakamaEntity nakama, BlockPos target) {
		if (!nakama.level().isLoaded(target)) {
			return false;
		}
		var spot = Terrain.standableNear(nakama.level(), target, true);
		if (spot.isEmpty()) {
			return false;
		}
		BlockPos p = spot.get();
		nakama.getNavigation().stop();
		nakama.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, nakama.getYRot(), nakama.getXRot());
		return true;
	}

	/** @return まだ見込みがあれば true。たどり着けないと判断したら false */
	boolean step(NakamaEntity nakama, BlockPos target, int reachRange, int repathTicks, double distance) {
		if (!target.equals(lastTarget)) {
			lastTarget = target;
			bestDistance = Double.MAX_VALUE;
			sinceProgress = 0;
			repathCooldown = 0;
			warped = false;
		}
		if (distance < bestDistance - PROGRESS_EPSILON) {
			bestDistance = distance;
			sinceProgress = 0;
		} else if (++sinceProgress > stuckTicks) {
			// 歩いては行けない（崖の上・穴の底・川の向こうなど）。離れているなら、ペットのように目的地のそばへ移る
			if (warped || distance < WARP_MIN_DISTANCE || !warp(nakama, target)) {
				return false;
			}
			warped = true;
			sinceProgress = 0;
			bestDistance = Double.MAX_VALUE;
		}
		if (--repathCooldown <= 0) {
			repathCooldown = repathTicks;
			BlockPos goal = distance > LONG_DISTANCE ? waypoint(nakama, target, distance) : target;
			nakama.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, reachRange, NakamaEntity.WALK_SPEED);
		}
		return true;
	}
}
