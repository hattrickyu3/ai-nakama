package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** チェストから取り出す・かまどで焼く・畑仕事・戦う、の言い方の読み取りと、焼くときの計算。 */
class NewAbilitiesTest {
	private static final Set<String> KNOWN = Set.of("oak_log", "oak_planks", "stick", "cobblestone", "stone", "coal", "charcoal",
		"raw_iron", "iron_ingot", "iron_block", "iron_pickaxe", "sand", "glass", "wheat", "wheat_seeds", "torch", "diamond", "diamond_pickaxe",
		"crafting_table", "furnace");
	private final SkillBook book = new SkillBook();
	private final PlanParser parser = new PlanParser(KNOWN::contains, book::knows);

	@AfterEach
	void resetTables() {
		Recipes.reset();
		Smelting.reset();
	}

	private static List<Command> commands(String text) {
		return QuickCommands.match(text, KNOWN::contains).orElseThrow(() -> new AssertionError("読めなかった: " + text))
			.plan().orElseThrow().commands();
	}

	@Test
	void takingFromAChest() {
		assertEquals(List.of(new Command.Take("cobblestone", 10)), commands("チェストから石を10個出して"));
		assertEquals(List.of(new Command.Take("iron_ingot", 0)), commands("チェストから鉄インゴットを取ってきて"));
		assertEquals(List.of(new Command.Take("cobblestone", 5)), commands("石をチェストから5個取り出して"));
		assertEquals(List.of(new Command.Take("oak_log", 64)), commands("近くの箱の中から原木を一スタック持ってきて"));
		// しまう・集めるは、これまでどおり
		assertEquals(List.of(new Command.Store()), commands("チェストにしまって"));
		assertEquals(List.of(new Command.Collect("cobblestone", 10)), commands("石を10個取ってきて"));
	}

	@Test
	void smeltingByInputOrOutputName() {
		// 焼く前のものの名前で言っても、焼いてできるものの名前で言ってもよい
		assertEquals(List.of(new Command.Smelt("iron_ingot", 8)), commands("鉄の原石を8個焼いて"));
		assertEquals(List.of(new Command.Smelt("iron_ingot", 0)), commands("鉄を焼いて"));
		assertEquals(List.of(new Command.Smelt("glass", 4)), commands("かまどで砂を4個焼いて"));
		assertEquals(List.of(new Command.Smelt("charcoal", 3)), commands("原木を3本焼いて"));
		assertEquals(List.of(new Command.Smelt("stone", 2)), commands("石を2個焼いてね"));
		// クラフトでは作れず、焼いて作るものを「作って」と言われたとき
		assertEquals(List.of(new Command.Smelt("glass", 4)), commands("ガラスを4個作って"));
		assertEquals(List.of(new Command.Smelt("charcoal", 2)), commands("木炭を2個作って"));
		// 焼けないものはAIに任せる
		assertTrue(QuickCommands.match("ダイヤを焼いて", KNOWN::contains).isEmpty());
		assertTrue(QuickCommands.match("松明を焼いて", KNOWN::contains).isEmpty());
	}

	@Test
	void farmingAndFighting() {
		for (String text : List.of("畑仕事して", "畑仕事をしてね", "畑の世話をして", "小麦を収穫して", "収穫して", "種を植えて")) {
			assertEquals(List.of(new Command.Farm()), commands(text), text);
		}
		for (String text : List.of("敵を倒して", "近くのゾンビをやっつけて", "戦って！")) {
			assertEquals(List.of(new Command.Fight()), commands(text), text);
		}
		for (String text : List.of("守って", "私を守ってね", "護衛して", "これから洞窟に行くから、そばで守っていてね", "夜は危ないのでずっと守っててね")) {
			assertEquals(List.of(new Command.Guard()), commands(text), text);
		}
		// プレイヤー以外を守る頼みは、決まった言い方としては受けない（AIに任せる）
		assertTrue(QuickCommands.match("敵が来るから拠点を守って", KNOWN::contains).isEmpty());
		// 「〜してから守って」は順番の指示。集めるほうを落とさない
		assertTrue(QuickCommands.match("木を5本集めてから守って", KNOWN::contains).isEmpty());
		assertEquals(List.of(new Command.Collect("oak_log", 5), new Command.Guard()), TeachParser.steps("木を5本集めてから守って", KNOWN::contains));
		// 「待って」は止まる、のまま
		assertEquals(List.of(new Command.Stop()), commands("待って"));
	}

