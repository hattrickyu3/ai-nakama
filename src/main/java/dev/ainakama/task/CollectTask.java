package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Sources;
import dev.ainakama.world.Terrain;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 資源を集める。近くで探す → 歩く → 掘る → 持ち物に入れる、を数がそろうまで繰り返す。
 * ・道具が要るのに持っていなければ「先に道具を作って」と返す
 * ・地表に見当たらない石や鉱石は、階段状に掘り下げて取りに行く
 * ・高くて届かない原木は、足場を積んで取る
 */
final class CollectTask implements Task {
	private static final int SEARCH_RADIUS = 24;
	private static final int WIDE_SEARCH_RADIUS = 56;
	private static final int SEARCH_HEIGHT = 8;
	private static final double REACH = 4.5;
	private static final int HOME_PROTECT_RADIUS = 8;
	private static final int STUCK_TICKS = 100;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 8;
	private static final int MAX_DIG_STEPS = 60;
	/** 真下〜斜め下に立っていて、高さだけが足りないとみなす水平の距離。 */
	private static final double UNDER_DISTANCE = 2.5;

	private final String item;
	private final String group;
	private final int count;
	private final Predicate<BlockState> matcher;
	private final Set<BlockPos> skipped = new HashSet<>();
	private final Walker walker = new Walker(STUCK_TICKS);
	private final Breaker breaker = new Breaker();
	private final Scaffold scaffold = new Scaffold();

	private Digger digger;
	private boolean searchDue;
	private int digSteps;
	private int gathered;
	private int ticks;
	private BlockPos target;

