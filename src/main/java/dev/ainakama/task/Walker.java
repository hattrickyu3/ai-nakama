package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import net.minecraft.core.BlockPos;

/**
 * 目的地へ歩かせ、近づけているかを見張る。
 * stuckTicks のあいだ距離が縮まらなければ「たどり着けない」と判断する。
 */
final class Walker {
	private static final double PROGRESS_EPSILON = 0.5;

	private final int stuckTicks;
	private double bestDistance = Double.MAX_VALUE;
	private int sinceProgress;
	private int repathCooldown;
	private BlockPos lastTarget;

	Walker(int stuckTicks) {
		this.stuckTicks = stuckTicks;
	}

	/** @return まだ見込みがあれば true。たどり着けないと判断したら false */
	boolean step(NakamaEntity nakama, BlockPos target, int reachRange, int repathTicks, double distance) {
		if (!target.equals(lastTarget)) {
			lastTarget = target;
			bestDistance = Double.MAX_VALUE;
			sinceProgress = 0;
			repathCooldown = 0;
		}
		if (distance < bestDistance - PROGRESS_EPSILON) {
			bestDistance = distance;
			sinceProgress = 0;
		} else if (++sinceProgress > stuckTicks) {
			return false;
		}
		if (--repathCooldown <= 0) {
			repathCooldown = repathTicks;
			nakama.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, reachRange, NakamaEntity.WALK_SPEED);
		}
		return true;
	}
}
