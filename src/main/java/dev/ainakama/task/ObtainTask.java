package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.Obtaining;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * アイテムを count 個持っている状態にする。手に入れ方は自分で選ぶ：
 * もう持っていれば何もしない → 近くのチェストにあれば出す → 集める・狩る・しぼる・釣る → 焼く → クラフトする。
 * 自分では何も運ばず、「先にこれをやって」と返して、終わったらもう一度数を確かめる。
 */
final class ObtainTask implements Task {
	private final String item;
	private final int count;

	ObtainTask(String item, int count) {
		this.item = item;
		this.count = count;
	}

	@Override
	public Result tick(NakamaEntity nakama, ServerLevel level) {
		String key = Items.groupOf(item);
		int missing = count - Inv.count(nakama.getInventory(), key);
		if (missing <= 0) {
			return DONE;
		}
		String why = Items.label(item) + "があと" + missing + "個足りない";
		// チェストにあるもの・集められるものは、材料を用意するときと同じ決め方に任せる
		Supplies.Plan supplies = Supplies.plan(Map.of(key, missing), nakama, level);
		if (supplies.ok()) {
			return new Result.Needs(supplies.commands(), why);
		}
		Optional<Command> how = Obtaining.how(item, missing);
		if (how.isPresent()) {
			return new Result.Needs(List.of(how.get()), why);
		}
		return new Result.Failed(Items.label(item) + "の手に入れ方がわからない");
	}

	@Override
	public String describe() {
		return Items.label(item) + "を" + count + "個用意する";
	}
}
