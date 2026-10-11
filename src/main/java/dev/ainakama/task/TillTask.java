package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Place;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Terrain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 地面を耕して、新しい畑にする。
 * 耕すのは、上に何もない土と草ブロックだけ（プレイヤーが置いたほかのブロックには手を出さない）。
 * 水のそば（4マス以内）の地面を先に選ぶ。水が近くになくても耕すが、作物の育ちは遅くなる。
 * 種をまくのは FarmTask。
 */
final class TillTask implements Task {
	private static final int RADIUS = 5;
	private static final int UP = 2;
	private static final int DOWN = 3;
	private static final int WATER_RANGE = 4;
	private static final double REACH = 3.5;
	private static final int STUCK_TICKS = 100;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 4;

	private final BlockPos center;
	private final int count;
	private final Walker walker = new Walker(STUCK_TICKS);
	private Deque<BlockPos> plots;
	private BlockPos target;
	private boolean nearWater;
	private int tilled;
	private int ticks;

	TillTask(BlockPos center, int count) {
		this.center = center;
		this.count = count;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		ItemStack hoe = hoe(nakama);
		if (hoe.isEmpty()) {
			return new Result.Needs(List.of(new Command.Craft("wooden_hoe", 1)), "クワがないと耕せない");
		}
		if (++ticks > TIME_LIMIT_TICKS) {
			return finish(nakama);
		}
		if (plots == null) {
			plots = choosePlots(level);
		}
		while (target == null && tilled < count && !plots.isEmpty()) {
			BlockPos next = plots.pollFirst();
			if (isTillable(level, next)) {
				target = next;
			}
		}
		if (target == null) {
			return finish(nakama);
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(target));
		if (dist > REACH) {
			if (!walker.step(nakama, target, 1, 20, dist)) {
				target = null;
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		nakama.setItemSlot(EquipmentSlot.MAINHAND, hoe.copyWithCount(1));
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(target));
		till(nakama, level, target);
		target = null;
		return RUNNING;
	}

	private void till(NakamaEntity nakama, ServerLevel level, BlockPos pos) {
		if (!level.getBlockState(pos.above()).isAir()) {
			// 上に生えている草や花をどける
			level.destroyBlock(pos.above(), true);
		}
		level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
		level.playSound(null, pos, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
		nakama.swingArm();
		tilled++;
	}

	/** 耕す場所を決める。水のそばを先に、そのうえで中心に近い順。 */
	private Deque<BlockPos> choosePlots(ServerLevel level) {
		record Plot(BlockPos pos, boolean wet) {
		}
		List<Plot> found = new ArrayList<>();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				for (int dy = UP; dy >= -DOWN; dy--) {
					BlockPos p = center.offset(dx, dy, dz);
					BlockState state = level.getBlockState(p);
					if (state.isAir() || state.canBeReplaced()) {
						continue;
					}
					// その列でいちばん上の地面だけを見る
					if (isTillable(level, p)) {
						found.add(new Plot(p, hasWaterNear(level, p)));
					}
					break;
				}
			}
		}
		found.sort(Comparator.comparing((Plot p) -> !p.wet()).thenComparingDouble(p -> p.pos().distSqr(center)));
		nearWater = found.stream().limit(count).anyMatch(Plot::wet);
		Deque<BlockPos> queue = new ArrayDeque<>();
		found.forEach(p -> queue.addLast(p.pos()));
		return queue;
	}

	/** 上に何もない（草や花だけの）、土か草ブロックか。 */
	private static boolean isTillable(ServerLevel level, BlockPos pos) {
		String id = Terrain.idOf(level.getBlockState(pos));
		BlockState above = level.getBlockState(pos.above());
		return (id.equals("grass_block") || id.equals("dirt"))
			&& (above.isAir() || above.canBeReplaced()) && above.getFluidState().isEmpty();
	}

	private static boolean hasWaterNear(ServerLevel level, BlockPos pos) {
		for (BlockPos p : BlockPos.betweenClosed(pos.offset(-WATER_RANGE, 0, -WATER_RANGE), pos.offset(WATER_RANGE, 1, WATER_RANGE))) {
			if (level.getFluidState(p).is(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	private static ItemStack hoe(NakamaEntity nakama) {
		var inv = nakama.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && Inv.idOf(s.getItem()).endsWith("_hoe")) {
				return s;
			}
		}
		return ItemStack.EMPTY;
	}

	private Result finish(NakamaEntity nakama) {
		nakama.getNavigation().stop();
		if (tilled == 0) {
			return new Result.Failed("耕せる地面（土か草）が近くにない");
		}
		nakama.say(tilled + "マス耕したよ。" + (nearWater ? "" : "近くに水がないから、作物が育つのはゆっくりだよ（水のそばだと早く育つよ）。"));
		return DONE;
	}

	/** クワを作ってからやり直すときも、同じ場所の残りを耕す。 */
	@Override
	public Command pinned(Command original) {
		return new Command.Till(new Place.Coords(center.getX(), center.getY(), center.getZ()), Math.max(1, count - tilled));
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}

	@Override
	public String describe() {
		return "畑を耕す（" + tilled + "/" + count + "）";
	}
}
