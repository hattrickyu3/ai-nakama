package dev.ainakama.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「これを何個作りたい」から、途中の材料も含めたクラフトの手順を決める。
 * 例：原木3本しか持っていないときに木のツルハシを頼まれたら、板材 → 棒 → 作業台 → ツルハシ の順に作る。
 * 足りない素材（原木・丸石など、作れないもの）は missing に入れて返し、集めるのは実行側に任せる。
 */
public final class CraftPlanner {
	private static final int MAX_DEPTH = 6;

	/** 1種類のクラフトをまとめて行う手順。consumed / produced は実際のIDごとの数。 */
	public record Step(String output, int times, Map<String, Integer> consumed, Map<String, Integer> produced) {
	}

	/**
	 * @param steps          実行する順のクラフト手順
	 * @param missing        足りない素材（キーはIDまたはグループ）
	 * @param inventoryAfter 手順をすべて行ったあとの持ち物
	 */
	public record Result(List<Step> steps, Map<String, Integer> missing, Map<String, Integer> inventoryAfter) {
		public boolean complete() {
			return missing.isEmpty();
		}
	}

	private final Map<String, Integer> inventory;
	private final boolean tableNearby;
	private final List<Step> steps = new ArrayList<>();
	private final Map<String, Integer> missing = new LinkedHashMap<>();
	private final Map<String, Integer> reserved = new LinkedHashMap<>();

	private CraftPlanner(Map<String, Integer> inventory, boolean tableNearby) {
		this.inventory = new LinkedHashMap<>(inventory);
		this.tableNearby = tableNearby;
	}

	/**
	 * @param inventory   今の持ち物（IDごとの数）。この Map は書き換えない
	 * @param target      作りたいもの（IDまたはグループ）
	 * @param count       持っていたい合計の数
	 * @param tableNearby 近くに作業台が置いてあるか
	 */
	public static Result plan(Map<String, Integer> inventory, String target, int count, boolean tableNearby) {
		CraftPlanner planner = new CraftPlanner(inventory, tableNearby);
		planner.ensure(Items.groupOf(target), count, 0);
		return new Result(List.copyOf(planner.steps), Map.copyOf(planner.missing), Map.copyOf(planner.inventory));
	}

	/** key を合計 count 個持っている状態にする。作れないものは missing に足す。 */
	private boolean ensure(String key, int count, int depth) {
		int have = available(key);
		if (have >= count) {
			return true;
		}
		int deficit = count - have;
		var recipe = Recipes.forOutput(key);
		if (recipe.isEmpty() || depth > MAX_DEPTH) {
			missing.merge(key, deficit, Integer::sum);
			return false;
		}
		var r = recipe.get();
		int times = (deficit + r.outputCount() - 1) / r.outputCount();
		Map<String, Integer> needs = new LinkedHashMap<>();
		if (r.needsTable() && !tableNearby) {
			needs.put("crafting_table", 1);
		}
		r.ingredients().forEach((ing, n) -> needs.merge(ing, n * times, Integer::sum));

		// 先にそろえた材料を、あとの材料づくりで使ってしまわないように取り置く
		Map<String, Integer> reservedHere = new LinkedHashMap<>();
		boolean ok = true;
		for (var need : needs.entrySet()) {
			if (ensure(need.getKey(), need.getValue(), depth + 1)) {
				reserved.merge(need.getKey(), need.getValue(), Integer::sum);
				reservedHere.put(need.getKey(), need.getValue());
			} else {
				ok = false;
			}
		}
		reservedHere.forEach((k, n) -> reserved.merge(k, -n, Integer::sum));
		if (!ok) {
			return false;
		}
		craft(r, times);
		return true;
	}

	private int available(String key) {
		return Items.count(inventory, key) - reserved.getOrDefault(key, 0);
	}

	private void craft(Recipes.Recipe r, int times) {
		Map<String, Integer> consumed = new LinkedHashMap<>();
		for (var ing : r.ingredients().entrySet()) {
			take(ing.getKey(), ing.getValue() * times, consumed);
		}
		Map<String, Integer> produced = new LinkedHashMap<>();
		if (Items.ANY_PLANKS.equals(r.output())) {
			// 板材の種類は使った原木で決まる
			for (var e : consumed.entrySet()) {
				produced.merge(Items.planksFor(e.getKey()), e.getValue() * r.outputCount(), Integer::sum);
			}
		} else {
			produced.put(r.output(), times * r.outputCount());
		}
		produced.forEach((id, n) -> inventory.merge(id, n, Integer::sum));
		steps.add(new Step(r.output(), times, Map.copyOf(consumed), Map.copyOf(produced)));
	}

	/** key に当てはまるものを、多く持っている種類から順に amount 個取り出す。 */
	private void take(String key, int amount, Map<String, Integer> consumed) {
		List<String> candidates = inventory.keySet().stream()
			.filter(id -> Items.matches(key, id) && inventory.get(id) > 0)
			.sorted(Comparator.comparing((String id) -> inventory.get(id)).reversed())
			.toList();
		int left = amount;
		for (String id : candidates) {
			if (left == 0) {
				break;
			}
			int n = Math.min(left, inventory.get(id));
			inventory.merge(id, -n, Integer::sum);
			if (inventory.get(id) == 0) {
				inventory.remove(id);
			}
			consumed.merge(id, n, Integer::sum);
			left -= n;
		}
		if (left > 0) {
			throw new IllegalStateException("not enough " + key + " after ensure: " + left);
		}
	}
}
