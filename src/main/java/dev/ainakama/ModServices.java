package dev.ainakama;

import dev.ainakama.ai.AiConfig;
import dev.ainakama.ai.AiService;
import dev.ainakama.ai.LlmClient;
import dev.ainakama.ai.UsageLog;
import dev.ainakama.core.CallBudget;
import dev.ainakama.core.PlanParser;
import dev.ainakama.core.SkillBook;
import dev.ainakama.world.Inv;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** 設定とAIの窓口を持っておく場所。/nakama reload で作り直せる。 */
public final class ModServices {
	private static final SkillBook SKILLS = new SkillBook();
	private static volatile AiConfig config;
	private static volatile AiService ai;
	private static volatile Library library;

	private ModServices() {
	}

	/** 設定を読み直してAIの窓口を作り直す。問題点を返す（なければ空）。 */
	public static synchronized List<String> reload() {
		List<String> warnings = new ArrayList<>();
		Path dir = FabricLoader.getInstance().getConfigDir();
		AiConfig cfg = AiConfig.loadOrCreate(dir.resolve("ainakama.json"), warnings);
		Path logFile = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("ainakama-usage.csv");
		UsageLog usage = ai == null ? new UsageLog(logFile) : ai.usage();
		PlanParser parser = new PlanParser(Inv::itemExists, SKILLS::knows);
		library = new Library(dir.resolve("ainakama"));
		library.loadBlueprints();
		library.loadSkills(SKILLS, parser);
		// 上限が変わっていなければ今日の回数を引き継ぐ（読み直しで回数がリセットされないように）
		CallBudget budget = ai != null && config != null && config.dailyCallLimit == cfg.dailyCallLimit
			? ai.budget()
			: new CallBudget(0, cfg.dailyCallLimit, ZoneId.systemDefault());
		ai = new AiService(
			LlmClient.create(cfg, dir.resolve("ainakama-claude-cli")),
			budget,
			parser,
			usage,
			cfg.model,
			System::currentTimeMillis);
		config = cfg;
		warnings.forEach(w -> AiNakama.LOGGER.warn(w));
		return warnings;
	}

	public static AiService ai() {
		if (ai == null) {
			reload();
		}
		return ai;
	}

	/**
	 * 覚えた技と決まりを、ファイルから読み直す。
	 * マイクラ本体のレシピを読み込んだあとに呼ぶ（起動直後は手書きのレシピしかなく、「ベッドを作る」のような技が読めずに消えてしまうため）。
	 */
	public static synchronized void reloadSkills() {
		library().loadSkills(SKILLS, new PlanParser(Inv::itemExists, SKILLS::knows));
	}

	/** 覚えた技と決まりの帳面（仲間全員で共有）。 */
	public static SkillBook skills() {
		return SKILLS;
	}

	public static Library library() {
		if (library == null) {
			reload();
		}
		return library;
	}

	public static AiConfig config() {
		if (config == null) {
			reload();
		}
		return config;
	}
}
