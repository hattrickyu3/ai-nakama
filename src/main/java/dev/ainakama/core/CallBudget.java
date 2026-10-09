package dev.ainakama.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AIを呼びすぎないための制限。
 * ・同じ仲間は前回から cooldownMillis 以上あける
 * ・1日（現実の日付）に呼べる回数は全員合わせて dailyLimit 回まで
 * スレッドをまたいで呼ばれるので synchronized にしている。
 */
public final class CallBudget {
	public enum Decision {
		OK, COOLDOWN, DAILY_LIMIT
	}

	private final long cooldownMillis;
	private final int dailyLimit;
	private final ZoneId zone;
	private final Map<UUID, Long> lastCall = new HashMap<>();
	private LocalDate day;
	private int usedToday;

	public CallBudget(long cooldownMillis, int dailyLimit, ZoneId zone) {
		this.cooldownMillis = cooldownMillis;
		this.dailyLimit = dailyLimit;
		this.zone = zone;
	}

	/** 呼んでよければ回数を数えて OK を返す。 */
	public synchronized Decision tryAcquire(UUID caller, long nowMillis) {
		LocalDate today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
		if (!today.equals(day)) {
			day = today;
			usedToday = 0;
		}
		if (usedToday >= dailyLimit) {
			return Decision.DAILY_LIMIT;
		}
		Long last = lastCall.get(caller);
		if (last != null && nowMillis - last < cooldownMillis) {
			return Decision.COOLDOWN;
		}
		lastCall.put(caller, nowMillis);
		usedToday++;
		return Decision.OK;
	}

	/** 聞き直しなど、制限の判定はせずに回数だけ数える呼び出し。 */
	public synchronized void recordExtra() {
		usedToday++;
	}

	public synchronized int usedToday() {
		return usedToday;
	}

	public int dailyLimit() {
		return dailyLimit;
	}
}
