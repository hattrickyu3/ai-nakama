package dev.ainakama.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** 命令を、AIの返事と同じ形のJSONに書き出す（覚えた技・決まりを保存するため）。読むのは {@link PlanParser}。 */
public final class CommandJson {
	private CommandJson() {
	}

	public static JsonArray write(List<Command> commands) {
		JsonArray arr = new JsonArray();
		commands.forEach(c -> arr.add(write(c)));
		return arr;
	}

	public static JsonObject write(Command command) {
		JsonObject o = new JsonObject();
		switch (command) {
			case Command.Follow f -> o.addProperty("do", "follow");
			case Command.Stop s -> o.addProperty("do", "stop");
			case Command.Store s -> o.addProperty("do", "store");
			case Command.MoveTo m -> {
				o.addProperty("do", "move_to");
				o.addProperty("at", m.place().describe());
			}
			case Command.Collect c -> {
				o.addProperty("do", "collect");
				o.addProperty("item", c.item());
				o.addProperty("count", c.count());
			}
			case Command.Craft c -> {
				o.addProperty("do", "craft");
				o.addProperty("item", c.item());
				o.addProperty("count", c.count());
			}
			case Command.Give g -> {
				o.addProperty("do", "give");
				if (g.item() != null) {
					o.addProperty("item", g.item());
					if (g.count() > 0) {
						o.addProperty("count", g.count());
					}
				}
			}
			case Command.PlaceBlock p -> {
				o.addProperty("do", "place");
				o.addProperty("item", p.item());
				o.addProperty("at", p.place().describe());
			}
			case Command.Build b -> {
				o.addProperty("do", "build");
				o.addProperty("blueprint", b.blueprint());
				o.addProperty("at", b.place().describe());
			}
			case Command.UseSkill u -> {
				o.addProperty("do", "skill");
				o.addProperty("name", u.name());
			}
			case Command.Say s -> {
				o.addProperty("do", "say");
				o.addProperty("text", s.text());
			}
			case Command.Remember r -> {
				o.addProperty("do", "remember");
				o.addProperty("text", r.note());
			}
		}
		return o;
	}
}
