package dev.ainakama.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 「これを何個作りたい」から、途中の材料も含めたクラフトの手順を決める。
 * 例：原木3本しか持っていないときに木のツルハシを頼まれたら、板材 → 棒 → 作業台 → ツルハシ の順に作る。
 * 足りない素材（原木・丸石など、作れないもの）は missing に入れて返し、集めるのは実行側に任せる。
 * 作り方が何通りかあるものは、材料がそろう（または手に入れられる）作り方を選ぶ。
 * 「手に入れられる」のは、集められるもの・かまどで焼いて作れるもの・近くのチェストに入っているもの。
 */
public final class CraftPlanner {
	private static final int MAX_DEPTH = 6;

	/** 1種類のクラフトをまとめて行う手順。consumed / produced は実際のIDごとの数。 */
	public record Step(String output, int times, Map<String, Integer> consumed, Map<String, Integer> produced) {
	}

	/**
	 * @param steps          実行する順のクラフト手順
	 * @param missing        足りない素材（キーはID・グループ・any:a|b）
	 * @param inventoryAfter 手順をすべて行ったあとの持ち物
	 */
	public record Result(List<Step> steps, Map<String, Integer> missing, Map<String, Integer> inventoryAfter) {
		public boolean complete() {
			return missing.isEmpty();
		}
	}

	/** 計画の途中の状態。作り方の候補を試すときに、写しを取ってから進める。 */
	private static final class State {
		final Map<String, Integer> inventory;
		final List<Step> steps;
		final Map<String, Integer> missing;
		/** ほかの材料づくりで使ってしまわないように取り置いている数（実際のIDごと）。 */
		final Map<String, Integer> reserved;
		/** 堂々めぐりにぶつかった回数。ぶつかった作り方は、採用しない。 */
		int cycles;

		State(Map<String, Integer> inventory) {
			this(new LinkedHashMap<>(inventory), new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
		}

		private State(Map<String, Integer> inventory, List<Step> steps, Map<String, Integer> missing, Map<String, Integer> reserved) {
			this.inventory = inventory;
			this.steps = steps;
			this.missing = missing;
			this.reserved = reserved;
		}

		State copy() {
			State copy = new State(new LinkedHashMap<>(inventory), new ArrayList<>(steps), new LinkedHashMap<>(missing), new LinkedHashMap<>(reserved));
			copy.cycles = cycles;
			return copy;
		}

		/** key に当てはまるもののうち、取り置かれていない数。 */
		int available(String key) {
			int total = 0;
			for (var e : inventory.entrySet()) {
				if (Items.matches(key, e.getKey())) {
					total += free(e.getKey());
				}
			}
			return total;
		}

		int free(String id) {
			return Math.max(0, inventory.getOrDefault(id, 0) - reserved.getOrDefault(id, 0));
		}

		/** key に当てはまるものを amount 個、種類ごとに取り置く。どの種類を何個取り置いたかを返す。 */
		Map<String, Integer> reserve(String key, int amount) {
			Map<String, Integer> taken = new LinkedHashMap<>();
			int left = amount;
			for (String id : byFreeAmount(key)) {
				if (left == 0) {
					break;
				}
				int n = Math.min(left, free(id));
				if (n > 0) {
					taken.put(id, n);
					left -= n;
				}
			}
			taken.forEach((id, n) -> reserved.merge(id, n, Integer::sum));
			return taken;
		}

		void release(Map<String, Integer> taken) {
			taken.forEach((id, n) -> reserved.merge(id, -n, Integer::sum));
		}

		/** key に当てはまる種類を、取り置かれていない数の多い順に。 */
		List<String> byFreeAmount(String key) {
			return inventory.keySet().stream()
				.filter(id -> Items.matches(key, id) && free(id) > 0)
				.sorted(Comparator.comparing((String id) -> free(id)).reversed())
				.toList();
		}
	}

	private final boolean tableNearby;
	/** 近くのチェストに入っているか（キーで聞く）。 */
	private final Predicate<String> stocked;
	/** いま作ろうとしている途中のもの。鉄ブロック ⇄ 鉄インゴットのような堂々めぐりを止める。 */
	private final Set<String> inProgress = new HashSet<>();
	private State state;

	private CraftPlanner(Map<String, Integer> inventory, boolean tableNearby, Predicate<String> stocked) {
		this.state = new State(inventory);
		this.tableNearby = tableNearby;
		this.stocked = stocked;
	}

	/**
	 * @param inventory   今の持ち物（IDごとの数）。この Map は書き換えない
	 * @param target      作りたいもの（IDまたはグループ）
	 * @param count       持っていたい合計の数
	 * @param tableNearby 近くに作業台が置いてあるか
	 */
	public static Result plan(Map<String, Integer> inventory, String target, int count, boolean tableNearby) {
		return plan(inventory, target, count, tableNearby, key -> false);
	}

