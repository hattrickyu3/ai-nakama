package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LightAndTradeTest {
	private static final Set<String> KNOWN = Set.of("torch", "wheat", "bread", "emerald", "oak_log", "cobblestone");

	private static List<Command> quick(String text) {
		return QuickCommands.match(text, KNOWN::contains).orElseThrow(() -> new AssertionError("読めなかった: " + text))
			.plan().orElseThrow().commands();
	}

	private static List<Command> parsed(String json) {
		var result = new PlanParser(KNOWN::contains).parse(json);
		assertEquals(List.of(), result.errors());
		return result.plan().commands();
	}

	private static int distance(Lighting.Spot a, Lighting.Spot b) {
		return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
	}

	@Test
	void torchesAreSpreadOutOverTheDarkArea() {
		List<Lighting.Spot> dark = new ArrayList<>();
		for (int x = -10; x <= 10; x++) {
			for (int z = -10; z <= 10; z++) {
				dark.add(new Lighting.Spot(x, 64, z));
			}
		}

		List<Lighting.Spot> chosen = Lighting.choose(dark, new Lighting.Spot(0, 64, 0));

		assertEquals(new Lighting.Spot(0, 64, 0), chosen.get(0));
		assertTrue(chosen.size() <= Lighting.MAX_TORCHES, "松明 " + chosen.size() + "本");
		for (int i = 0; i < chosen.size(); i++) {
			for (int j = i + 1; j < chosen.size(); j++) {
				assertTrue(distance(chosen.get(i), chosen.get(j)) >= Lighting.SPACING, chosen.get(i) + " と " + chosen.get(j) + " が近すぎる");
			}
		}
		// どの暗い場所も、どれかの松明から SPACING マス未満（松明の明かりが届く）
		for (Lighting.Spot spot : dark) {
			assertTrue(chosen.stream().anyMatch(c -> distance(c, spot) < Lighting.SPACING), spot + " に明かりが届かない");
		}
	}

	@Test
	void nothingToLightGivesNoTorches() {
		assertEquals(List.of(), Lighting.choose(List.of(), new Lighting.Spot(0, 64, 0)));
	}

	@Test
	void quickPhrasesForLighting() {
		assertEquals(List.of(new Command.Light(new Place.Looking())), quick("ここを明るくして"));
		assertEquals(List.of(new Command.Light(new Place.Home())), quick("拠点を明るくして"));
		assertEquals(List.of(new Command.Light(new Place.Looking())), quick("湧き潰しして"));
		assertEquals(List.of(new Command.Light(new Place.Looking())), quick("この辺に松明を置いて回って"));
		// 1本だけ置くのは、今までどおり「置く」
		assertEquals(List.of(new Command.PlaceBlock("torch", new Place.Looking())), quick("ここに松明を置いて"));
	}

	@Test
	void quickPhrasesForTrading() {
		assertEquals(List.of(new Command.Trade("wheat", true, 1)), quick("小麦を売って"));
		assertEquals(List.of(new Command.Trade("wheat", true, 3)), quick("村人に小麦を3回売ってきて"));
		assertEquals(List.of(new Command.Trade("bread", false, 2)), quick("パンを2個買ってきて"));
		assertEquals(List.of(new Command.Trade("emerald", false, 1)), quick("村人からエメラルドを買って"));
		assertEquals(List.of(new Command.Trade(null, false, 1)), quick("村人の取引を見せて"));
	}

	@Test
	void quickPhrasesForFishing() {
		assertEquals(List.of(new Command.Fish(Command.Fish.DEFAULT_COUNT)), quick("釣りをして"));
		assertEquals(List.of(new Command.Fish(5)), quick("魚を5匹釣ってきて"));
	}

	@Test
	void aiPlansCanLightAndTrade() {
		assertEquals(List.of(new Command.Light(new Place.Home()), new Command.Trade("wheat", true, 2), new Command.Trade("bread", false, 1)),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"light\",\"at\":\"home\"},{\"do\":\"sell\",\"item\":\"wheat\",\"count\":2},"
				+ "{\"do\":\"buy\",\"item\":\"bread\"}]}"));
	}

	@Test
	void lightAndTradeSurviveSavingAsASkill() {
		List<Command> steps = List.of(new Command.Light(new Place.Coords(1, 64, 2)), new Command.Trade("wheat", true, 2),
			new Command.Trade("bread", false, 1), new Command.Trade(null, false, 1));
		assertEquals(steps, parsed("{\"say\":\"x\",\"plan\":" + CommandJson.write(steps) + "}"));
	}
}
