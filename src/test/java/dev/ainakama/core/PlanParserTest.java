package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanParserTest {
	private static final Set<String> KNOWN = Set.of("oak_log", "oak_planks", "cobblestone", "stick", "crafting_table", "wooden_pickaxe");
	private final PlanParser parser = new PlanParser(KNOWN::contains);

	@Test
	void parsesValidPlan() {
		var r = parser.parse("""
			{"say": "わかった！", "plan": [
			  {"do": "collect", "item": "oak_log", "count": 20},
			  {"do": "build", "blueprint": "hut", "at": "home"}
			], "memo": null}
			""");

		assertTrue(r.ok(), () -> r.errors().toString());
		assertEquals("わかった！", r.plan().say());
		assertEquals(new Command.Collect("oak_log", 20), r.plan().commands().get(0));
		assertEquals(new Command.Build("hut", new Place.Home()), r.plan().commands().get(1));
		assertNull(r.plan().memo());
	}

	@Test
	void fixesCommonItemNameMistakes() {
		// 実際に qwen3.5:4b が "plank" と答えたことがある
		var r = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"craft\",\"item\":\"plank\",\"count\":12},{\"do\":\"collect\",\"item\":\"minecraft:Oak Log\",\"count\":3}]}");

		assertTrue(r.ok(), () -> r.errors().toString());
		assertEquals(new Command.Craft("oak_planks", 12), r.plan().commands().get(0));
		assertEquals(new Command.Collect("oak_log", 3), r.plan().commands().get(1));
	}

	@Test
	void acceptsJapaneseItemNames() {
		var r = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"collect\",\"item\":\"丸石\",\"count\":5}]}");
		assertEquals(new Command.Collect("cobblestone", 5), r.plan().commands().get(0));
	}

	@Test
	void reportsUnknownCommandAndItemButKeepsValidOnes() {
		var r = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"fly\"},{\"do\":\"collect\",\"item\":\"diamond_sword_of_doom\",\"count\":1},{\"do\":\"follow\"}]}");

		assertFalse(r.ok());
		assertEquals(2, r.errors().size());
		assertEquals(1, r.plan().commands().size());
		assertInstanceOf(Command.Follow.class, r.plan().commands().get(0));
	}

	@Test
	void clampsCountAndReportsIt() {
		var r = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"collect\",\"item\":\"oak_log\",\"count\":500}]}");

		assertFalse(r.ok());
		assertEquals(new Command.Collect("oak_log", PlanParser.MAX_COUNT), r.plan().commands().get(0));
	}

	@Test
	void collectOnlyAllowsNaturalResources() {
		// 板材を「集める」と、プレイヤーの家の壁を壊してしまうので受け付けない
		var planks = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"collect\",\"item\":\"oak_planks\",\"count\":10}]}");
		assertFalse(planks.ok());
		assertTrue(planks.plan().commands().isEmpty());

		// "stone" は掘ると丸石になるので、丸石を集める意味に読みかえる
		var stone = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"collect\",\"item\":\"stone\",\"count\":5}]}");
		assertTrue(stone.ok(), () -> stone.errors().toString());
		assertEquals(new Command.Collect("cobblestone", 5), stone.plan().commands().get(0));
	}

	@Test
	void rejectsUncraftableItem() {
		var r = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"craft\",\"item\":\"oak_log\",\"count\":1}]}");
		assertFalse(r.ok());
		assertTrue(r.plan().commands().isEmpty());
	}

	@Test
	void readsJsonWrappedInCodeFence() {
		var r = parser.parse("はい！\n```json\n{\"say\": \"了解\", \"plan\": []}\n```");
		assertTrue(r.ok());
		assertEquals("了解", r.plan().say());
	}

	@Test
	void brokenJsonIsAnError() {
		var r = parser.parse("{\"say\": \"了解\", \"plan\": [");
		assertFalse(r.ok());
		assertTrue(r.plan().commands().isEmpty());
	}

	@Test
	void tooManyStepsAreCut() {
		String step = "{\"do\":\"follow\"},";
		var r = parser.parse("{\"say\":\"\",\"plan\":[" + step.repeat(9) + "{\"do\":\"stop\"}]}");
		assertFalse(r.ok());
		assertEquals(PlanParser.MAX_STEPS, r.plan().commands().size());
	}

	@Test
	void parsesCoordinates() {
		var r = parser.parse("{\"say\":\"\",\"plan\":[{\"do\":\"move_to\",\"at\":\"10 64 -5\"}]}");
		assertEquals(new Command.MoveTo(new Place.Coords(10, 64, -5)), r.plan().commands().get(0));
	}
}
