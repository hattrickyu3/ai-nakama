package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.Recipes;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Placer;
import dev.ainakama.world.Terrain;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * ブロックを1つ置く（扉・松明・作業台・チェストなど）。持っていなければ先に作る。
 * 指定の場所がふさがっていたら、すぐ上の空いているマスに置く。
 */
final class PlaceTask implements Task {
	private static final double REACH = 4.5;
	private static final int STUCK_TICKS = 200;
	private static final int MAX_LIFT = 3;

	private final String item;
	private final BlockPos requested;
	private final Walker walker = new Walker(STUCK_TICKS);

	PlaceTask(String item, BlockPos requested) {
		this.item = item;
		this.requested = requested;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		String key = Items.groupOf(item);
		int slot = Inv.findSlot(nakama.getInventory(), key);
		if (slot < 0) {
			if (!Recipes.canCraft(item)) {
				return new Result.Failed(Items.label(item) + "を持っていないし、作り方も知らない");
			}
			return new Result.Needs(List.of(new Command.Craft(item, 1)), Items.label(item) + "を持っていない");
		}
		BlockPos pos = freeSpot(level);
		if (pos == null) {
			return new Result.Failed("置く場所がふさがっている");
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
		if (dist > REACH) {
			return walker.step(nakama, pos, 2, 20, dist) ? RUNNING : new Result.Failed("置く場所に近づけない");
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		ItemStack stack = nakama.getInventory().getItem(slot);
		Direction facing = Direction.getApproximateNearest(pos.getX() + 0.5 - nakama.getX(), 0, pos.getZ() + 0.5 - nakama.getZ());
		if (!Placer.place(level, pos, stack, facing)) {
			return new Result.Failed(Items.label(item) + "をそこには置けない");
		}
		nakama.getInventory().setChanged();
		nakama.swingArm();
		return DONE;
	}

	private BlockPos freeSpot(ServerLevel level) {
		for (int up = 0; up <= MAX_LIFT; up++) {
			BlockPos p = requested.above(up);
			if (Terrain.isOpen(level.getBlockState(p))) {
				return p;
			}
		}
		return null;
	}

	@Override
	public String describe() {
		return Items.label(item) + "を置く";
	}
}
