package dev.ainakama.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.ainakama.ModServices;
import dev.ainakama.ai.UsageLog;
import dev.ainakama.core.Blueprints;
import dev.ainakama.core.Command;
import dev.ainakama.core.SkillBook;
import dev.ainakama.core.Trigger;
import dev.ainakama.entity.ModEntities;
import dev.ainakama.entity.NakamaEntity;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * /nakama コマンド。
 * spawn・home・reload はチートが有効（OP）のときだけ使える。
 * skills：覚えた技・決まり・設計図の一覧 / forget 名前：それを忘れる
 * spawn [名前]：仲間を出す / home：近くの仲間の拠点をここにする / usage：今日のAIの使用量 / reload：設定の読み直し / list：仲間の一覧
 */
public final class NakamaCommands {
	/** 1つのワールドに出せる仲間の数（出しすぎ防止）。 */
	private static final int MAX_NAKAMA = 5;
	private static final double HOME_RANGE = 32;

	private NakamaCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("nakama")
			.then(Commands.literal("spawn")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> spawn(ctx, NakamaEntity.DEFAULT_NAME))
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))
			.then(Commands.literal("home").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(NakamaCommands::home))
			.then(Commands.literal("usage").executes(NakamaCommands::usage))
			.then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(NakamaCommands::reload))
			.then(Commands.literal("list").executes(NakamaCommands::list))
			.then(Commands.literal("skills").executes(NakamaCommands::skills))
			.then(Commands.literal("forget")
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(ctx -> forget(ctx, StringArgumentType.getString(ctx, "name"))))));
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, String name) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal("プレイヤーが実行してください"));
			return 0;
		}
		ServerLevel level = player.level();
		List<NakamaEntity> existing = all(level);
		if (existing.size() >= MAX_NAKAMA) {
			ctx.getSource().sendFailure(Component.literal("仲間は" + MAX_NAKAMA + "人までです"));
			return 0;
		}
		if (existing.stream().anyMatch(n -> n.baseName().equals(name))) {
			ctx.getSource().sendFailure(Component.literal("「" + name + "」はもういます"));
			return 0;
		}
		NakamaEntity nakama = ModEntities.NAKAMA.create(level, EntitySpawnReason.COMMAND);
		if (nakama == null) {
			return 0;
		}
		nakama.snapTo(player.getX() + 1.5, player.getY(), player.getZ(), player.getYRot() + 180, 0);
		nakama.setup(name, player.blockPosition());
		level.addFreshEntity(nakama);
		nakama.say("はじめまして、" + name + "だよ！「" + name + "、ついてきて」みたいに話しかけてね。");
		return 1;
	}

	private static int home(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		List<NakamaEntity> near = player.level().getEntitiesOfClass(NakamaEntity.class,
			player.getBoundingBox().inflate(HOME_RANGE), e -> true);
		near.forEach(n -> n.setHome(player.blockPosition()));
		ctx.getSource().sendSuccess(() -> Component.literal(near.size() + "人の拠点をここにしました"), false);
		return near.size();
	}

	private static int usage(CommandContext<CommandSourceStack> ctx) {
		var ai = ModServices.ai();
		UsageLog.Totals t = ai.usage().today();
		String text = "今日のAI: " + t.calls() + "回（上限 " + ai.budget().dailyLimit() + "回）"
			+ "、送った量 " + t.inputTokens() + "トークン、返事 " + t.outputTokens() + "トークン"
			+ (t.calls() > 0 ? "、平均 " + (t.millis() / t.calls()) + "ミリ秒" : "")
			+ "\n接続先: " + ai.describeClient()
			+ "\n記録: " + ai.usage().file();
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return t.calls();
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		List<String> warnings = ModServices.reload();
		String text = warnings.isEmpty() ? "設定を読み直しました（" + ModServices.ai().describeClient() + "）"
			: "設定を読み直しました。注意: " + String.join(" / ", warnings);
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		List<NakamaEntity> found = all(player.level());
		if (found.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("仲間はいません。/nakama spawn で出せます"), false);
			return 0;
		}
		for (NakamaEntity n : found) {
			String text = n.baseName() + "：" + n.blockPosition().toShortString() + "、" + n.currentJob();
			ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		}
		return found.size();
	}

	/** 覚えた技・決まり・設計図の一覧。 */
	private static int skills(CommandContext<CommandSourceStack> ctx) {
		SkillBook book = ModServices.skills();
		List<String> lines = new java.util.ArrayList<>();
		lines.add("【覚えた技】");
		book.all().forEach(s -> lines.add("・" + s.name() + "： " + describe(s.steps())));
		lines.add("【決まり】");
		book.rules().forEach(r -> lines.add("・" + r.when().japanese() + "： " + describe(r.steps())));
		lines.add("【覚えた設計図】");
		Blueprints.learned().forEach(b -> lines.add("・" + b.id() + "（" + b.parts().size() + "ブロック）"));
		lines.add("消すときは /nakama forget 名前");
		lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
		return 1;
	}

	private static String describe(List<Command> steps) {
		return steps.stream().map(Command::describe).collect(java.util.stream.Collectors.joining(" → "));
	}

	/** 名前の合う技・設計図・決まり（night などのきっかけ名）を忘れる。 */
	private static int forget(CommandContext<CommandSourceStack> ctx, String name) {
		SkillBook book = ModServices.skills();
		boolean skill = book.forget(name);
		boolean rule = Trigger.parse(name).map(book::removeRule).orElse(false);
		boolean blueprint = Blueprints.forget(name);
		if (skill || rule) {
			ModServices.library().saveSkills(book);
		}
		if (blueprint) {
			ModServices.library().deleteBlueprint(name);
		}
		boolean any = skill || rule || blueprint;
		ctx.getSource().sendSuccess(() -> Component.literal(any ? "「" + name + "」を忘れました" : "「" + name + "」は覚えていません"), false);
		return any ? 1 : 0;
	}

	private static List<NakamaEntity> all(ServerLevel level) {
		return new java.util.ArrayList<>(level.getEntities(ModEntities.NAKAMA, e -> e.isAlive()));
	}
}
