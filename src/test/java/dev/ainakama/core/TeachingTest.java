package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 教えて覚える仕組み（技・決まり・聞き返し）と、マイクラ本体のレシピを使ったクラフトの計画。 */
class TeachingTest {
	private static final Set<String> ITEMS = Set.of("oak_log", "torch", "coal", "stick", "cobblestone", "oak_door", "ladder", "iron_ingot", "iron_block");
	private static final Set<String> ITEMS_PLUS = Set.of("oak_log", "torch", "coal", "stick", "cobblestone", "crafting_table");
	private final SkillBook book = new SkillBook();
	private final PlanParser parser = new PlanParser(ITEMS::contains, book::knows);

	@AfterEach
	void resetRecipes() {
		Recipes.reset();
	}

	@Test
	void parsesTeachAskAndRule() {
		var taught = parser.parse("""
			{"say":"なるほど！","plan":[{"do":"craft","item":"torch","count":8}],
			 "teach":{"name":"拠点を明るくする","steps":[{"do":"craft","item":"torch","count":8},{"do":"place","item":"torch","at":"home"}]}}
			""");
		assertTrue(taught.ok(), () -> taught.errors().toString());
		assertEquals("拠点を明るくする", taught.plan().teach().name());
		assertEquals(new Command.PlaceBlock("torch", new Place.Home()), taught.plan().teach().steps().get(1));

		var asked = parser.parse("{\"say\":\"うーん\",\"plan\":[],\"ask\":\"「畑を作る」ってどうやるの？\"}");
		assertTrue(asked.ok());
		assertEquals("「畑を作る」ってどうやるの？", asked.plan().ask());
		assertNull(asked.plan().teach());

		var rule = parser.parse("{\"say\":\"わかった\",\"plan\":[],\"rule\":{\"when\":\"night\",\"steps\":[{\"do\":\"move_to\",\"at\":\"home\"}]}}");
		assertTrue(rule.ok(), () -> rule.errors().toString());
		assertEquals(Trigger.NIGHT, rule.plan().rule().when());
	}

