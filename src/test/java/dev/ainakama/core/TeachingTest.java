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
