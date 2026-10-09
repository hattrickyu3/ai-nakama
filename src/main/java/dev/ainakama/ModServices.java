package dev.ainakama;

import dev.ainakama.ai.AiConfig;
import dev.ainakama.ai.AiService;
import dev.ainakama.ai.HttpLlmClient;
import dev.ainakama.ai.UsageLog;
import dev.ainakama.core.CallBudget;
import dev.ainakama.core.PlanParser;
import dev.ainakama.world.Inv;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** 設定とAIの窓口を持っておく場所。/nakama reload で作り直せる。 */
public final class ModServices {
	private static volatile AiConfig config;
	private static volatile AiService ai;

	private ModServices() {
	}

	/** 設定を読み直してAIの窓口を作り直す。問題点を返す（なければ空）。 */
	public static synchronized List<String> reload() {
		List<String> warnings = new ArrayList<>();
		Path dir = FabricLoader.getInstance().getConfigDir();
		AiConfig cfg = AiConfig.loadOrCreate(dir.resolve("ainakama.json"), warnings);
		Path logFile = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("ainakama-usage.csv");
		UsageLog usage = ai == null ? new UsageLog(logFile) : ai.usage();
		// 上限が変わっていなければ今日の回数を引き継ぐ（読み直しで回数がリセットされないように）
		CallBudget budget = ai != null && config != null
			&& config.dailyCallLimit == cfg.dailyCallLimit && config.cooldownSeconds == cfg.cooldownSeconds
			? ai.budget()
			: new CallBudget(cfg.cooldownSeconds * 1000L, cfg.dailyCallLimit, ZoneId.systemDefault());
		ai = new AiService(
			new HttpLlmClient(cfg),
			budget,
			new PlanParser(Inv::itemExists),
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

	public static AiConfig config() {
		if (config == null) {
			reload();
		}
		return config;
	}
}
