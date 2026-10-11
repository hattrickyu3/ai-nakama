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
		  "do":{"type":"string","enum":["follow","stop","move_to","collect","craft","smelt","build","place","give","store","take","farm","fight","guard","hunt","pickup","till","get","shear","milk","breed","fish","light","buy","sell","skill","say","remember"]},
		  "item":{"type":"string"},"count":{"type":"integer"},"blueprint":{"type":"string"},
		  "at":{"type":"string"},"text":{"type":"string"},"name":{"type":"string"}},"required":["do"]}
		""";

	/**
	 * Ollama の format に渡す、返事の形の決まり（JSON Schema）。
	 * 技や決まりを教わるところは {@link TeachParser} がプログラムで読むので、AIの返事には入れない（小さいAIが混乱しないように）。
	 */
	public static final String RESPONSE_SCHEMA = """
		{"type":"object","properties":{
		 "say":{"type":"string"},
		 "plan":{"type":"array","maxItems":8,"items":%1$s},
		 "memo":{"type":["string","null"]},
		 "ask":{"type":["string","null"]}},
		 "required":["say","plan"]}
		""".formatted(STEP_SCHEMA);

	/** 今回の言葉と一緒にAIへ渡す、増えていく情報。 */
	public record Extras(List<String> skillNames, List<String> blueprintNames) {
		public static Extras none() {
			return new Extras(List.of(), List.of());
		}
	}

	private PromptBuilder() {
	}

	public static String system(String name, String personality) {
		return system(name, personality, false);
	}

	/** get の説明。小さいAIは、これを読むと数を1個にしたり、聞き返すべき頼みに勝手に動き出したりするので、渡さない。 */
	private static final String GET_LINE =
		"- get(item, count): アイテムを用意する。集める・狩る・焼く・クラフトする・チェストから出す、のどれにするかはプログラムが決める。手に入れ方に迷ったら get\n";

	/**
	 * @param small PCの中で動く小さいAIか。小さいAIには、説明を減らす（get は、書いてきたら受け付けるが、説明はしない）
	 */
	public static String system(String name, String personality, boolean small) {
		return """
			あなたはマインクラフトの世界に住む村人「%s」。性格: %s
			プレイヤーの言葉を聞いて、短い返事(say)と、実行する命令の並び(plan)を決める。
			歩く・掘る・途中の材料のクラフト・足りない材料や道具の用意・かまどや燃料の用意・整地はプログラムが自動でやるので、planは大まかな手順だけでよい。

			使える命令:
			- follow: プレイヤーについていくだけ / guard: プレイヤーについていき、近づく敵を倒して守る / stop: 今の作業をやめる
			- move_to(at): 場所へ行く
			- collect(item, count): 資源を集める（原木・丸石・石炭・鉄の原石・土・砂・砂利）。地下のものは自分で掘り下げて取る
			- craft(item, count): クラフトする（マインクラフトのレシピにあるもの）
			- smelt(item, count): かまどで焼く。item は焼いてできるもの（iron_ingot, glass, charcoal, cooked_beef など）。かまど・燃料・焼く前の材料の用意はプログラムが自動でやる
			- build(blueprint, at): 建物を建てる（hut=小屋, fence=柵、ほかは「覚えた設計図」）
			- place(item, at): ブロックを1つ置く（扉・松明・作業台・チェストなど）
			- give(item, count): プレイヤーに渡す。item を省くと道具以外を全部渡す
			- store: 持ち物を近くのチェストにしまう / take(item, count): 近くのチェストから取り出す
			- farm: 畑の世話（育った作物を収穫して植え直す。空いた畑には持っている種をまく）
			- till(at, count): 地面を耕して新しい畑にする（クワは自分で作る）。count は耕すマスの数。種をまくには、続けて farm
			- fight: 近くのモンスターを倒す（動物は hunt）
			- hunt(item, count): 動物を狩って、落としたものを拾う。item は動物（cow, pig, sheep, chicken, rabbit）か、ほしいもの（beef, porkchop, mutton, chicken, leather, feather, white_wool）
			- pickup: 近くに落ちているアイテムを拾う
			- shear(count): ハサミで羊の毛を刈る / milk(count): バケツで牛の乳をしぼる / breed(item, count): 動物にエサをやって増やす（item は動物）
			- fish(count): 釣りをする / light(at): まわりの暗いところに松明を置いて明るくする
			- buy(item, count): 村人から買う / sell(item, count): 村人に売る（count は取引の回数）
