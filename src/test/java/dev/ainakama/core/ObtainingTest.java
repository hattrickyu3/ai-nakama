package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObtainingTest {
	private static final Set<String> KNOWN = Set.of("oak_log", "cobblestone", "stick", "torch", "stone_pickaxe", "iron_ingot", "raw_iron",
		"glass", "sand", "beef", "cooked_beef", "leather", "white_wool", "milk_bucket", "bucket", "shears", "cod", "salmon", "wheat", "diamond");

	@BeforeEach
	void installRecipes() {
		Smelting.install(Map.of("iron_ingot", List.of("raw_iron"), "glass", List.of("sand"), "cooked_beef", List.of("beef")));
	}

	@AfterEach
	void resetRecipes() {
		Smelting.reset();
	}

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
	void choosesHowToGetEachKindOfItem() {
		// 掘って集めるもの
		assertEquals(Optional.of(new Command.Collect("cobblestone", 5)), Obtaining.how("cobblestone", 5));
		assertEquals(Optional.of(new Command.Collect("oak_log", 3)), Obtaining.how("oak_log", 3));
		// 動物が落とすもの
		assertEquals(Optional.of(new Command.Hunt("cow", "leather", 2)), Obtaining.how("leather", 2));
		// 焼いて作るもの（焼く前の材料は、焼く作業が用意する）
		assertEquals(Optional.of(new Command.Smelt("iron_ingot", 3)), Obtaining.how("iron_ingot", 3));
		assertEquals(Optional.of(new Command.Smelt("cooked_beef", 2)), Obtaining.how("cooked_beef", 2));
		// クラフトするもの（材料は、クラフトの作業が用意する）
		assertEquals(Optional.of(new Command.Craft("torch", 8)), Obtaining.how("torch", 8));
		assertEquals(Optional.of(new Command.Craft("stone_pickaxe", 1)), Obtaining.how("stone_pickaxe", 1));
		// 道具を使って取るもの
		assertEquals(Optional.of(new Command.Milk(1)), Obtaining.how("milk_bucket", 1));
		assertEquals(Optional.of(new Command.Fish(2)), Obtaining.how("cod", 2));
		// 畑で収穫するもの
		assertEquals(Optional.of(new Command.Farm()), Obtaining.how("wheat", 4));
	}

	@Test
	void unknownWaysAreReportedAsSuch() {
		assertEquals(Optional.empty(), Obtaining.how("diamond", 1));
		assertFalse(Obtaining.canObtain("diamond"));
		assertTrue(Obtaining.canObtain("torch"));
	}

	@Test
	void quickPhrasesForWanting() {
		assertEquals(List.of(new Command.Obtain("stone_pickaxe", 1)), quick("石のツルハシがほしいな"));
		assertEquals(List.of(new Command.Obtain("iron_ingot", 3)), quick("鉄のインゴットが3つ必要なんだ"));
		assertEquals(List.of(new Command.Obtain("torch", 8)), quick("松明を8個用意して"));
		assertEquals(List.of(new Command.Obtain("glass", 4)), quick("ガラスを4個手に入れて"));
	}

	@Test
	void aReasonInFrontOfTheRequestIsSkipped() {
		assertEquals(List.of(new Command.Obtain("milk_bucket", 1)), quick("ケーキを作りたいから、牛乳を用意しておいて"));
		assertEquals(List.of(new Command.Breed("cow", 1)), quick("牛をもっと増やしておいてほしい"));
		assertEquals(List.of(new Command.Obtain("torch", 8)), quick("暗いので、松明を8個用意して"));
		// 「〜てから」は順番の指示なので、理由としては外さない（いくつか続けた指示として読む）
		assertEquals(Optional.empty(), QuickCommands.match("木を集めてから、松明を8個用意して", KNOWN::contains));
	}

	@Test
	void aiIsNotToldAboutGetWhenTheModelIsSmall() {
		assertTrue(PromptBuilder.system("ミナ", "明るい", false).contains("get(item, count)"));
		assertFalse(PromptBuilder.system("ミナ", "明るい", true).contains("get(item, count)"));
		assertEquals(PromptBuilder.system("ミナ", "明るい", false), PromptBuilder.system("ミナ", "明るい"));
	}

	@Test
	void specificVerbsStillChooseTheWay() {
		assertEquals(List.of(new Command.Craft("torch", 8)), quick("松明を8個作って"));
		assertEquals(List.of(new Command.Collect("cobblestone", 64)), quick("石を一スタック掘ってきて"));
		assertEquals(List.of(new Command.Hunt("cow", "leather", 3)), quick("革を3枚手に入れて"));
	}

	@Test
	void wantingSomethingUnobtainableIsLeftToTheModel() {
		assertEquals(Optional.empty(), QuickCommands.match("ダイヤがほしいな", KNOWN::contains));
		assertEquals(Optional.empty(), QuickCommands.match("diamondを3個用意して", KNOWN::contains));
	}

	@Test
	void aiPlansCanGet() {
		assertEquals(List.of(new Command.Obtain("stone_pickaxe", 1)),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"get\",\"item\":\"stone_pickaxe\",\"count\":1}]}"));
		// 集められないものを collect と書いても、手に入れ方がわかるものなら任せる
		assertEquals(List.of(new Command.Obtain("torch", 4)),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"collect\",\"item\":\"torch\",\"count\":4}]}"));
		assertEquals(List.of(new Command.Obtain("milk_bucket", 1)),
			parsed("{\"say\":\"うん\",\"plan\":[{\"do\":\"craft\",\"item\":\"milk_bucket\",\"count\":1}]}"));
	}

	@Test
	void getOfSomethingUnobtainableIsAnError() {
		var result = new PlanParser(KNOWN::contains).parse("{\"say\":\"うん\",\"plan\":[{\"do\":\"get\",\"item\":\"diamond\",\"count\":1}]}");
		assertEquals(1, result.errors().size());
		assertTrue(result.errors().get(0).contains("手に入れ方"));
	}

	@Test
	void animalCarePhrases() {
		assertEquals(List.of(new Command.Shear(Command.Shear.DEFAULT_COUNT)), quick("羊の毛を刈って"));
		assertEquals(List.of(new Command.Shear(5)), quick("羊毛を5個刈ってきて"));
		assertEquals(List.of(new Command.Milk(1)), quick("乳しぼりして"));
		assertEquals(List.of(new Command.Milk(2)), quick("牛乳を2杯しぼってきて"));
		assertEquals(List.of(new Command.Breed("cow", 1)), quick("牛を増やして"));
		assertEquals(List.of(new Command.Breed("chicken", 2)), quick("ニワトリを2組繁殖させて"));
		assertEquals(List.of(new Command.Breed("sheep", 1)), quick("羊にエサをあげて"));
	}

	@Test
	void animalCareSurvivesSavingAsASkill() {
		List<Command> steps = List.of(new Command.Shear(4), new Command.Milk(2), new Command.Breed("pig", 2), new Command.Obtain("torch", 8),
			new Command.Fish(3));
		assertEquals(steps, parsed("{\"say\":\"x\",\"plan\":" + CommandJson.write(steps) + "}"));
	}
}
