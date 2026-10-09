package dev.ainakama.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 建物の設計図。置くブロックを「基準点からのずれ」と「ブロックのキー」で並べたもの。
 * キーが {@link Items#ANY_PLANKS} のところには、持っている板材ならどの種類でも置く。
 * 並び順は置く順（下の段から）。
 */
public record Blueprint(String id, String japaneseName, List<Part> parts) {
	public record Part(int dx, int dy, int dz, String block) {
	}

	public static final Blueprint HUT = hut();
	public static final Blueprint FENCE = fence();

	private static final Map<String, Blueprint> BY_NAME = new LinkedHashMap<>();

	static {
		register(HUT, "hut", "小屋", "家", "house", "cabin");
		register(FENCE, "fence", "柵", "フェンス", "fence_ring");
	}

	private static void register(Blueprint bp, String... names) {
		for (String name : names) {
			BY_NAME.put(name, bp);
		}
	}

	public static Optional<Blueprint> find(String name) {
		if (name == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(BY_NAME.get(name.trim().toLowerCase(Locale.ROOT)));
	}

	public static List<Blueprint> all() {
		return List.of(HUT, FENCE);
	}

	/** 必要な材料（キーごとの数）。 */
	public Map<String, Integer> materials() {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Part p : parts) {
			m.merge(p.block(), 1, Integer::sum);
		}
		return m;
	}

	/** 5×5、壁の高さ3の小屋。南側（+z）の真ん中に出入り口を空けておく。 */
	private static Blueprint hut() {
		List<Part> parts = new ArrayList<>();
		int size = 5;
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < size; x++) {
				for (int z = 0; z < size; z++) {
					boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
					boolean door = x == 2 && z == size - 1 && y < 2;
					if (edge && !door) {
						parts.add(new Part(x, y, z, Items.ANY_PLANKS));
					}
				}
			}
		}
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				parts.add(new Part(x, 3, z, Items.ANY_PLANKS));
			}
		}
		return new Blueprint("hut", "小屋", List.copyOf(parts));
	}

	/** 7×7の柵の囲い。南側（+z）の真ん中は出入り口として空ける。 */
	private static Blueprint fence() {
		List<Part> parts = new ArrayList<>();
		int size = 7;
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
				boolean gate = x == 3 && z == size - 1;
				if (edge && !gate) {
					parts.add(new Part(x, 0, z, "oak_fence"));
				}
			}
		}
		return new Blueprint("fence", "柵", List.copyOf(parts));
	}
}