	/** @param stocked 近くのチェストに入っているものか（入っていれば、足りない材料として挙げてよい） */
	public static Result plan(Map<String, Integer> inventory, String target, int count, boolean tableNearby, Predicate<String> stocked) {
		CraftPlanner planner = new CraftPlanner(inventory, tableNearby, stocked);
		planner.ensure(Items.groupOf(target), count, 0);
		State s = planner.state;
		return new Result(List.copyOf(s.steps), Map.copyOf(s.missing), Map.copyOf(s.inventory));
	}

	/** key を合計 count 個持っている状態にする。作れないものは missing に足す。 */
	private boolean ensure(String key, int count, int depth) {
		int have = state.available(key);
		if (have >= count) {
			return true;
		}
		int deficit = count - have;
		List<Recipes.Recipe> candidates = Recipes.candidates(key);
		if (candidates.isEmpty() || depth > MAX_DEPTH) {
			state.missing.merge(key, deficit, Integer::sum);
			return false;
		}
		if (!inProgress.add(key)) {
			// 自分を作る途中で、また自分が要る（鉄インゴット ← 鉄ブロック ← 鉄インゴット）
			state.cycles++;
			state.missing.merge(key, deficit, Integer::sum);
			return false;
		}
		try {
			State before = state;
			State bestFailure = null;
			for (Recipes.Recipe r : candidates) {
				state = before.copy();
				if (tryRecipe(r, deficit, depth)) {
					if (state.available(key) >= count) {
						return true;
					}
					// 作ったが、ほしい種類にならなかった（オークの板材がほしいのに、シラカバの原木しかない、など）
					state.missing.merge(key, count - state.available(key), Integer::sum);
				}
				// 足りないものが全部「手に入れられるもの」なら、その作り方を採用する
				if (bestFailure == null && newMissingIsObtainable(before)) {
					bestFailure = state;
				}
			}
			// 材料として要るもので、集めたりチェストから出したりできるなら、作るよりそのほうが早い（石炭を石炭ブロックから作らない）
			boolean fetchInstead = depth > 0 && (Items.collectableFor(key).isPresent() || stocked.test(key));
			if (bestFailure != null && !fetchInstead) {
				state = bestFailure;
			} else {
				// 作るより取ってくるほうが早いか、どの作り方でも材料の入手方法がわからない：このもの自体を「足りない」とする
				state = before;
				state.missing.merge(key, deficit, Integer::sum);
			}
			return false;
		} finally {
			inProgress.remove(key);
		}
	}

	private boolean newMissingIsObtainable(State before) {
		if (state.cycles > before.cycles) {
			return false;
		}
		for (var e : state.missing.entrySet()) {
			boolean isNew = e.getValue() > before.missing.getOrDefault(e.getKey(), 0);
			if (isNew && !isObtainable(e.getKey())) {
				return false;
			}
		}
		return true;
	}

	private boolean isObtainable(String key) {
		return Items.collectableFor(key).isPresent() || Smelting.canMake(key) || stocked.test(key);
	}

	private boolean tryRecipe(Recipes.Recipe r, int deficit, int depth) {
		int times = (deficit + r.outputCount() - 1) / r.outputCount();
		Map<String, Integer> needs = new LinkedHashMap<>();
		if (r.needsTable() && !tableNearby) {
			needs.put("crafting_table", 1);
		}
		r.ingredients().forEach((ing, n) -> needs.merge(ing, n * times, Integer::sum));

		// 先にそろえた材料を、あとの材料づくりで使ってしまわないように取り置く
		// 板材は「オークの板材」「板材ならどれでも」のように別のキーで重なって数えられるので、取り置きは実際の種類ごとに行う
		List<Map<String, Integer>> reservedHere = new ArrayList<>();
		boolean ok = true;
		for (var need : needs.entrySet()) {
			if (ensure(need.getKey(), need.getValue(), depth + 1)) {
				reservedHere.add(state.reserve(need.getKey(), need.getValue()));
			} else {
				ok = false;
			}
		}
		reservedHere.forEach(state::release);
		if (!ok) {
			return false;
		}
		craft(r, times);
		return true;
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
		produced.forEach((id, n) -> state.inventory.merge(id, n, Integer::sum));
		state.steps.add(new Step(r.output(), times, Map.copyOf(consumed), Map.copyOf(produced)));
	}

	/** key に当てはまるものを、取り置かれていない分の多い種類から順に amount 個取り出す。 */
	private void take(String key, int amount, Map<String, Integer> consumed) {
		Map<String, Integer> inventory = state.inventory;
		int left = amount;
		for (String id : state.byFreeAmount(key)) {
			if (left == 0) {
				break;
			}
			int n = Math.min(left, state.free(id));
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