%s			- skill(name): 覚えた技を使う
			- say(text): 話す / remember(text): 覚えておく
			場所(at): home=拠点, player=プレイヤーの足元, here=プレイヤーが見ているところ, self=自分のいる場所, "x y z"=座標
			item はマインクラフトのID（oak_log, cobblestone, torch, oak_door, crafting_table など）。1スタック=64個。

			ルール:
			- say は1〜2文の短い日本語。性格に合った話し方で。
			- 雑談や質問には say だけで答える（plan は空）。座標などは状況に書いてあることを答える。
			- 集める・作る・建てる・置く・渡すことを頼まれたら、必ず plan に命令を書く（「〇〇がほしい」は craft、「取ってきて」は collect）。頼まれたことは最後の手順まで全部書く。
			- 石・石炭・鉄は、近くの資源に書いていなくても collect で掘り下げて探せる（鉄は raw_iron。鉄インゴットは smelt）。
			- 使える命令にないことを頼まれたときは、plan を空にして、できないと正直に言う。
			- 何をすればいいのかはっきりしない頼み（「キャンプの準備をして」「冬支度して」など）は、勝手に決めずに、plan を空にして ask に質問を書く。
			  例: {"say": "キャンプの準備？", "plan": [], "ask": "「キャンプの準備」って、何をすればいいの？"}
			- 覚えておくべき事実（好み・場所・コツ）があれば memo に書く。
			出力はJSONだけ。使わない項目は省いてよい: {"say": "...", "plan": [{"do": "collect", "item": "oak_log", "count": 10}]}
			""".formatted(name, personality, small ? "" : GET_LINE);
	}

	/** プレイヤーに話しかけられたとき。 */
	public static String userForMessage(String name, Status status, Memory memory, Extras extras, String playerText) {
		return userForMessage(name, status, memory, extras, playerText, false);
	}

	/**
	 * @param explainQueue 作業中の頼みがどう扱われるかを説明するか。小さいAI（qwen3.5:4b）は、この説明を読むと
	 *                     感想を言われただけで stop を書いて今の作業をやめてしまうので、賢いAIのときだけ true にする
	 */
	public static String userForMessage(String name, Status status, Memory memory, Extras extras, String playerText, boolean explainQueue) {
		return context(name, status, memory, extras, playerText) + (explainQueue ? whileWorking(status) : "")
			+ "[今回]\nプレイヤー: " + playerText;
	}

	/**
	 * 作業中に話しかけられたときだけ足す説明。
	 * 小さいAIは説明が増えると慎重になりすぎる（手順を省く）ので、いつもの説明（system）には入れない。
	 */
	private static String whileWorking(Status status) {
		String job = status.currentJob();
		// 手が空いているとき、実行側（TaskRunner）は今の作業を「なし」と書く
		if (job == null || job.isBlank() || job.equals("なし")) {
			return "";
		}
		return "[作業中の注意]\nいまは作業中。plan を書くと、今の作業が終わったあとに実行される。"
			+ "今の作業をやめて別のことをさせたいときだけ、plan の最初に stop を書く。"
			+ "今の作業への感想や注文には say だけで答える（今の作業を plan に書き直さない）。\n";
	}

	/** プログラムで直せない失敗が起きたとき。 */
	public static String userForFailure(String name, Status status, Memory memory, Extras extras, String failedCommand, String reason) {
		return context(name, status, memory, extras, failedCommand + " " + reason)
			+ "[今回]\n作業「" + failedCommand + "」が失敗した。理由: " + reason
			+ "\n別のやり方で計画を立て直す。できないなら plan を空にして、プレイヤーに相談する返事をする。";
	}

	/**
	 * プレイヤーがやり方を教えてくれたが、プログラムでは読み取れなかったとき。説明を命令の並びに直してもらう。
	 *
	 * @param what 教わっているもの（「キャンプの準備」や「夜になったら」）
	 */
	public static String userForTeaching(String name, Status status, Memory memory, Extras extras, String what, String explanation) {
		return context(name, status, memory, extras, what + " " + explanation)
			+ "[今回]\nプレイヤーが「" + what + "」のやり方を教えてくれた: 「" + explanation + "」"
			+ "\nこの説明を、使える命令の並びに直して plan に書く（今は実行しない。覚えるための手順）。"
			+ "\n使える命令ではできない動きが入っていたら、plan を空にして、say で何ができないかを伝える。";
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
