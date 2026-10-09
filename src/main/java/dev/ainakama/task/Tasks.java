package dev.ainakama.task;

import dev.ainakama.core.Blueprint;
import dev.ainakama.core.Command;
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
				.orElseGet(() -> failing(m.place() instanceof dev.ainakama.core.Place.Home
					? "拠点がまだ決まっていない（「ここを拠点にして」と言ってね）"
					: "行き先が見つからない"));
			case Command.Collect c -> new CollectTask(c.item(), c.count());
			case Command.Craft c -> new CraftTask(c.item(), c.count());
			case Command.Build b -> {
				Blueprint bp = Blueprint.find(b.blueprint()).orElseThrow();
				yield Places.resolve(b.place(), nakama, level)
					.<Task>map(pos -> new BuildTask(bp, pos))
					.orElseGet(() -> failing("建てる場所が決まらない（拠点が未登録かも）"));
			}
			case Command.Store s -> new StoreTask();
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
