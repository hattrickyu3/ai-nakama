package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 近くに探しものがないとき、まわりを歩いて探しに行く。
 * 出発した場所のまわりを、東西南北・斜め・さらに遠くの順に回る。着くたびに、呼んだ側が探し直す。
 * 読み込まれていない遠くの土地へは行かない。
 */
final class Explorer {
	enum State {
		/** 次の地点へ歩いている。 */
		WALKING,
		/** 地点に着いた（か、そこへは行けなかった）。探し直すとき。 */
		ARRIVED,
		/** 回る地点がもうない。 */
		EXHAUSTED
	}

	/** 出発した場所から見た、見に行く地点（x, z）。探す広さ（56マス）と合わせて、まわり約160マスを見られる。 */
	private static final int[][] LEGS = {
		{0, -48}, {48, 0}, {0, 48}, {-48, 0},
		{45, -45}, {45, 45}, {-45, 45}, {-45, -45},
		{0, -104}, {104, 0}, {0, 104}, {-104, 0}};
	private static final double ARRIVE_DISTANCE = 4;
	private static final int STUCK_TICKS = 160;

	private final Walker walker = new Walker(STUCK_TICKS);
	private BlockPos origin;
	private BlockPos waypoint;
	private int leg = -1;

	boolean started() {
		return origin != null;
	}

	State tick(NakamaEntity nakama, ServerLevel level) {
		if (origin == null) {
			origin = nakama.blockPosition();
		}
		if (waypoint == null) {
			waypoint = next(nakama, level);
			if (waypoint == null) {
				return State.EXHAUSTED;
			}
		}
		double dx = waypoint.getX() + 0.5 - nakama.getX();
		double dz = waypoint.getZ() + 0.5 - nakama.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist <= ARRIVE_DISTANCE || !walker.step(nakama, waypoint, 2, 20, dist)) {
			waypoint = null;
			return State.ARRIVED;
		}
		return State.WALKING;
	}

	private BlockPos next(NakamaEntity nakama, ServerLevel level) {
		while (++leg < LEGS.length) {
			int x = origin.getX() + LEGS[leg][0];
			int z = origin.getZ() + LEGS[leg][1];
			if (level.isLoaded(new BlockPos(x, origin.getY(), z))) {
				return new BlockPos(x, Terrain.surfaceY(level, x, z, nakama.blockPosition().getY()), z);
			}
		}
		return null;
	}
}
