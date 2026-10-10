package dev.ainakama.task;

import dev.ainakama.core.Items;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Places;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * プレイヤーのところへ行って、持ち物を渡す。プレイヤーの持ち物がいっぱいなら足元に落とす。
 * item が null なら道具以外を全部、count が 0 ならその種類をあるだけ全部。
 */
final class GiveTask implements Task {
	private static final double HAND_OVER_DISTANCE = 3.0;
	private static final int STUCK_TICKS = 200;

	private final String item;
	private final int count;
	private final Walker walker = new Walker(STUCK_TICKS);

	GiveTask(String item, int count) {
		this.item = item;
		this.count = count;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		Player player = Places.nearestPlayer(nakama, level).orElse(null);
		if (player == null) {
			return new Result.Failed("渡す相手のプレイヤーが見当たらない");
		}
		double dist = nakama.distanceTo(player);
		if (dist > HAND_OVER_DISTANCE) {
			// たどり着けないほど離れていたら、ついていくときと同じようにそばへ移る
			if (!walker.step(nakama, player.blockPosition(), 2, 10, dist)) {
				nakama.snapTo(player.getX(), player.getY(), player.getZ(), nakama.getYRot(), nakama.getXRot());
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(player);
		int given = handOver(nakama, level, player);
		if (given == 0) {
			return new Result.Failed(item == null ? "渡せるものを何も持っていない" : Items.label(item) + "を持っていない");
		}
		nakama.swingArm();
		level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 1.2F);
		return DONE;
	}

	private int handOver(NakamaEntity nakama, ServerLevel level, Player player) {
		var inv = nakama.getInventory();
		int left = count > 0 ? count : Integer.MAX_VALUE;
		int given = 0;
		String key = item == null ? null : Items.groupOf(item);
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			boolean wanted = key == null ? !Inv.isTool(s) : Items.matches(key, Inv.idOf(s.getItem()));
			if (!wanted) {
				continue;
			}
			int n = Math.min(left, s.getCount());
			ItemStack part = s.split(n);
			left -= n;
			given += n;
			if (!player.getInventory().add(part) && !part.isEmpty()) {
				// 入りきらない分は、プレイヤーの足元に落とす
				ItemEntity drop = new ItemEntity(level, player.getX(), player.getY() + 0.5, player.getZ(), part);
				drop.setNoPickUpDelay();
				level.addFreshEntity(drop);
			}
		}
		inv.setChanged();
		return given;
	}

	@Override
	public String describe() {
		return item == null ? "持ち物を渡す" : Items.label(item) + "を渡す";
	}
}