	@Test
	void newCommandsWorkInChainsSkillsAndRules() {
		assertEquals(List.of(new Command.Collect("raw_iron", 3), new Command.Smelt("iron_ingot", 0)),
			TeachParser.steps("鉄を3個掘ってきて鉄を焼いて", KNOWN::contains));
		assertEquals(List.of(new Command.Take("cobblestone", 8), new Command.Give(null, 0)),
			TeachParser.steps("チェストから石を8個出して、それを渡して", KNOWN::contains));

		var morning = TeachParser.parse("朝になったら畑仕事をして", KNOWN::contains).orElseThrow();
		assertEquals(Trigger.MORNING, morning.trigger());
		assertEquals(List.of(new Command.Farm()), morning.steps());

		var enemy = TeachParser.parse("敵が来たら戦って", KNOWN::contains).orElseThrow();
		assertEquals(Trigger.ENEMY_NEAR, enemy.trigger());
		assertEquals(List.of(new Command.Fight()), enemy.steps());

		var patrol = TeachParser.parse("見張りっていうのは、敵を倒して拠点に帰ること", KNOWN::contains).orElseThrow();
		assertEquals("見張り", patrol.name());
		assertEquals(List.of(new Command.Fight(), new Command.MoveTo(new Place.Home())), patrol.steps());
	}

