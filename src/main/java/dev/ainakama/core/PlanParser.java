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
	public static final int MAX_COUNT = 64;
	public static final int MAX_SAY_LENGTH = 200;

	public record Result(Plan plan, List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	private final Predicate<String> itemExists;

	/** @param itemExists Minecraft にそのアイテムIDがあるかを確かめる関数 */
	public PlanParser(Predicate<String> itemExists) {
		this.itemExists = itemExists;
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
		String memo = stringOr(obj, "memo", null);
		if (memo != null && (memo.isBlank() || memo.equals("null"))) {
			memo = null;
		}

		List<Command> commands = new ArrayList<>();
		JsonElement planEl = obj.get("plan");
		if (planEl != null && planEl.isJsonArray()) {
			JsonArray arr = planEl.getAsJsonArray();
			if (arr.size() > MAX_STEPS) {
				errors.add("plan は最大" + MAX_STEPS + "個まで（" + arr.size() + "個あった）");
			}
			for (int i = 0; i < Math.min(arr.size(), MAX_STEPS); i++) {
				JsonElement el = arr.get(i);
				if (!el.isJsonObject()) {
					errors.add((i + 1) + "番目の命令がオブジェクトではない");
					continue;
				}
				toCommand(el.getAsJsonObject(), i + 1, errors).ifPresent(commands::add);
			}
		} else if (planEl != null && !planEl.isJsonNull()) {
			errors.add("plan が配列ではない");
		}
		return new Result(new Plan(say, commands, memo), errors);
	}

	private Optional<Command> toCommand(JsonObject o, int index, List<String> errors) {
		String name = stringOr(o, "do", "").trim().toLowerCase(Locale.ROOT);
		String where = index + "番目(" + name + ")";
		switch (name) {
			case "follow":
				return Optional.of(new Command.Follow());
			case "stop":
				return Optional.of(new Command.Stop());
			case "store":
				return Optional.of(new Command.Store());
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
					errors.add(where + ": at が読めない（home / player / here / \"x y z\"）");
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
				int count = clampCount(o, where, errors);
				if (name.equals("collect") && !Items.isCollectable(item.get())) {
					errors.add(where + ": " + item.get() + " は集められない（集められるのは原木・丸石・石炭・鉄の原石・土・砂・砂利・粘土。作るものは craft）");
					return Optional.empty();
				}
				if (name.equals("craft") && !Recipes.canCraft(item.get())) {
					errors.add(where + ": " + item.get() + " の作り方を知らない");
					return Optional.empty();
				}
				return Optional.of(name.equals("collect")
					? new Command.Collect(item.get(), count)
					: new Command.Craft(item.get(), count));
			}
			case "build": {
				Optional<Blueprint> bp = Blueprint.find(stringOr(o, "blueprint", ""));
				if (bp.isEmpty()) {
					errors.add(where + ": 知らない設計図「" + stringOr(o, "blueprint", "") + "」（hut / fence）");
					return Optional.empty();
				}
				Optional<Place> place = Place.parse(stringOr(o, "at", "here"));
				if (place.isEmpty()) {
					errors.add(where + ": at が読めない");
					return Optional.empty();
				}
				return Optional.of(new Command.Build(bp.get().id(), place.get()));
			}
			case "farm":
			case "guard":
			case "take":
				errors.add(where + ": この命令はまだ使えない");
				return Optional.empty();
			default:
				errors.add(where + ": 知らない命令");
				return Optional.empty();
		}
	}

	private static int clampCount(JsonObject o, String where, List<String> errors) {
		JsonElement el = o.get("count");
		if (el == null || el.isJsonNull()) {
			return 1;
		}
		try {
			int n = el.getAsInt();
			if (n < 1 || n > MAX_COUNT) {
				errors.add(where + ": count は1〜" + MAX_COUNT + "（" + n + "だった）");
			}
			return Math.max(1, Math.min(MAX_COUNT, n));
		} catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
			errors.add(where + ": count が数字ではない");
			return 1;
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

	private static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max);
	}
}
