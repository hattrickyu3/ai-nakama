package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RosterTest {
	private static final UUID MINA = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID HARU = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final Roster.Entry AT_VILLAGE = new Roster.Entry("ミナ", "minecraft:overworld", 100, 64, -200);

	@Test
	void remembersWhereEachCompanionWasLastSeen() {
		Roster roster = new Roster();
		roster.put(MINA, AT_VILLAGE);
		roster.put(HARU, new Roster.Entry("ハル", "minecraft:the_nether", 1, 2, 3));

		assertEquals(Map.of(MINA, AT_VILLAGE, HARU, new Roster.Entry("ハル", "minecraft:the_nether", 1, 2, 3)), roster.all());

		roster.remove(HARU);

		assertEquals(Map.of(MINA, AT_VILLAGE), roster.all());
	}

	@Test
	void survivesSavingAndLoading() {
		Roster roster = new Roster();
		roster.put(MINA, AT_VILLAGE);

		Roster loaded = Roster.fromJson(roster.toJson());

		assertEquals(Map.of(MINA, AT_VILLAGE), loaded.all());
	}

	@Test
	void brokenFileGivesAnEmptyRoster() {
		assertEquals(Map.of(), Roster.fromJson("").all());
		assertEquals(Map.of(), Roster.fromJson("{ not json").all());
		assertEquals(Map.of(), Roster.fromJson("{\"not-a-uuid\": {\"name\": \"x\"}}").all());
	}

	@Test
	void needsSavingOnlyAfterAChange() {
		Roster roster = new Roster();
		assertFalse(roster.takeDirty());

		roster.put(MINA, AT_VILLAGE);
		assertTrue(roster.takeDirty());
		assertFalse(roster.takeDirty());

		roster.put(MINA, AT_VILLAGE);
		assertFalse(roster.takeDirty());

		roster.put(MINA, new Roster.Entry("ミナ", "minecraft:overworld", 101, 64, -200));
		assertTrue(roster.takeDirty());
	}

	@Test
	void measuresHowFarAwaySheIs() {
		assertEquals(50, AT_VILLAGE.blocksFrom(130, -160));
	}
}
