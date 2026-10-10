package dev.ainakama.task;

import dev.ainakama.core.Items;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 近くのチェスト（樽も可）から、頼まれたものを取り出す。1つのチェストで足りなければ、次のチェストも見る。
 * count が 0 なら、あるだけ全部。
 */
final class TakeTask implements Task {
	private static final double REACH = 3.5;
	private static final int STUCK_TICKS = 160;

	private final String item;
	private final String key;
	private final int count;
	private final Walker walker = new Walker(STUCK_TICKS);
	private BlockPos chest;
	private int taken;

	TakeTask(String item, int count) {
		this.item = item;
		this.key = Items.groupOf(item);
		this.count = count;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (chest == null || !(level.getBlockEntity(chest) instanceof Container)) {
			var found = Storage.findItem(level, Storage.centers(nakama), key);
			if (found.isEmpty()) {
				// 少しでも取り出せていれば、あとは続きの作業に任せる（足りない分は集めるなどする）
				return taken > 0 ? DONE : new Result.Failed("近くのチェストに" + Items.label(item) + "が入っていない");
			}
			chest = found.get().pos();
		}
		double dist = nakama.getEyePosition().distanceTo(Vec3.atCenterOf(chest));
		if (dist > REACH) {
			return walker.step(nakama, chest, 1, 20, dist) ? RUNNING : new Result.Failed("チェストに近づけない");
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(Vec3.atCenterOf(chest));
		boolean full = !takeFrom((Container) level.getBlockEntity(chest), nakama);
		nakama.swingArm();
		level.playSound(null, chest, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4F, 1.0F);
		if (full) {
			return new Result.Failed("持ち物がいっぱいで、" + Items.label(item) + "を全部は取り出せなかった");
		}
		if (count > 0 && taken >= count) {
			return DONE;
		}
		// このチェストの分は取り終えた。まだ要るなら、次のtickでほかのチェストを探す
		chest = null;
		return RUNNING;
	}

	/** @return 持ち物に入りきったら true */
	private boolean takeFrom(Container source, NakamaEntity nakama) {
		int limit = count > 0 ? count : Integer.MAX_VALUE;
		for (int i = 0; i < source.getContainerSize() && taken < limit; i++) {
			ItemStack s = source.getItem(i);
			if (s.isEmpty() || !Items.matches(key, Inv.idOf(s.getItem()))) {
				continue;
			}
			int n = Math.min(limit - taken, s.getCount());
			ItemStack rest = Inv.add(nakama.getInventory(), s.split(n));
			taken += n - rest.getCount();
			if (!rest.isEmpty()) {
				// 入りきらなかった分はチェストに戻す
				Storage.moveInto(source, rest);
				source.setChanged();
				return false;
			}
		}
		source.setChanged();
		return true;
	}

	@Override
	public String describe() {
		return Items.label(item) + "をチェストから取り出す";
	}
}
