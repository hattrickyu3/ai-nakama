package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Sources;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 資源を集める。近くで探す → 歩く → 掘る → 持ち物に入れる、を数がそろうまで繰り返す。
 * 道具が要るのに持っていなければ「先に道具を作って」と返す。
 */
final class CollectTask implements Task {
	private static final int SEARCH_RADIUS = 24;
	private static final int SEARCH_HEIGHT = 8;
	private static final double REACH = 4.5;
	private static final int HOME_PROTECT_RADIUS = 8;
	private static final int STUCK_TICKS = 120;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 5;

	private final String item;
	private final String group;
	private final int count;
	private final Predicate<BlockState> matcher;
	private final Set<BlockPos> skipped = new HashSet<>();
	private final Walker walker = new Walker(STUCK_TICKS);

	private int gathered;
	private int ticks;
	private BlockPos target;
	private int breakProgress = -1;
	private int breakTotal;

	CollectTask(String item, int count) {
		this.item = item;
		this.group = Items.groupOf(item);
		this.count = count;
		this.matcher = Sources.matcher(item);
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (gathered >= count) {
			return DONE;
		}
		if (++ticks > TIME_LIMIT_TICKS) {
			return new Result.Failed(Items.label(item) + "を集めるのに時間がかかりすぎた（" + gathered + "/" + count + "個）");
		}
		if (target == null || !matcher.test(level.getBlockState(target))) {
			resetBreaking(nakama, level);
			target = findNearest(nakama, level);
			if (target == null) {
				return new Result.Failed("近くに" + Items.label(item) + "が見つからない（" + gathered + "/" + count + "個）");
			}
		}
		BlockState state = level.getBlockState(target);
		if (state.requiresCorrectToolForDrops() && Inv.bestToolFor(nakama.getInventory(), state).isEmpty()) {
			String tool = Sources.toolFor(state);
			if (tool == null) {
				return new Result.Failed(Items.label(item) + "を掘れる道具がない");
			}
			return new Result.Needs(List.of(new Command.Craft(tool, 1)), Items.label(tool) + "がないと掘れない");
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(target));
		if (dist > REACH) {
			resetBreaking(nakama, level);
			if (!walker.step(nakama, target, 1, 20, dist)) {
				skipped.add(target);
				target = null;
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(target));
		return breakStep(nakama, level, state);
	}

	private Result breakStep(NakamaEntity nakama, ServerLevel level, BlockState state) {
		ItemStack tool = Inv.bestToolFor(nakama.getInventory(), state);
		if (breakProgress < 0) {
			breakProgress = 0;
			breakTotal = breakTicks(state, !tool.isEmpty());
			nakama.setItemSlot(EquipmentSlot.MAINHAND, tool.copyWithCount(1));
		}
		breakProgress++;
		if (breakProgress % 5 == 0) {
			nakama.swingArm();
		}
		level.destroyBlockProgress(nakama.getId(), target, Math.min(9, breakProgress * 10 / breakTotal));
		if (breakProgress < breakTotal) {
			return RUNNING;
		}
		List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), nakama, tool);
		level.destroyBlock(target, false, nakama);
		resetBreaking(nakama, level);
		target = null;
		for (ItemStack drop : drops) {
			if (Items.matches(group, Inv.idOf(drop.getItem()))) {
				gathered += drop.getCount();
			}
			ItemStack rest = Inv.add(nakama.getInventory(), drop);
			if (!rest.isEmpty()) {
				Block.popResource(level, nakama.blockPosition(), rest);
				return new Result.Failed("持ち物がいっぱい（「チェストにしまって」と言ってね）");
			}
		}
		return gathered >= count ? DONE : RUNNING;
	}

	/** 硬さから掘る時間を決める。正しい道具があれば速い。 */
	private static int breakTicks(BlockState state, boolean hasTool) {
		float hardness = state.getBlock().defaultDestroyTime();
		int ticks = (int) (hardness * (hasTool ? 8 : 15));
		return Math.max(6, Math.min(60, ticks));
	}

	private BlockPos findNearest(NakamaEntity nakama, ServerLevel level) {
		BlockPos origin = nakama.blockPosition();
		BlockPos home = nakama.home();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
			for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					// 上下の移動は手間がかかるので、高さの違いを重く数える
					double d = dx * dx + dz * dz + dy * dy * 4.0;
					if (d >= bestDist || skipped.contains(p) || !matcher.test(level.getBlockState(p))) {
						continue;
					}
					if (home != null && home.distSqr(p) < HOME_PROTECT_RADIUS * HOME_PROTECT_RADIUS) {
						continue;
					}
					best = p.immutable();
					bestDist = d;
				}
			}
		}
		return best;
	}

	private void resetBreaking(NakamaEntity nakama, ServerLevel level) {
		if (breakProgress >= 0 && target != null) {
			level.destroyBlockProgress(nakama.getId(), target, -1);
		}
		breakProgress = -1;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		resetBreaking(nakama, level);
	}

	@Override
	public String describe() {
		return Items.label(item) + "を集める（" + gathered + "/" + count + "）";
	}
}
