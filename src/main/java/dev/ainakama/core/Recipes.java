package dev.ainakama.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 仲間が作れるもののレシピ表。最小版ではよく使うものだけを手で書いている。
 * 材料のキーは Minecraft のID、または種類を問わないグループ（{@link Items#ANY_LOG} / {@link Items#ANY_PLANKS}）。
 */
public final class Recipes {
	/**
	 * @param output      できるもののID（板材は {@link Items#ANY_PLANKS}。実際の種類は使った原木で決まる）
	 * @param outputCount 1回のクラフトでできる数
	 * @param ingredients 1回に使う材料
	 * @param needsTable  作業台が必要か（3×3のレシピ）
	 */
	public record Recipe(String output, int outputCount, Map<String, Integer> ingredients, boolean needsTable) {
	}

	private static final Map<String, Recipe> BY_OUTPUT = new LinkedHashMap<>();

	static {
		add(Items.ANY_PLANKS, 4, false, Items.ANY_LOG, 1);
		add("stick", 4, false, Items.ANY_PLANKS, 2);
		add("crafting_table", 1, false, Items.ANY_PLANKS, 4);
		add("chest", 1, true, Items.ANY_PLANKS, 8);
		add("wooden_pickaxe", 1, true, Items.ANY_PLANKS, 3, "stick", 2);
		add("wooden_axe", 1, true, Items.ANY_PLANKS, 3, "stick", 2);
		add("wooden_shovel", 1, true, Items.ANY_PLANKS, 1, "stick", 2);
		add("wooden_sword", 1, true, Items.ANY_PLANKS, 2, "stick", 1);
		add("stone_pickaxe", 1, true, "cobblestone", 3, "stick", 2);
		add("stone_axe", 1, true, "cobblestone", 3, "stick", 2);
		add("stone_shovel", 1, true, "cobblestone", 1, "stick", 2);
		add("stone_sword", 1, true, "cobblestone", 2, "stick", 1);
		add("furnace", 1, true, "cobblestone", 8);
		add("torch", 4, false, "coal", 1, "stick", 1);
		add("oak_fence", 3, true, Items.ANY_PLANKS, 4, "stick", 2);
		add("oak_door", 3, true, Items.ANY_PLANKS, 6);
		add("bread", 1, true, "wheat", 3);
	}

	private Recipes() {
	}

	private static void add(String output, int count, boolean table, Object... pairs) {
		Map<String, Integer> ingredients = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			ingredients.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		BY_OUTPUT.put(output, new Recipe(output, count, Map.copyOf(ingredients), table));
	}

	/** そのアイテム（またはグループ）を作るレシピ。板材はどの種類を頼まれても同じレシピを返す。 */
	public static Optional<Recipe> forOutput(String key) {
		String k = Items.isPlanks(key) ? Items.ANY_PLANKS : key;
		return Optional.ofNullable(BY_OUTPUT.get(k));
	}

	public static boolean canCraft(String key) {
		return forOutput(key).isPresent();
	}
}
