package dev.ainakama.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仲間が作れるもののレシピ表。
 * ゲームを起動するとマイクラ本体のレシピに入れ替わる（{@link #install}）。手書きの表は、テストと「読み込めなかったとき」のための控え。
 * 材料のキーは次のどれか：
 * ・Minecraft のID（stick）
 * ・種類を問わないグループ（{@link Items#ANY_LOG} / {@link Items#ANY_PLANKS}）
 * ・どれか1つでよい（any:coal|charcoal）
 */
public final class Recipes {
	/**
	 * @param output      できるもののID（板材は {@link Items#ANY_PLANKS}。実際の種類は使った原木で決まる）
	 * @param outputCount 1回のクラフトでできる数
	 * @param ingredients 1回に使う材料
	 * @param needsTable  作業台が必要か（3×3のレシピ）
	 */
	public record Recipe(String output, int outputCount, Map<String, Integer> ingredients, boolean needsTable) {
		public Recipe {
			ingredients = Map.copyOf(ingredients);
		}
	}

	/** 1つのものに対して試すレシピの数の上限（色ちがいなどで候補が多いものがあるため）。 */
	public static final int MAX_CANDIDATES = 3;

	/** 原木 → 板材。板材の種類は使った原木で決まるので、これだけは本体のレシピを使わずに特別扱いする。 */
	private static final Recipe PLANKS = new Recipe(Items.ANY_PLANKS, 4, Map.of(Items.ANY_LOG, 1), false);

	private static volatile Map<String, List<Recipe>> byOutput = builtIn();

	private Recipes() {
	}

	private static Map<String, List<Recipe>> builtIn() {
		List<Recipe> list = new ArrayList<>();
		add(list, "stick", 4, false, Items.ANY_PLANKS, 2);
		add(list, "crafting_table", 1, false, Items.ANY_PLANKS, 4);
		add(list, "chest", 1, true, Items.ANY_PLANKS, 8);
		add(list, "wooden_pickaxe", 1, true, Items.ANY_PLANKS, 3, "stick", 2);
		add(list, "wooden_axe", 1, true, Items.ANY_PLANKS, 3, "stick", 2);
		add(list, "wooden_shovel", 1, true, Items.ANY_PLANKS, 1, "stick", 2);
		add(list, "wooden_sword", 1, true, Items.ANY_PLANKS, 2, "stick", 1);
		add(list, "stone_pickaxe", 1, true, "cobblestone", 3, "stick", 2);
		add(list, "stone_axe", 1, true, "cobblestone", 3, "stick", 2);
		add(list, "stone_shovel", 1, true, "cobblestone", 1, "stick", 2);
		add(list, "stone_sword", 1, true, "cobblestone", 2, "stick", 1);
		add(list, "furnace", 1, true, "cobblestone", 8);
		add(list, "torch", 4, false, "any:coal|charcoal", 1, "stick", 1);
		add(list, "oak_fence", 3, true, Items.ANY_PLANKS, 4, "stick", 2);
		add(list, "oak_door", 3, true, Items.ANY_PLANKS, 6);
		add(list, "ladder", 3, true, "stick", 7);
		add(list, "bread", 1, true, "wheat", 3);
		return index(list);
	}

	private static void add(List<Recipe> list, String output, int count, boolean table, Object... pairs) {
		Map<String, Integer> ingredients = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			ingredients.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		list.add(new Recipe(output, count, ingredients, table));
	}

	private static Map<String, List<Recipe>> index(List<Recipe> recipes) {
		Map<String, List<Recipe>> map = new LinkedHashMap<>();
		for (Recipe r : recipes) {
			if (Items.isPlanks(r.output())) {
				continue;
			}
			map.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
		}
		map.replaceAll((k, v) -> List.copyOf(v));
		return Map.copyOf(map);
	}

	/** マイクラ本体から読んだレシピに入れ替える。空なら何もしない。 */
	public static void install(List<Recipe> recipes) {
		if (!recipes.isEmpty()) {
			byOutput = index(recipes);
		}
	}

	/** テスト用：手書きの表に戻す。 */
	public static void reset() {
		byOutput = builtIn();
	}

	/**
	 * そのアイテム（またはグループ）を作るレシピの候補。使いやすそうな順に、最大 {@link #MAX_CANDIDATES} 個。
	 * 板材はどの種類を頼まれても「原木 → 板材」を返す。
	 */
	public static List<Recipe> candidates(String key) {
		if (Items.isPlanks(key) || Items.ANY_PLANKS.equals(key)) {
			return List.of(PLANKS);
		}
		List<Recipe> all = byOutput.getOrDefault(key, List.of());
		if (all.size() <= 1) {
			return all;
		}
		return all.stream()
			.sorted(java.util.Comparator.comparingInt(Recipes::difficulty))
			.limit(MAX_CANDIDATES)
			.toList();
	}

	/** 材料が身近なレシピほど小さい値。板材・棒・丸石など、仲間がすぐ用意できるものを優先する。 */
	private static int difficulty(Recipe r) {
		int score = 0;
		for (String key : r.ingredients().keySet()) {
			boolean easy = key.startsWith("#") || Items.options(key).stream().anyMatch(Items::isEasy);
			score += easy ? 0 : 10;
		}
		return score + r.ingredients().size();
	}

	public static boolean canCraft(String key) {
		return !candidates(key).isEmpty();
	}

	public static int count() {
		return byOutput.size();
	}
}
