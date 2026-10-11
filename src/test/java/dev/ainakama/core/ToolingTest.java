package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolingTest {
	private static final float LOG = 2.0F;
	private static final float STONE = 1.5F;
	private static final float HAND = 1.0F;

	@Test
	void betterToolsBreakBlocksFaster() {
		int byHand = Tooling.breakTicks(LOG, HAND, true);
		int woodenAxe = Tooling.breakTicks(LOG, 2.0F, true);
		int stoneAxe = Tooling.breakTicks(LOG, 4.0F, true);
		int ironAxe = Tooling.breakTicks(LOG, 6.0F, true);

		assertTrue(byHand > woodenAxe && woodenAxe > stoneAxe && stoneAxe > ironAxe,
			byHand + " > " + woodenAxe + " > " + stoneAxe + " > " + ironAxe);
		// 道具の速さが2倍なら、かかる時間はおよそ半分
		assertEquals(byHand / 2, woodenAxe);
		assertEquals(byHand / 4, stoneAxe);
	}

	@Test
	void theWrongToolIsMuchSlower() {
		// ツルハシなしで石を掘ると、何も落とさないうえに、とても時間がかかる
		assertTrue(Tooling.breakTicks(STONE, HAND, false) > 3 * Tooling.breakTicks(STONE, HAND, true));
	}

	@Test
	void breakTimeStaysWithinSensibleBounds() {
		assertEquals(1, Tooling.breakTicks(0.0F, HAND, true));
		assertEquals(Tooling.MAX_BREAK_TICKS, Tooling.breakTicks(50.0F, HAND, false));
		assertEquals(Tooling.MAX_BREAK_TICKS, Tooling.breakTicks(-1.0F, HAND, true));
	}

	@Test
	void anAxeIsWorthMakingBeforeCuttingManyTrees() {
		assertEquals(Optional.of("wooden_axe"), Tooling.upgradeFor("oak_log", Map.of("oak_log", 3), 10));
		assertEquals(Optional.of("wooden_axe"), Tooling.upgradeFor("oak_log", Map.of("spruce_log", 4), 10));
		// 丸石があれば、石の斧のほうが速い
		assertEquals(Optional.of("stone_axe"), Tooling.upgradeFor("oak_log", Map.of("oak_log", 3, "cobblestone", 5), 10));
	}

	@Test
	void noAxeWhenItWouldNotPayOff() {
		// もう持っている
		assertEquals(Optional.empty(), Tooling.upgradeFor("oak_log", Map.of("oak_log", 3, "wooden_axe", 1), 10));
		// 残りが少ない
		assertEquals(Optional.empty(), Tooling.upgradeFor("oak_log", Map.of("oak_log", 3), 2));
		// 材料にする原木がまだない
		assertEquals(Optional.empty(), Tooling.upgradeFor("oak_log", Map.of("oak_log", 1), 10));
	}

	@Test
	void aShovelIsWorthMakingBeforeDiggingALotOfDirtOrSand() {
		assertEquals(Optional.of("wooden_shovel"), Tooling.upgradeFor("sand", Map.of("oak_log", 2), 16));
		assertEquals(Optional.of("stone_shovel"), Tooling.upgradeFor("dirt", Map.of("oak_log", 1, "cobblestone", 2), 16));
		assertEquals(Optional.empty(), Tooling.upgradeFor("dirt", Map.of("oak_log", 2, "stone_shovel", 1), 16));
		assertEquals(Optional.empty(), Tooling.upgradeFor("dirt", Map.of("oak_log", 2), 3));
	}

	@Test
	void aStonePickaxeReplacesAWoodenOneOnceThereIsCobblestone() {
		assertEquals(Optional.of("stone_pickaxe"),
			Tooling.upgradeFor("cobblestone", Map.of("wooden_pickaxe", 1, "cobblestone", 3, "stick", 2), 20));
		assertEquals(Optional.of("stone_pickaxe"),
			Tooling.upgradeFor("raw_iron", Map.of("wooden_pickaxe", 1, "cobblestone", 8, "oak_log", 1), 6));
		// 丸石がまだ足りない・もっと良いツルハシがある・残りが少ない
		assertEquals(Optional.empty(), Tooling.upgradeFor("cobblestone", Map.of("wooden_pickaxe", 1, "cobblestone", 2, "stick", 2), 20));
		assertEquals(Optional.empty(), Tooling.upgradeFor("cobblestone", Map.of("iron_pickaxe", 1, "cobblestone", 9, "stick", 2), 20));
		assertEquals(Optional.empty(), Tooling.upgradeFor("cobblestone", Map.of("wooden_pickaxe", 1, "cobblestone", 3, "stick", 2), 2));
	}
}
