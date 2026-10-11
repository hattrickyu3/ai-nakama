package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HuntingTest {
	private static final Set<String> KNOWN = Set.of("beef", "leather", "porkchop", "mutton", "white_wool", "chicken", "feather",
		"rabbit", "rabbit_hide", "oak_log", "cobblestone", "torch");

	private static List<Command> quick(String text) {
		return QuickCommands.match(text, KNOWN::contains).orElseThrow(() -> new AssertionError("読めなかった: " + text))
			.plan().orElseThrow().commands();
	}

	private static List<Command> parsed(String json) {
		List<String> errors = new ArrayList<>();
		var result = new PlanParser(KNOWN::contains).parse(json);
		errors.addAll(result.errors());
		assertEquals(List.of(), errors);
		return result.plan().commands();
	}

	@Test
	void resolvesAnimalsByName() {
		assertEquals(Optional.of(new Hunting.Prey("cow", null)), Hunting.resolve("牛", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey("cow", null)), Hunting.resolve("cow", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey("sheep", null)), Hunting.resolve("ヒツジ", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey("chicken", null)), Hunting.resolve("ニワトリ", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey(Hunting.ANY, null)), Hunting.resolve("動物", KNOWN::contains));
	}

	@Test
	void resolvesWhatTheAnimalDrops() {
		assertEquals(Optional.of(new Hunting.Prey("cow", "beef")), Hunting.resolve("牛肉", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey("cow", "leather")), Hunting.resolve("革", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey("sheep", "white_wool")), Hunting.resolve("羊毛", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey("chicken", "feather")), Hunting.resolve("feather", KNOWN::contains));
		assertEquals(Optional.of(new Hunting.Prey(Hunting.ANY, Hunting.MEAT)), Hunting.resolve("肉", KNOWN::contains));
		// 「鶏肉」はニワトリを倒せば1つ落とすので、ニワトリの数として数えても同じ
		assertEquals(Optional.of(new Hunting.Prey("chicken", "chicken")), Hunting.resolve("鶏肉", KNOWN::contains));
	}

	@Test
	void thingsThatAreNotHuntedAreNotResolved() {
		assertEquals(Optional.empty(), Hunting.resolve("原木", KNOWN::contains));
		assertEquals(Optional.empty(), Hunting.resolve("ゾンビ", KNOWN::contains));
		assertEquals(Optional.empty(), Hunting.resolve("村人", KNOWN::contains));
		assertEquals(Optional.empty(), Hunting.resolve("", KNOWN::contains));
	}

	@Test
	void knowsWhichAnimalsAndDropsCount() {
		assertEquals(Set.of("cow"), Hunting.animalsFor("cow"));
		assertEquals(Set.of("cow", "pig", "sheep", "chicken", "rabbit"), Hunting.animalsFor(Hunting.ANY));
		assertTrue(Hunting.counts("beef", "beef"));
		assertFalse(Hunting.counts("beef", "leather"));
		assertTrue(Hunting.counts(Hunting.MEAT, "porkchop"));
		assertTrue(Hunting.counts(Hunting.MEAT, "mutton"));
		assertFalse(Hunting.counts(Hunting.MEAT, "leather"));
		assertTrue(Hunting.counts("white_wool", "black_wool"));
	}

	@Test
	void quickPhrasesForHunting() {
		assertEquals(List.of(new Command.Hunt("cow", null, 3)), quick("牛を3頭狩って"));
		assertEquals(List.of(new Command.Hunt("pig", null, 2)), quick("ブタを二匹倒して"));
		assertEquals(List.of(new Command.Hunt(Hunting.ANY, null, Hunting.DEFAULT_COUNT)), quick("動物を狩ってきて"));
		assertEquals(List.of(new Command.Hunt("cow", "beef", 5)), quick("牛肉を5個取ってきて"));
		assertEquals(List.of(new Command.Hunt(Hunting.ANY, Hunting.MEAT, Hunting.DEFAULT_COUNT)), quick("肉を集めて"));
		assertEquals(List.of(new Command.Hunt("sheep", "white_wool", 4)), quick("羊毛を4個集めて"));
	}

	@Test
	void enemiesAreStillFoughtNotHunted() {
		assertEquals(List.of(new Command.Fight()), quick("ゾンビを倒して"));
		assertEquals(List.of(new Command.Fight()), quick("敵を倒して"));
		// 集められる資源は、今までどおり集める
		assertEquals(List.of(new Command.Collect("oak_log", 10)), quick("木を10本集めて"));
	}

	@Test
	void quickPhrasesForPickingUp() {
		assertEquals(List.of(new Command.Pickup()), quick("拾って"));
		assertEquals(List.of(new Command.Pickup()), quick("落ちてるものを拾って"));
		assertEquals(List.of(new Command.Pickup()), quick("そこのアイテムを全部拾ってきて"));
		assertEquals(List.of(new Command.Pickup()), quick("ドロップを回収して"));
	}

	@Test
	void theUsersRequestFromTheFirstPlaySessionIsUnderstood() {
		// 2026-10-10 に「動物を狩ってきて、肉を集めて」と頼んだら、アイアンゴーレムの話を返された
		assertEquals(List.of(new Command.Hunt(Hunting.ANY, null, Hunting.DEFAULT_COUNT), new Command.Hunt(Hunting.ANY, Hunting.MEAT, Hunting.DEFAULT_COUNT)),
			TeachParser.steps("動物を狩ってきて、肉を集めて", KNOWN::contains));
	}

	@Test
	void aiPlansCanHuntAndPickUp() {
		assertEquals(List.of(new Command.Hunt("sheep", null, 2), new Command.Pickup()),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"hunt\",\"item\":\"sheep\",\"count\":2},{\"do\":\"pickup\"}]}"));
		assertEquals(List.of(new Command.Hunt("cow", "leather", 3)),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"hunt\",\"item\":\"leather\",\"count\":3}]}"));
		// AIは肉や羊毛にも collect を使いがちなので、狩りに読み替える
		assertEquals(List.of(new Command.Hunt("cow", "beef", 4)),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"collect\",\"item\":\"beef\",\"count\":4}]}"));
	}

	@Test
	void huntAndPickupSurviveSavingAsASkill() {
		List<Command> steps = List.of(new Command.Hunt("cow", "beef", 4), new Command.Hunt(Hunting.ANY, null, 2), new Command.Pickup());
		String json = "{\"say\":\"x\",\"plan\":" + CommandJson.write(steps) + "}";
		assertEquals(steps, parsed(json));
	}
}
