package dev.ainakama.core;

import java.util.Locale;
import java.util.Optional;

/** 「〇〇したら」のきっかけ。ゲーム側が毎tick様子を見て、起きた瞬間に知らせる。 */
public enum Trigger {
	NIGHT("night", "夜になったら"),
	MORNING("morning", "朝になったら"),
	RAIN("rain", "雨が降ったら"),
	ENEMY_NEAR("enemy", "敵が近づいたら"),
	INVENTORY_FULL("inventory_full", "持ち物がいっぱいになったら"),
	PLAYER_FAR("player_far", "プレイヤーが遠くに行ったら"),
	IDLE("idle", "やることがなくなったら");

	private final String id;
	private final String japanese;

	Trigger(String id, String japanese) {
		this.id = id;
		this.japanese = japanese;
	}

	public String id() {
		return id;
	}

	public String japanese() {
		return japanese;
	}

	public static Optional<Trigger> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String s = raw.trim().toLowerCase(Locale.ROOT);
		for (Trigger t : values()) {
			if (t.id.equals(s) || t.name().toLowerCase(Locale.ROOT).equals(s)) {
				return Optional.of(t);
			}
		}
		return Optional.empty();
	}
}
