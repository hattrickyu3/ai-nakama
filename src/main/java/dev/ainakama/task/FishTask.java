package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Terrain;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 釣りをする。釣り竿を持って水辺に立ち、しばらく待つと魚が釣れる。
 * 本物の浮きは投げず、待ち時間（5〜15秒）と釣れる魚の割合（タラ60・サケ25・フグ13・熱帯魚2）をまねている。魚以外（宝物やごみ）は釣れない。
 */
final class FishTask implements Task {
	private static final int SEARCH_RADIUS = 24;
	private static final int SEARCH_HEIGHT = 6;
	private static final double CAST_DISTANCE = 4.0;
	private static final int MIN_WAIT = 100;
	private static final int MAX_WAIT = 300;
	private static final int STUCK_TICKS = 120;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 6;
	/** 釣れる魚と、その出やすさ。 */
	private static final List<String> FISH = List.of("cod", "salmon", "pufferfish", "tropical_fish");
	private static final int[] WEIGHTS = {60, 25, 13, 2};

	private final int count;
	private final Walker walker = new Walker(STUCK_TICKS);
	private BlockPos water;
	private int wait;
	private int caught;
	private int ticks;

	FishTask(int count) {
		this.count = count;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (caught >= count) {
			return finish(nakama, DONE);
		}
		int rodSlot = Inv.findSlot(nakama.getInventory(), "fishing_rod");
		if (rodSlot < 0) {
			return finish(nakama, new Result.Needs(List.of(new Command.Craft("fishing_rod", 1)), "釣り竿がないと釣りができない"));
		}
		if (++ticks > TIME_LIMIT_TICKS) {
			return finish(nakama, new Result.Failed("釣りに時間がかかりすぎた（" + caught + "/" + count + "）"));
		}
		if (water == null) {
			water = findWater(nakama, level);
			if (water == null) {
				return finish(nakama, new Result.Failed("釣りのできる水辺が近くに見つからない"));
			}
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(water));
		if (dist > CAST_DISTANCE) {
			wait = 0;
			if (!walker.step(nakama, water, 2, 20, dist)) {
				return finish(nakama, new Result.Failed("水辺にたどり着けない"));
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(water));
		if (wait == 0) {
			// 竿を振る
			nakama.setItemSlot(EquipmentSlot.MAINHAND, nakama.getInventory().getItem(rodSlot).copyWithCount(1));
			nakama.swingArm();
			level.playSound(null, nakama.blockPosition(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F, 0.5F);
			wait = MIN_WAIT + nakama.getRandom().nextInt(MAX_WAIT - MIN_WAIT + 1);
			return RUNNING;
		}
		if (--wait > 0) {
			return RUNNING;
		}
		return land(nakama, level);
	}

	/** 魚が釣れた。 */
	private Result land(NakamaEntity nakama, ServerLevel level) {
		var fish = Inv.item(pickFish(nakama.getRandom().nextInt(100)));
		if (fish.isEmpty()) {
			return RUNNING;
		}
		nakama.swingArm();
		level.playSound(null, water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.4F, 1.0F);
		caught++;
		if (!Breaker.stash(nakama, level, List.of(new ItemStack(fish.get())))) {
			return finish(nakama, new Result.Failed("持ち物がいっぱい（「しまって」か「渡して」と言ってね）"));
		}
		return RUNNING;
	}

	/** 0〜99 の数から、出やすさに合わせて魚を選ぶ。 */
	static String pickFish(int roll) {
		int upTo = 0;
		for (int i = 0; i < FISH.size(); i++) {
			upTo += WEIGHTS[i];
			if (roll < upTo) {
				return FISH.get(i);
			}
		}
		return FISH.get(0);
	}

	/** いちばん近い、上が空いている水面で、そばに立てる場所があるもの。 */
	private static BlockPos findWater(NakamaEntity nakama, ServerLevel level) {
		BlockPos origin = nakama.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-SEARCH_RADIUS, -SEARCH_HEIGHT, -SEARCH_RADIUS),
			origin.offset(SEARCH_RADIUS, SEARCH_HEIGHT, SEARCH_RADIUS))) {
			double d = p.distSqr(origin);
			if (d >= bestDist || !level.isLoaded(p) || !level.getFluidState(p).is(FluidTags.WATER) || !level.getBlockState(p.above()).isAir()) {
				continue;
			}
			if (Terrain.standableNear(level, p, true).isPresent()) {
				best = p.immutable();
				bestDist = d;
			}
		}
		return best;
	}

	private Result finish(NakamaEntity nakama, Result result) {
		nakama.getNavigation().stop();
		return result;
	}

	/** 釣り竿を作ってからやり直すときは、残りの数だけ釣る。 */
	@Override
	public Command pinned(Command original) {
		return caught > 0 && caught < count ? new Command.Fish(count - caught) : original;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}

	@Override
	public String describe() {
		return "釣りをする（" + caught + "/" + count + "）";
	}
}
