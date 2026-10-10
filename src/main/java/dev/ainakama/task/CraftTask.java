package dev.ainakama.task;

import dev.ainakama.AiNakama;
import dev.ainakama.core.Command;
import dev.ainakama.core.CraftPlanner;
import dev.ainakama.core.Items;
import dev.ainakama.core.Smelting;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * クラフトする。途中の材料（板材・棒・作業台）は {@link CraftPlanner} が決めた順に自動で作る。
 * 素材そのもの（原木・丸石・鉄インゴットなど）が足りなければ、チェストから出す・集める・焼く、のどれかを「先にやって」と返す。
 * クラフトでは作れず、集めるか焼くしかないもの（鉄インゴット・石炭など）を頼まれたときは、その作業に任せる。
 */
final class CraftTask implements Task {
	private static final int TABLE_SEARCH_RADIUS = 6;
	private static final int TICKS_PER_STEP = 10;

	private final String item;
	private final int count;
	private List<CraftPlanner.Step> steps;
	private int stepIndex;
	private int wait;
	/** クラフトでは作れないものを頼まれたときに、代わりに行う作業（集める・焼く）。 */
	private Task instead;

	CraftTask(String item, int count) {
		this.item = item;
		this.count = count;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (instead != null) {
			return instead.tick(nakama, level);
		}
		if (steps == null) {
			Result planned = plan(nakama, level);
			if (planned != null) {
				return planned;
			}
		}
		if (stepIndex >= steps.size()) {
			return DONE;
		}
		if (--wait > 0) {
			return RUNNING;
		}
		wait = TICKS_PER_STEP;
		nakama.swingArm();
		switch (apply(nakama, steps.get(stepIndex))) {
			case CHANGED -> {
				// 計画の途中で持ち物が変わった（プレイヤーが取ったなど）ので立て直す
				steps = null;
				return RUNNING;
			}
			case FULL -> {
				return new Result.Failed("持ち物がいっぱいで、作ったものを足元に置いた（「しまって」と言ってね）");
			}
			case OK -> {
			}
		}
		stepIndex++;
		return stepIndex >= steps.size() ? DONE : RUNNING;
	}

	/** 手順を決める。すぐに結果を返すべきとき（足りない・作れない）はその結果、続けられるなら null。 */
	private Result plan(NakamaEntity nakama, ServerLevel level) {
		Map<String, Integer> inv = Inv.counts(nakama.getInventory());
		String key = Items.groupOf(item);
		int want = Items.count(inv, key) + count;
		CraftPlanner.Result result;
		// 計画の途中で同じ材料を何度も聞かれるので、チェストを調べた結果は覚えておく（調べるたびに近くのチェストを全部開けるため）
		Map<String, Boolean> stocked = new HashMap<>();
		try {
			result = CraftPlanner.plan(inv, item, want, tableNearby(nakama, level),
				k -> stocked.computeIfAbsent(k, key2 -> Supplies.isStocked(key2, nakama, level)));
		} catch (RuntimeException e) {
			// 計画の計算に不具合があっても、ゲームを止めずに「作れなかった」で済ませる
			AiNakama.LOGGER.error("{} の作り方を計算できませんでした（持ち物: {}）", item, inv, e);
			return new Result.Failed(Items.label(item) + "の作り方を考えているうちに、わからなくなった");
		}
		if (!result.complete()) {
			if (result.missing().containsKey(key)) {
				return planInstead(nakama, level);
			}
			Supplies.Plan supplies = Supplies.plan(result.missing(), nakama, level);
			if (!supplies.ok()) {
				return new Result.Failed(Items.label(item) + "を作るのに" + Items.describeKey(supplies.unknown()) + "が"
					+ result.missing().get(supplies.unknown()) + "個足りない（集め方を知らない。渡してくれたら作れるよ）");
			}
			return new Result.Needs(supplies.commands(), Items.label(item) + "の材料が足りない");
		}
		steps = result.steps();
		stepIndex = 0;
		wait = TICKS_PER_STEP;
		return steps.isEmpty() ? DONE : null;
	}

	/**
	 * 頼まれたもの自体が、手持ちの材料からはクラフトできないとき。集めるか焼くかで手に入るなら、その作業に任せる。
	 * （ここで「先に集めて」と返すと、やり直したときに同じ数をもう一度作ろうとしてしまう）
	 */
	private Result planInstead(NakamaEntity nakama, ServerLevel level) {
		if (Items.isCollectable(item)) {
			instead = new CollectTask(item, count);
		} else if (Smelting.isProduct(item)) {
			instead = new SmeltTask(item, count);
		} else {
			return new Result.Failed(Items.label(item) + "の材料の集め方を知らない（材料を渡してくれたら作れるよ）");
		}
		return instead.tick(nakama, level);
	}

	/** 代わりに焼いている途中でやり直すときは、まだ焼けていない数だけを頼み直す。 */
	@Override
	public Command pinned(Command original) {
		if (instead != null && instead.pinned(original) instanceof Command.Smelt rest) {
			return new Command.Craft(item, rest.count());
		}
		return original;
	}

	private enum Applied {
		OK, CHANGED, FULL
	}

	private static Applied apply(NakamaEntity nakama, CraftPlanner.Step step) {
		for (var e : step.consumed().entrySet()) {
			if (Inv.count(nakama.getInventory(), e.getKey()) < e.getValue()) {
				return Applied.CHANGED;
			}
		}
		step.consumed().forEach((id, n) -> Inv.remove(nakama.getInventory(), id, n));
		boolean full = false;
		for (var e : step.produced().entrySet()) {
			var it = Inv.item(e.getKey());
			if (it.isEmpty()) {
				continue;
			}
			ItemStack rest = Inv.add(nakama.getInventory(), new ItemStack(it.get(), e.getValue()));
			if (!rest.isEmpty()) {
				// 入りきらない分はなくさずに足元に置く
				Block.popResource(nakama.level(), nakama.blockPosition(), rest);
				full = true;
			}
		}
		return full ? Applied.FULL : Applied.OK;
	}

	private static boolean tableNearby(NakamaEntity nakama, ServerLevel level) {
		BlockPos origin = nakama.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-TABLE_SEARCH_RADIUS, -2, -TABLE_SEARCH_RADIUS),
			origin.offset(TABLE_SEARCH_RADIUS, 2, TABLE_SEARCH_RADIUS))) {
			if (level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		if (instead != null) {
			instead.cancel(nakama, level);
		}
	}

	@Override
	public String describe() {
		if (instead != null) {
			return instead.describe();
		}
		return Items.label(item) + "を" + count + "個作る";
	}
}
