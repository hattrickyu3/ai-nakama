package dev.ainakama.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AIに渡す「今の状況」。ゲームから集めた値を入れ、{@link #toText()} で数行の文章にする。
 * 文章を短く保つことが料金を抑える一番の手段なので、並べる数には上限を設けている。
 *
 * @param timeOfDay  朝・昼・夕方・夜
 * @param weather    晴れ・雨・雷雨
 * @param health     体力
 * @param maxHealth  最大体力
 * @param position   自分の位置の説明（拠点からの向きと距離など）
 * @param inventory  持ち物（IDごとの数）
 * @param resources  近くの資源（例：「oak_log(オークの原木) 北に12マス 約30個」）
 * @param creatures  近くの生き物（例：「ゾンビ×1 西に8マス」）
 * @param places     登録した場所の名前
 * @param currentJob 今の作業。なければ「なし」
 */
public record Status(
	String timeOfDay,
	String weather,
	int health,
	int maxHealth,
	String position,
	Map<String, Integer> inventory,
	List<String> resources,
	List<String> creatures,
	List<String> places,
	String currentJob) {

	public static final int MAX_INVENTORY_LINES = 12;
	public static final int MAX_RESOURCE_LINES = 8;
	public static final int MAX_CREATURE_LINES = 5;

	public String toText() {
		String inv = inventory.isEmpty() ? "なし" : inventory.entrySet().stream()
			.limit(MAX_INVENTORY_LINES)
			.map(e -> Items.label(e.getKey()) + "×" + e.getValue())
			.collect(Collectors.joining(", "));
		return String.join("\n",
			"時刻: " + timeOfDay + " / 天気: " + weather,
			"自分: 体力 " + health + "/" + maxHealth + "、" + position,
			"持ち物: " + inv,
			"近くの資源: " + joinOrNone(resources, MAX_RESOURCE_LINES),
			"近くの生き物: " + joinOrNone(creatures, MAX_CREATURE_LINES),
			"登録した場所: " + joinOrNone(places, 5),
			"今の作業: " + (currentJob == null || currentJob.isBlank() ? "なし" : currentJob));
	}

	private static String joinOrNone(List<String> items, int limit) {
		if (items.isEmpty()) {
			return "なし";
		}
		return items.stream().limit(limit).collect(Collectors.joining("、"));
	}

	/** dx, dz（東が+x、南が+z）から「北東に12マス」のような説明を作る。 */
	public static String direction(int dx, int dz) {
		int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
		if (dist <= 1) {
			return "すぐそば";
		}
		String ns = Math.abs(dz) * 2 < Math.abs(dx) ? "" : (dz < 0 ? "北" : "南");
		String ew = Math.abs(dx) * 2 < Math.abs(dz) ? "" : (dx > 0 ? "東" : "西");
		return ns + ew + "に" + dist + "マス";
	}

	/** Minecraft の1日（24000tick）のうちの時刻から、朝・昼・夕方・夜を決める。 */
	public static String timeOfDay(long ticks) {
		long t = Math.floorMod(ticks, 24000L);
		if (t < 3000) {
			return "朝";
		}
		if (t < 11000) {
			return "昼";
		}
		if (t < 13000) {
			return "夕方";
		}
		if (t < 23000) {
			return "夜";
		}
		return "明け方";
	}
}
