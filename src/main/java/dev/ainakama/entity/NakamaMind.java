package dev.ainakama.entity;

import dev.ainakama.AiNakama;
import dev.ainakama.ModServices;
import dev.ainakama.ai.AiService;
import dev.ainakama.core.Blueprint;
import dev.ainakama.core.Blueprints;
import dev.ainakama.core.CallBudget;
import dev.ainakama.core.Command;
import dev.ainakama.core.Items;
import dev.ainakama.core.Plan;
import dev.ainakama.core.PromptBuilder;
import dev.ainakama.core.QuickCommands;
import dev.ainakama.core.SkillBook;
import dev.ainakama.core.Status;
import dev.ainakama.core.Trigger;
import dev.ainakama.world.BuildRecorder;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Places;
import dev.ainakama.world.StatusCollector;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 仲間の「考える」部分。プレイヤーの言葉を受け取り、次の順で何をするか決める。
 * 1. 決まった言い方 → そのまま命令にする（AIなし）
 * 2. 覚えた技の名前 → その手順を実行する（AIなし）
 * 3. それ以外 → AIに計画を聞く
 * AIの返事に「教わった技」「決まり」「質問」が入っていれば、覚えたり聞き返したりする。
 */
final class NakamaMind {
	/** 1回の話しかけのあと、失敗してAIに立て直しを頼める回数。これを超えたらプレイヤーに任せる。 */
	private static final int MAX_AUTO_REPLANS = 2;
	private static final int SKILLS_PER_PROMPT = 8;
	private static final int BLUEPRINTS_PER_PROMPT = 12;

	private final NakamaEntity nakama;
	/** 新しい指示のたびに増える番号。AIの返事が届いたとき、もっと新しい指示が出ていれば古い返事は捨てる。 */
	private int generation;
	private int autoReplans;
	/** やり方を聞き返して、答えを待っている質問。 */
	private @Nullable String pendingQuestion;

	NakamaMind(NakamaEntity nakama) {
		this.nakama = nakama;
	}

	// ---- 話しかけられたとき ----

	void onPlayerMessage(ServerPlayer player, String text, ServerLevel level) {
		autoReplans = 0;
		var quick = QuickCommands.match(text, Inv::itemExists);
		if (quick.isPresent()) {
			generation++;
			QuickCommands.Match m = quick.get();
			m.special().ifPresent(s -> handleSpecial(s, m.arg(), player));
			m.plan().ifPresent(p -> applyPlan(p, text, level));
			return;
		}
		var skill = ModServices.skills().matchRequest(text);
		if (skill.isPresent() && pendingQuestion == null) {
			generation++;
			applyPlan(new Plan("「" + skill.get().name() + "」だね、まかせて！", skill.get().steps(), null), text, level);
			return;
		}
		if (nakama.isThinking()) {
			nakama.say("ちょっと待ってね、いま考え中…");
			return;
		}
		generation++;
		Status status = StatusCollector.collect(nakama, level, nakama.currentJob());
		String user = PromptBuilder.userForMessage(nakama.baseName(), status, nakama.memory(), extras(text), text);
		askAi("message", user, text, level, false);
	}

	private void handleSpecial(QuickCommands.Special special, String arg, ServerPlayer player) {
		switch (special) {
			case SET_HOME -> {
				nakama.setHome(player.blockPosition());
				nakama.say("ここを拠点にするね！");
			}
			case SHOW_INVENTORY -> nakama.say(inventoryText());
			case WHERE_HOME -> {
				BlockPos h = nakama.home();
				nakama.say(h == null ? "拠点はまだ決まってないよ。「ここを拠点にして」と言ってね。"
					: "拠点は x=" + h.getX() + " y=" + h.getY() + " z=" + h.getZ() + " だよ。");
			}
			case WATCH -> {
				BuildRecorder.start(player);
				nakama.say("うん、見てるね！建て終わったら「これが〇〇だよ」って名前を教えて。");
			}
			case NAME_BUILD -> learnBuilding(player, arg);
		}
	}

	private void learnBuilding(ServerPlayer player, String name) {
		if (!BuildRecorder.isWatching(player)) {
			nakama.say("ごめん、見てなかった…。建てる前に「見てて」って言ってね。");
			return;
		}
		var blueprint = BuildRecorder.finish(player, name);
		if (blueprint.isEmpty()) {
			nakama.say("あれ、増えたブロックが見つからなかった（大きすぎるか、遠すぎるのかも）。もう一度「見てて」からお願い。");
			return;
		}
		Blueprint bp = blueprint.get();
		Blueprints.learn(bp);
		ModServices.library().saveBlueprint(bp);
		String materials = bp.materials().entrySet().stream()
			.map(e -> Items.describeKey(e.getKey()) + "×" + e.getValue())
			.collect(Collectors.joining("、"));
		nakama.say("「" + name + "」を覚えたよ！（" + bp.parts().size() + "ブロック。材料: " + materials + "）");
	}

	String inventoryText() {
		var counts = Inv.counts(nakama.getInventory());
		if (counts.isEmpty()) {
			return "何も持ってないよ。";
		}
		return "持ち物: " + counts.entrySet().stream()
			.map(e -> Items.label(e.getKey()) + "×" + e.getValue())
			.collect(Collectors.joining(", "));
	}

	// ---- AIに聞く ----

