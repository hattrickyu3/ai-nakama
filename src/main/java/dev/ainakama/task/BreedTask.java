package dev.ainakama.task;

import dev.ainakama.core.Hunting;
import dev.ainakama.entity.NakamaEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/**
 * 動物にエサをやって増やす。大人2頭にエサをやると、子どもが1頭生まれる。
 * エサは、持ち物の中からその動物が食べるもの（牛・羊は小麦、豚はニンジンなど、ニワトリは種）を使う。
 */
final class BreedTask extends AnimalTask {
	private final String animal;
	private int fed;

	/** @param pairs 増やす数。1頭増やすのに、2頭にエサをやる */
	BreedTask(String animal, int pairs) {
		super(pairs * 2);
		this.animal = animal;
	}

	@Override
	Result prepare(NakamaEntity nakama) {
		return null;
	}

	@Override
	boolean wants(Animal a, NakamaEntity nakama) {
		return typeId(a).equals(animal) && a.canFallInLove() && foodSlot(nakama, a) >= 0;
	}

	@Override
	boolean act(NakamaEntity nakama, ServerLevel level, Animal a) {
		int slot = foodSlot(nakama, a);
		if (slot < 0) {
			return false;
		}
		nakama.getInventory().getItem(slot).shrink(1);
		nakama.getInventory().setChanged();
		a.setInLove(null);
		fed++;
		return false;
	}

	/** その動物が食べるものが入っている、持ち物の位置。なければ -1。 */
	private static int foodSlot(NakamaEntity nakama, Animal a) {
		var inv = nakama.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && a.isFood(s)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	int progress() {
		return fed;
	}

	@Override
	String doneMessage() {
		return Hunting.label(animal) + fed + "頭にエサをあげたよ。もうすぐ子どもが生まれるよ。";
	}

	@Override
	Result nothingLeft(NakamaEntity nakama) {
		if (fed >= 2) {
			nakama.say(Hunting.label(animal) + fed + "頭にエサをあげたよ。エサか、相手になる大人が足りなくて、ここまでだった。");
			return DONE;
		}
		return new Result.Failed("エサをあげられる" + Hunting.label(animal) + "が2頭いない（大人が2頭と、その動物が食べるエサが要る。牛と羊は小麦、豚はニンジン、ニワトリは種）");
	}

	@Override
	public String describe() {
		return Hunting.label(animal) + "を増やす（エサ " + fed + "/" + count + "）";
	}
}
