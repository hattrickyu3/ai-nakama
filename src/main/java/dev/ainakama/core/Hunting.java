package dev.ainakama.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 狩りの決まり。狩ってよい動物と、倒すと落とすもの。
 * 「牛を3頭」のように動物で頼まれることも、「牛肉を5個」「革を3枚」のように、ほしいもので頼まれることもある。
 */
public final class Hunting {
	/** どの動物でもよい。 */
	public static final String ANY = "animal";
	/** どの肉でもよい。 */
	public static final String MEAT = "meat";
	/** 数を言われなかったときに狩る数・集める数。 */
	public static final int DEFAULT_COUNT = 3;
	/** 同じ種類の大人を、近くにこの数だけは残す（また増やせるように。囲いの中の家畜を全部倒さないように）。 */
	public static final int KEEP_ALIVE = 2;

	/**
	 * @param animal 動物のID（cow など）か {@link #ANY}
	 * @param item   ほしいもののID。{@link #MEAT} はどの肉でもよい。null なら、動物の数で数える
	 */
	public record Prey(String animal, String item) {
	}

	/** 動物ごとの、倒すと落とすもの。 */
	private static final Map<String, List<String>> DROPS = new LinkedHashMap<>();
	private static final Map<String, String> NAMES = new LinkedHashMap<>();
	private static final Map<String, String> LABELS = new LinkedHashMap<>();
	private static final Set<String> MEATS = Set.of("beef", "porkchop", "mutton", "chicken", "rabbit");
	private static final Set<String> ANY_NAMES = Set.of("動物", "どうぶつ", "animal", "animals", "獲物");
	private static final Set<String> MEAT_NAMES = Set.of("肉", "お肉", "生肉", "meat", "食べる肉");

	static {
		animal("cow", "牛", List.of("beef", "leather"), "牛", "ウシ", "うし", "cows");
		animal("pig", "豚", List.of("porkchop"), "豚", "ブタ", "ぶた", "pigs");
		animal("sheep", "羊", List.of("mutton", "white_wool"), "羊", "ヒツジ", "ひつじ");
		animal("chicken", "ニワトリ", List.of("chicken", "feather"), "鶏", "ニワトリ", "にわとり", "chickens", "とり", "鳥");
		animal("rabbit", "ウサギ", List.of("rabbit", "rabbit_hide"), "ウサギ", "うさぎ", "兎", "rabbits");
	}

	private Hunting() {
	}

	private static void animal(String id, String label, List<String> drops, String... names) {
		DROPS.put(id, drops);
		LABELS.put(id, label);
		NAMES.put(id, id);
		for (String name : names) {
			NAMES.put(name, id);
		}
	}

	/** 言葉を、狩るものにする。動物でも、動物が落とすものでもなければ空。 */
	public static Optional<Prey> resolve(String raw, Predicate<String> itemExists) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String s = raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
		String animal = NAMES.get(s);
		if (animal != null) {
			return Optional.of(new Prey(animal, null));
		}
		if (ANY_NAMES.contains(s)) {
			return Optional.of(new Prey(ANY, null));
		}
		if (MEAT_NAMES.contains(s)) {
			return Optional.of(new Prey(ANY, MEAT));
		}
		return Items.resolve(raw, itemExists).flatMap(Hunting::forDrop);
	}

	/** そのアイテムを落とす動物。どの動物も落とさなければ空。 */
	public static Optional<Prey> forDrop(String item) {
		String wanted = item.endsWith("_wool") ? "white_wool" : item;
		return DROPS.entrySet().stream().filter(e -> e.getValue().contains(wanted)).findFirst()
			.map(e -> new Prey(e.getKey(), wanted));
	}

	/** 狩る相手になる動物のID。 */
	public static Set<String> animalsFor(String animal) {
		return ANY.equals(animal) ? Set.copyOf(DROPS.keySet()) : Set.of(animal);
	}

	/** 拾ったもの picked が、ほしいもの wanted の数に入るか。 */
	public static boolean counts(String wanted, String picked) {
		if (MEAT.equals(wanted)) {
			return MEATS.contains(picked);
		}
		// 羊毛は、羊の色によって種類が違う
		return wanted.equals(picked) || (wanted.endsWith("_wool") && picked.endsWith("_wool"));
	}

	/** 返事や作業の説明に出す名前。 */
	public static String label(String animal) {
		return LABELS.getOrDefault(animal, "動物");
	}

	public static String itemLabel(String item) {
		return MEAT.equals(item) ? "肉" : Items.label(item);
	}
}
