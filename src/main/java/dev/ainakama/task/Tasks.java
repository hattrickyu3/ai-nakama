package dev.ainakama.task;

import dev.ainakama.core.Blueprints;
import dev.ainakama.core.Command;
import dev.ainakama.core.Place;
import dev.ainakama.entity.NakamaEntity;
import dev.ainakama.world.Places;
import net.minecraft.server.level.ServerLevel;

/** 命令から、それを実行するタスクを作る。 */
public final class Tasks {
	private Tasks() {
	}

	public static Task create(Command command, NakamaEntity nakama, ServerLevel level) {
		return switch (command) {
			case Command.Follow f -> new FollowTask();
			case Command.Stop s -> instant("止まる", () -> nakama.getNavigation().stop());
			case Command.Say s -> instant("話す", () -> nakama.say(s.text()));
			case Command.Remember r -> instant("覚える", () -> nakama.remember(r.note()));
			case Command.MoveTo m -> Places.resolve(m.place(), nakama, level)
				.<Task>map(pos -> new MoveToTask(pos, m.place().describe()))
				.orElseGet(() -> failing(m.place() instanceof Place.Home
					? "拠点がまだ決まっていない（「ここを拠点にして」と言ってね）"
					: "行き先が見つからない"));
			case Command.Collect c -> new CollectTask(c.item(), c.count());
			case Command.Craft c -> new CraftTask(c.item(), c.count());
			case Command.Build b -> {
				var bp = Blueprints.find(b.blueprint());
				if (bp.isEmpty()) {
					yield failing("「" + b.blueprint() + "」という設計図を覚えていない");
				}
				yield Places.resolve(b.place(), nakama, level)
					.<Task>map(pos -> new BuildTask(bp.get(), pos, b.place() instanceof Place.Exact))
					.orElseGet(() -> failing("建てる場所が決まらない（拠点が未登録かも）"));
			}
			case Command.Give g -> new GiveTask(g.item(), g.count());
			case Command.PlaceBlock p -> Places.resolve(p.place(), nakama, level)
				.<Task>map(pos -> new PlaceTask(p.item(), pos))
				.orElseGet(() -> failing("置く場所が決まらない"));
			case Command.UseSkill u -> failing("「" + u.name() + "」という技を覚えていない");
			case Command.Store s -> new StoreTask();
			case Command.Take t -> new TakeTask(t.item(), t.count());
			case Command.Smelt s -> new SmeltTask(s.item(), s.count());
			case Command.Farm f -> new FarmTask();
			case Command.Fight f -> new FightTask(false);
			case Command.Guard g -> new FightTask(true);
			case Command.Hunt h -> new HuntTask(h.animal(), h.item(), h.count());
			case Command.Pickup p -> new PickupTask();
			case Command.Till t -> Places.resolve(t.place(), nakama, level)
				.<Task>map(pos -> new TillTask(pos, t.count()))
				.orElseGet(() -> failing("耕す場所が決まらない（拠点が未登録かも）"));
		};
	}

	private static Task instant(String name, Runnable action) {
		return new Task() {
			@Override
			public Result tick(NakamaEntity nakama, ServerLevel level) {
				action.run();
				return DONE;
			}

			@Override
			public String describe() {
				return name;
			}
		};
	}

	private static Task failing(String reason) {
		return new Task() {
			@Override
			public Result tick(NakamaEntity nakama, ServerLevel level) {
				return new Result.Failed(reason);
			}

			@Override
			public String describe() {
				return "失敗";
			}
		};
	}
}
