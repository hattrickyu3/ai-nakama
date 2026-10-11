package dev.ainakama.ai;

import dev.ainakama.core.CallBudget;
import dev.ainakama.core.Items;
import dev.ainakama.core.Memory;
import dev.ainakama.core.PlanParser;
import dev.ainakama.core.PromptBuilder;
import dev.ainakama.core.Status;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 本物のAI（Ollama）に決まった依頼を送り、決まりどおりの返事が返るかを数える。
 * 普段のテストでは動かさない。`./gradlew test -Dainakama.live=true --tests '*LiveModelEval*'` で動かす。
 * モデルは -Dainakama.model=gemma4:e4b のように変えられる。
 */
@EnabledIfSystemProperty(named = "ainakama.live", matches = "true")
class LiveModelEval {
	private static final Set<String> ITEMS = Set.of("oak_log", "oak_planks", "stick", "crafting_table", "chest", "cobblestone",
		"coal", "torch", "wooden_pickaxe", "stone_pickaxe", "furnace", "dirt", "sand", "wheat", "bread", "oak_fence", "oak_door", "raw_iron", "iron_ingot", "white_wool",
		"glass", "charcoal", "stone", "wheat_seeds", "beef", "cooked_beef", "porkchop", "mutton", "chicken", "leather", "feather", "emerald", "milk_bucket", "cod", "shears", "bucket", "fishing_rod");

	private static final List<String> REQUESTS = List.of(
		"石のツルハシがほしいな",
		"夜になる前に明かりを用意しておいて",
		"疲れたでしょ、ちょっと休んでいいよ。そのあと木を3本だけ取ってきてくれる？",
		"今日はいい天気だね",
		"拠点の座標を教えて",
		"羊を倒して羊毛を取ってきて",
		"丸石を集めてかまどを作って、できたら私に渡して",
		"僕は青い色が好きなんだ。覚えておいて",
		"扉をもう1つ作って、私が見ているところに付けて",
		"鉄を少し掘ってきてほしい",
		"冬支度をして",
		"キャンプの準備をして",
		"チェストにある石を少し持ってきてくれる？",
		"砂を焼いてガラスにしておいてほしいな",
		"鉄のインゴットが3つ必要なんだ",
		"畑の小麦が育ったみたいだから、お願いできる？",
		"ゾンビが来た！助けて！",
		"これから洞窟に行くから、そばで守っていてね",
		"お腹がすいたな。何か肉を取ってきてくれない？",
		"本を作りたいから、革が3枚ほしいんだ",
		"さっき倒したゾンビが何か落としたみたい。拾っておいて",
		"拠点のそばに、小麦を育てる畑を新しく作ってほしいな",
		"夜が怖いから、拠点のまわりを明るくしておいて",
		"村人に小麦を売って、エメラルドにしてきて",
		"魚が食べたいな。釣ってきてくれる？",
		"牛をもっと増やしておいてほしい",
		"ケーキを作りたいから、牛乳を用意しておいて");

	/** 作業中（木を集めている途中）に話しかけられたときの頼み。2026-10-10 に実際に遊んだときの言い方を含む。 */
	private static final List<String> REQUESTS_WHILE_WORKING = List.of(
		"あのね、木は上まで残らず取り切ってほしいんだ",
		"がんばってるね！",
		"終わったら石も30個掘ってきて",
		"やっぱり木はもういいや。石を掘ってきて",
		"あとで松明も8個作っておいて");

