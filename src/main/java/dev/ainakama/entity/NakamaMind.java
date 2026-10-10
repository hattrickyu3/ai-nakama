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
import dev.ainakama.core.TeachParser;
import dev.ainakama.core.Trigger;
import dev.ainakama.world.BuildRecorder;
import dev.ainakama.world.Inv;
import dev.ainakama.world.Places;
import dev.ainakama.world.StatusCollector;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 仲間の「考える」部分。プレイヤーの言葉を受け取り、次の順で何をするか決める。
 * 1. 教える言い方（〇〇っていうのは…／夜になったら…）→ 技・決まりとして覚える
 * 2. 決まった言い方 → そのまま命令にする
 * 3. 覚えた技の名前 → その手順を実行する
 * 4. それ以外 → AIに計画を聞く
 * AIを使うのは 4 と、1 で説明をプログラムが読み取れなかったときだけ。
 */
final class NakamaMind {
	/** 1回の話しかけのあと、失敗してAIに立て直しを頼める回数。これを超えたらプレイヤーに任せる。 */
	private static final int MAX_AUTO_REPLANS = 2;
	private static final int SKILLS_PER_PROMPT = 8;
	private static final int BLUEPRINTS_PER_PROMPT = 12;
	private static final String HOW_TO_TEACH = "やり方を教えてくれるなら「〇〇っていうのは、△△して□□すること」って言ってね。";

	private final NakamaEntity nakama;
	/** 新しい指示のたびに増える番号。AIの返事が届いたとき、もっと新しい指示が出ていれば古い返事は捨てる。 */
	private int generation;
	private int autoReplans;
	/** 「〇〇って何をすればいいの？」と聞き返して、答えを待っている技の名前。 */
	private @Nullable String pendingTeachName;

	NakamaMind(NakamaEntity nakama) {
		this.nakama = nakama;
	}

	// ---- 話しかけられたとき ----

	void onPlayerMessage(ServerPlayer player, String text, ServerLevel level) {
		autoReplans = 0;
		var teaching = TeachParser.parse(text, Inv::itemExists);
		if (teaching.isPresent()) {
			generation++;
			pendingTeachName = null;
			learn(teaching.get(), level);
			return;
		}
		var quick = QuickCommands.match(text, Inv::itemExists);
		if (pendingTeachName != null) {
			String name = pendingTeachName;
			pendingTeachName = null;
			// 聞き返した直後の言葉は、その答え（やり方の説明）として受け取る。「止まって」などは普通の指示として扱う
			boolean isAnswer = quick.isEmpty() || !isControl(quick.get());
			if (isAnswer) {
				generation++;
				learn(new TeachParser.Teaching(name, null, text, TeachParser.steps(text, Inv::itemExists)), level);
				return;
			}
		}
		if (quick.isPresent()) {
			generation++;
			QuickCommands.Match m = quick.get();
			m.special().ifPresent(s -> handleSpecial(s, m.arg(), player));
			m.plan().ifPresent(p -> applyPlan(p, text, level));
			return;
		}
		var skill = ModServices.skills().matchRequest(text);
		if (skill.isPresent()) {
			generation++;
			applyPlan(new Plan("「" + skill.get().name() + "」だね、まかせて！", skill.get().steps(), null), text, level);
			return;
		}
		// 「木を2本集めて、松明を4個作って」のように、決まった言い方をいくつか続けた指示
		List<Command> several = TeachParser.steps(text, Inv::itemExists);
		if (several.size() > 1) {
			generation++;
			applyPlan(new Plan("わかった、順番にやるね！", several, null), text, level);
			return;
		}
		if (nakama.isThinking()) {
			nakama.say("ちょっと待ってね、いま考え中…");
			return;
		}
		generation++;
		Status status = StatusCollector.collect(nakama, level, nakama.currentJob());
		String user = PromptBuilder.userForMessage(nakama.baseName(), status, nakama.memory(), extras(text), text);
		askAi("message", user, level, outcome -> onReply(outcome, text, level));
	}