	private PromptBuilder.Extras extras(String query) {
		SkillBook skills = ModServices.skills();
		List<String> blueprints = Blueprints.learned().stream().map(Blueprint::id).limit(BLUEPRINTS_PER_PROMPT).toList();
		return new PromptBuilder.Extras(skills.relevantNames(query, SKILLS_PER_PROMPT), blueprints, pendingQuestion);
	}

	private void askAi(String purpose, String user, @Nullable String playerText, ServerLevel level, boolean ignoreCooldown) {
		AiService ai = ModServices.ai();
		nakama.setThinking(true);
		String system = PromptBuilder.system(nakama.baseName(), nakama.personality());
		int askedAt = generation;
		ai.ask(nakama.getUUID(), purpose, system, user, ignoreCooldown).whenComplete((outcome, err) ->
			level.getServer().execute(() -> {
				nakama.setThinking(false);
				if (nakama.isRemoved() || askedAt != generation) {
					// いなくなった、または考えている間に「止まって」など新しい指示が出たので、古い返事は使わない
					return;
				}
				if (err != null) {
					AiNakama.LOGGER.warn("AIの返事を受け取れませんでした", err);
					nakama.say("うまく考えられなかった…。");
					return;
				}
				handleOutcome(outcome, playerText, level);
			}));
	}

	private void handleOutcome(AiService.Outcome outcome, @Nullable String playerText, ServerLevel level) {
		switch (outcome) {
			case AiService.Outcome.Success s -> {
				Plan p = s.plan();
				boolean empty = p.commands().isEmpty() && p.say().isBlank() && p.ask() == null && p.teach() == null && p.rule() == null;
				if (empty) {
					nakama.say("ごめん、よくわからなかった…。言い方を変えてみて？");
					return;
				}
				applyPlan(p, playerText, level);
			}
			case AiService.Outcome.Refused r -> nakama.say(r.decision() == CallBudget.Decision.DAILY_LIMIT
				? "今日はもう考え疲れた…。「ついてきて」「木を10本集めて」みたいな決まった言い方や、覚えた技なら動けるよ。"
				: "ちょっとだけ待ってね。");
			case AiService.Outcome.Failed f -> nakama.say(f.reason());
		}
	}

	private void applyPlan(Plan plan, @Nullable String playerText, ServerLevel level) {
		if (!plan.say().isBlank()) {
			nakama.say(plan.say());
		}
		if (plan.memo() != null) {
			nakama.remember(plan.memo());
		}
		if (playerText != null) {
			nakama.setMemory(nakama.memory().withExchange(playerText, plan.say()));
			// 聞き返していた質問への答えは、ここで受け取ったことにする
			pendingQuestion = null;
		}
		if (plan.teach() != null) {
			ModServices.skills().learn(plan.teach());
			ModServices.library().saveSkills(ModServices.skills());
			nakama.say("「" + plan.teach().name() + "」を覚えたよ！次からは同じ言い方で頼んでね。");
		}
		if (plan.rule() != null) {
			ModServices.skills().setRule(plan.rule());
			ModServices.library().saveSkills(ModServices.skills());
			nakama.say("「" + plan.rule().when().japanese() + "」の決まりを覚えたよ！");
		}
		if (plan.ask() != null) {
			pendingQuestion = plan.ask();
			if (!plan.say().contains("？") && !plan.say().contains("?")) {
				nakama.say(plan.ask());
			}
		}
		if (!plan.commands().isEmpty()) {
			start(plan.commands(), level);
		}
	}

	/** 命令の列を実行に移す。覚えた技は手順に展開し、「ここ」などは今の座標に置き換える。 */
	private void start(List<Command> commands, ServerLevel level) {
		List<Command> expanded = ModServices.skills().expand(commands);
		nakama.runner().replace(Places.pin(expanded, nakama, level), nakama, level);
	}

	// ---- 実行側からの知らせ ----

	void onTaskFailed(Command command, String reason, ServerLevel level) {
		if (autoReplans >= MAX_AUTO_REPLANS || nakama.isThinking()) {
			nakama.say("うまくいかない…。" + reason + "。どうしたらいいか教えて？");
			return;
		}
		autoReplans++;
		nakama.say(reason + "…。どうするか考えるね。");
		Status status = StatusCollector.collect(nakama, level, "なし");
		String query = command.describe() + " " + reason;
		String user = PromptBuilder.userForFailure(nakama.baseName(), status, nakama.memory(), extras(query), command.describe(), reason);
		// 失敗は直前のAI呼び出しのすぐあとに起きるので、呼ぶ間隔の制限は外す（回数の上限は autoReplans と1日の上限で守る）
		askAi("failure", user, null, level, true);
	}

	/** きっかけが起きたとき。覚えた決まりがあれば、AIを使わずに実行する。 */
	void onTriggers(List<Trigger> fired, ServerLevel level) {
		for (Trigger trigger : fired) {
			var rule = ModServices.skills().rule(trigger);
			if (rule.isEmpty()) {
				continue;
			}
			// 作業中に割り込むのは、急ぎのきっかけだけ。ほかは手が空いているときだけ動く
			boolean urgent = trigger == Trigger.ENEMY_NEAR || trigger == Trigger.NIGHT;
			if (!nakama.runner().isIdle() && !urgent) {
				continue;
			}
			generation++;
			nakama.say("（" + trigger.japanese() + "、の決まりだね）");
			start(rule.get().steps(), level);
			return;
		}
	}
}
