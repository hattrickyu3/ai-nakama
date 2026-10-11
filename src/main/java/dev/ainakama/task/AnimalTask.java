package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

/**
 * 動物のそばへ行って、何かをする作業（毛を刈る・乳をしぼる・エサをやる）の共通部分。
 * 相手を探す → 歩く → 手を出す、を数がそろうまで繰り返す。動物が何かを落としたら、それを拾う。
 */
abstract class AnimalTask implements Task {
	private static final double SEARCH_RADIUS = 32;
	private static final double REACH = 3.0;
	private static final double LOOT_RADIUS = 5;
	private static final int STUCK_TICKS = 100;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 3;

	protected final int count;
	protected final Looter looter = new Looter();
	private final Walker walker = new Walker(STUCK_TICKS);
	private final Set<UUID> skipped = new HashSet<>();
	private Animal target;
	private Vec3 lootAt;
	private int ticks;

	AnimalTask(int count) {
		this.count = count;
	}

	/** 道具や材料がそろっていれば null。足りなければ、その結果（先に作る・失敗）。 */
	abstract Result prepare(NakamaEntity nakama);

	/** 手を出す相手か。 */
	abstract boolean wants(Animal animal, NakamaEntity nakama);

	/** 相手のそばで行うこと。動物が何かを落とすなら true を返す（そのあと拾いに行く）。 */
	abstract boolean act(NakamaEntity nakama, ServerLevel level, Animal animal);

	/** ここまでに済んだ数。 */
	abstract int progress();

	/** 相手がもういないときの結果。 */
	abstract Result nothingLeft(NakamaEntity nakama);

	/** 数がそろったときに言うこと（言わないなら空文字）。 */
	String doneMessage() {
		return "";
	}

	@Override
	public final Result tick(NakamaEntity nakama, ServerLevel level) {
		if (lootAt != null) {
			if (looter.tick(nakama, level, lootAt, LOOT_RADIUS)) {
				return RUNNING;
			}
			lootAt = null;
		}
		if (progress() >= count) {
			nakama.say(doneMessage());
			return finish(nakama, DONE);
		}
		Result notReady = prepare(nakama);
		if (notReady != null) {
			return finish(nakama, notReady);
		}
		if (++ticks > TIME_LIMIT_TICKS) {
			return finish(nakama, new Result.Failed(describe() + "に時間がかかりすぎた"));
		}
		if (target == null || !target.isAlive() || !wants(target, nakama)) {
			target = level.getEntitiesOfClass(Animal.class, nakama.getBoundingBox().inflate(SEARCH_RADIUS),
					a -> a.isAlive() && !skipped.contains(a.getUUID()) && wants(a, nakama))
				.stream()
				.min(Comparator.comparingDouble(nakama::distanceToSqr))
				.orElse(null);
			if (target == null) {
				return finish(nakama, nothingLeft(nakama));
			}
		}
		double dist = nakama.distanceTo(target);
		if (dist > REACH) {
			if (!walker.step(nakama, target.blockPosition(), 2, 10, dist)) {
				skipped.add(target.getUUID());
				target = null;
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(target, 30.0F, 30.0F);
		nakama.swingArm();
		if (act(nakama, level, target)) {
			lootAt = target.position();
		}
		target = null;
		return RUNNING;
	}

	static String typeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	private Result finish(NakamaEntity nakama, Result result) {
		nakama.getNavigation().stop();
		return result;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}
}
