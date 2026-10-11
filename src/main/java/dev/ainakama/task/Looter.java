package dev.ainakama.task;

import dev.ainakama.core.Items;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 落ちているアイテムを拾う。プレイヤーと同じように、そばまで歩いていって持ち物に入れる。
 * 仲間は歩いているだけでは物を拾わない（プレイヤーが落としたものを勝手に持っていかないように）ので、
 * 拾うのは「拾って」と頼まれたときと、自分で倒した相手が落としたものだけ。
 */
final class Looter {
	private static final double REACH = 2.0;
	private static final int STUCK_TICKS = 80;

	private final Walker walker = new Walker(STUCK_TICKS);
	private final Set<UUID> skipped = new HashSet<>();
	private final Map<String, Integer> picked = new LinkedHashMap<>();
	private ItemEntity target;
	private boolean full;

	/**
	 * center のまわり radius マスに落ちているアイテムを、近いものから1つずつ拾う。
	 *
	 * @return まだ拾うものがあれば true。拾い終わったか、持ち物がいっぱいなら false
	 */
	boolean tick(NakamaEntity nakama, ServerLevel level, Vec3 center, double radius) {
		if (full) {
			return false;
		}
		if (target == null || !target.isAlive()) {
			target = level.getEntitiesOfClass(ItemEntity.class, AABB.ofSize(center, radius * 2, radius * 2, radius * 2),
					e -> e.isAlive() && !e.getItem().isEmpty() && !skipped.contains(e.getUUID()))
				.stream()
				.min(Comparator.comparingDouble(nakama::distanceToSqr))
				.orElse(null);
			if (target == null) {
				return false;
			}
		}
		double dist = nakama.distanceTo(target);
		if (dist > REACH) {
			if (!walker.step(nakama, target.blockPosition(), 1, 10, dist)) {
				skipped.add(target.getUUID());
				target = null;
			}
			return true;
		}
		take(nakama, level, target);
		target = null;
		return !full;
	}

	private void take(NakamaEntity nakama, ServerLevel level, ItemEntity entity) {
		ItemStack stack = entity.getItem();
		String id = Inv.idOf(stack.getItem());
		int before = stack.getCount();
		ItemStack rest = Inv.add(nakama.getInventory(), stack.copy());
		int took = before - rest.getCount();
		if (took > 0) {
			picked.merge(id, took, Integer::sum);
			level.playSound(null, nakama.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.3F, 1.4F);
		}
		if (rest.isEmpty()) {
			entity.discard();
		} else {
			entity.setItem(rest);
			full = true;
		}
	}

	/** 持ち物がいっぱいで、拾いきれなかったか。 */
	boolean isFull() {
		return full;
	}

	boolean pickedAnything() {
		return !picked.isEmpty();
	}

	/** 拾ったもののうち、wanted に合うものの数。 */
	int count(Predicate<String> wanted) {
		return picked.entrySet().stream().filter(e -> wanted.test(e.getKey())).mapToInt(Map.Entry::getValue).sum();
	}

	/** 「生の牛肉×3、革×1」のような、拾ったものの一覧。 */
	String summary() {
		return picked.entrySet().stream().map(e -> Items.label(e.getKey()) + "×" + e.getValue()).collect(Collectors.joining("、"));
	}
}
