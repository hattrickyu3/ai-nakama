package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmallPartsTest {
	private static final Set<String> KNOWN = Set.of("oak_log", "oak_planks", "cobblestone", "stick", "torch", "oak_door", "crafting_table");

	private static List<Command> commands(String text) {
		return QuickCommands.match(text, KNOWN::contains).orElseThrow(() -> new AssertionError("読めなかった: " + text))
			.plan().orElseThrow().commands();
	}

	private static Optional<QuickCommands.Special> special(String text) {
		return QuickCommands.match(text, KNOWN::contains).flatMap(QuickCommands.Match::special);
	}

	@Test
	void quickCommandsHandleFixedPhrases() {
		assertEquals(List.of(new Command.Follow()), commands("ついてきて"));
		assertEquals(List.of(new Command.Stop()), commands("止まって！"));
		assertEquals(List.of(new Command.Collect("oak_log", 10)), commands("木を10本集めて"));
		assertEquals(List.of(new Command.Build("hut", new Place.Looking())), commands("小屋を建てて"));
		assertEquals(List.of(new Command.Build("hut", new Place.Home())), commands("拠点に小屋を建てて"));
		assertEquals(List.of(new Command.Craft("torch", 8)), commands("松明を8個作って"));
		assertEquals(Optional.of(QuickCommands.Special.SET_HOME), special("ここを拠点にして"));
	}

	@Test
	void phrasesFromTheFirstPlaySession() {
		// 2026-10-10 に実際に遊んだとき、通じなかったり1個しか掘らなかったりした言い方
		assertEquals(List.of(new Command.Collect("cobblestone", 64)), commands("石を一スタック掘ってきて"));
		assertEquals(List.of(new Command.Collect("cobblestone", 64)), commands("石を一スタックくらい掘ってきてよ"));
		assertEquals(List.of(new Command.Give(null, 0)), commands("そしたら掘ったやつを私に渡してください"));
		assertEquals(List.of(new Command.Give(null, 0)), commands("私の目の前にアイテムをドロップして"));
		assertEquals(List.of(new Command.Store()), commands("そしたらこのチェストに入れて"));
		assertEquals(List.of(new Command.MoveTo(new Place.Home())), commands("もう帰っていいよ"));
		assertEquals(Optional.of(QuickCommands.Special.WHERE_HOME), special("拠点はどこにあるの？"));
		assertEquals(Optional.of(QuickCommands.Special.WHERE_HOME), special("君の拠点がどこなのかを教えてほしいんだ"));
		assertEquals(Optional.of(QuickCommands.Special.WHERE_HOME), special("そのhomeの座標が知りたい"));
	}

	@Test
	void giveAndPlace() {
		assertEquals(List.of(new Command.Give("cobblestone", 0)), commands("石を渡して"));
		assertEquals(List.of(new Command.Give("oak_log", 5)), commands("原木を5本ちょうだい"));
		assertEquals(List.of(new Command.PlaceBlock("oak_door", new Place.Looking())), commands("ここに扉を置いて"));
		assertEquals(List.of(new Command.PlaceBlock("crafting_table", new Place.Looking())), commands("ここに作業台を置いて"));
	}

	@Test
	void rememberingFactsNeedsNoAi() {
		assertEquals(List.of(new Command.Remember("僕は青い色が好きなんだ")), commands("僕は青い色が好きなんだ。覚えておいて"));
		assertEquals(List.of(new Command.Remember("鉄は拠点の北の洞窟にある")), commands("鉄は拠点の北の洞窟にあるって覚えておいて"));
	}

	@Test
	void severalRequestsInOneSentence() {
		assertEquals(List.of(new Command.Collect("oak_log", 2), new Command.Craft("torch", 4)),
			TeachParser.steps("木を2本集めて、松明を4個作って", KNOWN::contains));
		assertEquals(List.of(new Command.Collect("cobblestone", 8), new Command.Give(null, 0)),
			TeachParser.steps("石を8個掘ってきてそれを渡して", KNOWN::contains));
		// 1つでも読めないものがあれば、全部をAIに任せる
		assertTrue(TeachParser.steps("木を2本集めて、空を飛んで", KNOWN::contains).isEmpty());
	}

	@Test
	void teachingABuilding() {
		assertEquals(Optional.of(QuickCommands.Special.WATCH), special("見てて"));
		assertEquals(Optional.of(QuickCommands.Special.WATCH), special("今から見張り台を建てるから見てて"));
		var m = QuickCommands.match("これが見張り台だよ", KNOWN::contains).orElseThrow();
		assertEquals(Optional.of(QuickCommands.Special.NAME_BUILD), m.special());
		assertEquals("見張り台", m.arg());
		assertEquals("井戸", QuickCommands.match("これを「井戸」として覚えて", KNOWN::contains).orElseThrow().arg());
	}

	@Test
	void freeTextGoesToAi() {
		assertTrue(QuickCommands.match("冬に備えて何かしておいて", KNOWN::contains).isEmpty());
		assertTrue(QuickCommands.match("ダイヤを10個集めて", KNOWN::contains).isEmpty());
		// 板材は集めるものではない（家を壊さないため）。とても大きな数も受け付けない
		assertTrue(QuickCommands.match("木材を10個集めて", KNOWN::contains).isEmpty());
		assertTrue(QuickCommands.match("木を99999999999本集めて", KNOWN::contains).isEmpty());
	}

	@Test
	void quantities() {
		assertEquals(Optional.of(10), Quantity.parse("10"));
		assertEquals(Optional.of(64), Quantity.parse("一スタック"));
		assertEquals(Optional.of(64), Quantity.parse("スタック"));
		assertEquals(Optional.of(32), Quantity.parse("半スタック"));
		assertEquals(Optional.of(128), Quantity.parse("2スタック"));
		assertEquals(Optional.of(25), Quantity.parse("二十五"));
		assertEquals(Optional.of(10), Quantity.parse("十"));
		assertEquals(Optional.of(300), Quantity.parse("三百"));
		assertEquals(Optional.empty(), Quantity.parse(""));
	}

	@Test
	void logsAndCollectables() {
		assertTrue(Items.isLog("birch_log"));
		assertTrue(Items.isLog("crimson_stem"));
		assertFalse(Items.isLog("mushroom_stem"));
		assertTrue(Items.isCollectable("cobblestone"));
		assertFalse(Items.isCollectable("oak_planks"));
		assertFalse(Items.isCollectable("chest"));
		assertEquals("oak_planks", Items.planksFor("stripped_oak_wood"));
		assertEquals(Items.ANY_PLANKS, Items.keyFor(List.of("oak_planks", "birch_planks")));
		assertEquals("any:coal|charcoal", Items.keyFor(List.of("coal", "charcoal")));
		assertTrue(Items.matches("any:coal|charcoal", "charcoal"));
		assertEquals(Optional.of("coal"), Items.collectableFor("any:charcoal|coal"));
	}

	@Test
	void blueprintMaterials() {
		assertEquals(71, Blueprints.HUT.materials().get(Items.ANY_PLANKS));
		assertEquals(1, Blueprints.HUT.materials().get("oak_door"));
		assertEquals(23, Blueprints.FENCE.materials().get("oak_fence"));
		assertEquals(List.of(5, 4, 5), java.util.Arrays.stream(Blueprints.HUT.size()).boxed().toList());
	}

	@Test
	void learnedBlueprintsCanBeFoundAndForgotten() {
		Blueprints.clearLearned();
		Blueprint tower = new Blueprint("見張り台", "見張り台", List.of(
			new Blueprint.Part(0, 0, 0, "cobblestone"),
			new Blueprint.Part(0, 1, 0, "oak_door", "minecraft:oak_door[half=upper]", true)), true);
		Blueprints.learn(tower);

		assertEquals(Optional.of(tower), Blueprints.find("見張り台"));
		assertEquals(Map.of("cobblestone", 1), tower.materials());
		assertEquals(List.of(new Command.Build("見張り台", new Place.Home())), commands("拠点に見張り台を建てて"));
		assertTrue(Blueprints.forget("見張り台"));
		assertTrue(Blueprints.find("見張り台").isEmpty());
	}

	@Test
	void memoryKeepsLimitsAndPicksRelevantNotes() {
		Memory m = Memory.empty();
		for (int i = 0; i < Memory.MAX_NOTES + 5; i++) {
			m = m.withNote("メモ" + i).withExchange("p" + i, "r" + i);
		}
		assertEquals(Memory.MAX_NOTES, m.notes().size());
		assertEquals("メモ5", m.notes().get(0));
		assertEquals(Memory.MAX_RECENT, m.recent().size());

		m = m.withNote("石は土の下を3マス掘ると出てくる");
		List<String> picked = m.relevantNotes("石を掘ってきて");
		assertEquals(Memory.NOTES_PER_PROMPT, picked.size());
		assertTrue(picked.contains("石は土の下を3マス掘ると出てくる"));
	}

	@Test
	void budgetEnforcesCooldownAndDailyLimit() {
		CallBudget b = new CallBudget(5000, 4, ZoneId.of("Asia/Tokyo"));
		UUID a = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		long t = 1_800_000_000_000L;

		assertEquals(CallBudget.Decision.OK, b.tryAcquire(a, t));
		assertEquals(CallBudget.Decision.COOLDOWN, b.tryAcquire(a, t + 1000));
		// 失敗の立て直しは、直前の呼び出しのすぐあとでも呼べる
		assertEquals(CallBudget.Decision.OK, b.tryAcquire(a, t + 1000, true));
		assertEquals(CallBudget.Decision.OK, b.tryAcquire(c, t + 1000));
		assertEquals(CallBudget.Decision.OK, b.tryAcquire(a, t + 9000));
		// 1日の上限は、立て直しでも超えられない
		assertEquals(CallBudget.Decision.DAILY_LIMIT, b.tryAcquire(c, t + 20000, true));
		// 次の日になれば数え直す
		assertEquals(CallBudget.Decision.OK, b.tryAcquire(c, t + 86_400_000L));
	}

	@Test
	void directionText() {
		assertEquals("北に12マス", Status.direction(0, -12));
		assertEquals("南東に14マス", Status.direction(10, 10));
		assertEquals("西に5マス", Status.direction(-5, 1));
		assertEquals("すぐそば", Status.direction(1, 0));
	}

	@Test
	void promptStaysSmallEvenWithManySkills() {
		Map<String, Integer> inv = new LinkedHashMap<>();
		inv.put("stone_axe", 1);
		inv.put("oak_log", 3);
		Status s = new Status("朝", "晴れ", 20, 20, "座標 10 64 -5、拠点（5 64 -5）から東に5マス", inv,
			List.of("oak_log(オークの原木) 北に12マス 約30個", "stone 下に3マス"),
			List.of("羊×3 西に20マス"), List.of("home"), "なし");
		Memory m = Memory.empty();
		for (int i = 0; i < 100; i++) {
			m = m.withNote("覚えたこと その" + i + "：これはテスト用の知識です");
		}
		m = m.withExchange("おはよう", "おはよう！");
		SkillBook book = new SkillBook();
		for (int i = 0; i < 50; i++) {
			book.learn(new Plan.Skill("技その" + i, List.of(new Command.Follow())));
		}
		book.learn(new Plan.Skill("拠点を明るくする", List.of(new Command.Craft("torch", 8))));
		var extras = new PromptBuilder.Extras(book.relevantNames("拠点を明るくして", 8), List.of("見張り台"));

		String system = PromptBuilder.system("ミナ", "明るく元気");
		String user = PromptBuilder.userForMessage("ミナ", s, m, extras, "拠点を明るくして");

		// 日本語はおよそ1文字1トークン。技を50個・知識を100件覚えても、送る量は増え続けない
		// 命令を足すと説明も増える（狩る・拾う・耕すを足して約1850文字）。足すときは、ここの上限を見て増やしすぎないようにする
		assertTrue(system.length() < 2000, "system length " + system.length());
		assertTrue(user.length() < 900, "user length " + user.length());
		assertTrue(user.contains("拠点を明るくする"));
		assertTrue(user.contains("見張り台"));
	}
}
