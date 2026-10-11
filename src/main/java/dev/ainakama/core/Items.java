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

	/**
	 * collect で集めてよいもの（原木のほか）。自然にあるブロックから取れるものだけにして、
	 * 板材やガラスなど「プレイヤーが置いたブロックを壊さないと手に入らないもの」は集めさせない。
	 */
	public static final java.util.Set<String> COLLECTABLE = java.util.Set.of(
		"cobblestone", "coal", "raw_iron", "dirt", "sand", "gravel", "clay_ball");

	private static final Map<String, String> ALIASES = new LinkedHashMap<>();
	private static final Map<String, String> JAPANESE = new LinkedHashMap<>();

	static {
		alias("oak_log", "原木", "木", "丸太", "オークの原木", "log", "logs", "wood", "tree");
		alias("oak_planks", "板材", "木材", "板", "オークの板材", "plank", "planks", "wooden_planks");
		alias("stick", "棒", "木の棒", "sticks");
		alias("crafting_table", "作業台", "workbench");
		alias("chest", "チェスト", "箱");
		alias("furnace", "かまど");
		alias("cobblestone", "丸石", "石", "stone", "stone_block", "rock");
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
		alias("raw_iron", "鉄の原石", "鉄鉱石", "鉄", "iron_ore");
		alias("iron_ingot", "鉄インゴット", "鉄のインゴット");
		alias("iron_pickaxe", "鉄のツルハシ");
		alias("iron_sword", "鉄の剣");
		alias("charcoal", "木炭");
		alias("glass", "ガラス");
		alias("wheat", "小麦");
		alias("wheat_seeds", "小麦の種", "種");
		alias("carrot", "ニンジン", "にんじん", "人参");
		alias("potato", "ジャガイモ", "じゃがいも");
		alias("baked_potato", "ベイクドポテト", "焼き芋");
		alias("beef", "生の牛肉", "牛肉");
		alias("cooked_beef", "ステーキ");
		alias("porkchop", "生の豚肉", "豚肉");
		alias("chicken", "生の鶏肉", "鶏肉");
		alias("mutton", "生の羊肉", "羊肉", "マトン");
		alias("rabbit", "生の兎肉", "ウサギ肉", "うさぎ肉");
		alias("leather", "革", "皮", "レザー");
		alias("white_wool", "羊毛", "ウール", "wool");
		alias("feather", "羽", "羽根");
		alias("milk_bucket", "牛乳", "ミルク", "ミルク入りバケツ", "milk");
		alias("bucket", "バケツ");
		alias("shears", "ハサミ", "はさみ");
		alias("fishing_rod", "釣り竿", "釣竿", "つりざお");
		alias("cod", "生鱈", "タラ", "魚", "さかな", "fish");
		alias("salmon", "生鮭", "鮭", "サケ");
		alias("emerald", "エメラルド");
		alias("wooden_hoe", "木のクワ", "クワ", "くわ");
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
		japanese("iron_ingot", "鉄インゴット");
		japanese("charcoal", "木炭");
		japanese("glass", "ガラス");
		japanese("stone", "石");
		japanese("wheat_seeds", "小麦の種");
		japanese("carrot", "ニンジン");
		japanese("potato", "ジャガイモ");
		japanese("wheat", "小麦");
		japanese("bread", "パン");
		japanese("oak_fence", "オークの柵");
		japanese("beef", "生の牛肉");
		japanese("porkchop", "生の豚肉");
		japanese("mutton", "生の羊肉");
		japanese("chicken", "生の鶏肉");
		japanese("rabbit", "生の兎肉");
		japanese("leather", "革");
		japanese("white_wool", "白色の羊毛");
		japanese("feather", "羽");
		japanese("milk_bucket", "牛乳入りバケツ");
		japanese("bucket", "バケツ");
		japanese("shears", "ハサミ");
		japanese("fishing_rod", "釣り竿");
		japanese("cod", "生鱈");
		japanese("salmon", "生鮭");
		japanese("emerald", "エメラルド");
		japanese("wooden_hoe", "木のクワ");
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
		return s.endsWith("_log") || s.endsWith("_wood")
			|| s.equals("crimson_stem") || s.equals("warped_stem") || s.equals("crimson_hyphae") || s.equals("warped_hyphae");
	}

	/** collect で集めてよいか。原木はどの種類でもよい。 */
	public static boolean isCollectable(String id) {
		return isLog(id) || COLLECTABLE.contains(id);
	}

	public static boolean isPlanks(String id) {
		return id.endsWith("_planks");
	}

	public static final String ANY_PREFIX = "any:";

	/** id が key（同じID、グループ #log / #planks、または any:a|b のどれか）に当てはまるか。 */
	public static boolean matches(String key, String id) {
		if (key.startsWith(ANY_PREFIX)) {
			return options(key).contains(id);
		}
		return switch (key) {
			case ANY_LOG -> isLog(id);
			case ANY_PLANKS -> isPlanks(id);
			default -> key.equals(id);
		};
	}

	/** key が指すIDの候補。any:a|b なら [a, b]、IDそのものならそれ1つ、グループなら空。 */
	public static java.util.List<String> options(String key) {
		if (key.startsWith(ANY_PREFIX)) {
			return java.util.List.of(key.substring(ANY_PREFIX.length()).split("\\|"));
		}
		return key.startsWith("#") ? java.util.List.of() : java.util.List.of(key);
	}

	/**
	 * 候補のID一覧から、材料のキーを作る。全部が板材なら #planks、全部が原木なら #log、1つならそのID、それ以外は any:a|b。
	 */
	public static String keyFor(java.util.List<String> ids) {
		if (ids.size() == 1) {
			return ids.get(0);
		}
		if (ids.stream().allMatch(Items::isPlanks)) {
			return ANY_PLANKS;
		}
		if (ids.stream().allMatch(Items::isLog)) {
			return ANY_LOG;
		}
		return ANY_PREFIX + String.join("|", ids);
	}

	/** 仲間がすぐ用意できる材料か（レシピの候補を選ぶときの目安）。 */
	public static boolean isEasy(String id) {
		return isLog(id) || isPlanks(id) || COLLECTABLE.contains(id)
			|| id.equals("stick") || id.equals("crafting_table") || id.equals("charcoal");
	}

	/** 足りない材料 key を集めるなら、何を collect すればよいか。集められないものなら空。 */
	public static Optional<String> collectableFor(String key) {
		if (ANY_LOG.equals(key)) {
			return Optional.of("oak_log");
		}
		return options(key).stream().filter(Items::isCollectable).findFirst();
	}

	public static String describeKey(String key) {
		if (ANY_LOG.equals(key)) {
			return "原木";
		}
		if (ANY_PLANKS.equals(key)) {
			return "板材";
		}
		java.util.List<String> opts = options(key);
		return opts.size() == 1 ? label(opts.get(0)) : label(opts.get(0)) + "など";
	}

	/** 原木から作れる板材のID。oak_log → oak_planks、crimson_stem → crimson_planks。 */
	public static String planksFor(String logId) {
		String s = logId.replace("stripped_", "");
		for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae"}) {
			if (s.endsWith(suffix)) {
				return s.substring(0, s.length() - suffix.length()) + "_planks";
			}
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
