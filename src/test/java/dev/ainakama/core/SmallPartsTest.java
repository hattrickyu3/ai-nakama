package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SmallPartsTest {
	private static final Set<String> KNOWN = Set.of("oak_log", "oak_planks", "cobblestone", "stick", "torch");

	@Test
	void quickCommandsHandleFixedPhrases() {
		assertEquals(List.of(new Command.Follow()), QuickCommands.match("ついてきて", KNOWN::contains).orElseThrow().plan().orElseThrow().commands());
		assertEquals(List.of(new Command.Stop()), QuickCommands.match("止まって！", KNOWN::contains).orElseThrow().plan().orElseThrow().commands());
		assertEquals(List.of(new Command.Collect("oak_log", 10)), QuickCommands.match("木を10本集めて", KNOWN::contains).orElseThrow().plan().orElseThrow().commands());
		assertEquals(List.of(new Command.Build("hut", new Place.NearPlayer())), QuickCommands.match("小屋を建てて", KNOWN::contains).orElseThrow().plan().orElseThrow().commands());
		assertEquals(List.of(new Command.Build("hut", new Place.Home())), QuickCommands.match("拠点に小屋を建てて", KNOWN::contains).orElseThrow().plan().orElseThrow().commands());
		assertEquals(List.of(new Command.Craft("torch", 8)), QuickCommands.match("松明を8個作って", KNOWN::contains).orElseThrow().plan().orElseThrow().commands());
		assertEquals(QuickCommands.Special.SET_HOME, QuickCommands.match("ここを拠点にして", KNOWN::contains).orElseThrow().special().orElseThrow());
	}

	@Test
	void freeTextGoesToAi() {
		assertTrue(QuickCommands.match("冬に備えて何かしておいて", KNOWN::contains).isEmpty());
		assertTrue(QuickCommands.match("ダイヤを10個集めて", KNOWN::contains).isEmpty());
	}

	@Test
	void blueprintMaterials() {
		assertEquals(71, Blueprint.HUT.materials().get(Items.ANY_PLANKS));
		assertEquals(23, Blueprint.FENCE.materials().get("oak_fence"));
	}

	@Test
	void memoryKeepsLimits() {
		Memory m = Memory.empty();
		for (int i = 0; i < 25; i++) {
			m = m.withNote("note" + i).withExchange("p" + i, "r" + i);
		}
		assertEquals(Memory.MAX_NOTES, m.notes().size());
		assertEquals("note5", m.notes().get(0));
		assertEquals(Memory.MAX_RECENT, m.recent().size());
		assertEquals("p24", m.recent().get(2).player());
	}

	@Test
	void budgetEnforcesCooldownAndDailyLimit() {
		CallBudget b = new CallBudget(5000, 3, ZoneId.of("Asia/Tokyo"));
		UUID a = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		long t = 1_800_000_000_000L;

		assertEquals(CallBudget.Decision.OK, b.tryAcquire(a, t));
		assertEquals(CallBudget.Decision.COOLDOWN, b.tryAcquire(a, t + 1000));
		assertEquals(CallBudget.Decision.OK, b.tryAcquire(c, t + 1000));
		assertEquals(CallBudget.Decision.OK, b.tryAcquire(a, t + 6000));
		assertEquals(CallBudget.Decision.DAILY_LIMIT, b.tryAcquire(c, t + 20000));
		// 次の日になれば数え直す
		assertEquals(CallBudget.Decision.OK, b.tryAcquire(c, t + 86_400_000L));
	}

	@Test
	void directionText() {
		assertEquals("北に12マス", Status.direction(0, -12));
		assertEquals("南東に14マス", Status.direction(10, 10));
		assertEquals("西に5マス", Status.direction(-5, 1));
		assertEquals("すぐそば", Status.direction(1, 0));
	}

	@Test
	void promptStaysSmall() {
		Map<String, Integer> inv = new LinkedHashMap<>();
		inv.put("stone_axe", 1);
		inv.put("oak_log", 3);
		inv.put("bread", 2);
		Status s = new Status("朝", "晴れ", 20, 20, "拠点から東に5マス", inv,
			List.of("oak_log(オークの原木) 北に12マス 約30個", "stone 下に3マス"),
			List.of("羊×3 西に20マス"), List.of("home"), "なし");
		Memory m = Memory.empty().withNote("プレイヤーは小麦が好き").withExchange("おはよう", "おはよう！");
		String system = PromptBuilder.system("ミナ", "明るく元気");
		String user = PromptBuilder.userForMessage("ミナ", s, m, "拠点に小屋を建てて");

		// 日本語はおよそ1文字1トークン強。設計の目安（固定部分700・その他500）から大きく外れないこと
		assertTrue(system.length() < 1400, "system length " + system.length());
		assertTrue(user.length() < 600, "user length " + user.length());
		assertTrue(user.contains("oak_log(オークの原木)×3"));
	}
}
