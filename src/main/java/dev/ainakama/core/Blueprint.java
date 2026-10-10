package dev.ainakama.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建物の設計図。置くブロックを「基準点からのずれ」と「ブロックのキー」で並べたもの。
 * キーが {@link Items#ANY_PLANKS} のところには、持っている板材ならどの種類でも置く。
 * 並び順は置く順（下の段から）。
 *
 * @param id           命令で使う名前（最初からあるものは hut / fence、覚えたものは教わった名前）
 * @param japaneseName 表示に使う名前
 * @param learned      プレイヤーに見せてもらって覚えたものか
 */
public record Blueprint(String id, String japaneseName, List<Part> parts, boolean learned) {
	/**
	 * @param block 必要なアイテムのキー（ID または #planks）
	 * @param state 置くブロックの向きなどの状態（覚えた設計図で使う）。null なら向きは置くときに決める
	 * @param free  材料がいらない部分（扉の上半分など、下半分を置くと一緒に置かれるもの）
	 */
	public record Part(int dx, int dy, int dz, String block, String state, boolean free) {
		public Part(int dx, int dy, int dz, String block) {
			this(dx, dy, dz, block, null, false);
		}
	}

	public Blueprint {
		parts = List.copyOf(parts);
	}

	/** 必要な材料（キーごとの数）。 */
	public Map<String, Integer> materials() {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Part p : parts) {
			if (!p.free()) {
				m.merge(p.block(), 1, Integer::sum);
			}
		}
		return m;
	}

	/** 幅（x）・高さ（y）・奥行き（z）。 */
	public int[] size() {
		int x = 0;
		int y = 0;
		int z = 0;
		for (Part p : parts) {
			x = Math.max(x, p.dx() + 1);
			y = Math.max(y, p.dy() + 1);
			z = Math.max(z, p.dz() + 1);
		}
		return new int[] {x, y, z};
	}

	/** 5×5、壁の高さ3の小屋。南側（+z）の真ん中に扉を付ける。 */
	static Blueprint hut() {
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
		// 扉は壁ができてから付ける（下半分を置くと上半分も一緒に置かれる）
		parts.add(new Part(2, 0, size - 1, "oak_door"));
		return new Blueprint("hut", "小屋", parts, false);
	}

	/** 7×7の柵の囲い。南側（+z）の真ん中は出入り口として空ける。 */
	static Blueprint fence() {
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
		return new Blueprint("fence", "柵", parts, false);
	}
}
