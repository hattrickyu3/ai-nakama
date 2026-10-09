package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 道具以外の持ち物を、近くのチェスト（樽なども可）にしまう。拠点の近くのチェストを優先する。 */
final class StoreTask implements Task {
	private static final int SEARCH_RADIUS = 16;
	private static final double REACH = 3.5;
	private static final int STUCK_TICKS = 160;

	private final Walker walker = new Walker(STUCK_TICKS);
	private BlockPos chest;

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (chest == null || !(level.getBlockEntity(chest) instanceof Container)) {
			chest = findChest(nakama, level);
			if (chest == null) {
				return new Result.Failed("近くにチェストがない");
			}
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(chest));
		if (dist > REACH) {
			return walker.step(nakama, chest, 1, 20, dist) ? RUNNING : new Result.Failed("チェストに近づけない");
		}
		nakama.getNavigation().stop();
		Container target = (Container) level.getBlockEntity(chest);
		boolean full = false;
		var inv = nakama.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || Inv.isTool(s)) {
				continue;
			}
			moveInto(target, s);
			if (!s.isEmpty()) {
				full = true;
			}
		}
		inv.setChanged();
		target.setChanged();
		return full ? new Result.Failed("チェストがいっぱいで全部はしまえなかった") : DONE;
	}

	/** stack を container に入れる。入った分だけ stack が減る。 */
	private static void moveInto(Container container, ItemStack stack) {
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack slot = container.getItem(i);
			if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
				int room = Math.min(slot.getMaxStackSize(), container.getMaxStackSize()) - slot.getCount();
				int n = Math.min(room, stack.getCount());
				if (n > 0) {
					slot.grow(n);
					stack.shrink(n);
				}
			}
		}
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			if (container.getItem(i).isEmpty()) {
				container.setItem(i, stack.copy());
				stack.setCount(0);
			}
		}
	}

	private static BlockPos findChest(NakamaEntity nakama, ServerLevel level) {
		BlockPos center = nakama.home() != null && nakama.home().closerThan(nakama.blockPosition(), SEARCH_RADIUS * 2)
			? nakama.home() : nakama.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -4, -SEARCH_RADIUS),
			center.offset(SEARCH_RADIUS, 4, SEARCH_RADIUS))) {
			BlockEntity be = level.getBlockEntity(p);
			if (be instanceof Container && isStorage(be)) {
				double d = p.distSqr(center);
				if (d < bestDist) {
					bestDist = d;
					best = p.immutable();
				}
			}
		}
		return best;
	}

	private static boolean isStorage(BlockEntity be) {
		String id = Inv.idOf(be.getBlockState().getBlock().asItem());
		return id.equals("chest") || id.equals("barrel") || id.equals("trapped_chest");
	}

	@Override
	public String describe() {
		return "持ち物をチェストにしまう";
	}
}
