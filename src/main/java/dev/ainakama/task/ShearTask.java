package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Inv;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/** ハサミで羊の毛を刈って、落ちた羊毛を拾う。羊は倒さないので、毛が生えればまた刈れる。 */
final class ShearTask extends AnimalTask {
	ShearTask(int count) {
		super(count);
	}

	/** ハサミを持っているか（羊毛を頼まれたとき、狩るか刈るかを決めるのに使う）。 */
	static boolean hasShears(NakamaEntity nakama) {
		return Inv.count(nakama.getInventory(), "shears") > 0;
	}

	@Override
	Result prepare(NakamaEntity nakama) {
		return hasShears(nakama) ? null : new Result.Needs(List.of(new Command.Craft("shears", 1)), "ハサミがないと毛を刈れない");
	}

	@Override
	boolean wants(Animal animal, NakamaEntity nakama) {
		return animal instanceof Shearable shearable && shearable.readyForShearing() && typeId(animal).equals("sheep");
	}

	@Override
	boolean act(NakamaEntity nakama, ServerLevel level, Animal animal) {
		ItemStack shears = nakama.getInventory().getItem(Inv.findSlot(nakama.getInventory(), "shears"));
		nakama.setItemSlot(EquipmentSlot.MAINHAND, shears.copyWithCount(1));
		((Shearable) animal).shear(level, SoundSource.PLAYERS, shears);
		return true;
	}

	@Override
	int progress() {
		return looter.count(id -> id.endsWith("_wool"));
	}

	@Override
	Result nothingLeft(NakamaEntity nakama) {
		if (progress() == 0) {
			return new Result.Failed("毛を刈れる羊が近くに見つからない");
		}
		nakama.say("刈れる羊はもういないみたい。羊毛を" + progress() + "個集めたよ。");
		return DONE;
	}

	@Override
	public String describe() {
		return "羊の毛を刈る（" + progress() + "/" + count + "）";
	}
}