	/** 止まる・ついてくる・特別な指示のように、説明ではなく「いますぐの指示」か。 */
	private static boolean isControl(QuickCommands.Match match) {
		if (match.special().isPresent()) {
			return true;
		}
		List<Command> commands = match.plan().map(Plan::commands).orElse(List.of());
		return commands.size() == 1 && (commands.get(0) instanceof Command.Stop || commands.get(0) instanceof Command.Follow);
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

	String inventoryText() {
		var counts = Inv.counts(nakama.getInventory());
		if (counts.isEmpty()) {
			return "何も持ってないよ。";
		}
		return "持ち物: " + counts.entrySet().stream()
			.map(e -> Items.label(e.getKey()) + "×" + e.getValue())
			.collect(Collectors.joining(", "));
	}

	// ---- 教わる ----

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

	/** 教わった技・決まりを覚える。説明をプログラムで読み取れなかったときだけ、AIに命令へ直してもらう。 */
	private void learn(TeachParser.Teaching teaching, ServerLevel level) {
		if (teaching.complete()) {
			remember(teaching, teaching.steps(), level);
			return;
		}
		if (nakama.isThinking()) {
			nakama.say("ちょっと待ってね、いま考え中…。そのあとでもう一度教えて。");
			return;
		}
		String what = teaching.name() != null ? teaching.name() : teaching.trigger().japanese();
		Status status = StatusCollector.collect(nakama, level, nakama.currentJob());
		String user = PromptBuilder.userForTeaching(nakama.baseName(), status, nakama.memory(),
			extras(teaching.explanation()), what, teaching.explanation());
		askAi("teach", user, level, outcome -> {
			if (outcome instanceof AiService.Outcome.Success s && !s.plan().commands().isEmpty()) {
				remember(teaching, s.plan().commands(), level);
			} else if (outcome instanceof AiService.Outcome.Success s) {
				nakama.say(s.plan().say().isBlank() ? "ごめん、そのやり方は私にはできないみたい…。" : s.plan().say());
			} else {
				sayProblem(outcome);
			}
		});
	}

	private void remember(TeachParser.Teaching teaching, List<Command> steps, ServerLevel level) {
		SkillBook book = ModServices.skills();
		String summary = steps.stream().map(Command::describe).collect(Collectors.joining(" → "));
		if (teaching.trigger() != null) {
			book.setRule(new Plan.Rule(teaching.trigger(), steps));
			ModServices.library().saveSkills(book);
			nakama.say("「" + teaching.trigger().japanese() + "」の決まりを覚えたよ！（" + summary + "）");
			return;
		}
		book.learn(new Plan.Skill(teaching.name(), steps));
		ModServices.library().saveSkills(book);
		nakama.say("「" + teaching.name() + "」を覚えたよ！（" + summary + "）さっそくやってみるね。");
		start(steps, level);
	}

	// ---- AIに聞く ----

	private PromptBuilder.Extras extras(String query) {
		List<String> blueprints = Blueprints.learned().stream().map(Blueprint::id).limit(BLUEPRINTS_PER_PROMPT).toList();
		return new PromptBuilder.Extras(ModServices.skills().relevantNames(query, SKILLS_PER_PROMPT), blueprints);
	}

	/**
	 * AIに聞く。呼びすぎは「考えている間は次を受け付けない」「失敗の立て直しは2回まで」「1日の上限」で防いでいるので、
	 * 呼ぶ間隔の制限は使わない（話しかけるたびに「待って」と言われると会話にならないため）。
	 */
	private void askAi(String purpose, String user, ServerLevel level, Consumer<AiService.Outcome> onOutcome) {
		AiService ai = ModServices.ai();
		nakama.setThinking(true);
		String system = PromptBuilder.system(nakama.baseName(), nakama.personality());
		int askedAt = generation;
		ai.ask(nakama.getUUID(), purpose, system, user, true).whenComplete((outcome, err) ->
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
				onOutcome.accept(outcome);
			}));
	}

	/** 話しかけ・失敗の立て直しに対する、AIの返事を受け取る。 */
	private void onReply(AiService.Outcome outcome, @Nullable String playerText, ServerLevel level) {
		if (!(outcome instanceof AiService.Outcome.Success s)) {
			sayProblem(outcome);
			return;
		}
		Plan p = s.plan();
		if (p.commands().isEmpty() && p.say().isBlank() && p.ask() == null) {
			nakama.say("ごめん、よくわからなかった…。" + HOW_TO_TEACH);
			return;
		}
		applyPlan(p, playerText, level);
		// 小さいAIは、聞き返しの質問を ask ではなく say に書くことがあるので、両方を見る
		String question = p.ask() != null ? p.ask() : p.say();
		if (p.commands().isEmpty() && playerText != null && isHowToQuestion(question)) {
			// やり方を聞き返した：次の言葉を、この頼みのやり方として覚える
			pendingTeachName = SkillBook.nameOf(playerText);
			if (p.ask() != null && !p.say().contains("？") && !p.say().contains("?")) {
				nakama.say(p.ask());
			}
		}
	}

	/** 「どうやるの？」「何をすればいいの？」のような、やり方を聞く質問か（ただの雑談の質問と区別する）。 */
	private static boolean isHowToQuestion(String ask) {
		return ask.contains("どうや") || ask.contains("何をす") || ask.contains("なにをす") || ask.contains("やり方");
	}

	private void sayProblem(AiService.Outcome outcome) {
		switch (outcome) {
			case AiService.Outcome.Refused r -> nakama.say(r.decision() == CallBudget.Decision.DAILY_LIMIT
				? "今日はもう考え疲れた…。「ついてきて」「木を10本集めて」みたいな決まった言い方や、覚えた技なら動けるよ。"
				: "ちょっとだけ待ってね。");
			case AiService.Outcome.Failed f -> nakama.say(f.reason());
			case AiService.Outcome.Success s -> {
			}
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
			nakama.say("うまくいかない…。" + reason + "。" + HOW_TO_TEACH);
			return;
		}
		autoReplans++;
		nakama.say(reason + "…。どうするか考えるね。");
		Status status = StatusCollector.collect(nakama, level, "なし");
		String query = command.describe() + " " + reason;
		String user = PromptBuilder.userForFailure(nakama.baseName(), status, nakama.memory(), extras(query), command.describe(), reason);
		askAi("failure", user, level, outcome -> onReply(outcome, null, level));
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
