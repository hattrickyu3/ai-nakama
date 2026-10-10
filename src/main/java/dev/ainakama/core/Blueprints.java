package dev.ainakama.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 設計図の一覧。最初からある小屋・柵と、プレイヤーに見せてもらって覚えたものを、名前で引けるようにする。
 * 覚えた設計図は起動時にファイルから読み込んで {@link #learn} で入れる。
 */
public final class Blueprints {
	public static final Blueprint HUT = Blueprint.hut();
	public static final Blueprint FENCE = Blueprint.fence();

	private static final Map<String, Blueprint> BUILT_IN = new LinkedHashMap<>();
	private static final Map<String, Blueprint> LEARNED = new LinkedHashMap<>();

	static {
		for (String name : List.of("hut", "小屋", "家", "house", "cabin")) {
			BUILT_IN.put(name, HUT);
		}
		for (String name : List.of("fence", "柵", "フェンス", "fence_ring")) {
			BUILT_IN.put(name, FENCE);
		}
	}

	private Blueprints() {
	}

	public static synchronized Optional<Blueprint> find(String name) {
		if (name == null) {
			return Optional.empty();
		}
		String key = normalize(name);
		// 覚えたものを先に見る（「家」を自分の形で教え直せるように）
		Blueprint learned = LEARNED.get(key);
		return learned != null ? Optional.of(learned) : Optional.ofNullable(BUILT_IN.get(key));
	}

	public static synchronized List<Blueprint> all() {
		List<Blueprint> list = new ArrayList<>(List.of(HUT, FENCE));
		list.addAll(LEARNED.values());
		return list;
	}

	public static synchronized List<Blueprint> learned() {
		return List.copyOf(LEARNED.values());
	}

	public static synchronized void learn(Blueprint blueprint) {
		LEARNED.put(normalize(blueprint.id()), blueprint);
	}

	public static synchronized boolean forget(String name) {
		return LEARNED.remove(normalize(name)) != null;
	}

	/** テスト用：覚えたものを全部忘れる。 */
	public static synchronized void clearLearned() {
		LEARNED.clear();
	}

	public static String normalize(String name) {
		return name.trim().toLowerCase(Locale.ROOT).replace("「", "").replace("」", "");
	}
}
