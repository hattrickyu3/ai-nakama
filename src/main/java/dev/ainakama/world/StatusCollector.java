package dev.ainakama.world;

import dev.ainakama.core.Items;
import dev.ainakama.core.Status;
import dev.ainakama.entity.NakamaEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/** ゲームの様子を集めて {@link Status} にする。AIを呼ぶときだけ使う（毎tickは呼ばない）。 */
public final class StatusCollector {
	private static final int SCAN_RADIUS = 16;
	private static final int SCAN_HEIGHT = 6;
	private static final int CREATURE_RADIUS = 16;

	/** 近くの資源として数えるブロック（ID → 表示名）。原木はまとめて数える。 */
	private static final Map<String, String> INTERESTING = Map.of(
		"stone", "stone(石)",
		"coal_ore", "coal_ore(石炭鉱石)",
		"iron_ore", "iron_ore(鉄鉱石)",
		"sand", "sand(砂)",
		"water", "water(水)",
		"chest", "chest(チェスト)",
		"crafting_table", "crafting_table(作業台)",
		"furnace", "furnace(かまど)",
		"wheat", "wheat(小麦畑)");

	private record Found(String label, int count, int dx, int dy, int dz, double dist) {
	}

	private StatusCollector() {
	}

	public static Status collect(NakamaEntity nakama, ServerLevel level, String currentJob) {
		String weather = level.isThundering() ? "雷雨" : level.isRaining() ? "雨" : "晴れ";
		BlockPos pos = nakama.blockPosition();
		String position;
		String here = "座標 " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
		if (nakama.home() == null) {
			position = here + "、拠点は未登録";
		} else {
			BlockPos h = nakama.home();
			position = here + "、拠点（" + h.getX() + " " + h.getY() + " " + h.getZ() + "）から"
				+ Status.direction(pos.getX() - h.getX(), pos.getZ() - h.getZ());
		}
		List<String> places = new ArrayList<>();
		if (nakama.home() != null) {
			places.add("home(拠点)");
		}
		return new Status(
			Status.timeOfDay(level.getOverworldClockTime()),
			weather,
			(int) Math.ceil(nakama.getHealth()),
			(int) nakama.getMaxHealth(),
			position,
			Inv.counts(nakama.getInventory()),
			resources(pos, level),
			creatures(nakama, level),
			places,
			currentJob);
	}

	private static List<String> resources(BlockPos origin, ServerLevel level) {
		Map<String, Found> nearest = new LinkedHashMap<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = -SCAN_HEIGHT; dy <= SCAN_HEIGHT; dy++) {
			for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
				for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
					p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					BlockState s = level.getBlockState(p);
					String label = labelOf(s);
					if (label == null) {
						continue;
					}
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					Found prev = nearest.get(label);
					if (prev == null) {
						nearest.put(label, new Found(label, 1, dx, dy, dz, d));
					} else if (d < prev.dist()) {
						nearest.put(label, new Found(label, prev.count() + 1, dx, dy, dz, d));
					} else {
						nearest.put(label, new Found(label, prev.count() + 1, prev.dx(), prev.dy(), prev.dz(), prev.dist()));
					}
				}
			}
		}
		return nearest.values().stream()
			.sorted(Comparator.comparingDouble(Found::dist))
			.limit(Status.MAX_RESOURCE_LINES)
			.map(f -> f.label() + " " + where(f.dx(), f.dy(), f.dz()) + " 約" + f.count() + "個")
			.toList();
	}

	private static String labelOf(BlockState s) {
		if (s.isAir()) {
			return null;
		}
		String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		if (s.is(BlockTags.LOGS) && !id.startsWith("stripped_")) {
			return Items.label(id);
		}
		return INTERESTING.get(id);
	}

	private static String where(int dx, int dy, int dz) {
		String h = Status.direction(dx, dz);
		if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy != 0) {
			return dy < 0 ? "足元の" + (-dy) + "マス下" : (dy) + "マス上";
		}
		return h;
	}

	private static List<String> creatures(NakamaEntity nakama, ServerLevel level) {
		Map<String, int[]> groups = new LinkedHashMap<>();
		List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
			nakama.getBoundingBox().inflate(CREATURE_RADIUS), e -> e != nakama && e.isAlive() && !(e instanceof Player));
		near.sort(Comparator.comparingDouble(nakama::distanceToSqr));
		for (LivingEntity e : near) {
			String name = e.getType().getDescription().getString() + (e instanceof Enemy ? "(敵)" : "");
			int[] g = groups.computeIfAbsent(name, k -> new int[] {0,
				(int) (e.getX() - nakama.getX()), (int) (e.getZ() - nakama.getZ())});
			g[0]++;
		}
		List<String> out = new ArrayList<>();
		groups.forEach((name, g) -> out.add(name + "×" + g[0] + " " + Status.direction(g[1], g[2])));
		return out;
	}
}