	@Test
	void parserReadsNewCommands() {
		var r = parser.parse("""
			{"say":"はい","plan":[
			 {"do":"take","item":"丸石","count":5},
			 {"do":"smelt","item":"raw_iron","count":3},
			 {"do":"smelt","item":"iron_ingot"},
			 {"do":"craft","item":"glass","count":2},
			 {"do":"farm"},{"do":"fight"},{"do":"guard"}]}
			""");
		assertTrue(r.ok(), () -> r.errors().toString());
		assertEquals(List.of(
			new Command.Take("cobblestone", 5),
			new Command.Smelt("iron_ingot", 3),
			new Command.Smelt("iron_ingot", 0),
			new Command.Smelt("glass", 2),
			new Command.Farm(), new Command.Fight(), new Command.Guard()), r.plan().commands());

		var bad = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"smelt\",\"item\":\"diamond\",\"count\":1}]}");
		assertFalse(bad.ok());
		assertTrue(bad.plan().commands().isEmpty());
	}

	@Test
	void furnacePreparationBeforeSmeltIsDropped() {
		// 2026-10-10 の LiveModelEval で、qwen3.5:4b が3回に2回こう答えた。smelt が自分で用意するので、かまどを増やさない
		var r = parser.parse("""
			{"say":"作るね","plan":[
			 {"do":"collect","item":"raw_iron","count":3},
			 {"do":"craft","item":"furnace","count":1},
			 {"do":"place","item":"furnace","at":"self"},
			 {"do":"smelt","item":"iron_ingot","count":3}]}
			""");
		assertTrue(r.ok(), () -> r.errors().toString());
		assertEquals(List.of(new Command.Collect("raw_iron", 3), new Command.Smelt("iron_ingot", 3)), r.plan().commands());

		// 焼く命令がなければ、かまどを作って置く頼みはそのまま
		var only = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"craft\",\"item\":\"furnace\",\"count\":1},{\"do\":\"place\",\"item\":\"furnace\",\"at\":\"here\"}]}");
		assertEquals(2, only.plan().commands().size());
	}

	@Test
	void newCommandsSurviveSaveAndLoad() {
		book.learn(new Plan.Skill("鉄づくり", List.of(
			new Command.Take("raw_iron", 0), new Command.Smelt("iron_ingot", 4), new Command.Farm(), new Command.Fight(), new Command.Guard())));

		SkillBook loaded = new SkillBook();
		loaded.load(book.toJson(), parser);

		assertEquals(book.all(), loaded.all());
	}

	@Test
	void smeltingTable() {
		assertEquals(Optional.of("iron_ingot"), Smelting.resultFor("raw_iron"));
		assertEquals(Optional.of("iron_ingot"), Smelting.resultFor("iron_ingot"));
		assertEquals(Optional.of("charcoal"), Smelting.resultFor("birch_log"));
		assertEquals(Optional.empty(), Smelting.resultFor("diamond"));
		assertTrue(Smelting.canMake("iron_ingot"));
		assertFalse(Smelting.canMake("raw_iron"));
		assertFalse(Smelting.canMake(Items.ANY_PLANKS));

		// 本体のレシピは、同じものを焼く方法が何通りもある。持っているものを使い、なければ集められるものを選ぶ
		Smelting.install(Map.of("iron_ingot", List.of("iron_ore", "deepslate_iron_ore", "raw_iron")));
		assertEquals(Optional.of("raw_iron"), Smelting.chooseInput("iron_ingot", Map.of()));
		assertEquals(Optional.of("iron_ore"), Smelting.chooseInput("iron_ingot", Map.of("iron_ore", 2, "raw_iron", 1)));
		assertEquals(Optional.empty(), Smelting.chooseInput("glass", Map.of("sand", 3)));
	}

	@Test
	void fuelIsCountedAndChosenWithoutWaste() {
		assertEquals(3, Smelting.fuelCapacity(Map.of("oak_log", 2)));
		assertEquals(1, Smelting.fuelCapacity(Map.of("stick", 3)));
		assertEquals(8, Smelting.fuelCapacity(Map.of("coal", 1, "wooden_pickaxe", 1)));
		assertEquals(2, Smelting.fuelShortage(Map.of("oak_planks", 2), 5));
		assertEquals(0, Smelting.fuelShortage(Map.of("coal", 1), 5));
		assertEquals(1, Smelting.logsFor(1));
		assertEquals(2, Smelting.logsFor(3));

		Map<String, Integer> inv = Map.of("coal", 1, "birch_planks", 4, "stick", 2);
		assertEquals(Optional.of("stick"), Smelting.pickFuel(inv, 1));
		assertEquals(Optional.of("birch_planks"), Smelting.pickFuel(inv, 3));
		assertEquals(Optional.of("coal"), Smelting.pickFuel(inv, 8));
		// ちょうどよいものがなければ、いちばん短く燃えるものを使う
		assertEquals(Optional.of("coal"), Smelting.pickFuel(Map.of("coal", 2), 1));
		assertEquals(Optional.empty(), Smelting.pickFuel(Map.of("cobblestone", 9), 1));

		// 木炭を作るときは、焼く分の原木を燃料に数えない
		Map<String, Integer> rest = Smelting.without(Map.of("oak_log", 3, "birch_log", 2, "coal", 1), Items.ANY_LOG, 4);
		assertEquals(1, Items.count(rest, Items.ANY_LOG));
		assertEquals(1, rest.get("coal"));
	}

	@Test
	void plannerReportsSmeltableAndStockedMaterialsAsMissing() {
		Recipes.install(List.of(
			new Recipes.Recipe("iron_pickaxe", 1, Map.of("iron_ingot", 3, "stick", 2), true),
			new Recipes.Recipe("diamond_pickaxe", 1, Map.of("diamond", 3, "stick", 2), true),
			new Recipes.Recipe("stick", 4, Map.of(Items.ANY_PLANKS, 2), false),
			new Recipes.Recipe("crafting_table", 1, Map.of(Items.ANY_PLANKS, 4), false),
			new Recipes.Recipe("iron_ingot", 9, Map.of("iron_block", 1), false),
			new Recipes.Recipe("iron_block", 1, Map.of("iron_ingot", 9), true)));

		// 鉄インゴットは焼けば手に入るので、「鉄のツルハシが足りない」ではなく、材料の名前で報告する
		var iron = CraftPlanner.plan(Map.of(), "iron_pickaxe", 1, false);
		assertEquals(Set.of("iron_ingot", Items.ANY_LOG), iron.missing().keySet());
		assertEquals(3, iron.missing().get("iron_ingot"));

		// ダイヤは集め方を知らないが、チェストに入っているなら、そこから取り出せばよい
		var noDiamond = CraftPlanner.plan(Map.of(), "diamond_pickaxe", 1, false);
		assertEquals(Set.of("diamond_pickaxe"), noDiamond.missing().keySet());
		var stocked = CraftPlanner.plan(Map.of(), "diamond_pickaxe", 1, false, "diamond"::equals);
		assertEquals(Set.of("diamond", Items.ANY_LOG), stocked.missing().keySet());
	}
}
