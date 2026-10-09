package dev.ainakama.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * アイテム名の扱い。AIや人が書いた名前（日本語・複数形・"minecraft:" 付きなど）を Minecraft のIDにそろえる。
 * 原木と板材は種類を問わない「グループ」として扱う（#log / #planks）。
 */
public final class Items {
	public static final String ANY_LOG = "#log";
	public static final String ANY_PLANKS = "#planks";

	private static final Map<String, String> ALIASES = new LinkedHashMap<>();
	private static final Map<String, String> JAPANESE = new LinkedHashMap<>();

	static {
		alias("oak_log", "原木", "木", "丸太", "オークの原木", "log", "logs", "wood", "tree");
		alias("oak_planks", "板材", "木材", "板", "オークの板材", "plank", "planks", "wooden_planks");
		alias("stick", "棒", "木の棒", "sticks");
		alias("crafting_table", "作業台", "workbench");
		alias("chest", "チェスト", "箱");
		alias("furnace", "かまど");
		alias("cobblestone", "丸石", "石", "stone_block", "rock");
		alias("coal", "石炭");
		alias("torch", "松明", "たいまつ", "torches");
		alias("wooden_pickaxe", "木のツルハシ");
		alias("stone_pickaxe", "石のツルハシ");
		alias("wooden_axe", "木の斧");
		alias("stone_axe", "石の斧");
		alias("wooden_shovel", "木のシャベル");
		alias("stone_shovel", "石のシャベル");
		alias("wooden_sword", "木の剣");
		alias("stone_sword", "石の剣");
		alias("dirt", "土");
		alias("sand", "砂");
		alias("gravel", "砂利");
		alias("raw_iron", "鉄の原石", "鉄鉱石", "iron_ore");
		alias("wheat", "小麦");
		alias("bread", "パン");
		alias("oak_fence", "柵", "フェンス", "fence");
		alias("oak_door", "ドア", "扉", "door");

		japanese("oak_log", "オークの原木");
		japanese("birch_log", "シラカバの原木");
		japanese("spruce_log", "トウヒの原木");
		japanese("oak_planks", "オークの板材");
		japanese("birch_planks", "シラカバの板材");
		japanese("spruce_planks", "トウヒの板材");
		japanese("stick", "棒");
		japanese("crafting_table", "作業台");
		japanese("chest", "チェスト");
		japanese("furnace", "かまど");
		japanese("cobblestone", "丸石");
		japanese("coal", "石炭");
		japanese("torch", "松明");
		japanese("wooden_pickaxe", "木のツルハシ");
		japanese("stone_pickaxe", "石のツルハシ");
		japanese("wooden_axe", "木の斧");
		japanese("stone_axe", "石の斧");
		japanese("dirt", "土");
		japanese("sand", "砂");
		japanese("raw_iron", "鉄の原石");
		japanese("wheat", "小麦");
		japanese("bread", "パン");
		japanese("oak_fence", "オークの柵");
	}

	private Items() {
	}

	private static void alias(String id, String... names) {
		for (String name : names) {
			ALIASES.put(name, id);
		}
	}

	private static void japanese(String id, String name) {
		JAPANESE.put(id, name);
	}

	/**
	 * 名前をIDにする。exists はそのIDが実際のゲームにあるかを確かめる関数（テストでは差し替える）。
	 */
	public static Optional<String> resolve(String raw, Predicate<String> exists) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String s = raw.trim();
		String alias = ALIASES.get(s);
		if (alias != null) {
			return Optional.of(alias);
		}
		s = s.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
		alias = ALIASES.get(s);
		if (alias != null) {
			return Optional.of(alias);
		}
		if (exists.test(s)) {
			return Optional.of(s);
		}
		if (s.endsWith("s") && exists.test(s.substring(0, s.length() - 1))) {
			return Optional.of(s.substring(0, s.length() - 1));
		}
		return Optional.empty();
	}

	/** 状況の要約に使う表示名。"oak_log(オークの原木)" のようにIDと日本語を並べる。 */
	public static String label(String id) {
		String jp = JAPANESE.get(id);
		return jp == null ? id : id + "(" + jp + ")";
	}

	public static boolean isLog(String id) {
		String s = id.replace("stripped_", "");
		return s.endsWith("_log") || s.endsWith("_stem");
	}

	public static boolean isPlanks(String id) {
		return id.endsWith("_planks");
	}

	/** id がグループ key（#log / #planks）または同じIDに当てはまるか。 */
	public static boolean matches(String key, String id) {
		return switch (key) {
			case ANY_LOG -> isLog(id);
			case ANY_PLANKS -> isPlanks(id);
			default -> key.equals(id);
		};
	}

	/** 原木から作れる板材のID。oak_log → oak_planks、crimson_stem → crimson_planks。 */
	public static String planksFor(String logId) {
		String s = logId.replace("stripped_", "");
		if (s.endsWith("_log")) {
			return s.substring(0, s.length() - "_log".length()) + "_planks";
		}
		if (s.endsWith("_stem")) {
			return s.substring(0, s.length() - "_stem".length()) + "_planks";
		}
		return "oak_planks";
	}

	/** 命令の item をグループにそろえる。原木ならどれでも、板材ならどれでもよい扱いにする。 */
	public static String groupOf(String id) {
		if (isLog(id)) {
			return ANY_LOG;
		}
		if (isPlanks(id)) {
			return ANY_PLANKS;
		}
		return id;
	}

	public static int count(Map<String, Integer> inventory, String key) {
		int total = 0;
		for (Map.Entry<String, Integer> e : inventory.entrySet()) {
			if (matches(key, e.getKey())) {
				total += e.getValue();
			}
		}
		return total;
	}
}
