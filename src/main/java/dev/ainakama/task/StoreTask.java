package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Storage;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** 道具以外の持ち物を、近くのチェスト（樽なども可）にしまう。拠点の近くのチェストを優先する。 */
final class StoreTask implements Task {
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
			Storage.moveInto(target, s);
			if (!s.isEmpty()) {
				full = true;
			}
		}
		inv.setChanged();
		target.setChanged();
		return full ? new Result.Failed("チェストがいっぱいで全部はしまえなかった") : DONE;
	}

	private static BlockPos findChest(NakamaEntity nakama, ServerLevel level) {
		BlockPos center = nakama.home() != null && nakama.home().closerThan(nakama.blockPosition(), Storage.CHEST_RADIUS * 2)
			? nakama.home() : nakama.blockPosition();
		return Storage.chests(level, List.of(center)).stream().findFirst().orElse(null);
	}

	@Override
	public String describe() {
		return "持ち物をチェストにしまう";
	}
}
