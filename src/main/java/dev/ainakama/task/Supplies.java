package dev.ainakama.task;

import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.PlanParser;
import dev.ainakama.core.Smelting;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Storage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * 足りない材料を、どうやって手に入れるかを決める。
 * 近くのチェストに入っていればそこから出し、なければ集め、集められないものは焼いて作る。
 */
final class Supplies {
	/**
	 * @param commands 先にやること
	 * @param unknown  手に入れ方がわからなかった材料のキー（全部わかれば null）
	 */
	record Plan(List<Command> commands, String unknown) {
		boolean ok() {
			return unknown == null;
		}
	}

	private Supplies() {
	}

	/** @param missing 足りないもの（キー → 数） */
	static Plan plan(Map<String, Integer> missing, NakamaEntity nakama, ServerLevel level) {
		List<Command> commands = new ArrayList<>();
		for (var e : missing.entrySet()) {
			Optional<Command> how = obtain(e.getKey(), Math.min(e.getValue(), PlanParser.MAX_COUNT), nakama, level);
			if (how.isEmpty()) {
				return new Plan(List.of(), e.getKey());
			}
			commands.add(how.get());
		}
		return new Plan(commands, null);
	}

	private static Optional<Command> obtain(String key, int count, NakamaEntity nakama, ServerLevel level) {
		var stocked = Storage.findItem(level, Storage.centers(nakama), key);
		if (stocked.isPresent()) {
			// チェストの分だけでは足りなくても、まず出す。残りは、やり直したときに集める
			return Optional.of(new Command.Take(stocked.get().id(), count));
		}
		var collectable = Items.collectableFor(key);
		if (collectable.isPresent()) {
			return Optional.of(new Command.Collect(collectable.get(), count));
		}
		return Items.options(key).stream()
			.filter(Smelting::canMake)
			.findFirst()
			.map(id -> new Command.Smelt(id, count));
	}

	/** チェストに入っているか（クラフトの計画で、手に入れられる材料として数えるため）。 */
	static boolean isStocked(String key, NakamaEntity nakama, ServerLevel level) {
		return Storage.findItem(level, Storage.centers(nakama), key).isPresent();
	}
}
