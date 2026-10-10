package dev.ainakama.world;

import dev.ainakama.core.Blueprint;
import dev.ainakama.core.Items;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * プレイヤーが建てて見せた建物を、設計図として覚える。
 * 「見てて」と言われたときにまわりのブロックを写し取っておき、「これが〇〇だよ」と言われたときにもう一度見て、
 * 増えたブロックを建物として取り出す（誰が置いたかは見ていないので、その間に仲間が置いたものも含まれる）。
 */
public final class BuildRecorder {
	private static final int RADIUS = 14;
	private static final int DOWN = 4;
	private static final int UP = 24;
	public static final int MAX_PARTS = 1500;

	private record Snapshot(ServerLevel level, BlockPos center, Map<Long, BlockState> before) {
	}

	private static final Map<UUID, Snapshot> WATCHING = new HashMap<>();

	private BuildRecorder() {
	}

	public static void start(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos center = player.blockPosition();
		Map<Long, BlockState> before = new HashMap<>();
		for (BlockPos p : region(center)) {
			BlockState s = level.getBlockState(p);
			if (!s.isAir()) {
				before.put(p.asLong(), s);
			}
		}
		WATCHING.put(player.getUUID(), new Snapshot(level, center, before));
	}

	public static boolean isWatching(ServerPlayer player) {
		return WATCHING.containsKey(player.getUUID());
	}

	public static void cancel(ServerPlayer player) {
		WATCHING.remove(player.getUUID());
	}

	/**
	 * 見せてもらった建物を設計図にする。
	 *
	 * @return 増えたブロックがなければ空
	 */
	public static Optional<Blueprint> finish(ServerPlayer player, String name) {
		Snapshot snap = WATCHING.remove(player.getUUID());
		if (snap == null || snap.level() != player.level()) {
			return Optional.empty();
		}
		record Added(BlockPos pos, BlockState state) {
		}
		List<Added> added = new ArrayList<>();
		for (BlockPos p : region(snap.center())) {
			BlockState now = snap.level().getBlockState(p);
			if (now.isAir() || now.equals(snap.before().get(p.asLong())) || !isBuilt(now)) {
				continue;
			}
			added.add(new Added(p.immutable(), now));
		}
		if (added.isEmpty() || added.size() > MAX_PARTS) {
			return Optional.empty();
		}
		int minX = added.stream().mapToInt(a -> a.pos().getX()).min().orElseThrow();
		int minY = added.stream().mapToInt(a -> a.pos().getY()).min().orElseThrow();
		int minZ = added.stream().mapToInt(a -> a.pos().getZ()).min().orElseThrow();
		List<Blueprint.Part> parts = new ArrayList<>();
		for (Added a : added) {
			String item = Inv.idOf(a.state().getBlock().asItem());
			if (item.equals("air")) {
				continue;
			}
			boolean free = isSecondHalf(a.state());
			boolean planks = Items.isPlanks(item);
			parts.add(new Blueprint.Part(
				a.pos().getX() - minX, a.pos().getY() - minY, a.pos().getZ() - minZ,
				planks ? Items.ANY_PLANKS : item,
				planks ? null : Placer.write(a.state()),
				free));
		}
		// 下の段から置く。同じ段では、扉の上半分のような「おまけ」をあとに回す
		parts.sort(Comparator.comparingInt(Blueprint.Part::dy)
			.thenComparing(Blueprint.Part::free)
			.thenComparingInt(Blueprint.Part::dx)
			.thenComparingInt(Blueprint.Part::dz));
		return parts.isEmpty() ? Optional.empty() : Optional.of(new Blueprint(name, name, parts, true));
	}

	/** 勝手に変わるもの（水の流れ、草の広がり、葉っぱ、火など）は建物に数えない。 */
	private static boolean isBuilt(BlockState state) {
		if (!state.getFluidState().isEmpty() && state.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
			return false;
		}
		String id = Terrain.idOf(state);
		return !state.is(BlockTags.LEAVES) && !id.equals("grass_block") && !id.equals("dirt") && !id.equals("fire")
			&& !id.equals("snow") && !id.equals("short_grass") && !id.equals("tall_grass");
	}

	/** 扉の上半分・ベッドの頭側のように、相方を置けば材料がいらない部分か。 */
	private static boolean isSecondHalf(BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
		}
		if (state.hasProperty(BlockStateProperties.BED_PART)) {
			return state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
		}
		return false;
	}

	private static Iterable<BlockPos> region(BlockPos center) {
		return BlockPos.betweenClosed(center.offset(-RADIUS, -DOWN, -RADIUS), center.offset(RADIUS, UP, RADIUS));
	}
}
