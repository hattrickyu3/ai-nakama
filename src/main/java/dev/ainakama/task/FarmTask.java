package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Placer;
import dev.ainakama.world.Terrain;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 畑の世話をする。育ちきった作物を収穫して同じものを植え直し、空いている畑には持っている種を植える。
 * 育っている途中の作物には触らない。畑を新しく耕すことはしない。
 */
final class FarmTask implements Task {
	private static final int RADIUS = 16;
	private static final int HEIGHT = 4;
	private static final double REACH = 3.5;
	private static final int STUCK_TICKS = 100;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 6;
	/** 空いている畑に植えるもの（持ち物のID）。先に書いたものから使う。 */
	private static final List<String> SEEDS = List.of("wheat_seeds", "carrot", "potato", "beetroot_seeds");

	private final Walker walker = new Walker(STUCK_TICKS);
	private final Breaker breaker = new Breaker();
	private final Set<BlockPos> skipped = new HashSet<>();
	/** 畑を探す中心。最初は自分のいる場所（畑がなければ拠点）、そのあとは最後に手を入れた場所。 */
	private BlockPos center;
	/** 次に手を入れる場所（作物のあるマス。空いている畑なら、土の1つ上）。 */
	private BlockPos target;
	private boolean sawFarm;
	private boolean triedHome;
	private int harvested;
	private int planted;
	private int ticks;

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (++ticks > TIME_LIMIT_TICKS) {
			breaker.cancel(nakama, level);
			return new Result.Failed("畑仕事に時間がかかりすぎた（収穫 " + harvested + "か所）");
		}
		if (center == null) {
			center = nakama.blockPosition();
		}
		if (target == null) {
			target = findWork(nakama, level);
			if (target == null && !sawFarm && !triedHome && nakama.home() != null) {
				// 今いる場所のまわりに畑がなければ、拠点のまわりを見る
				triedHome = true;
				center = nakama.home();
				return RUNNING;
			}
			if (target == null) {
				return finish(nakama);
			}
		}
		BlockState state = level.getBlockState(target);
		boolean ripe = Terrain.isRipeCrop(state);
		if (!ripe && !isEmptyPlot(level, target)) {
			// 向かっている間に様子が変わった
			target = null;
			return RUNNING;
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(target));
		if (dist > REACH) {
			breaker.cancel(nakama, level);
			if (!walker.step(nakama, target, 1, 20, dist)) {
				skipped.add(target);
				target = null;
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		BlockPos pos = target;
		// 次は、いま手を入れた場所のまわりを探す（最初に立っていた場所からは遠い、畑の向こう側も見落とさないため）
		center = pos;
		if (!ripe) {
			target = null;
			sow(nakama, level, pos);
			return RUNNING;
		}
		List<ItemStack> drops = breaker.tick(nakama, level, pos);
		if (drops == null) {
			return RUNNING;
		}
		target = null;
		harvested++;
		boolean allFit = Breaker.stash(nakama, level, drops);
		replant(nakama, level, pos, state.getBlock());
		return allFit ? RUNNING : new Result.Failed("持ち物がいっぱい（「しまって」か「渡して」と言ってね）");
	}

	/** いちばん近い、収穫できる作物。なければ、種を植えられる空いた畑。どちらもなければ null。 */
	private BlockPos findWork(NakamaEntity nakama, ServerLevel level) {
		boolean hasSeed = seedSlot(nakama) >= 0;
		BlockPos ripe = null;
		BlockPos plot = null;
		double ripeDist = Double.MAX_VALUE;
		double plotDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-RADIUS, -HEIGHT, -RADIUS), center.offset(RADIUS, HEIGHT, RADIUS))) {
			if (!level.isLoaded(p)) {
				continue;
			}
			BlockState state = level.getBlockState(p);
			if (!(state.getBlock() instanceof CropBlock) && !(state.getBlock() instanceof FarmlandBlock)) {
				continue;
			}
			sawFarm = true;
			double d = p.distSqr(nakama.blockPosition());
			if (Terrain.isRipeCrop(state)) {
				if (d < ripeDist && !skipped.contains(p)) {
					ripe = p.immutable();
					ripeDist = d;
				}
			} else if (hasSeed && d < plotDist && isEmptyPlot(level, p.above()) && !skipped.contains(p.above())) {
				plot = p.above();
				plotDist = d;
			}
		}
		return ripe != null ? ripe : plot;
	}

	/** 何も植わっていない畑の上のマスか。 */
	private static boolean isEmptyPlot(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).getBlock() instanceof FarmlandBlock;
	}

	/** 収穫したあとに、同じ作物を植え直す。種が手元になければ空けておく。 */
	private void replant(NakamaEntity nakama, ServerLevel level, BlockPos pos, Block crop) {
		String seed = Inv.idOf(crop.asItem());
		BlockState young = crop.defaultBlockState();
		if (Inv.count(nakama.getInventory(), seed) > 0 && young.canSurvive(level, pos) && Placer.placeExact(level, pos, young)) {
			Inv.remove(nakama.getInventory(), seed, 1);
			planted++;
		}
	}

	private void sow(NakamaEntity nakama, ServerLevel level, BlockPos pos) {
		int slot = seedSlot(nakama);
		if (slot < 0) {
			return;
		}
		ItemStack stack = nakama.getInventory().getItem(slot);
		BlockState young = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
		if (young.canSurvive(level, pos) && Placer.placeExact(level, pos, young)) {
			stack.shrink(1);
			nakama.getInventory().setChanged();
			nakama.swingArm();
			planted++;
		} else {
			skipped.add(pos);
		}
	}

	private static int seedSlot(NakamaEntity nakama) {
		for (String seed : SEEDS) {
			int slot = Inv.findSlot(nakama.getInventory(), seed);
			if (slot >= 0 && nakama.getInventory().getItem(slot).getItem() instanceof BlockItem) {
				return slot;
			}
		}
		return -1;
	}

	private Result finish(NakamaEntity nakama) {
		if (!sawFarm) {
			return new Result.Failed("近くに畑が見つからない（畑のそばで言うか、拠点の近くに畑を作ってね）");
		}
		if (harvested == 0 && planted == 0) {
			nakama.say("収穫できる作物は、まだなかったよ。");
		} else {
			nakama.say("作物を" + harvested + "か所で収穫して、" + planted + "か所に植えたよ。");
		}
		return DONE;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		breaker.cancel(nakama, level);
	}

	@Override
	public String describe() {
		return "畑の世話をする（収穫 " + harvested + "か所）";
	}
}
