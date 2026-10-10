package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Places;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * 敵（モンスター）と戦う。持っている中でいちばん強い剣か斧を使い、なければ素手でたたく。動物や村人は相手にしない。
 * エンダーマンやゾンビピグリンは、怒っているときだけ相手にする（こちらから手を出すと、群れを怒らせてしまう）。
 * ・戦う（fight）：近くの敵がいなくなったら終わり
 * ・守る（guard）：プレイヤーについていき、プレイヤーに近づく敵を倒す。stop か新しい指示が来るまで続く
 */
final class FightTask implements Task {
	/** 戦うときに、自分のまわりで敵を探す広さ。 */
	private static final double FIGHT_RADIUS = 16;
	/** 守るときに、プレイヤーのまわりで敵を探す広さ。 */
	private static final double GUARD_RADIUS = 10;
	private static final int ATTACK_COOLDOWN = 20;
	private static final int REPATH_TICKS = 10;
	/** これだけ追いかけても手が届かない敵（空を飛ぶもの・壁の向こうなど）は、あきらめる。 */
	private static final int GIVE_UP_TICKS = 20 * 15;
	private static final int TIME_LIMIT_TICKS = 20 * 60 * 3;

	private final boolean guard;
	private final FollowTask follow = new FollowTask();
	private final Set<UUID> unreachable = new HashSet<>();
	private LivingEntity target;
	private int cooldown;
	private int repath;
	private int chaseTicks;
	private int ticks;
	private int defeated;

	FightTask(boolean guard) {
		this.guard = guard;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		cooldown--;
		Player player = guard ? Places.nearestPlayer(nakama, level).orElse(null) : null;
		if (guard && player == null) {
			return new Result.Failed("守る相手のプレイヤーが見当たらない");
		}
		Entity anchor = guard ? player : nakama;
		double radius = guard ? GUARD_RADIUS : FIGHT_RADIUS;
		if (target == null || !target.isAlive() || target.distanceTo(anchor) > radius * 1.5) {
			if (target != null && !target.isAlive()) {
				defeated++;
			}
			target = findTarget(nakama, level, anchor, radius);
			chaseTicks = 0;
			if (target != null) {
				nakama.setItemSlot(EquipmentSlot.MAINHAND, Inv.bestWeapon(nakama.getInventory()).copyWithCount(1));
			}
		}
		if (target == null) {
			return guard ? follow.tick(nakama, level) : DONE;
		}
		if (!guard && ++ticks > TIME_LIMIT_TICKS) {
			// 夜のあいだは次々にわいてくるので、きりのよいところでやめる
			nakama.say("きりがないから、いったんやめるね（" + defeated + "体は倒したよ）。");
			return DONE;
		}
		nakama.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (nakama.isWithinMeleeAttackRange(target)) {
			nakama.getNavigation().stop();
			if (cooldown <= 0) {
				cooldown = ATTACK_COOLDOWN;
				chaseTicks = 0;
				nakama.swingArm();
				nakama.doHurtTarget(level, target);
			}
			return RUNNING;
		}
		if (++chaseTicks > GIVE_UP_TICKS) {
			unreachable.add(target.getUUID());
			target = null;
			return RUNNING;
		}
		if (--repath <= 0) {
			repath = REPATH_TICKS;
			nakama.getNavigation().moveTo(target, NakamaEntity.WALK_SPEED * 1.4);
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
