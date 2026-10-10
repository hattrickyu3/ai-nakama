package dev.ainakama.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AIに送る文章を組み立てる。
 * system（毎回同じ部分）と user（状況・記憶・会話・今回の言葉）に分ける。
 * system を毎回同じにしておくと、Ollama やAPIのキャッシュが効いて速く・安くなる。
 * そのため、覚えた技や設計図のように増えていくものは system には入れず、関係のあるものだけを user に入れる。
 */
public final class PromptBuilder {
	private static final String STEP_SCHEMA = """
		{"type":"object","properties":{
		  "do":{"type":"string","enum":["follow","stop","move_to","collect","craft","build","place","give","store","skill","say","remember"]},
		  "item":{"type":"string"},"count":{"type":"integer"},"blueprint":{"type":"string"},
		  "at":{"type":"string"},"text":{"type":"string"},"name":{"type":"string"}},"required":["do"]}
		""";

	/** Ollama の format に渡す、返事の形の決まり（JSON Schema）。 */
	public static final String RESPONSE_SCHEMA = """
		{"type":"object","properties":{
		 "say":{"type":"string"},
		 "plan":{"type":"array","maxItems":8,"items":%1$s},
		 "memo":{"type":["string","null"]},
		 "ask":{"type":["string","null"]},
		 "teach":{"type":["object","null"],"properties":{"name":{"type":"string"},"steps":{"type":"array","maxItems":8,"items":%1$s}},"required":["name","steps"]},
		 "rule":{"type":["object","null"],"properties":{
		   "when":{"type":"string","enum":["night","morning","rain","enemy","inventory_full","player_far","idle"]},
		   "steps":{"type":"array","maxItems":8,"items":%1$s}},"required":["when","steps"]}},
		 "required":["say","plan"]}
		""".formatted(STEP_SCHEMA);

	/** 今回の言葉と一緒にAIへ渡す、増えていく情報。 */
	public record Extras(List<String> skillNames, List<String> blueprintNames, String pendingQuestion) {
		public static Extras none() {
			return new Extras(List.of(), List.of(), null);
		}
	}

	private PromptBuilder() {
	}

	public static String system(String name, String personality) {
		return """
			あなたはマインクラフトの世界に住む村人「%s」。性格: %s
			プレイヤーの言葉を聞いて、短い返事(say)と、実行する命令の並び(plan)を決める。
			歩く・掘る・途中の材料のクラフト・足りない材料や道具の用意・整地はプログラムが自動でやるので、planは大まかな手順だけでよい。

			使える命令:
			- follow: プレイヤーについていく / stop: 今の作業をやめる
			- move_to(at): 場所へ行く
			- collect(item, count): 資源を集める（原木・丸石・石炭・鉄の原石・土・砂・砂利）。地下のものは自分で掘り下げて取る
			- craft(item, count): クラフトする（マインクラフトのレシピにあるもの）
			- build(blueprint, at): 建物を建てる（hut=小屋, fence=柵、ほかは「覚えた設計図」）
			- place(item, at): ブロックを1つ置く（扉・松明・作業台・チェストなど）
			- give(item, count): プレイヤーに渡す。item を省くと道具以外を全部渡す
			- store: 持ち物を近くのチェストにしまう
			- skill(name): 覚えた技を使う
			- say(text): 話す / remember(text): 覚えておく
			場所(at): home=拠点, player=プレイヤーの足元, here=プレイヤーが見ているところ, self=自分のいる場所, "x y z"=座標
			item はマインクラフトのID（oak_log, cobblestone, torch, oak_door, crafting_table など）。1スタック=64個。

			ルール:
			- say は1〜2文の短い日本語。性格に合った話し方で。
			- 雑談や質問には say だけで答える（plan は空）。座標などは状況に書いてあることを答える。
			- 上の命令でできないことは、やると言わない。やり方がわからない頼みは plan を空にして、ask に質問を書く（例: 「拠点を明るくする」ってどうやるの？）。
			- プレイヤーがやり方を教えてくれたら、teach に {"name": 技の名前, "steps": [命令]} を書いて覚える。plan にも同じ命令を書いてすぐやる。
			- 「夜になったら〇〇して」のような決まりは、rule に {"when": きっかけ, "steps": [命令]} を書く。きっかけ: night, morning, rain, enemy, inventory_full, player_far, idle
			- 覚えておくべき事実（好み・場所・コツ）があれば memo に書く。
			出力はJSONだけ。使わない項目は省いてよい: {"say": "...", "plan": [{"do": "collect", "item": "oak_log", "count": 10}]}
			""".formatted(name, personality);
	}

	/** プレイヤーに話しかけられたとき。 */
	public static String userForMessage(String name, Status status, Memory memory, Extras extras, String playerText) {
		StringBuilder sb = new StringBuilder(context(name, status, memory, extras, playerText));
		sb.append("[今回]\n");
		if (extras.pendingQuestion() != null) {
			sb.append("あなたはさっき「").append(extras.pendingQuestion()).append("」と聞いた。次の言葉がその答えなら teach で覚える。\n");
		}
		return sb.append("プレイヤー: ").append(playerText).toString();
	}

	/** プログラムで直せない失敗が起きたとき。 */
	public static String userForFailure(String name, Status status, Memory memory, Extras extras, String failedCommand, String reason) {
		return context(name, status, memory, extras, failedCommand + " " + reason)
			+ "[今回]\n作業「" + failedCommand + "」が失敗した。理由: " + reason
			+ "\n別のやり方で計画を立て直す。できないなら plan を空にして、プレイヤーに相談する返事をする。";
	}

	/** AIの返事が決まりどおりでなかったとき、間違いを添えて聞き直す。 */
	public static String userForRetry(String previousUser, String badReply, List<String> errors) {
		return previousUser
			+ "\n\n[前の返事に間違いがあった]\n" + badReply
			+ "\n間違い: " + String.join(" / ", errors)
			+ "\n決まりどおりのJSONで答え直して。";
	}

	private static String context(String name, Status status, Memory memory, Extras extras, String query) {
		StringBuilder sb = new StringBuilder();
		sb.append("[状況]\n").append(status.toText()).append("\n");
		List<String> notes = memory.relevantNotes(query);
		if (!notes.isEmpty()) {
			sb.append("[覚えていること]\n");
			notes.forEach(n -> sb.append("- ").append(n).append("\n"));
		}
		if (!extras.skillNames().isEmpty()) {
			sb.append("[覚えた技] ").append(extras.skillNames().stream().collect(Collectors.joining("、"))).append("\n");
		}
		if (!extras.blueprintNames().isEmpty()) {
			sb.append("[覚えた設計図] ").append(String.join("、", extras.blueprintNames())).append("\n");
		}
		if (!memory.recent().isEmpty()) {
			sb.append("[最近の会話]\n");
			for (Memory.Exchange e : memory.recent()) {
				sb.append("プレイヤー: ").append(e.player()).append("\n");
				sb.append(name).append(": ").append(e.reply()).append("\n");
			}
		}
		return sb.toString();
	}
}
