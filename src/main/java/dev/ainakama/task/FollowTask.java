package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Places;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/** プレイヤーについていく。stop か新しい指示が来るまで続く。 */
final class FollowTask implements Task {
	private static final double START_DISTANCE = 4.0;
	private static final double TELEPORT_DISTANCE = 24.0;
	private static final int REPATH_TICKS = 10;

	private int cooldown;

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		Player player = Places.nearestPlayer(nakama, level).orElse(null);
		if (player == null) {
			return new Result.Failed("ついていくプレイヤーが見当たらない");
		}
		nakama.getLookControl().setLookAt(player, 10.0F, nakama.getMaxHeadXRot());
		double dist = nakama.distanceTo(player);
		if (dist > TELEPORT_DISTANCE && player.onGround()) {
			// 離れすぎたら、マイクラのペットと同じようにそばへ移る
			nakama.snapTo(player.getX(), player.getY(), player.getZ(), nakama.getYRot(), nakama.getXRot());
			nakama.getNavigation().stop();
			return RUNNING;
		}
		if (dist <= START_DISTANCE) {
			nakama.getNavigation().stop();
			return RUNNING;
		}
		if (--cooldown <= 0) {
			cooldown = REPATH_TICKS;
			nakama.getNavigation().moveTo(player, NakamaEntity.WALK_SPEED * 1.3);
		}
		return RUNNING;
	}

	@Override
	public String describe() {
		return "プレイヤーについていく";
	}
}