	@Test
	void badTeachOrRuleIsReported() {
		assertFalse(parser.parse("{\"say\":\"\",\"plan\":[],\"teach\":{\"name\":\"\",\"steps\":[{\"do\":\"follow\"}]}}").ok());
		assertFalse(parser.parse("{\"say\":\"\",\"plan\":[],\"teach\":{\"name\":\"踊る\",\"steps\":[{\"do\":\"dance\"}]}}").ok());
		assertFalse(parser.parse("{\"say\":\"\",\"plan\":[],\"rule\":{\"when\":\"someday\",\"steps\":[{\"do\":\"follow\"}]}}").ok());
		// 覚えていない技は使えない
		assertFalse(parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"skill\",\"name\":\"空を飛ぶ\"}]}").ok());
	}

	@Test
	void skillIsMatchedByNameWithDifferentEndings() {
		book.learn(new Plan.Skill("拠点を明るくする", List.of(new Command.Craft("torch", 8))));

		assertTrue(book.matchRequest("拠点を明るくして").isPresent());
		assertTrue(book.matchRequest("拠点を明るくしてください！").isPresent());
		assertTrue(book.matchRequest("じゃあ、拠点を明るくして").isPresent());
		assertTrue(book.matchRequest("拠点を暗くして").isEmpty());
		assertTrue(parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"skill\",\"name\":\"拠点を明るくする\"}]}").ok());
	}

	@Test
	void skillsExpandAndCannotLoopForever() {
		book.learn(new Plan.Skill("準備", List.of(new Command.Collect("oak_log", 4))));
		book.learn(new Plan.Skill("冬支度", List.of(new Command.UseSkill("準備"), new Command.Craft("torch", 4))));
		book.learn(new Plan.Skill("ぐるぐる", List.of(new Command.UseSkill("ぐるぐる"), new Command.Follow())));

		assertEquals(List.of(new Command.Collect("oak_log", 4), new Command.Craft("torch", 4)),
			book.expand(List.of(new Command.UseSkill("冬支度"))));
		// 自分自身を呼ぶ技は、入れ子の上限で止まる
		assertEquals(SkillBook.MAX_NESTING, book.expand(List.of(new Command.UseSkill("ぐるぐる"))).size());
		assertTrue(book.expand(List.of(new Command.UseSkill("知らない技"))).isEmpty());
	}

	@Test
	void skillsAndRulesSurviveSaveAndLoad() {
		book.learn(new Plan.Skill("拠点を明るくする", List.of(
			new Command.Craft("torch", 8), new Command.PlaceBlock("torch", new Place.Home()), new Command.Give("torch", 2))));
		book.setRule(new Plan.Rule(Trigger.NIGHT, List.of(new Command.MoveTo(new Place.Home()), new Command.Say("おやすみ"))));

		SkillBook loaded = new SkillBook();
		loaded.load(book.toJson(), parser);

		assertEquals(book.all(), loaded.all());
		assertEquals(book.rules(), loaded.rules());
	}

	@Test
	void forgetAndRelevantNames() {
		book.learn(new Plan.Skill("拠点を明るくする", List.of(new Command.Follow())));
		book.learn(new Plan.Skill("畑の世話", List.of(new Command.Follow())));

		assertEquals(List.of("拠点を明るくする"), book.relevantNames("拠点が暗いから明るくしたい", 5));
		assertTrue(book.forget("拠点を明るくして"));
		assertFalse(book.knows("拠点を明るくする"));
	}

	@Test
	void teachingPhrasesAreReadWithoutAi() {
		// 2026-10-10 のテストで、小さいAIが「教わった」と気づけなかった言い方
		var t = TeachParser.parse("キャンプの準備っていうのは、木を3本集めて、作業台を1個作ることだよ", ITEMS_PLUS::contains).orElseThrow();
		assertEquals("キャンプの準備", t.name());
		assertEquals(List.of(new Command.Collect("oak_log", 3), new Command.Craft("crafting_table", 1)), t.steps());

		// 読点がなくても、動詞のところで区切る
		var joined = TeachParser.parse("冬支度というのは木を一スタック集めて拠点に帰ること", ITEMS_PLUS::contains).orElseThrow();
		assertEquals("冬支度", joined.name());
		assertEquals(List.of(new Command.Collect("oak_log", 64), new Command.MoveTo(new Place.Home())), joined.steps());

		// プログラムで読めない説明は、名前だけ確定して、手順はAIに直してもらう
		var hard = TeachParser.parse("見回りっていうのは、村のまわりをぐるっと歩いて様子を見ること", ITEMS_PLUS::contains).orElseThrow();
		assertEquals("見回り", hard.name());
		assertFalse(hard.complete());

		assertTrue(TeachParser.parse("木を10本集めて", ITEMS_PLUS::contains).isEmpty());
		assertTrue(TeachParser.parse("今日はいい天気だね", ITEMS_PLUS::contains).isEmpty());
	}

	@Test
	void rulesAreReadWithoutAi() {
		var night = TeachParser.parse("夜になったら拠点に帰って", ITEMS_PLUS::contains).orElseThrow();
		assertEquals(Trigger.NIGHT, night.trigger());
		assertEquals(List.of(new Command.MoveTo(new Place.Home())), night.steps());

		var full = TeachParser.parse("持ち物がいっぱいになったら、チェストにしまって", ITEMS_PLUS::contains).orElseThrow();
		assertEquals(Trigger.INVENTORY_FULL, full.trigger());
		assertEquals(List.of(new Command.Store()), full.steps());

		var far = TeachParser.parse("私が遠くに行ったらついてきて", ITEMS_PLUS::contains).orElseThrow();
		assertEquals(Trigger.PLAYER_FAR, far.trigger());
		assertEquals(List.of(new Command.Follow()), far.steps());
	}

	@Test
	void skillNameComesFromTheRequest() {
		assertEquals("キャンプの準備", SkillBook.nameOf("キャンプの準備をして"));
		assertEquals("拠点を明るく", SkillBook.nameOf("拠点を明るくしてください"));
	}

	@Test
	void exactPlanksAndAnyPlanksDoNotStealFromEachOther() {
		// 2026-10-10 のゲーム内テストでゲームが落ちた組み合わせ：柵は「オークの板材」、棒は「板材ならどれでも」を使う
		Recipes.install(List.of(
			new Recipes.Recipe("oak_fence", 3, Map.of("oak_planks", 4, "stick", 2), true),
			new Recipes.Recipe("stick", 4, Map.of(Items.ANY_PLANKS, 2), false),
			new Recipes.Recipe("crafting_table", 1, Map.of(Items.ANY_PLANKS, 4), false)));

		var fence = CraftPlanner.plan(Map.of("oak_planks", 2, "oak_log", 2), "oak_fence", 1, false);
		assertTrue(fence.complete(), () -> "missing: " + fence.missing());
		assertEquals(3, fence.inventoryAfter().get("oak_fence"));

		// 板材がシラカバしかなくても、オークの板材が要るところは原木から作る（原木がオークならできる）
		var mixed = CraftPlanner.plan(Map.of("birch_planks", 6, "oak_log", 1, "crafting_table", 1), "oak_fence", 1, false);
		assertTrue(mixed.complete(), () -> "missing: " + mixed.missing());

		// オークの板材が要るのに、シラカバの原木しかない：落ちずに「足りない」と報告する
		var wrongWood = CraftPlanner.plan(Map.of("birch_log", 4, "crafting_table", 1), "oak_fence", 1, false);
		assertFalse(wrongWood.complete());
		assertFalse(wrongWood.missing().isEmpty());
	}

	@Test
	void plannerUsesInstalledRecipesAndPicksWorkableOne() {
		// 本体のレシピを入れたときと同じ形：松明は 石炭か木炭 ＋ 棒、棒は板材か竹
		Recipes.install(List.of(
			new Recipes.Recipe("torch", 4, Map.of("any:charcoal|coal", 1, "stick", 1), false),
			new Recipes.Recipe("stick", 1, Map.of("bamboo", 2), false),
			new Recipes.Recipe("stick", 4, Map.of(Items.ANY_PLANKS, 2), false),
			new Recipes.Recipe("ladder", 3, Map.of("stick", 7), true),
			new Recipes.Recipe("crafting_table", 1, Map.of(Items.ANY_PLANKS, 4), false),
			new Recipes.Recipe("iron_ingot", 9, Map.of("iron_block", 1), false),
			new Recipes.Recipe("iron_block", 1, Map.of("iron_ingot", 9), true)));

		var torch = CraftPlanner.plan(Map.of("charcoal", 2, "oak_log", 1), "torch", 8, false);
		assertTrue(torch.complete(), () -> "missing: " + torch.missing());
		assertEquals(8, torch.inventoryAfter().get("torch"));

		// 何も持っていなければ、集められるもの（石炭・原木）が足りないと報告する。竹は集められないので選ばない
		var empty = CraftPlanner.plan(Map.of(), "torch", 4, false);
		assertFalse(empty.complete());
		assertEquals(Set.of("any:charcoal|coal", Items.ANY_LOG), empty.missing().keySet());

		var ladder = CraftPlanner.plan(Map.of("oak_log", 3), "ladder", 3, false);
		assertTrue(ladder.complete(), () -> "missing: " + ladder.missing());
		assertNotNull(ladder.inventoryAfter().get("crafting_table"));

		// 鉄インゴット ⇄ 鉄ブロックの堂々めぐりで止まらなくならない
		var ingot = CraftPlanner.plan(Map.of(), "iron_ingot", 1, true);
		assertFalse(ingot.complete());
		assertEquals(Set.of("iron_ingot"), ingot.missing().keySet());
	}
}
