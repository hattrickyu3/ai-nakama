package dev.ainakama.task;

import dev.ainakama.core.Items;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * 村人（や行商人）と取引する。
 * ・売る：そのアイテムを受け取ってくれる取引を探し、持っている分で取引する
 * ・買う：そのアイテムを出してくれる取引を探し、代金（たいていエメラルド）を払う
 * ・item が null：近くの村人の取引を、チャットで教えるだけ
 * 村人ごとに、1日に取引できる回数には限りがある（売り切れ）。
 */
final class TradeTask implements Task {
	private static final double SEARCH_RADIUS = 32;
	private static final double REACH = 3.5;
	private static final int STUCK_TICKS = 120;
	private static final int MAX_LISTED = 8;

	private final String item;
	private final boolean sell;
	private final int count;
	private final Walker walker = new Walker(STUCK_TICKS);
	private LivingEntity merchant;
	private int traded;

	TradeTask(String item, boolean sell, int count) {
		this.item = item;
		this.sell = sell;
		this.count = count;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		List<LivingEntity> merchants = level.getEntitiesOfClass(LivingEntity.class, nakama.getBoundingBox().inflate(SEARCH_RADIUS),
			e -> e.isAlive() && e instanceof Merchant m && !m.getOffers().isEmpty());
		if (merchants.isEmpty()) {
			return new Result.Failed("取引できる村人が近くに見つからない");
		}
		if (item == null) {
			nakama.say("近くの村人の取引: " + String.join(" / ", listOffers(merchants)));
			return DONE;
		}
		if (merchant == null || !merchant.isAlive()) {
			merchant = merchants.stream()
				.filter(e -> offerFor(nakama, (Merchant) e, false) != null)
				.min(Comparator.comparingDouble(nakama::distanceToSqr))
				.orElse(null);
			if (merchant == null) {
				return new Result.Failed(noOfferReason(nakama, merchants));
			}
		}
		double dist = nakama.distanceTo(merchant);
		if (dist > REACH) {
			if (!walker.step(nakama, merchant.blockPosition(), 2, 10, dist)) {
				return new Result.Failed("村人のところにたどり着けない");
			}
			return RUNNING;
		}
		nakama.getNavigation().stop();
		nakama.getLookControl().setLookAt(merchant, 30.0F, 30.0F);
		MerchantOffer offer = offerFor(nakama, (Merchant) merchant, true);
		if (offer == null) {
			return stoppedEarly(nakama);
		}
		Inv.remove(nakama.getInventory(), Inv.idOf(offer.getCostA().getItem()), offer.getCostA().getCount());
		if (!offer.getCostB().isEmpty()) {
			Inv.remove(nakama.getInventory(), Inv.idOf(offer.getCostB().getItem()), offer.getCostB().getCount());
		}
		Breaker.stash(nakama, level, List.of(offer.getResult().copy()));
		((Merchant) merchant).notifyTrade(offer);
		nakama.swingArm();
		if (++traded >= count) {
			nakama.say(sell ? Items.label(item) + "を" + traded + "回売ったよ。" : Items.label(item) + "を" + traded + "回買ったよ。");
			return DONE;
		}
		return RUNNING;
	}

	/**
	 * この村人の取引のうち、頼まれたものに合うもの。
	 *
	 * @param affordable true なら、売り切れておらず、払うものを持っている取引だけ
	 */
	private MerchantOffer offerFor(NakamaEntity nakama, Merchant m, boolean affordable) {
		for (MerchantOffer offer : m.getOffers()) {
			String wanted = Inv.idOf((sell ? offer.getCostA() : offer.getResult()).getItem());
			if (!wanted.equals(item)) {
				continue;
			}
			if (!affordable || (!offer.isOutOfStock() && canPay(nakama, offer))) {
				return offer;
			}
		}
		return null;
	}

	private static boolean canPay(NakamaEntity nakama, MerchantOffer offer) {
		return has(nakama, offer.getCostA()) && (offer.getCostB().isEmpty() || has(nakama, offer.getCostB()));
	}

	private static boolean has(NakamaEntity nakama, ItemStack cost) {
		return Inv.count(nakama.getInventory(), Inv.idOf(cost.getItem())) >= cost.getCount();
	}

	/** 途中で取引できなくなったとき（売り切れ・払うものが尽きた）。1回でもできていれば、そこまでで終わりにする。 */
	private Result stoppedEarly(NakamaEntity nakama) {
		MerchantOffer any = offerFor(nakama, (Merchant) merchant, false);
		String why = any != null && any.isOutOfStock() ? "村人が今日はもう取引してくれない（売り切れ）"
			: describeCost(any) + "が足りない";
		if (traded > 0) {
			nakama.say(traded + "回取引したよ。" + why + "ので、ここまで。");
			return DONE;
		}
		return new Result.Failed(why);
	}

	private String noOfferReason(NakamaEntity nakama, List<LivingEntity> merchants) {
		return Items.label(item) + (sell ? "を買ってくれる" : "を売ってくれる") + "村人が近くにいない（近くの取引: "
			+ String.join(" / ", listOffers(merchants)) + "）";
	}

	private static String describeCost(MerchantOffer offer) {
		if (offer == null) {
			return "払うもの";
		}
		return Items.label(Inv.idOf(offer.getCostA().getItem())) + "×" + offer.getCostA().getCount();
	}

	/** 「小麦×20 → エメラルド×1」のような、取引の一覧（多すぎるときは途中まで）。 */
	private static List<String> listOffers(List<LivingEntity> merchants) {
		List<String> lines = new ArrayList<>();
		for (LivingEntity e : merchants) {
			for (MerchantOffer offer : ((Merchant) e).getOffers()) {
				if (lines.size() >= MAX_LISTED) {
					return lines;
				}
				String cost = stack(offer.getCostA()) + (offer.getCostB().isEmpty() ? "" : "＋" + stack(offer.getCostB()));
				lines.add(cost + " → " + stack(offer.getResult()) + (offer.isOutOfStock() ? "（売り切れ）" : ""));
			}
		}
		return lines;
	}

	private static String stack(ItemStack s) {
		return Items.label(Inv.idOf(s.getItem())) + "×" + s.getCount();
	}

	@Override
	public void cancel(NakamaEntity nakama, ServerLevel level) {
		nakama.getNavigation().stop();
	}

	@Override
	public String describe() {
		return item == null ? "村人の取引を見る" : "村人と取引する（" + traded + "/" + count + "）";
	}
}
