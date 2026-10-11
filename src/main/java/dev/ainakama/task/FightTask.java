package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Places;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 敵（モンスター）と戦う。持っている中でいちばん強い剣か斧を使い、なければ素手でたたく。動物や村人は相手にしない。
 * 敵がいなくなったら、倒した相手が落としたものを拾う。
 * エンダーマンやゾンビピグリンは、怒っているときだけ相手にする（こちらから手を出すと、群れを怒らせてしまう）。
 * ・戦う（fight）：近くの敵がいなくなったら終わり
 * ・守る（guard）：プレイヤーについていき、プレイヤーに近づく敵を倒す。stop か新しい指示が来るまで続く
 */
final class FightTask implements Task {
	/** 戦うときに、自分のまわりで敵を探す広さ。 */
	private static final double FIGHT_RADIUS = 16;
	/** 守るときに、プレイヤーのまわりで敵を探す広さ。 */
	private static final double GUARD_RADIUS = 10;
	private static final double LOOT_RADIUS = 6;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 3;

	private final boolean guard;
	private final FollowTask follow = new FollowTask();
	private final Set<UUID> unreachable = new HashSet<>();
	private final Melee melee = new Melee();
	private final Looter looter = new Looter();
	private LivingEntity target;
	private Vec3 lastKill;
	private int ticks;
	private int defeated;

	FightTask(boolean guard) {
		this.guard = guard;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		Player player = guard ? Places.nearestPlayer(nakama, level).orElse(null) : null;
		if (guard && player == null) {
			return new Result.Failed("守る相手のプレイヤーが見当たらない");
		}
		Entity anchor = guard ? player : nakama;
		double radius = guard ? GUARD_RADIUS : FIGHT_RADIUS;
		if (target == null || !target.isAlive() || target.distanceTo(anchor) > radius * 1.5) {
			if (target != null && !target.isAlive()) {
				defeated++;
				lastKill = target.position();
			}
			target = findTarget(nakama, level, anchor, radius);
			if (target != null) {
				melee.aim(nakama);
			}
		}
		if (target == null) {
			melee.rest();
			// 敵がいなくなったら、倒した相手が落としたものを拾う
			if (lastKill != null && looter.tick(nakama, level, lastKill, LOOT_RADIUS)) {
				return RUNNING;
			}
			lastKill = null;
			return guard ? follow.tick(nakama, level) : DONE;
		}
		if (!guard && ++ticks > TIME_LIMIT_TICKS) {
			// 夜のあいだは次々にわいてくるので、きりのよいところでやめる
			nakama.say("きりがないから、いったんやめるね（" + defeated + "体は倒したよ）。");
			return DONE;
		}
		if (!melee.tick(nakama, level, target)) {
			unreachable.add(target.getUUID());
			target = null;
		}
		return RUNNING;
	}

	/** anchor のまわりにいる敵のうち、自分にいちばん近いもの。 */
	private LivingEntity findTarget(NakamaEntity nakama, ServerLevel level, Entity anchor, double radius) {
		return level.getEntitiesOfClass(LivingEntity.class, anchor.getBoundingBox().inflate(radius),
				e -> isHostile(e) && !unreachable.contains(e.getUUID()))
			.stream()
			.min(Comparator.comparingDouble(nakama::distanceToSqr))
			.orElse(null);
	}

	/** 倒してよい敵か。エンダーマンやゾンビピグリンのように、怒らせなければ襲ってこないものは、怒っているときだけ相手にする。 */
	private static boolean isHostile(LivingEntity e) {
		return e instanceof Enemy && e.isAlive() && !(e instanceof NeutralMob neutral && !neutral.isAngry());
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}

	@Override
	public String describe() {
		return (guard ? "プレイヤーを守る" : "敵と戦う") + "（倒した数 " + defeated + "）";
	}
}
