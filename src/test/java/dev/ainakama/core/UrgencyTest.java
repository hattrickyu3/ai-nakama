package dev.ainakama.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UrgencyTest {
	private static final Command COLLECT = new Command.Collect("cobblestone", 30);
	private static final Command GO_HOME = new Command.MoveTo(new Place.Home());

	@Test
	void stopFollowGuardAndGoHomeSwitchFromTheCurrentWork() {
		assertEquals(Urgency.SWITCH, Urgency.of(List.of(new Command.Stop())));
		assertEquals(Urgency.SWITCH, Urgency.of(List.of(new Command.Follow())));
		assertEquals(Urgency.SWITCH, Urgency.of(List.of(new Command.Guard())));
		assertEquals(Urgency.SWITCH, Urgency.of(List.of(GO_HOME)));
	}

	@Test
	void planStartingWithStopReplacesTheCurrentWork() {
		// 「やっぱり石じゃなくて砂にして」のように、今の作業をやめてから別のことをする計画
		assertEquals(Urgency.SWITCH, Urgency.of(List.of(new Command.Stop(), new Command.Collect("sand", 8))));
	}

	@Test
	void quickThingsAreDoneNowAndTheWorkResumes() {
		assertEquals(Urgency.NOW, Urgency.of(List.of(new Command.Give(null, 0))));
		assertEquals(Urgency.NOW, Urgency.of(List.of(new Command.Store())));
		assertEquals(Urgency.NOW, Urgency.of(List.of(new Command.Fight())));
		assertEquals(Urgency.NOW, Urgency.of(List.of(new Command.Remember("青が好き"))));
		assertEquals(Urgency.NOW, Urgency.of(List.of(new Command.Say("やあ"), new Command.Give("cobblestone", 3))));
	}

	@Test
	void aiIsToldHowPlansAreQueuedOnlyWhileWorking() {
		// 小さいAIは説明が増えると慎重になりすぎるので、手が空いているときの説明文は変えない
		Status working = status("oak_log(オークの原木)を集める（3/30）");
		String idle = PromptBuilder.userForMessage("ミナ", status("なし"), Memory.empty(), PromptBuilder.Extras.none(), "石も掘って", true);
		String busy = PromptBuilder.userForMessage("ミナ", working, Memory.empty(), PromptBuilder.Extras.none(), "石も掘って", true);
		String busySmallModel = PromptBuilder.userForMessage("ミナ", working, Memory.empty(), PromptBuilder.Extras.none(), "石も掘って", false);

		assertFalse(idle.contains("stop"));
		// 小さいAIは、この説明を読むと感想を言われただけで作業をやめてしまうので、渡さない
		assertFalse(busySmallModel.contains("stop"));
		assertEquals(busySmallModel, PromptBuilder.userForMessage("ミナ", working, Memory.empty(), PromptBuilder.Extras.none(), "石も掘って"));
		assertTrue(busy.contains("今の作業が終わったあと"));
		assertTrue(busy.contains("最初に stop"));
		assertTrue(busy.endsWith("プレイヤー: 石も掘って"));
		assertFalse(PromptBuilder.system("ミナ", "明るい").contains("今の作業が終わったあと"));
	}

	private static Status status(String currentJob) {
		return new Status("昼", "晴れ", 20, 20, "座標 0 64 0", Map.of(), List.of(), List.of(), List.of(), currentJob);
	}

	@Test
	void otherRequestsWaitUntilTheCurrentWorkIsDone() {
		assertEquals(Urgency.LATER, Urgency.of(List.of(COLLECT)));
		assertEquals(Urgency.LATER, Urgency.of(List.of(new Command.Craft("torch", 8))));
		// 手順の途中に「拠点に戻る」「渡す」が入っているだけの計画は、切り替えでも割り込みでもない
		assertEquals(Urgency.LATER, Urgency.of(List.of(COLLECT, GO_HOME)));
		assertEquals(Urgency.LATER, Urgency.of(List.of(GO_HOME, new Command.Store())));
		assertEquals(Urgency.LATER, Urgency.of(List.of(COLLECT, new Command.Give("cobblestone", 30))));
	}
}
