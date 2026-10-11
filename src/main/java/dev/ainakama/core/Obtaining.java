package dev.ainakama.core;

import java.util.Optional;
import java.util.Set;

/**
 * アイテムの手に入れ方を選ぶ。「〇〇がほしい」と頼まれたときや、クラフトの材料が足りないときに使う。
 * 集める → 狩る・しぼる・釣る・収穫する → 焼く → クラフトする、の順に当てはまるものを選ぶ。
 * 近くのチェストに入っているかは、実行するときにしかわからないので、ここでは見ない（実行側が先にチェストを見る）。
 */
public final class Obtaining {
	private static final Set<String> FISH = Set.of("cod", "salmon", "tropical_fish", "pufferfish");
	/** 畑で収穫して手に入れるもの。 */
	private static final Set<String> CROPS = Set.of("wheat", "carrot", "potato", "beetroot", "wheat_seeds", "beetroot_seeds");

	private Obtaining() {
	}

	/** 手に入れるための命令。手に入れ方がわからなければ空。 */
	public static Optional<Command> how(String item, int count) {
		Optional<Command> gathered = gather(item, count);
		if (gathered.isPresent()) {
			return gathered;
		}
		if (Smelting.isProduct(item)) {
			return Optional.of(new Command.Smelt(item, count));
		}
		if (Recipes.canCraft(item)) {
			return Optional.of(new Command.Craft(item, count));
		}
		return Optional.empty();
	}

	/** 作るのではなく、世界から取ってくる手に入れ方（集める・狩る・しぼる・釣る・収穫する）。なければ空。 */
	public static Optional<Command> gather(String item, int count) {
		if (Items.isCollectable(item)) {
			return Optional.of(new Command.Collect(item, count));
		}
		Optional<Hunting.Prey> prey = Hunting.forDrop(item);
		if (prey.isPresent()) {
			return Optional.of(new Command.Hunt(prey.get().animal(), prey.get().item(), count));
		}
		if (item.equals("milk_bucket")) {
			return Optional.of(new Command.Milk(count));
		}
		if (FISH.contains(item)) {
			return Optional.of(new Command.Fish(count));
		}
		if (CROPS.contains(item)) {
			// 育ちきった作物を収穫する。数が足りるかは、収穫のあとで実行側が確かめる
			return Optional.of(new Command.Farm());
		}
		return Optional.empty();
	}

	public static boolean canObtain(String item) {
		return how(item, 1).isPresent();
	}

	public static boolean isFish(String item) {
		return FISH.contains(item);
	}
}
