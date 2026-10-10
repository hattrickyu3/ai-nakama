package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Terrain;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 階段状に1段ずつ掘り下げる。地表に石や鉱石が見えていないときに、地下へ取りに行くために使う。
 * 階段にするのは、掘ったあと歩いて戻ってこられるようにするため。
 * 水・溶岩・空洞・自然のものではないブロックに当たったら、向きを変える。
 */
final class Digger {
	enum Outcome {
		/** 掘っている・動いている途中。 */
		WORKING,
		/** 1段下りた。 */
		STEPPED,
		/** 四方とも進めない。 */
		BLOCKED,
		/** ツルハシが要る。 */
		NEED_PICKAXE
	}

	private static final int MOVE_TIMEOUT_TICKS = 30;

	private final Breaker breaker = new Breaker();
	private Direction direction = Direction.EAST;
	private BlockPos nextFeet;
	private List<BlockPos> cells;
	private int turns;
	private int moveTicks;
	/** 直前に壊したブロックが落としたもの。呼び出し側が受け取る。 */
	private List<ItemStack> lastDrops = List.of();

	List<ItemStack> takeDrops() {
		List<ItemStack> drops = lastDrops;
		lastDrops = List.of();
		return drops;
	}

	Outcome tick(NakamaEntity nakama, ServerLevel level) {
		if (cells == null && !planStep(nakama, level)) {
			return turns >= 4 ? Outcome.BLOCKED : Outcome.WORKING;
		}
		for (BlockPos cell : cells) {
			BlockState state = level.getBlockState(cell);
			if (Terrain.isOpen(state)) {
				continue;
			}
			if (!Terrain.isGround(state) || Terrain.touchesFluid(level, cell)) {
				// 掘っている間に様子が変わった（水が流れてきたなど）ので、この向きはやめる
				breaker.cancel(nakama, level);
				cells = null;
				turn();
				return Outcome.WORKING;
			}
			if (Breaker.lacksTool(nakama, state)) {
				breaker.cancel(nakama, level);
				if (!Inv.hasPickaxe(nakama.getInventory())) {
					return Outcome.NEED_PICKAXE;
				}
				// ツルハシはあるが、この硬さは掘れない：この向きはやめる
				cells = null;
				turn();
				return Outcome.WORKING;
			}
			List<ItemStack> drops = breaker.tick(nakama, level, cell);
			if (drops != null) {
				lastDrops = drops;
			}
			return Outcome.WORKING;
		}
		// 3マスとも開いたので、1段下へ移る
		if (!nakama.blockPosition().equals(nextFeet)) {
			if (moveTicks++ == 0) {
				nakama.getNavigation().moveTo(nextFeet.getX() + 0.5, nextFeet.getY(), nextFeet.getZ() + 0.5, NakamaEntity.WALK_SPEED);
			} else if (moveTicks > MOVE_TIMEOUT_TICKS) {
				nakama.snapTo(nextFeet.getX() + 0.5, nextFeet.getY(), nextFeet.getZ() + 0.5, nakama.getYRot(), nakama.getXRot());
			}
			return Outcome.WORKING;
		}
		cells = null;
		turns = 0;
		return Outcome.STEPPED;
	}

	/** 次の1段で壊す3マスを決める。この向きに進めなければ向きを変えて false。 */
	private boolean planStep(NakamaEntity nakama, ServerLevel level) {
		BlockPos feet = nakama.blockPosition();
		BlockPos ahead = feet.relative(direction);
		BlockPos down = ahead.below();
		List<BlockPos> plan = List.of(ahead.above(), ahead, down);
		for (BlockPos cell : plan) {
			BlockState state = level.getBlockState(cell);
			boolean passable = Terrain.isOpen(state) || Terrain.isGround(state);
			if (!passable || Terrain.touchesFluid(level, cell)) {
				turn();
				return false;
			}
		}
		// 下りた先の足元が抜けている（洞窟の天井など）と落ちてしまうので進まない
		BlockState floor = level.getBlockState(down.below());
		if (floor.canBeReplaced() || !floor.getFluidState().isEmpty()) {
			turn();
			return false;
		}
		cells = plan;
		nextFeet = down;
		moveTicks = 0;
		return true;
	}

	private void turn() {
		direction = direction.getClockWise();
		turns++;
	}

	void cancel(NakamaEntity nakama, ServerLevel level) {
		breaker.cancel(nakama, level);
	}
}
