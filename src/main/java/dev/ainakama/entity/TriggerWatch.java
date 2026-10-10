package dev.ainakama.entity;

import dev.ainakama.core.Status;
import dev.ainakama.core.Trigger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * 「夜になった」「敵が近づいた」などのきっかけを見張る。
 * 状態が「そうでない → そうである」に変わった瞬間だけを知らせる（夜の間ずっと知らせ続けない）。
 */
final class TriggerWatch {
	private static final double ENEMY_RANGE = 10;
	private static final double PLAYER_FAR_RANGE = 48;
	/** 何もしていない時間がこれだけ続いたら「やることがなくなった」とみなす。 */
	private static final int IDLE_TICKS = 20 * 30;
	static final int POLL_INTERVAL = 20;

	private final Map<Trigger, Boolean> last = new EnumMap<>(Trigger.class);
	private int idleTicks;

	/** POLL_INTERVAL tickごとに呼ぶ。今回新しく起きたきっかけを返す。 */
	List<Trigger> poll(NakamaEntity nakama, ServerLevel level, boolean idle) {
		String time = Status.timeOfDay(level.getOverworldClockTime());
		idleTicks = idle ? idleTicks + POLL_INTERVAL : 0;
		Player player = level.getNearestPlayer(nakama, -1);

		List<Trigger> fired = new ArrayList<>();
		check(fired, Trigger.NIGHT, time.equals("夜"));
		check(fired, Trigger.MORNING, time.equals("朝"));
		check(fired, Trigger.RAIN, level.isRaining());
		check(fired, Trigger.ENEMY_NEAR, !level.getEntitiesOfClass(LivingEntity.class,
			nakama.getBoundingBox().inflate(ENEMY_RANGE), e -> e instanceof Enemy && e.isAlive()).isEmpty());
		check(fired, Trigger.INVENTORY_FULL, isFull(nakama));
		check(fired, Trigger.PLAYER_FAR, player != null && nakama.distanceTo(player) > PLAYER_FAR_RANGE);
		check(fired, Trigger.IDLE, idleTicks >= IDLE_TICKS);
		return fired;
	}

	private void check(List<Trigger> fired, Trigger trigger, boolean now) {
		Boolean before = last.put(trigger, now);
		// 最初の1回（before == null）は、今の状態を覚えるだけにする。ワールドに入った瞬間に動き出さないように
		if (now && Boolean.FALSE.equals(before)) {
			fired.add(trigger);
		}
	}

	private static boolean isFull(NakamaEntity nakama) {
		var inv = nakama.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).isEmpty()) {
				return false;
			}
		}
		return true;
	}
}
