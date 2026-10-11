package dev.ainakama.core;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 道具の決まり。ブロックを壊すのにかかる時間と、作業の前に作っておくと速くなる道具。
 * 前は、道具の種類や材質が速さに関係なく、斧やシャベルを自分で作ることもなかった。
 */
public final class Tooling {
	/** どんなに硬いブロックでも、これより長くはかけない。 */
	public static final int MAX_BREAK_TICKS = 200;
	/**
	 * マイクラ本体の決まり（合う道具なら 硬さ×30÷道具の速さ tick）に掛ける倍率。
	 * 本体そのままだと素手が前の2倍遅くなるので、前と同じくらいの手際になるように少し速くしてある。
	 */
	private static final float PACE = 0.6F;

	/** 斧を作る意味がある、残りの原木の数。 */
	private static final int AXE_WORTH = 4;
	/** シャベルを作る意味がある、残りの土や砂の数。 */
	private static final int SHOVEL_WORTH = 8;
	/** 石のツルハシに作り替える意味がある、残りの石や鉱石の数。 */
	private static final int PICKAXE_WORTH = 6;
	private static final Set<String> SHOVEL_WORK = Set.of("dirt", "sand", "gravel", "clay_ball");
	private static final Set<String> PICKAXE_WORK = Set.of("cobblestone", "coal", "raw_iron");

	private Tooling() {
	}

	/**
	 * ブロックを壊すのにかかる時間（tick）。
	 *
	 * @param hardness   ブロックの硬さ（壊せないブロックは負の数）
	 * @param toolSpeed  持っている道具の、そのブロックに対する速さ（素手は 1）
	 * @param canHarvest 壊したときにアイテムを落とすか（道具が要らないブロックか、合う道具を持っている）
	 */
	public static int breakTicks(float hardness, float toolSpeed, boolean canHarvest) {
		if (hardness < 0) {
			return MAX_BREAK_TICKS;
		}
		double ticks = hardness * (canHarvest ? 30 : 100) / Math.max(1.0F, toolSpeed) * PACE;
		return (int) Math.max(1, Math.min(MAX_BREAK_TICKS, Math.ceil(ticks)));
	}

	/**
	 * 集める作業を続ける前に、作っておくと速くなる道具。作る材料が手元にあるときだけ返す。
	 *
	 * @param item      集めているもの
	 * @param inventory 持ち物（ID → 数）
	 * @param remaining あと何個集めるか
	 */
	public static Optional<String> upgradeFor(String item, Map<String, Integer> inventory, int remaining) {
		int logs = count(inventory, "_log");
		int cobblestone = inventory.getOrDefault("cobblestone", 0);
		if (Items.isLog(item)) {
			if (remaining < AXE_WORTH || has(inventory, "_axe")) {
				return Optional.empty();
			}
			if (cobblestone >= 3 && logs >= 2) {
				return Optional.of("stone_axe");
			}
			// 板材3枚と棒2本、それに作業台の分
			return logs >= 3 ? Optional.of("wooden_axe") : Optional.empty();
		}
		if (SHOVEL_WORK.contains(item)) {
			if (remaining < SHOVEL_WORTH || has(inventory, "_shovel") || logs < 1) {
				return Optional.empty();
			}
			if (cobblestone >= 1) {
				return Optional.of("stone_shovel");
			}
			return logs >= 2 ? Optional.of("wooden_shovel") : Optional.empty();
		}
		if (PICKAXE_WORK.contains(item)) {
			boolean onlyWooden = inventory.getOrDefault("wooden_pickaxe", 0) > 0
				&& inventory.keySet().stream().noneMatch(id -> id.endsWith("_pickaxe") && !id.equals("wooden_pickaxe"));
			boolean handle = inventory.getOrDefault("stick", 0) >= 2 || logs >= 1 || count(inventory, "_planks") >= 2;
			if (remaining >= PICKAXE_WORTH && onlyWooden && cobblestone >= 3 && handle) {
				return Optional.of("stone_pickaxe");
			}
		}
		return Optional.empty();
	}

	private static boolean has(Map<String, Integer> inventory, String suffix) {
		return count(inventory, suffix) > 0;
	}

	private static int count(Map<String, Integer> inventory, String suffix) {
		return inventory.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).mapToInt(Map.Entry::getValue).sum();
	}
}
