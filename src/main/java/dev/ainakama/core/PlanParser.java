package dev.ainakama.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * AIの返事（JSON）を読み、命令として正しいかを確かめる。
 * 間違いがあっても読めた命令は残し、間違いの内容は errors に入れる（AIに聞き直すときに渡す）。
 */
public final class PlanParser {
	public static final int MAX_STEPS = 8;
	public static final int MAX_COUNT = 256;
	public static final int MAX_SAY_LENGTH = 200;
	public static final int MAX_NAME_LENGTH = 30;
	private static final String FURNACE = "furnace";

	public record Result(Plan plan, List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	private final Predicate<String> itemExists;
	private final Predicate<String> skillExists;

	/**
	 * @param itemExists  Minecraft にそのアイテムIDがあるかを確かめる関数
	 * @param skillExists その名前の技を覚えているかを確かめる関数
	 */
	public PlanParser(Predicate<String> itemExists, Predicate<String> skillExists) {
		this.itemExists = itemExists;
		this.skillExists = skillExists;
	}

	public PlanParser(Predicate<String> itemExists) {
		this(itemExists, name -> false);
	}

	public Result parse(String raw) {
		List<String> errors = new ArrayList<>();
		Optional<JsonObject> json = extractObject(raw);
		if (json.isEmpty()) {
			errors.add("返事がJSONとして読めない");
			return new Result(new Plan("", List.of(), null), errors);
		}
		JsonObject obj = json.get();
		String say = truncate(stringOr(obj, "say", ""), MAX_SAY_LENGTH);
		String memo = blankToNull(stringOr(obj, "memo", null));
		String ask = blankToNull(stringOr(obj, "ask", null));
		List<Command> commands = withoutFurnacePrep(commandList(obj.get("plan"), "plan", errors));

		Plan.Skill teach = null;
		JsonElement teachEl = obj.get("teach");
		if (teachEl != null && teachEl.isJsonObject()) {
			JsonObject t = teachEl.getAsJsonObject();
			String name = truncate(stringOr(t, "name", "").trim(), MAX_NAME_LENGTH);
			List<Command> steps = commandList(t.get("steps"), "teach.steps", errors);
			if (name.isBlank()) {
				errors.add("teach: name が空");
			} else if (steps.isEmpty()) {
				errors.add("teach: steps が空（使える命令で手順を書く）");
			} else {
				teach = new Plan.Skill(name, steps);
			}
		}

		Plan.Rule rule = null;
		JsonElement ruleEl = obj.get("rule");
		if (ruleEl != null && ruleEl.isJsonObject()) {
			JsonObject r = ruleEl.getAsJsonObject();
			Optional<Trigger> when = Trigger.parse(stringOr(r, "when", ""));
			List<Command> steps = commandList(r.get("steps"), "rule.steps", errors);
			if (when.isEmpty()) {
				errors.add("rule: when が読めない（night / morning / rain / enemy / inventory_full / player_far / idle）");
			} else if (steps.isEmpty()) {
				errors.add("rule: steps が空");
			} else {
				rule = new Plan.Rule(when.get(), steps);
			}
		}
		return new Result(new Plan(say, commands, memo, ask, teach, rule), errors);
	}

	/**
	 * smelt はかまどの用意も自分でやる（近くにあればそれを使う）ので、その前に書かれた「かまどを作る・置く」は取り除く。
	 * 小さいAIは説明しても毎回これを書くことがあり、そのままだと焼くたびにかまどが1つ増えてしまう。
	 */
	static List<Command> withoutFurnacePrep(List<Command> commands) {
		int lastSmelt = -1;
		for (int i = 0; i < commands.size(); i++) {
			if (commands.get(i) instanceof Command.Smelt) {
				lastSmelt = i;
			}
		}
		List<Command> kept = new ArrayList<>();
		for (int i = 0; i < commands.size(); i++) {
			Command c = commands.get(i);
			boolean furnacePrep = c instanceof Command.Craft craft && craft.item().equals(FURNACE)
				|| c instanceof Command.PlaceBlock place && place.item().equals(FURNACE);
			if (i > lastSmelt || !furnacePrep) {
				kept.add(c);
			}
		}
		return kept;
	}

	/** 保存してあった手順（技・決まり）を読み直す。読めない命令は捨てる。 */
	public List<Command> parseSteps(JsonArray steps) {
		return commandList(steps, "steps", new ArrayList<>());
	}

	private List<Command> commandList(JsonElement el, String label, List<String> errors) {
		List<Command> commands = new ArrayList<>();
		if (el == null || el.isJsonNull()) {
			return commands;
		}
		if (!el.isJsonArray()) {
			errors.add(label + " が配列ではない");
			return commands;
		}
		JsonArray arr = el.getAsJsonArray();
		if (arr.size() > MAX_STEPS) {
			errors.add(label + " は最大" + MAX_STEPS + "個まで（" + arr.size() + "個あった）");
		}
		for (int i = 0; i < Math.min(arr.size(), MAX_STEPS); i++) {
			JsonElement item = arr.get(i);
			if (!item.isJsonObject()) {
				errors.add(label + "の" + (i + 1) + "番目がオブジェクトではない");
				continue;
			}
			toCommand(item.getAsJsonObject(), label + "の" + (i + 1) + "番目", errors).ifPresent(commands::add);
		}
		return commands;
	}

	private Optional<Command> toCommand(JsonObject o, String position, List<String> errors) {
		String name = stringOr(o, "do", "").trim().toLowerCase(Locale.ROOT);
		String where = position + "(" + name + ")";
		switch (name) {
			case "follow":
				return Optional.of(new Command.Follow());
			case "stop":
				return Optional.of(new Command.Stop());
			case "store":
				return Optional.of(new Command.Store());
			case "farm":
				return Optional.of(new Command.Farm());
			case "fight":
				return Optional.of(new Command.Fight());
			case "guard":
				return Optional.of(new Command.Guard());
			case "take":
			case "smelt": {
				Optional<String> item = Items.resolve(stringOr(o, "item", ""), itemExists);
				if (item.isEmpty()) {
					errors.add(where + ": 知らないアイテム「" + stringOr(o, "item", "") + "」");
					return Optional.empty();
				}
				// count を省いたら「あるだけ全部」
				int count = clampCount(o, where, errors, 0);
				if (name.equals("take")) {
					return Optional.of(new Command.Take(item.get(), count));
				}
				Optional<String> result = Smelting.resultFor(item.get());
				if (result.isEmpty()) {
					errors.add(where + ": " + item.get() + " はかまどで焼くものではない");
					return Optional.empty();
				}
				return Optional.of(new Command.Smelt(result.get(), count));
			}
			case "say": {
				String text = stringOr(o, "text", stringOr(o, "say", ""));
				if (text.isBlank()) {
					errors.add(where + ": text が空");
					return Optional.empty();
				}
				return Optional.of(new Command.Say(truncate(text, MAX_SAY_LENGTH)));
			}
			case "remember": {
				String note = stringOr(o, "text", stringOr(o, "note", ""));
				if (note.isBlank()) {
					errors.add(where + ": text が空");
					return Optional.empty();
				}
				return Optional.of(new Command.Remember(truncate(note, MAX_SAY_LENGTH)));
			}
			case "move_to": {
				Optional<Place> place = Place.parse(stringOr(o, "at", ""));
				if (place.isEmpty()) {
					errors.add(where + ": at が読めない（home / player / here / self / \"x y z\"）");
					return Optional.empty();
				}
				return Optional.of(new Command.MoveTo(place.get()));
			}
			case "collect":
			case "craft": {
				Optional<String> item = Items.resolve(stringOr(o, "item", ""), itemExists);
				if (item.isEmpty()) {
					errors.add(where + ": 知らないアイテム「" + stringOr(o, "item", "") + "」");
					return Optional.empty();
				}
				int count = clampCount(o, where, errors, 1);
				if (name.equals("collect") && !Items.isCollectable(item.get())) {
					errors.add(where + ": " + item.get() + " は集められない（集められるのは原木・丸石・石炭・鉄の原石・土・砂・砂利・粘土。作るものは craft）");
					return Optional.empty();
				}
				if (name.equals("craft") && !Recipes.canCraft(item.get())) {
					if (Smelting.isProduct(item.get())) {
						// ガラスや木炭のように、クラフトではなく焼いて作るもの
						return Optional.of(new Command.Smelt(item.get(), count));
					}
					errors.add(where + ": " + item.get() + " の作り方を知らない");
					return Optional.empty();
				}
				return Optional.of(name.equals("collect")
					? new Command.Collect(item.get(), count)
					: new Command.Craft(item.get(), count));
			}
			case "give": {
				String rawItem = stringOr(o, "item", "");
				if (rawItem.isBlank() || rawItem.equals("all") || rawItem.equals("全部")) {
					return Optional.of(new Command.Give(null, 0));
				}
				Optional<String> item = Items.resolve(rawItem, itemExists);
				if (item.isEmpty()) {
					errors.add(where + ": 知らないアイテム「" + rawItem + "」（全部渡すなら item を省く）");
					return Optional.empty();
				}
				return Optional.of(new Command.Give(item.get(), clampCount(o, where, errors, 0)));
			}
			case "place": {
				Optional<String> item = Items.resolve(stringOr(o, "item", ""), itemExists);
				if (item.isEmpty()) {
					errors.add(where + ": 知らないアイテム「" + stringOr(o, "item", "") + "」");
					return Optional.empty();
				}
				Optional<Place> place = Place.parse(stringOr(o, "at", "here"));
				if (place.isEmpty()) {
					errors.add(where + ": at が読めない");
					return Optional.empty();
				}
				return Optional.of(new Command.PlaceBlock(item.get(), place.get()));
			}
			case "build": {
				Optional<Blueprint> bp = Blueprints.find(stringOr(o, "blueprint", ""));
				if (bp.isEmpty()) {
					errors.add(where + ": 知らない設計図「" + stringOr(o, "blueprint", "") + "」");
					return Optional.empty();
				}
				Optional<Place> place = Place.parse(stringOr(o, "at", "here"));
				if (place.isEmpty()) {
					errors.add(where + ": at が読めない");
					return Optional.empty();
				}
				return Optional.of(new Command.Build(bp.get().id(), place.get()));
			}
			case "skill": {
				String skill = stringOr(o, "name", "").trim();
				if (!skillExists.test(skill)) {
					errors.add(where + ": 「" + skill + "」という技は覚えていない");
					return Optional.empty();
				}
				return Optional.of(new Command.UseSkill(skill));
			}
			default:
				errors.add(where + ": 知らない命令");
				return Optional.empty();
		}
	}

	private static int clampCount(JsonObject o, String where, List<String> errors, int fallback) {
		JsonElement el = o.get("count");
		if (el == null || el.isJsonNull()) {
			return fallback;
		}
		try {
			int n = el.getAsInt();
			if (n < 1 || n > MAX_COUNT) {
				errors.add(where + ": count は1〜" + MAX_COUNT + "（" + n + "だった）");
			}
			return Math.max(1, Math.min(MAX_COUNT, n));
		} catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
			errors.add(where + ": count が数字ではない");
			return Math.max(1, fallback);
		}
	}

	/** 前後に説明文や ```json があっても、最初の { から最後の } までを読む。 */
	static Optional<JsonObject> extractObject(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		if (start < 0 || end <= start) {
			return Optional.empty();
		}
		try {
			JsonElement el = JsonParser.parseString(raw.substring(start, end + 1));
			return el.isJsonObject() ? Optional.of(el.getAsJsonObject()) : Optional.empty();
		} catch (JsonParseException e) {
			return Optional.empty();
		}
	}

	private static String stringOr(JsonObject o, String key, String fallback) {
		JsonElement el = o.get(key);
		if (el == null || el.isJsonNull()) {
			return fallback;
		}
		if (el.isJsonPrimitive()) {
			return el.getAsString();
		}
		return el.toString();
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() || s.equals("null") ? null : s;
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max);
	}
}
