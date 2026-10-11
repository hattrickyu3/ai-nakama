package dev.ainakama.task;

import dev.ainakama.core.Hunting;
import dev.ainakama.entity.NakamaEntity;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

/**
 * 動物を狩って、落としたものを拾う。
 * プレイヤーが飼っているかもしれない動物には手を出さない：名前を付けた動物・リードにつないだ動物・子どもは狩らない。
 * 同じ種類の大人が近くに {@link Hunting#KEEP_ALIVE} 頭以下になるなら狩らない（また増やせるように。囲いの家畜を全部倒さないように）。
 */
final class HuntTask implements Task {
	private static final double SEARCH_RADIUS = 32;
	/** 「同じ種類が近くに何頭いるか」を数える広さ。 */
	private static final double HERD_RADIUS = 24;
	private static final double LOOT_RADIUS = 6;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 4;

	private final String animal;
	private final String item;
	private final int count;
	private final Set<String> animals;
	private final Set<UUID> unreachable = new HashSet<>();
	private final Melee melee = new Melee();
	private final Looter looter = new Looter();
	private Animal target;
	private Vec3 lastKill;
	private int kills;
	private int ticks;

	HuntTask(String animal, String item, int count) {
		this.animal = animal;
		this.item = item;
		this.count = count;
		this.animals = Hunting.animalsFor(animal);
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		if (target != null && !target.isAlive()) {
			kills++;
			lastKill = target.position();
			target = null;
		}
		if (lastKill != null) {
			// 倒した相手が落としたものを、次へ行く前に拾う
			if (looter.tick(nakama, level, lastKill, LOOT_RADIUS)) {
				return RUNNING;
			}
			lastKill = null;
			if (looter.isFull()) {
				return finish(nakama, new Result.Failed("持ち物がいっぱいで拾いきれない（「しまって」か「渡して」と言ってね）"));
			}
		}
		if (progress() >= count) {
			return finish(nakama, DONE);
		}
		if (++ticks > TIME_LIMIT_TICKS) {
			return finish(nakama, new Result.Failed(what() + "を狩るのに時間がかかりすぎた（" + progress() + "/" + count + "）"));
		}
		if (target == null) {
			target = findTarget(nakama, level);
			if (target == null) {
				return finish(nakama, nothingLeft(nakama, level));
			}
			melee.aim(nakama);
		}
		if (!melee.tick(nakama, level, target)) {
			unreachable.add(target.getUUID());
			target = null;
		}
		return RUNNING;
	}

	/** これ以上狩る相手がいないとき。少しでも狩れていれば、そこまでで終わりにする。 */
	private Result nothingLeft(NakamaEntity nakama, ServerLevel level) {
		boolean someAround = !level.getEntitiesOfClass(Animal.class, nakama.getBoundingBox().inflate(SEARCH_RADIUS),
			e -> e.isAlive() && animals.contains(typeId(e))).isEmpty();
		if (kills > 0 || someAround) {
			nakama.say(someAround
				? "近くの" + Hunting.label(animal) + "は残り少ないから、これ以上は狩らないでおくね（また増やせるように）。"
				: "近くの" + Hunting.label(animal) + "はもういないみたい。");
			return DONE;
		}
		return new Result.Failed(Hunting.label(animal) + "が近くに見つからない");
	}

	private Animal findTarget(NakamaEntity nakama, ServerLevel level) {
		return level.getEntitiesOfClass(Animal.class, nakama.getBoundingBox().inflate(SEARCH_RADIUS),
				e -> canHunt(e, level) && !unreachable.contains(e.getUUID()))
			.stream()
			.min(Comparator.comparingDouble(nakama::distanceToSqr))
			.orElse(null);
	}

	private boolean canHunt(Animal e, ServerLevel level) {
		String id = typeId(e);
		if (!e.isAlive() || e.isBaby() || e.hasCustomName() || !animals.contains(id) || (e instanceof Leashable l && l.isLeashed())) {
			return false;
		}
		int adults = level.getEntitiesOfClass(Animal.class, e.getBoundingBox().inflate(HERD_RADIUS),
			o -> o.isAlive() && !o.isBaby() && typeId(o).equals(id)).size();
		return adults > Hunting.KEEP_ALIVE;
	}

	private static String typeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	/** ほしいものを頼まれたときは拾った数、動物を頼まれたときは倒した数。 */
	private int progress() {
		return item == null ? kills : looter.count(picked -> Hunting.counts(item, picked));
	}

	private String what() {
		return item == null ? Hunting.label(animal) : Hunting.itemLabel(item);
	}

	private Result finish(NakamaEntity nakama, Result result) {
		nakama.getNavigation().stop();
		return result;
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}

	@Override
	public String describe() {
		return what() + "を狩る（" + progress() + "/" + count + "）";
	}
}
