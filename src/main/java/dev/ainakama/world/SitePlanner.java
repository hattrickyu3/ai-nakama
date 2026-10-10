package dev.ainakama.world;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 建物を建てる場所を選ぶ。頼まれた場所のまわりで、いちばん平らで、プレイヤーの建物や人を巻き込まないところを探す。
 */
public final class SitePlanner {
	/** 頼まれた場所から、ずらして探す範囲（マス）。 */
	private static final int SEARCH = 4;
	private static final double COST_PLAYER_BLOCK = 1000;
	private static final double COST_MAYBE_WALL = 300;
	private static final double COST_OCCUPIED = 10000;
	private static final double COST_PER_SHIFT = 0.5;

	private SitePlanner() {
	}

	/**
	 * @param around 頼まれた場所（建物の中心にしたいところ）
	 * @param home   仲間の拠点（建物で埋めないように避ける）。なければ null
	 * @param sx     建物の幅（x）
	 * @param sy     建物の高さ
	 * @param sz     建物の奥行き（z）
	 * @return 建物の基準点（床の高さの、x と z がいちばん小さい角）
	 */
	public static BlockPos choose(ServerLevel level, BlockPos around, BlockPos home, int sx, int sy, int sz) {
		Map<Long, Integer> heights = new HashMap<>();
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		int baseX = around.getX() - sx / 2;
		int baseZ = around.getZ() - sz / 2;
		for (int dx = -SEARCH; dx <= SEARCH; dx++) {
			for (int dz = -SEARCH; dz <= SEARCH; dz++) {
				int x0 = baseX + dx;
				int z0 = baseZ + dz;
				int[] hs = new int[sx * sz];
				for (int x = 0; x < sx; x++) {
					for (int z = 0; z < sz; z++) {
						int wx = x0 + x;
						int wz = z0 + z;
						hs[x * sz + z] = heights.computeIfAbsent(BlockPos.asLong(wx, 0, wz),
							k -> Terrain.surfaceY(level, wx, wz, around.getY()));
					}
				}
				int[] sorted = hs.clone();
				Arrays.sort(sorted);
				int y0 = sorted[sorted.length / 2];
				double cost = (Math.abs(dx) + Math.abs(dz)) * COST_PER_SHIFT;
				for (int h : hs) {
					cost += Math.abs(h - y0);
				}
				if (cost >= bestCost) {
					continue;
				}
				BlockPos origin = new BlockPos(x0, y0, z0);
				cost += obstacles(level, origin, home, sx, sy, sz);
				if (cost < bestCost) {
					bestCost = cost;
					best = origin;
				}
			}
		}
		return best;
	}

	/** プレイヤーが置いたらしいブロック、人、拠点が建物の範囲に入っていると、大きな減点にする。 */
	private static double obstacles(ServerLevel level, BlockPos origin, BlockPos home, int sx, int sy, int sz) {
		double cost = 0;
		BlockPos max = origin.offset(sx - 1, sy - 1, sz - 1);
		for (BlockPos p : BlockPos.betweenClosed(origin, max)) {
			BlockState state = level.getBlockState(p);
			if (Terrain.isOpen(state)) {
				continue;
			}
			if (!Terrain.isNaturalClearable(level, p, state)) {
				cost += COST_PLAYER_BLOCK;
			} else if (Terrain.isHardGround(state) && p.getY() - origin.getY() >= Terrain.WALL_HEIGHT) {
				// 床より2マス以上高い石は、石の家の壁かもしれないので、できるだけ避ける
				cost += COST_MAYBE_WALL;
			}
		}
		AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
		if (!level.getEntitiesOfClass(Player.class, box).isEmpty()) {
			cost += COST_OCCUPIED;
		}
		if (home != null && box.contains(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5)) {
			cost += COST_OCCUPIED;
		}
		return cost;
	}
}
