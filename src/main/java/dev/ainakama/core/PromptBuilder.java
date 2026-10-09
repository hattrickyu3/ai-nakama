package dev.ainakama.core;

import java.util.stream.Collectors;

/**
 * AIに送る文章を組み立てる。
 * system（毎回同じ部分）と user（状況・記憶・会話・今回の言葉）に分ける。
 * system を毎回同じにしておくと、Ollama やAPIのキャッシュが効いて速く・安くなる。
 */
public final class PromptBuilder {
	/** Ollama の format に渡す、返事の形の決まり（JSON Schema）。 */
	public static final String RESPONSE_SCHEMA = """
		{"type":"object","properties":{
		 "say":{"type":"string"},
		 "plan":{"type":"array","maxItems":8,"items":{"type":"object","properties":{
		  "do":{"type":"string","enum":["follow","stop","move_to","collect","craft","build","store","say","remember"]},
		  "item":{"type":"string"},"count":{"type":"integer"},"blueprint":{"type":"string"},
		  "at":{"type":"string"},"text":{"type":"string"}},"required":["do"]}},
		 "memo":{"type":["string","null"]}},
		 "required":["say","plan"]}
		""";

	private PromptBuilder() {
	}

	public static String system(String name, String personality) {
		String blueprints = Blueprint.all().stream()
			.map(b -> b.id() + "=" + b.japaneseName())
			.collect(Collectors.joining(", "));
		return """
			あなたはマインクラフトの世界に住む村人「%s」。性格: %s
			プレイヤーの言葉を聞いて、短い返事(say)と、実行する命令の並び(plan)を決める。
			歩く・掘る・途中の材料のクラフト・足りない材料や道具の用意はプログラムが自動でやるので、planは大まかな手順だけでよい。

			使える命令:
			- follow: プレイヤーについていく
			- stop: 今の作業をやめる
			- move_to(at): 場所へ行く
			- collect(item, count): 資源を集める（木・石・土・砂・石炭など）
			- craft(item, count): クラフトする
			- build(blueprint, at): 建物を建てる（材料は自動で集める）
			- store: 持ち物を近くのチェストにしまう
			- say(text): 話す
			- remember(text): 覚えておく
			設計図(blueprint): %s
			場所(at): home=拠点, player=プレイヤーのそば, here=自分のいる場所, または "x y z" の座標
			item はマインクラフトのID（oak_log, oak_planks, cobblestone, stick, crafting_table, wooden_pickaxe, torch など）

			ルール:
			- say は1〜2文の短い日本語。性格に合った話し方で。
			- plan は最大8個。雑談だけなら空でよい。
			- できないこと（戦う・畑仕事・取引など）を頼まれたら、plan を空にして、できないと伝える。
			- 覚えておくべき事実（プレイヤーの好み、場所の情報）があれば memo に書く。なければ null。
			出力はJSONだけ: {"say": "...", "plan": [{"do": "collect", "item": "oak_log", "count": 10}], "memo": null}
			""".formatted(name, personality, blueprints);
	}

	/** プレイヤーに話しかけられたとき。 */
	public static String userForMessage(String name, Status status, Memory memory, String playerText) {
		return context(name, status, memory) + "[今回]\nプレイヤー: " + playerText;
	}

	/** プログラムで直せない失敗が起きたとき。 */
	public static String userForFailure(String name, Status status, Memory memory, String failedCommand, String reason) {
		return context(name, status, memory)
			+ "[今回]\n作業「" + failedCommand + "」が失敗した。理由: " + reason
			+ "\n計画を立て直すか、できないならプレイヤーに相談する返事をして。";
	}

	/** AIの返事が決まりどおりでなかったとき、間違いを添えて聞き直す。 */
	public static String userForRetry(String previousUser, String badReply, java.util.List<String> errors) {
		return previousUser
			+ "\n\n[前の返事に間違いがあった]\n" + badReply
			+ "\n間違い: " + String.join(" / ", errors)
			+ "\n決まりどおりのJSONで答え直して。";
	}

	private static String context(String name, Status status, Memory memory) {
		StringBuilder sb = new StringBuilder();
		sb.append("[状況]\n").append(status.toText()).append("\n");
		if (!memory.notes().isEmpty()) {
			sb.append("[覚えていること]\n");
			memory.notes().forEach(n -> sb.append("- ").append(n).append("\n"));
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
