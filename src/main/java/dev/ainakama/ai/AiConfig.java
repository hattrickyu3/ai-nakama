package dev.ainakama.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 設定ファイル（config/ainakama.json）の中身。
 * APIキーはModの中には入れず、このファイルか環境変数 AINAKAMA_API_KEY に書いてもらう。
 */
public final class AiConfig {
	public static final String API_KEY_ENV = "AINAKAMA_API_KEY";

	/** "ollama" / "anthropic" / "openai" / "claude-cli"（PCの Claude Code を呼ぶ。サブスクのログインを使うので APIキーがいらない） */
	public String provider = "ollama";
	public String baseUrl = "http://localhost:11434";
	public String model = "qwen3.5:4b";
	public String apiKey = "";
	/** provider が claude-cli のときに動かすコマンド。 */
	public String claudeCommand = "claude";
	public double temperature = 0.2;
	public int maxOutputTokens = 400;
	public int timeoutSeconds = 120;
	public int dailyCallLimit = 300;
	public String defaultPersonality = "明るくて元気。ちょっとおっちょこちょいだけど頼れる。";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** 読み込む。ファイルがなければ初期値で作る。中身がおかしければ初期値を使い、問題点を warnings に入れる。 */
	public static AiConfig loadOrCreate(Path file, List<String> warnings) {
		AiConfig config = new AiConfig();
		try {
			if (Files.exists(file)) {
				AiConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AiConfig.class);
				if (read != null) {
					config = read;
				}
			} else {
				Files.createDirectories(file.getParent());
				Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
			}
		} catch (IOException | JsonParseException e) {
			warnings.add("設定ファイルを読めなかったので初期値を使います: " + e.getMessage());
			config = new AiConfig();
		}
		warnings.addAll(config.validate());
		return config;
	}

	/** おかしな値を直し、直した内容を返す。 */
	List<String> validate() {
		List<String> problems = new ArrayList<>();
		provider = provider == null ? "ollama" : provider.trim().toLowerCase(Locale.ROOT);
		if (!List.of("ollama", "anthropic", "openai", ClaudeCliClient.PROVIDER).contains(provider)) {
			problems.add("provider「" + provider + "」は使えないので ollama にします");
			provider = "ollama";
		}
		boolean cli = provider.equals(ClaudeCliClient.PROVIDER);
		if (claudeCommand == null || claudeCommand.isBlank()) {
			claudeCommand = "claude";
		}
		if (!cli && (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")))) {
			problems.add("baseUrl が正しくないので初期値にします");
			baseUrl = defaultBaseUrl(provider);
		}
		if (model == null || model.isBlank()) {
			problems.add("model が空です");
			model = "qwen3.5:4b";
		}
		if (temperature < 0 || temperature > 2) {
			temperature = 0.2;
		}
		maxOutputTokens = Math.max(100, Math.min(2000, maxOutputTokens));
		timeoutSeconds = Math.max(10, Math.min(600, timeoutSeconds));
		dailyCallLimit = Math.max(1, dailyCallLimit);
		if (!provider.equals("ollama") && !cli && effectiveApiKey().isBlank()) {
			problems.add(provider + " を使うには APIキーが必要です（設定ファイルの apiKey か環境変数 " + API_KEY_ENV + "）");
		}
		return problems;
	}

	public String effectiveApiKey() {
		String env = System.getenv(API_KEY_ENV);
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		return apiKey == null ? "" : apiKey.trim();
	}

	static String defaultBaseUrl(String provider) {
		return switch (provider) {
			case "anthropic" -> "https://api.anthropic.com";
			case "openai" -> "https://api.openai.com";
			default -> "http://localhost:11434";
		};
	}
}
