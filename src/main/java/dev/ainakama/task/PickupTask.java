package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** 近くに落ちているアイテムを拾って回る。 */
final class PickupTask implements Task {
	private static final double RADIUS = 16;

	private final Looter looter = new Looter();
	private Vec3 center;

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (center == null) {
			center = nakama.position();
		}
		if (looter.tick(nakama, level, center, RADIUS)) {
			return RUNNING;
		}
		nakama.getNavigation().stop();
		if (looter.isFull()) {
			return new Result.Failed("持ち物がいっぱいで拾いきれない（「しまって」か「渡して」と言ってね）");
		}
		// 何も落ちていないのは失敗ではないので、AIに立て直しを頼まずに、そう伝えるだけにする
		nakama.say(looter.pickedAnything() ? looter.summary() + "を拾ったよ。" : "近くには何も落ちていないみたい。");
		return DONE;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}

	@Override
	public String describe() {
		return "落ちているものを拾う";
	}
}