	CollectTask(String item, int count) {
		this.item = item;
		this.group = Items.groupOf(item);
		this.count = count;
		this.matcher = Sources.matcher(item);
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (gathered >= count) {
			return finish(nakama, level, DONE);
		}
		if (++ticks > TIME_LIMIT_TICKS) {
			return finish(nakama, level, failed("を集めるのに時間がかかりすぎた"));
		}
		if (digger != null && !searchDue) {
			// 掘り下げている間は、1段下りるごとにだけ探し直す（毎tick探すと重い）
			return digFor(nakama, level);
		}
		if (target == null || !matcher.test(level.getBlockState(target))) {
			breaker.cancel(nakama, level);
			searchDue = false;
			target = findNearest(nakama, level, SEARCH_RADIUS);
			if (target == null && digger == null) {
				// 近くになければ、もっと遠くまで見渡して歩いていく
				target = findNearest(nakama, level, WIDE_SEARCH_RADIUS);
			}
			if (target == null) {
				return digFor(nakama, level);
			}
		}
		if (digger != null) {
			digger.cancel(nakama, level);
			digger = null;
		}
		BlockState state = level.getBlockState(target);
		if (Breaker.lacksTool(nakama, state)) {
			return needTool(state);
		}
		Vec3 center = Vec3.atCenterOf(target);
		double dist = nakama.getEyePosition().distanceTo(center);
		if (dist > REACH) {
			breaker.cancel(nakama, level);
			if (isRightUnder(nakama, center) && scaffold.raise(nakama, level, Set.of())) {
				return RUNNING;
			}
			if (!walker.step(nakama, target, 1, 20, dist)) {
				skipped.add(target);
				target = null;
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		List<ItemStack> drops = breaker.tick(nakama, level, target);
		if (drops == null) {
			return RUNNING;
		}
		target = null;
		return take(nakama, level, drops);
	}

	/** 見えるところにないので、掘り下げて探す。掘り下げても意味のないもの（原木など）はここで失敗にする。 */
	private Result digFor(NakamaEntity nakama, ServerLevel level) {
		if (!Sources.isUnderground(item)) {
			return finish(nakama, level, failed("が近くに見つからない"));
		}
		if (digger == null) {
			digger = new Digger();
		}
		switch (digger.tick(nakama, level)) {
			case WORKING -> {
				List<ItemStack> drops = digger.takeDrops();
				return drops.isEmpty() ? RUNNING : take(nakama, level, drops);
			}
			case STEPPED -> {
				if (++digSteps > MAX_DIG_STEPS) {
					return finish(nakama, level, failed("は掘り下げても見つからなかった"));
				}
				searchDue = true;
				return RUNNING;
			}
			case NEED_PICKAXE -> {
				return new Result.Needs(List.of(new Command.Craft("wooden_pickaxe", 1)), "ツルハシがないと掘れない");
			}
			default -> {
				return finish(nakama, level, failed("を掘りに行けない（水や溶岩、空洞に囲まれている）"));
			}
		}
	}

	private Result needTool(BlockState state) {
		String tool = Sources.toolFor(state);
		if (tool == null) {
			return new Result.Failed(Items.label(item) + "を掘れる道具がない");
		}
		return new Result.Needs(List.of(new Command.Craft(tool, 1)), Items.label(tool) + "がないと掘れない");
	}

	private Result take(NakamaEntity nakama, ServerLevel level, List<ItemStack> drops) {
		for (ItemStack drop : drops) {
			if (Items.matches(group, Inv.idOf(drop.getItem()))) {
				gathered += drop.getCount();
			}
		}
		if (!Breaker.stash(nakama, level, drops)) {
			return finish(nakama, level, new Result.Failed("持ち物がいっぱい（「しまって」か「渡して」と言ってね）"));
		}
		return gathered >= count ? finish(nakama, level, DONE) : RUNNING;
	}

	private boolean isRightUnder(NakamaEntity nakama, Vec3 center) {
		double dx = center.x - nakama.getX();
		double dz = center.z - nakama.getZ();
		return Math.sqrt(dx * dx + dz * dz) <= UNDER_DISTANCE && center.y > nakama.getEyeY();
	}

	private BlockPos findNearest(NakamaEntity nakama, ServerLevel level, int radius) {
		BlockPos origin = nakama.blockPosition();
		BlockPos home = nakama.home();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		boolean logs = Items.isLog(item);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				p.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
				if (!level.isLoaded(p)) {
					// 読み込まれていない遠くの土地は、見に行かない
					continue;
				}
				for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
					p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					// 上下の移動は手間がかかるので、高さの違いを重く数える
					double d = dx * dx + dz * dz + dy * dy * 4.0;
					if (d >= bestDist || skipped.contains(p) || !matcher.test(level.getBlockState(p))) {
						continue;
					}
					if (home != null && home.distSqr(p) < HOME_PROTECT_RADIUS * HOME_PROTECT_RADIUS) {
						continue;
					}
					if (logs && !Sources.isNaturalLog(level, p)) {
						// 葉っぱのない原木はプレイヤーが置いたものかもしれないので切らない
						continue;
					}
					if (!logs && !Terrain.isExposed(level, p)) {
						// まわりが全部埋まっているものには手が届かない（掘り下げて探す）
						continue;
					}
					if (!logs && Terrain.isHardGround(level.getBlockState(p)) && !Terrain.isEmbedded(level, p)) {
						// 壁のように立っている石は、プレイヤーの建物かもしれないので掘らない
						continue;
					}
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		return best;
	}

	private Result.Failed failed(String what) {
		return new Result.Failed(Items.label(item) + what + "（" + gathered + "/" + count + "個）");
	}

	/** 終わるとき（成功でも失敗でも）、足場を片付けてから結果を返す。 */
	private Result finish(NakamaEntity nakama, ServerLevel level, Result result) {
		cancel(nakama, level);
		return result;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		breaker.cancel(nakama, level);
		if (digger != null) {
			digger.cancel(nakama, level);
		}
		scaffold.removeAll(nakama, level);
	}

	@Override
	public String describe() {
		return Items.label(item) + "を集める（" + gathered + "/" + count + "）";
	}
}
