package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CraftPlannerTest {
	@Test
	void makesPlanksFromMatchingLogType() {
		var result = CraftPlanner.plan(Map.of("birch_log", 2), "oak_planks", 8, false);

		assertTrue(result.complete());
		assertEquals(8, result.inventoryAfter().get("birch_planks"));
		assertFalse(result.inventoryAfter().containsKey("birch_log"));
	}

	@Test
	void woodenPickaxeFromLogsBuildsWholeChain() {
		var result = CraftPlanner.plan(Map.of("oak_log", 3), "wooden_pickaxe", 1, false);

		assertTrue(result.complete(), () -> "missing: " + result.missing());
		List<String> outputs = result.steps().stream().map(CraftPlanner.Step::output).toList();
		assertTrue(outputs.contains("crafting_table"));
		assertTrue(outputs.contains("stick"));
		assertEquals("wooden_pickaxe", outputs.get(outputs.size() - 1));
		assertEquals(1, result.inventoryAfter().get("wooden_pickaxe"));
		assertEquals(1, result.inventoryAfter().get("crafting_table"));
	}

	@Test
	void sticksDoNotEatPlanksReservedForPickaxe() {
		// 板材3枚＋原木1本：棒に板材2枚を使うと、ツルハシ用の3枚が足りなくなる
		var result = CraftPlanner.plan(Map.of("oak_planks", 3, "oak_log", 1, "crafting_table", 1), "wooden_pickaxe", 1, false);

		assertTrue(result.complete(), () -> "missing: " + result.missing());
		assertEquals(1, result.inventoryAfter().get("wooden_pickaxe"));
	}

	@Test
	void reportsMissingRawMaterials() {
		var result = CraftPlanner.plan(Map.of(), "stone_pickaxe", 1, true);

		assertFalse(result.complete());
		assertEquals(3, result.missing().get("cobblestone"));
		assertTrue(result.missing().containsKey(Items.ANY_LOG));
		assertTrue(result.steps().isEmpty() || result.steps().stream().noneMatch(s -> s.output().equals("stone_pickaxe")));
	}

	@Test
	void tableNearbyMeansNoTableCrafted() {
		var result = CraftPlanner.plan(Map.of("oak_log", 2), "wooden_pickaxe", 1, true);

		assertTrue(result.complete());
		assertTrue(result.steps().stream().noneMatch(s -> s.output().equals("crafting_table")));
	}

	@Test
	void alreadyHaveEnoughDoesNothing() {
		var result = CraftPlanner.plan(Map.of("stick", 10), "stick", 4, false);

		assertTrue(result.complete());
		assertTrue(result.steps().isEmpty());
	}

	@Test
	void doesNotModifyInputInventory() {
		Map<String, Integer> inv = new java.util.HashMap<>(Map.of("oak_log", 5));
		CraftPlanner.plan(inv, "chest", 1, false);
		assertEquals(Map.of("oak_log", 5), inv);
	}
}