	/** 作業中の頼み：感想には say だけ、追加の頼みは plan、やめさせる頼みは plan の最初に stop、となっているかを目で読む。 */
	@Test
	void evaluateWhileWorking() {
		AiConfig cfg = new AiConfig();
		cfg.provider = System.getProperty("ainakama.provider", cfg.provider);
		cfg.model = System.getProperty("ainakama.model", "qwen3.5:4b");
		AiService service = new AiService(LlmClient.create(cfg, Path.of("build", "claude-cli")), new CallBudget(0, 1000, ZoneId.systemDefault()),
			new PlanParser(ITEMS::contains), new UsageLog(Path.of("build", "live-eval-usage.csv")), cfg.model, System::currentTimeMillis);
		Status status = new Status("昼", "晴れ", 20, 20, "座標 10 64 -5、拠点（5 64 -5）から東に5マス", Map.of("oak_log", 14),
			List.of(Items.label("oak_log") + " 北に12マス 約30個", "stone 下に3マス"), List.of(), List.of("home"),
			"oak_log(オークの原木)を集める（14/30）（このあと1個）");
		String system = PromptBuilder.system("ミナ", cfg.defaultPersonality, cfg.isSmallLocalModel());
		for (String req : REQUESTS_WHILE_WORKING) {
			String user = PromptBuilder.userForMessage("ミナ", status, Memory.empty(), PromptBuilder.Extras.none(), req, !cfg.isSmallLocalModel());
			var out = service.ask(UUID.randomUUID(), "eval", system, user).join();
			System.out.println("\n### 作業中: " + req);
			if (out instanceof AiService.Outcome.Success s) {
				System.out.println("say: " + s.plan().say());
				s.plan().commands().forEach(c -> System.out.println("  - " + c.describe()));
			} else {
				System.out.println("  " + out);
			}
		}
	}

	@Test
	void evaluate() {
		AiConfig cfg = new AiConfig();
		cfg.provider = System.getProperty("ainakama.provider", cfg.provider);
		cfg.model = System.getProperty("ainakama.model", "qwen3.5:4b");
		PlanParser parser = new PlanParser(ITEMS::contains);
		UsageLog usage = new UsageLog(Path.of("build", "live-eval-usage.csv"));
		AiService service = new AiService(LlmClient.create(cfg, Path.of("build", "claude-cli")), new CallBudget(0, 1000, ZoneId.systemDefault()),
			parser, usage, cfg.model, System::currentTimeMillis);

		Map<String, Integer> inv = new LinkedHashMap<>();
		inv.put("stone_axe", 1);
		inv.put("oak_log", 3);
		Status status = new Status("昼", "晴れ", 20, 20, "座標 10 64 -5、拠点（5 64 -5）から東に5マス", inv,
			List.of(Items.label("oak_log") + " 北に12マス 約30個", "stone 下に3マス"),
			List.of("羊×3 西に20マス"), List.of("home"), "なし");
		String system = PromptBuilder.system("ミナ", cfg.defaultPersonality, cfg.isSmallLocalModel());

		int firstTryOk = 0;
		for (String req : REQUESTS) {
			String user = PromptBuilder.userForMessage("ミナ", status, Memory.empty(), PromptBuilder.Extras.none(), req);
			long start = System.currentTimeMillis();
			var out = service.ask(UUID.randomUUID(), "eval", system, user).join();
			long ms = System.currentTimeMillis() - start;
			System.out.println("\n### " + req + "  (" + ms + "ms)");
			if (out instanceof AiService.Outcome.Success s) {
				System.out.println("say: " + s.plan().say());
				if (s.plan().ask() != null) {
					System.out.println("ask: " + s.plan().ask());
				}
				if (s.plan().memo() != null) {
					System.out.println("memo: " + s.plan().memo());
				}
				s.plan().commands().forEach(c -> System.out.println("  - " + c.describe()));
				if (s.errors().isEmpty()) {
					firstTryOk++;
				} else {
					System.out.println("  残った間違い: " + s.errors());
				}
			} else {
				System.out.println("  " + out);
			}
		}
		var t = usage.today();
		System.out.println("\n=== " + cfg.model + ": 決まりどおり " + firstTryOk + "/" + REQUESTS.size()
			+ "、呼び出し " + t.calls() + "回、入力 " + t.inputTokens() + "、出力 " + t.outputTokens() + " トークン ===");
	}
}
