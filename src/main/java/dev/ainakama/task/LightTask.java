package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Lighting;
import dev.ainakama.core.Place;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Placer;
import dev.ainakama.world.Terrain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * まわりの暗いところに松明を置いて、明るくする（モンスターがわかないようにする）。
 * 空の明るさは数えない（夜になれば暗くなるので）。松明やかがり火などの明かりが届いていない地面を探し、
 * 間をあけて松明を置く。足りない松明は作る。
 */
final class LightTask implements Task {
	private static final int RADIUS = 12;
	private static final double REACH = 3.5;
	private static final int STUCK_TICKS = 100;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 5;

	private final BlockPos center;
	private final Walker walker = new Walker(STUCK_TICKS);
	private Deque<BlockPos> spots;
	private BlockPos target;
	private int placed;
	private int ticks;

	LightTask(BlockPos center) {
		this.center = center;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (spots == null) {
			spots = chooseSpots(level);
			if (spots.isEmpty()) {
				nakama.say("このあたりは、もう十分明るいよ。");
				return DONE;
			}
		}
		if (target == null && spots.isEmpty()) {
			nakama.say("松明を" + placed + "本置いて、明るくしたよ。");
			return finish(nakama, DONE);
		}
		int torches = Inv.count(nakama.getInventory(), "torch");
		int needed = spots.size() + (target == null ? 0 : 1);
		if (torches == 0) {
			return finish(nakama, new Result.Needs(List.of(new Command.Craft("torch", needed)), "松明が足りない"));
		}
		if (++ticks > TIME_LIMIT_TICKS) {
			return finish(nakama, new Result.Failed("明るくするのに時間がかかりすぎた（松明 " + placed + "本）"));
		}
		if (target == null) {
			target = spots.pollFirst();
			if (!canPlaceTorch(level, target)) {
				target = null;
				return RUNNING;
			}
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(target));
		if (dist > REACH) {
			if (!walker.step(nakama, target, 1, 20, dist)) {
				target = null;
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(target));
		if (Placer.placeExact(level, target, Blocks.TORCH.defaultBlockState())) {
			Inv.remove(nakama.getInventory(), "torch", 1);
			nakama.swingArm();
			placed++;
		}
		target = null;
		return RUNNING;
	}

	/** 明かりの届いていない地面から、松明を置く場所を選ぶ。 */
	private Deque<BlockPos> chooseSpots(ServerLevel level) {
		List<Lighting.Spot> dark = new ArrayList<>();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				BlockPos p = new BlockPos(x, Terrain.surfaceY(level, x, z, center.getY()), z);
				if (level.isLoaded(p) && canPlaceTorch(level, p) && level.getBrightness(LightLayer.BLOCK, p) < Lighting.MIN_LIGHT) {
					dark.add(new Lighting.Spot(p.getX(), p.getY(), p.getZ()));
				}
			}
		}
		Deque<BlockPos> queue = new ArrayDeque<>();
		Lighting.choose(dark, new Lighting.Spot(center.getX(), center.getY(), center.getZ()))
			.forEach(s -> queue.addLast(new BlockPos(s.x(), s.y(), s.z())));
		return queue;
	}

	/** 何もなく、下がしっかりした地面で、松明を立てられる場所か。 */
	private static boolean canPlaceTorch(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).isAir() && level.getFluidState(pos).isEmpty()
			&& level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
	}

	private Result finish(NakamaEntity nakama, Result result) {
		nakama.getNavigation().stop();
		return result;
	}

	/** 松明を作ってからやり直すときも、同じ場所を明るくする（もう置いた松明の明かりは、探し直すときに数に入る）。 */
	@Override
	public Command pinned(Command original) {
		return new Command.Light(new Place.Coords(center.getX(), center.getY(), center.getZ()));
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}

	@Override
	public String describe() {
		return "暗いところに松明を置く（" + placed + "本）";
	}
}
