package dev.ainakama.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * かまどで焼くレシピと燃料の表。
 * ゲームを起動するとマイクラ本体のレシピに入れ替わる（{@link #install}）。手書きの表は、テストと「読み込めなかったとき」のための控え。
 * 焼く前のもののキーは {@link Recipes} と同じ（ID・グループ・any:a|b）。
 */
public final class Smelting {
	/** かまどで1個焼くのにかかる時間（tick）。 */
	public static final int TICKS_PER_ITEM = 200;
	/** 「焼いたものをさらに焼く」をたどる深さの上限。 */
	private static final int MAX_CHAIN = 2;

	/** @param ticks 1個が燃えている時間 */
	private record Fuel(String key, int ticks) {
	}

	/**
	 * 燃料に使ってよいもの。道具や作業台も本当は燃えるが、燃やされると困るので入れていない。
	 * 同じ長さなら、先に書いたものから使う。
	 */
	private static final List<Fuel> FUELS = List.of(
		new Fuel("coal", 1600),
		new Fuel("charcoal", 1600),
		new Fuel(Items.ANY_PLANKS, 300),
		new Fuel(Items.ANY_LOG, 300),
		new Fuel("stick", 100));

	private static volatile Map<String, List<String>> inputsByOutput = builtIn();

	private Smelting() {
	}

	private static Map<String, List<String>> builtIn() {
		Map<String, List<String>> map = new LinkedHashMap<>();
		map.put("iron_ingot", List.of("raw_iron"));
		map.put("gold_ingot", List.of("raw_gold"));
		map.put("copper_ingot", List.of("raw_copper"));
		map.put("stone", List.of("cobblestone"));
		map.put("smooth_stone", List.of("stone"));
		map.put("glass", List.of("sand"));
		map.put("charcoal", List.of(Items.ANY_LOG));
		map.put("brick", List.of("clay_ball"));
		map.put("cooked_beef", List.of("beef"));
		map.put("cooked_porkchop", List.of("porkchop"));
		map.put("cooked_chicken", List.of("chicken"));
		map.put("cooked_mutton", List.of("mutton"));
		map.put("baked_potato", List.of("potato"));
		return index(map);
	}

	/** できるものの順をそろえ、焼く前のものは「自分で集められるもの」を先にする。 */
	private static Map<String, List<String>> index(Map<String, List<String>> raw) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		new TreeMap<>(raw).forEach((output, inputs) -> {
			List<String> sorted = new ArrayList<>(inputs);
			sorted.sort(Comparator.comparing((String key) -> Items.collectableFor(key).isEmpty()));
			map.put(output, List.copyOf(sorted));
		});
		return Collections.unmodifiableMap(map);
	}

	/** マイクラ本体から読んだレシピ（できるもの → 焼く前のものの候補）に入れ替える。空なら何もしない。 */
	public static void install(Map<String, List<String>> recipes) {
		if (!recipes.isEmpty()) {
			inputsByOutput = index(recipes);
		}
	}

	/** テスト用：手書きの表に戻す。 */
	public static void reset() {
		inputsByOutput = builtIn();
	}

	public static int count() {
		return inputsByOutput.size();
	}

	/** output を焼いて作るときの、焼く前のものの候補。焼いて作れないものなら空。 */
	public static List<String> inputsFor(String output) {
		return inputsByOutput.getOrDefault(output, List.of());
	}

	/** かまどで焼いてできるものか。 */
	public static boolean isProduct(String id) {
		return !inputsFor(id).isEmpty();
	}

	/**
	 * key（IDまたは any:a|b）が指すもののどれかを、仲間が自分で焼いて作れるか。
	 * 焼く前のものを集められる（鉄の原石・砂・原木など）か、それも焼いて作れる（丸石 → 石 → 滑らかな石）ものに限る。
	 * 生の肉や鉄の道具のように、集め方を知らないものを焼くレシピは数えない。
	 */
	public static boolean canMake(String key) {
		return Items.options(key).stream().anyMatch(id -> canMake(id, 0));
	}

	private static boolean canMake(String id, int depth) {
		return inputsFor(id).stream().anyMatch(input -> Items.collectableFor(input).isPresent()
			|| (depth < MAX_CHAIN && Items.options(input).stream().anyMatch(in -> canMake(in, depth + 1))));
	}

	/**
	 * 「これを焼いて」と言われたものから、焼いてできるものを決める。
	 * 焼いてできるものの名前（iron_ingot）ならそのまま、焼く前のものの名前（raw_iron）ならそれを焼いたもの。
	 */
	public static Optional<String> resultFor(String id) {
		if (inputsByOutput.containsKey(id)) {
			return Optional.of(id);
		}
		for (var e : inputsByOutput.entrySet()) {
			if (e.getValue().stream().anyMatch(key -> Items.matches(key, id))) {
				return Optional.of(e.getKey());
			}
		}
		return Optional.empty();
	}

	/**
	 * output を焼くのに、どの「焼く前のもの」を使うか。いちばん多く持っているものを選び、
	 * どれも持っていなければ、集められるものを選ぶ。焼いて作れないものなら空。
	 */
	public static Optional<String> chooseInput(String output, Map<String, Integer> inventory) {
		return inputsFor(output).stream().reduce((best, key) ->
			Items.count(inventory, key) > Items.count(inventory, best) ? key : best);
	}

	/** key に当てはまるものを amount 個取りのけた持ち物（燃料を数えるときに、焼く分を燃やさないため）。 */
	public static Map<String, Integer> without(Map<String, Integer> inventory, String key, int amount) {
		Map<String, Integer> rest = new LinkedHashMap<>(inventory);
		int left = amount;
		for (String id : List.copyOf(rest.keySet())) {
			if (left <= 0) {
				break;
			}
			if (Items.matches(key, id)) {
				int n = Math.min(left, rest.get(id));
				left -= n;
				rest.merge(id, -n, Integer::sum);
				rest.remove(id, 0);
			}
		}
		return rest;
	}

	/** 持っている燃料で、あと何個焼けるか。 */
	public static int fuelCapacity(Map<String, Integer> inventory) {
		long ticks = 0;
		for (var e : inventory.entrySet()) {
			ticks += (long) burnTicks(e.getKey()) * e.getValue();
		}
		return (int) Math.min(Integer.MAX_VALUE, ticks / TICKS_PER_ITEM);
	}

	/** items 個焼くのに、燃料が何個分足りないか。足りていれば 0。 */
	public static int fuelShortage(Map<String, Integer> inventory, int items) {
		return Math.max(0, items - fuelCapacity(inventory));
	}

	/** 足りない分（items 個分）を原木でまかなうなら、何本要るか。 */
	public static int logsFor(int items) {
		int perLog = burnTicks("oak_log");
		return (items * TICKS_PER_ITEM + perLog - 1) / perLog;
	}

	/**
	 * 次にかまどへ入れる燃料を1つ選ぶ。あと items 個焼くのにちょうどよい大きさのものを選び、
	 * 1個だけ焼くのに石炭を使うようなむだを減らす（使い切れる中でいちばん長く燃えるもの。なければいちばん短いもの）。
	 */
	public static Optional<String> pickFuel(Map<String, Integer> inventory, int items) {
		int need = Math.max(1, items) * TICKS_PER_ITEM;
		String best = null;
		for (Fuel fuel : FUELS) {
			for (var e : inventory.entrySet()) {
				if (e.getValue() <= 0 || !Items.matches(fuel.key(), e.getKey())) {
					continue;
				}
				if (best == null || isBetterFuel(fuel.ticks(), burnTicks(best), need)) {
					best = e.getKey();
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean isBetterFuel(int ticks, int bestTicks, int need) {
		boolean fits = ticks <= need;
		boolean bestFits = bestTicks <= need;
		if (fits != bestFits) {
			return fits;
		}
		return fits ? ticks > bestTicks : ticks < bestTicks;
	}

	private static int burnTicks(String id) {
		for (Fuel fuel : FUELS) {
			if (Items.matches(fuel.key(), id)) {
				return fuel.ticks();
			}
		}
		return 0;
	}
}
