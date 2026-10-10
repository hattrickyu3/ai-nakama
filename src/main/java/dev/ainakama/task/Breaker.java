package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * ブロックを1つ、時間をかけて壊す。ひびの表示と腕を振る動きも受け持つ。
 * 集める・掘り下げる・整地で共通に使う。
 */
final class Breaker {
	private static final int SWING_INTERVAL = 5;
	private static final int MAX_STAGE = 9;

	private BlockPos pos;
	private int progress;
	private int total;

	/**
	 * target を壊す作業を1tick進める。
	 *
	 * @return まだ壊している途中なら null。壊れたら落としたもの（持ち物にはまだ入れていない）
	 */
	List<ItemStack> tick(NakamaEntity nakama, ServerLevel level, BlockPos target) {
		BlockState state = level.getBlockState(target);
		ItemStack tool = Inv.bestToolFor(nakama.getInventory(), state);
		if (!target.equals(pos)) {
			cancel(nakama, level);
			pos = target.immutable();
			progress = 0;
			total = breakTicks(state, !tool.isEmpty());
			nakama.setItemSlot(EquipmentSlot.MAINHAND, tool.copyWithCount(1));
		}
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(target));
		progress++;
		if (progress % SWING_INTERVAL == 0) {
			nakama.swingArm();
		}
		level.destroyBlockProgress(nakama.getId(), target, Math.min(MAX_STAGE, progress * 10 / total));
		if (progress < total) {
			return null;
		}
		List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), nakama, tool);
		level.destroyBlock(target, false, nakama);
		level.destroyBlockProgress(nakama.getId(), target, -1);
		pos = null;
		return drops;
	}

	/** 途中でやめるとき、ひびの表示を消す。 */
	void cancel(NakamaEntity nakama, ServerLevel level) {
		if (pos != null) {
			level.destroyBlockProgress(nakama.getId(), pos, -1);
			pos = null;
		}
	}

	/** そのブロックは道具がないと何も落とさないのに、合う道具を持っていないか。 */
	static boolean lacksTool(NakamaEntity nakama, BlockState state) {
		return state.requiresCorrectToolForDrops() && Inv.bestToolFor(nakama.getInventory(), state).isEmpty();
	}

	/**
	 * 落としたものを持ち物に入れる。入りきらない分は足元に置く。
	 *
	 * @return 全部入ったら true
	 */
	static boolean stash(NakamaEntity nakama, ServerLevel level, List<ItemStack> drops) {
		boolean allFit = true;
		for (ItemStack drop : drops) {
			ItemStack rest = Inv.add(nakama.getInventory(), drop);
			if (!rest.isEmpty()) {
				Block.popResource(level, nakama.blockPosition(), rest);
				allFit = false;
			}
		}
		return allFit;
	}

	/** 硬さから掘る時間を決める。正しい道具があれば速い。 */
	private static int breakTicks(BlockState state, boolean hasTool) {
		float hardness = state.getBlock().defaultDestroyTime();
		int ticks = (int) (hardness * (hasTool ? 8 : 15));
		return Math.max(4, Math.min(60, ticks));
	}
}
