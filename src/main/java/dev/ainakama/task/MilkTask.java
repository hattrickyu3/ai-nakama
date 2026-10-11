package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/** バケツで牛の乳をしぼる。バケツ1つが、牛乳入りバケツ1つになる。 */
final class MilkTask extends AnimalTask {
	private int milked;

	MilkTask(int count) {
		super(count);
	}

	@Override
	Result prepare(NakamaEntity nakama) {
		if (Inv.count(nakama.getInventory(), "bucket") > 0) {
			return null;
		}
		return new Result.Needs(List.of(new Command.Craft("bucket", count - milked)), "バケツがないと乳をしぼれない");
	}

	@Override
	boolean wants(Animal animal, NakamaEntity nakama) {
		String id = typeId(animal);
		return !animal.isBaby() && (id.equals("cow") || id.equals("mooshroom"));
	}

	@Override
	boolean act(NakamaEntity nakama, ServerLevel level, Animal animal) {
		var milk = Inv.item("milk_bucket");
		if (milk.isEmpty() || Inv.remove(nakama.getInventory(), "bucket", 1) == 0) {
			return false;
		}
		ItemStack rest = Inv.add(nakama.getInventory(), new ItemStack(milk.get()));
		if (!rest.isEmpty()) {
			Breaker.stash(nakama, level, List.of(rest));
		}
		level.playSound(null, animal.blockPosition(), SoundEvents.COW_MILK, SoundSource.PLAYERS, 1.0F, 1.0F);
		milked++;
		return false;
	}

	@Override
	int progress() {
		return milked;
	}

	@Override
	Result nothingLeft(NakamaEntity nakama) {
		return new Result.Failed("乳をしぼれる牛が近くに見つからない");
	}

	/** バケツを作ってからやり直すときは、残りの杯数だけしぼる。 */
	@Override
	public Command pinned(Command original) {
		return milked > 0 && milked < count ? new Command.Milk(count - milked) : original;
	}

	@Override
	public String describe() {
		return "牛の乳をしぼる（" + milked + "/" + count + "）";
	}
}
