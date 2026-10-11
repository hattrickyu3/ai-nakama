package dev.ainakama.task;

import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/** 相手に近づいて、持っている中でいちばん強い剣か斧（なければ素手）でたたく。戦うときと狩るときに使う。 */
final class Melee {
	private static final int ATTACK_COOLDOWN = 20;
	private static final int REPATH_TICKS = 10;
	/** これだけ追いかけても手が届かない相手（空を飛ぶもの・壁の向こうなど）は、あきらめる。 */
	private static final int GIVE_UP_TICKS = 20 * 15;

	private int cooldown;
	private int repath;
	private int chaseTicks;

	/** 新しい相手に向かう前に呼ぶ。武器を持ち、追いかけた時間を数え直す。 */
	void aim(NakamaEntity nakama) {
		chaseTicks = 0;
		nakama.setItemSlot(EquipmentSlot.MAINHAND, Inv.bestWeapon(nakama.getInventory()).copyWithCount(1));
	}

	/** 相手がいない間も、次にたたけるまでの時間は進める。 */
	void rest() {
		cooldown--;
	}

	/** @return 追いかけても届かないのであきらめたら false */
	boolean tick(NakamaEntity nakama, ServerLevel level, LivingEntity target) {
		cooldown--;
		nakama.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (nakama.isWithinMeleeAttackRange(target)) {
			nakama.getNavigation().stop();
			if (cooldown <= 0) {
				cooldown = ATTACK_COOLDOWN;
				chaseTicks = 0;
				nakama.swingArm();
				nakama.doHurtTarget(level, target);
			}
			return true;
		}
		if (++chaseTicks > GIVE_UP_TICKS) {
			return false;
		}
		if (--repath <= 0) {
			repath = REPATH_TICKS;
			nakama.getNavigation().moveTo(target, NakamaEntity.WALK_SPEED * 1.4);
		}
		return true;
	}
}
