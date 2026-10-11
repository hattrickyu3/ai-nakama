package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TillingTest {
	private static final Set<String> KNOWN = Set.of("oak_log", "cobblestone", "torch", "wheat_seeds", "wooden_hoe");
	private static final Command FARM = new Command.Farm();

	private static List<Command> quick(String text) {
		return QuickCommands.match(text, KNOWN::contains).orElseThrow(() -> new AssertionError("読めなかった: " + text))
			.plan().orElseThrow().commands();
	}

	private static List<Command> parsed(String json) {
		var result = new PlanParser(KNOWN::contains).parse(json);
		assertEquals(List.of(), result.errors());
		return result.plan().commands();
	}

	@Test
	void quickPhrasesForMakingAField() {
		// 耕したあと、持っている種をまく
		assertEquals(List.of(new Command.Till(new Place.Looking(), Command.Till.DEFAULT_COUNT), FARM), quick("ここを耕して"));
		assertEquals(List.of(new Command.Till(new Place.Looking(), Command.Till.DEFAULT_COUNT), FARM), quick("畑を作って"));
		assertEquals(List.of(new Command.Till(new Place.Looking(), 12), FARM), quick("ここに畑を12マス作って"));
		assertEquals(List.of(new Command.Till(new Place.Looking(), 6), FARM), quick("この辺を6マス耕して"));
		assertEquals(List.of(new Command.Till(new Place.Home(), Command.Till.DEFAULT_COUNT), FARM), quick("拠点に畑を作って"));
		assertEquals(List.of(new Command.Till(new Place.Looking(), 20), FARM), quick("畑を20マス広げて"));
	}

	@Test
	void tendingAnExistingFieldIsStillFarm() {
		assertEquals(List.of(FARM), quick("畑仕事して"));
		assertEquals(List.of(FARM), quick("種を植えて"));
	}

	@Test
	void aiPlansCanTill() {
		assertEquals(List.of(new Command.Till(new Place.Home(), 16), FARM),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"till\",\"at\":\"home\",\"count\":16},{\"do\":\"farm\"}]}"));
		assertEquals(List.of(new Command.Till(new Place.Looking(), Command.Till.DEFAULT_COUNT)),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"till\"}]}"));
	}

	@Test
	void tillSurvivesSavingAsASkill() {
		List<Command> steps = List.of(new Command.Till(new Place.Coords(3, 64, -8), 9), FARM);
		assertEquals(steps, parsed("{\"say\":\"x\",\"plan\":" + CommandJson.write(steps) + "}"));
	}

	@Test
	void buildingAcceptsLooseWordsForHere() {
		// 2026-10-10 に「ここら辺に小屋をたてて」が通じず、AIが「建てたいの？」と聞き返した
		assertEquals(List.of(new Command.Build("hut", new Place.Looking())), quick("ここら辺に小屋をたてて"));
		assertEquals(List.of(new Command.Build("hut", new Place.Looking())), quick("この辺に小屋を建てて"));
		assertEquals(List.of(new Command.Build("hut", new Place.Home())), quick("拠点に小屋を建てて"));
	}

	@Test
	void dailyRoutinesBecomeRules() {
		// 2026-10-10 に「ここで、畑仕事を毎日朝にして」が決まりにならず、AIが「大変だね！」と返しただけだった
		var daily = TeachParser.parse("ここで、畑仕事を毎日朝にして", KNOWN::contains).orElseThrow();
		assertEquals(Trigger.MORNING, daily.trigger());
		assertEquals(List.of(FARM), daily.steps());

		var everyMorning = TeachParser.parse("毎朝、畑仕事して", KNOWN::contains).orElseThrow();
		assertEquals(Trigger.MORNING, everyMorning.trigger());
		assertEquals(List.of(FARM), everyMorning.steps());

		var everyNight = TeachParser.parse("毎晩、拠点に帰って", KNOWN::contains).orElseThrow();
		assertEquals(Trigger.NIGHT, everyNight.trigger());
		assertEquals(List.of(new Command.MoveTo(new Place.Home())), everyNight.steps());
	}

	@Test
	void talkAboutMorningIsNotARule() {
		assertEquals(Optional.empty(), TeachParser.parse("朝は苦手なんだ", KNOWN::contains));
		assertEquals(Optional.empty(), TeachParser.parse("毎日楽しいね", KNOWN::contains));
	}
}
