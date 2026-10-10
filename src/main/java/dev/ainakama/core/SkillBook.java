package dev.ainakama.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * プレイヤーに教わって覚えた「技（名前の付いた手順）」と「決まり（〇〇したら△△する）」の帳面。
 * 覚えた技は、頼まれたときにAIを使わずに実行する。仲間全員で共有する。
 */
public final class SkillBook {
	public static final int MAX_SKILLS = 100;
	/** 技を別の技から呼ぶときの、入れ子の深さの上限（自分自身を呼ぶ技で止まらなくなるのを防ぐ）。 */
	public static final int MAX_NESTING = 3;

	private final Map<String, Plan.Skill> skills = new LinkedHashMap<>();
	private final Map<Trigger, Plan.Rule> rules = new EnumMap<>(Trigger.class);

	public synchronized void learn(Plan.Skill skill) {
		String key = stem(skill.name());
		skills.remove(key);
		if (skills.size() >= MAX_SKILLS) {
			skills.remove(skills.keySet().iterator().next());
		}
		skills.put(key, skill);
	}

	public synchronized boolean forget(String name) {
		return skills.remove(stem(name)) != null;
	}

	public synchronized Optional<Plan.Skill> find(String name) {
		return Optional.ofNullable(skills.get(stem(name)));
	}

	public synchronized boolean knows(String name) {
		return skills.containsKey(stem(name));
	}

	public synchronized List<Plan.Skill> all() {
		return List.copyOf(skills.values());
	}

	/**
	 * プレイヤーの言葉が、覚えた技の名前そのものなら、その技を返す。
	 * 「拠点を明るくして」と「拠点を明るくする」のような語尾の違いは同じとみなす。
	 */
	public synchronized Optional<Plan.Skill> matchRequest(String text) {
		return Optional.ofNullable(skills.get(stem(text)));
	}

	/** 今回の言葉に関係がありそうな技の名前（AIに渡す分）。 */
	public synchronized List<String> relevantNames(String text, int max) {
		List<String> names = skills.values().stream().map(Plan.Skill::name).toList();
		return Similarity.top(text, names, max);
	}

	public synchronized void setRule(Plan.Rule rule) {
		rules.put(rule.when(), rule);
	}

	public synchronized boolean removeRule(Trigger trigger) {
		return rules.remove(trigger) != null;
	}

	public synchronized Optional<Plan.Rule> rule(Trigger trigger) {
		return Optional.ofNullable(rules.get(trigger));
	}

	public synchronized List<Plan.Rule> rules() {
		return List.copyOf(rules.values());
	}

	/**
	 * 命令の列の中の「技を使う」を、その技の手順に置き換える。
	 * 知らない技や、入れ子が深すぎるものは取り除く。
	 */
	public synchronized List<Command> expand(List<Command> commands) {
		return expand(commands, 0);
	}

	private List<Command> expand(List<Command> commands, int depth) {
		List<Command> out = new ArrayList<>();
		for (Command c : commands) {
			if (c instanceof Command.UseSkill use) {
				Plan.Skill skill = skills.get(stem(use.name()));
				if (skill != null && depth < MAX_NESTING) {
					out.addAll(expand(skill.steps(), depth + 1));
				}
			} else {
				out.add(c);
			}
		}
		return out;
	}

	/** 頼みの言葉から、技の名前にするところを取り出す。「キャンプの準備をして」→「キャンプの準備」。 */
	public static String nameOf(String request) {
		return stem(request);
	}

	/** 名前の語尾（〜して、〜する、〜してね など）と記号を取って、比べやすい形にする。 */
	static String stem(String name) {
		String s = QuickCommands.normalize(name).replaceAll("[!！。?？「」]", "");
		s = s.replaceAll("(してください|してほしい|して欲しい|してくれる|してくれ|してね|してよ|して|する|しよう|してきて|をやって|をお願い|お願い)$", "");
		return s.replaceAll("(を|に|は)$", "");
	}

	public synchronized JsonObject toJson() {
		JsonObject root = new JsonObject();
		JsonArray skillArr = new JsonArray();
		for (Plan.Skill s : skills.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", s.name());
			o.add("steps", CommandJson.write(s.steps()));
			skillArr.add(o);
		}
		root.add("skills", skillArr);
		JsonArray ruleArr = new JsonArray();
		for (Plan.Rule r : rules.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("when", r.when().id());
			o.add("steps", CommandJson.write(r.steps()));
			ruleArr.add(o);
		}
		root.add("rules", ruleArr);
		return root;
	}

	/** 保存したものを読み込む。読めない技や、中身が空になった技は捨てる。 */
	public synchronized void load(JsonObject root, PlanParser parser) {
		skills.clear();
		rules.clear();
		for (JsonElement el : array(root, "skills")) {
			JsonObject o = el.getAsJsonObject();
			List<Command> steps = parser.parseSteps(array(o, "steps"));
			if (o.has("name") && !steps.isEmpty()) {
				Plan.Skill skill = new Plan.Skill(o.get("name").getAsString(), steps);
				skills.put(stem(skill.name()), skill);
			}
		}
		for (JsonElement el : array(root, "rules")) {
			JsonObject o = el.getAsJsonObject();
			Optional<Trigger> when = Trigger.parse(o.has("when") ? o.get("when").getAsString() : "");
			List<Command> steps = parser.parseSteps(array(o, "steps"));
			if (when.isPresent() && !steps.isEmpty()) {
				rules.put(when.get(), new Plan.Rule(when.get(), steps));
			}
		}
	}

	private static JsonArray array(JsonObject o, String key) {
		JsonElement el = o.get(key);
		return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
	}
}
